package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.dto.IncidentDto;
import com.smartelderly.dto.IncidentRequests.AcknowledgeRequest;
import com.smartelderly.dto.IncidentRequests.DetectionReport;
import com.smartelderly.dto.IncidentRequests.IncidentUpdate;
import com.smartelderly.dto.IncidentRequests.ManualIncident;
import com.smartelderly.dto.IncidentRequests.ResolveRequest;
import com.smartelderly.dto.PageResponse;
import com.smartelderly.dto.ReportDto;
import com.smartelderly.exception.ApiException;
import com.smartelderly.model.AppSettings;
import com.smartelderly.model.DetectionDetails;
import com.smartelderly.model.EmailStatus;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import com.smartelderly.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Incident lifecycle: creation (from ML detections or manually), the post-processing pipeline
 * (AI summary -> PDF -> e-mail, each step isolated so a failure never loses the incident), querying and caregiver actions.
 */
@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final String ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Set<String> SORTABLE = Set.of("timestamp", "confidence", "status", "elderlyName", "incidentId");
    private static final int MAX_EXPORT_ROWS = 5000;

    private final IncidentRepository repo;
    private final MongoTemplate mongo;
    private final SettingsService settings;
    private final GroqService groq;
    private final PdfService pdf;
    private final EmailService email;
    private final StorageService storage;
    private final ZoneId zone;
    private final SecureRandom random = new SecureRandom();

    public record CreateResult(IncidentDto incident, boolean created) {}

    public IncidentService(IncidentRepository repo, MongoTemplate mongo, SettingsService settings, GroqService groq,
                           PdfService pdf, EmailService email, StorageService storage, AppProperties props) {
        this.repo = repo;
        this.mongo = mongo;
        this.settings = settings;
        this.groq = groq;
        this.pdf = pdf;
        this.email = email;
        this.storage = storage;
        this.zone = ZoneId.of(props.timezone());
    }

    // ------------------------------------------------------------------ creation

    /** Records a detection reported by the ML service. Idempotent on detectionId. */
    public CreateResult recordDetection(DetectionReport r) {
        var existing = repo.findByDetectionId(r.detectionId());
        if (existing.isPresent()) return new CreateResult(toDto(existing.get()), false);

        AppSettings s = settings.current();
        boolean confirmed = "FALL_CONFIRMED".equals(r.outcome());
        if (!confirmed && !s.isRecordInconclusive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Recording of inconclusive detections is disabled in settings.");
        }
        byte[] frame = null;
        if (r.frameImageBase64() != null && !r.frameImageBase64().isBlank()) {
            try {
                frame = Base64.getDecoder().decode(r.frameImageBase64().replaceFirst("^data:image/\\w+;base64,", ""));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("frameImageBase64 is not valid base64");
            }
            if (StorageService.imageExtension(frame) == null) throw ApiException.badRequest("Frame image must be a JPEG or PNG");
        }

        Incident i = new Incident();
        i.setDetectionId(r.detectionId());
        i.setSource("VIDEO_ANALYSIS");
        i.setElderlyName(firstNonBlank(r.residentName(), s.getResidentName(), "Resident"));
        i.setElderlyAge(r.residentAge() != null ? r.residentAge() : s.getResidentAge());
        i.setCameraName(firstNonBlank(r.cameraName(), s.getCameraName(), "Camera"));
        i.setTimestamp(r.detectedAt() != null ? r.detectedAt() : Instant.now());
        i.setConfidence(round2(r.confidence()));
        // The confidence threshold is enforced here too, so direct ML calls follow the same policy.
        boolean verified = confirmed && r.confidence() >= s.getConfidenceThreshold();
        i.setStatus((verified ? IncidentStatus.VERIFIED : IncidentStatus.PENDING).name());
        DetectionDetails d = r.detection() != null ? r.detection() : new DetectionDetails();
        if (d.getOutcome() == null) d.setOutcome(r.outcome());
        if (d.getConfidenceThreshold() == null) d.setConfidenceThreshold(s.getConfidenceThreshold());
        if (confirmed && !verified) {
            List<String> reasons = d.getReasons() == null ? new ArrayList<>() : new ArrayList<>(d.getReasons());
            reasons.add(String.format(Locale.ROOT, "Confidence %.0f%% is below the %.0f%% threshold; marked for review",
                    r.confidence() * 100, s.getConfidenceThreshold() * 100));
            d.setReasons(reasons);
        }
        i.setDetection(d);
        i.setEmailStatus(EmailStatus.PENDING.name());

        Incident saved;
        try {
            saved = insertWithUniqueId(i);
        } catch (DuplicateKeyException race) {
            // Another request with the same detectionId won the race.
            return repo.findByDetectionId(r.detectionId()).map(x -> new CreateResult(toDto(x), false)).orElseThrow(() -> race);
        }
        log.info("Incident {} recorded ({}), confidence {}", saved.getIncidentId(), saved.getStatus(), saved.getConfidence());

        if (frame != null) {
            try {
                saved.setImagePath(storage.saveFrame(saved.getIncidentId(), frame));
                saved = repo.save(saved);
            } catch (Exception e) {
                log.warn("Could not store frame for {}: {}", saved.getIncidentId(), e.getMessage());
            }
        }
        return new CreateResult(toDto(postProcess(saved, s)), true);
    }

    public IncidentDto createManual(ManualIncident m, String user) {
        AppSettings s = settings.current();
        Incident i = new Incident();
        i.setSource("MANUAL");
        i.setElderlyName(m.elderlyName().trim());
        i.setElderlyAge(m.elderlyAge());
        i.setCameraName(m.cameraName().trim());
        i.setTimestamp(m.timestamp() != null ? m.timestamp() : Instant.now());
        i.setConfidence(m.confidence() == null ? 1.0 : round2(m.confidence()));
        i.setStatus(m.status() == null ? IncidentStatus.VERIFIED.name() : m.status());
        DetectionDetails d = new DetectionDetails();
        d.setOutcome("MANUAL_REPORT");
        d.setReasons(List.of("Reported manually by " + user + (blank(m.note()) ? "" : ": " + m.note().trim())));
        i.setDetection(d);
        i.setEmailStatus(EmailStatus.PENDING.name());
        return toDto(postProcess(insertWithUniqueId(i), s));
    }

    private Incident insertWithUniqueId(Incident i) {
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = "INC-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(i.getTimestamp()) + "-" + randomSuffix();
            if (repo.existsByIncidentId(candidate)) continue;
            i.setIncidentId(candidate);
            try {
                return repo.insert(i);
            } catch (DuplicateKeyException e) {
                if (i.getDetectionId() != null && repo.findByDetectionId(i.getDetectionId()).isPresent()) throw e;
                // incidentId collision - try another suffix
            }
        }
        throw new IllegalStateException("Could not allocate a unique incident id");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(5);
        for (int k = 0; k < 5; k++) sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        return sb.toString();
    }

    /** AI summary -> PDF -> e-mail. Each step is persisted and isolated. */
    private Incident postProcess(Incident i, AppSettings s) {
        try {
            GroqService.AiResult ai = groq.analyze(i);
            i.setAiSummary(ai.summary());
            i.setCaregiverRecommendation(ai.recommendation());
            i.setAiSource(ai.source());
            i.setAiModel(ai.model());
            i = repo.save(i);
        } catch (Exception e) {
            log.warn("AI analysis step failed for {}: {}", i.getIncidentId(), e.getMessage());
        }
        Path report = writeReport(i);
        if (report != null) i = repo.save(i);
        return deliverEmail(i, s, report, false);
    }

    private Path writeReport(Incident i) {
        try {
            Path p = pdf.create(i);
            i.setPdfPath(storage.toReference(p));
            i.setPdfGeneratedAt(Instant.now());
            return p;
        } catch (Exception e) {
            log.error("PDF generation failed for {}: {}", i.getIncidentId(), e.toString());
            return null;
        }
    }

    private Incident deliverEmail(Incident i, AppSettings s, Path report, boolean manualResend) {
        EmailService.Result result;
        IncidentStatus status = i.statusEnum();
        if (!manualResend && !s.isEmailNotificationsEnabled()) {
            result = new EmailService.Result(EmailStatus.DISABLED, "Email notifications are switched off in settings.");
        } else if (!manualResend && status == IncidentStatus.PENDING && !s.isNotifyOnPending()) {
            result = new EmailService.Result(EmailStatus.SKIPPED, "Inconclusive incidents are not emailed (configurable in settings).");
        } else {
            result = email.send(i, settings.emergencyRecipient(s), s.isAttachPdfToEmail() ? report : null);
        }
        i.setEmailStatus(result.status().name());
        i.setEmailDetail(result.detail());
        if (result.status() == EmailStatus.SENT || result.status() == EmailStatus.FAILED) i.setEmailAttemptedAt(Instant.now());
        return repo.save(i);
    }

    // ------------------------------------------------------------------ queries

    public IncidentDto get(String id) {
        return toDto(find(id));
    }

    public Incident find(String id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Incident not found"));
    }

    public PageResponse<IncidentDto> search(String q, List<String> statuses, LocalDate from, LocalDate to,
                                            String sortField, String direction, int page, int size) {
        return page(buildQuery(q, statuses, from, to), sortField, direction, page, size, this::toDto);
    }

    private <T> PageResponse<T> page(Query query, String sortField, String direction, int page, int size,
                                     java.util.function.Function<Incident, T> mapper) {
        long total = mongo.count(query, Incident.class);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        query.with(PageRequest.of(safePage, safeSize, sort(sortField, direction)));
        List<T> content = mongo.find(query, Incident.class).stream().map(mapper).toList();
        return new PageResponse<>(content, safePage, safeSize, total, (int) Math.ceil(total / (double) safeSize));
    }

    public PageResponse<ReportDto> reports(String q, int page, int size) {
        return page(buildQuery(q, null, null, null), "timestamp", "desc", page, size, i -> {
            Path p = storage.resolveExisting(i.getPdfPath());
            Long bytes = null;
            try {
                if (p != null) bytes = Files.size(p);
            } catch (Exception ignored) {
                // size is informational only
            }
            return new ReportDto(i.getId(), i.getIncidentId(), i.getElderlyName(), i.statusEnum().name(), i.getTimestamp(),
                    p != null, i.getPdfGeneratedAt(), bytes);
        });
    }

    public String exportCsv(String q, List<String> statuses, LocalDate from, LocalDate to) {
        Query query = buildQuery(q, statuses, from, to).with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(MAX_EXPORT_ROWS);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
        StringBuilder csv = new StringBuilder("Incident ID,Resident,Age,Camera,Timestamp (" + zone + "),Confidence,Status,Email status,Acknowledged at,Resolved at,Resolution note\n");
        for (Incident i : mongo.find(query, Incident.class)) {
            csv.append(String.join(",",
                    csvCell(i.getIncidentId()), csvCell(i.getElderlyName()),
                    csvCell(i.getElderlyAge() == null ? "" : String.valueOf(i.getElderlyAge())), csvCell(i.getCameraName()),
                    csvCell(i.getTimestamp() == null ? "" : fmt.format(i.getTimestamp())),
                    csvCell(Math.round(i.getConfidence() * 100) + "%"), csvCell(i.statusEnum().name()),
                    csvCell(EmailStatus.parse(i.getEmailStatus()).name()),
                    csvCell(i.getAcknowledgedAt() == null ? "" : fmt.format(i.getAcknowledgedAt())),
                    csvCell(i.getResolvedAt() == null ? "" : fmt.format(i.getResolvedAt())),
                    csvCell(i.getResolutionNote()))).append('\n');
        }
        return csv.toString();
    }

    private Query buildQuery(String q, List<String> statuses, LocalDate from, LocalDate to) {
        List<Criteria> and = new ArrayList<>();
        if (!blank(q)) {
            Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
            and.add(new Criteria().orOperator(Criteria.where("incidentId").regex(p), Criteria.where("elderlyName").regex(p),
                    Criteria.where("cameraName").regex(p)));
        }
        if (statuses != null && !statuses.isEmpty()) {
            List<String> valid = statuses.stream().map(String::trim).map(String::toUpperCase)
                    .filter(v -> Set.of("VERIFIED", "PENDING", "RESOLVED", "FALSE_ALARM").contains(v)).toList();
            if (valid.isEmpty()) throw ApiException.badRequest("Unknown status filter");
            and.add(Criteria.where("status").in(valid));
        }
        if (from != null || to != null) {
            if (from != null && to != null && to.isBefore(from)) throw ApiException.badRequest("'to' date must not be before 'from' date");
            Criteria c = Criteria.where("timestamp");
            if (from != null) c = c.gte(from.atStartOfDay(zone).toInstant());
            if (to != null) c = c.lt(to.plusDays(1).atStartOfDay(zone).toInstant());
            and.add(c);
        }
        Query query = new Query();
        if (!and.isEmpty()) query.addCriteria(new Criteria().andOperator(and.toArray(new Criteria[0])));
        return query;
    }

    private static Sort sort(String field, String direction) {
        String f = field != null && SORTABLE.contains(field) ? field : "timestamp";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, f).and(Sort.by(Sort.Direction.DESC, "_id"));
    }

    // ------------------------------------------------------------------ caregiver actions

    public IncidentDto update(String id, IncidentUpdate u, String user) {
        Incident i = find(id);
        if (u.elderlyName() != null) i.setElderlyName(u.elderlyName().trim());
        if (u.elderlyAge() != null) i.setElderlyAge(u.elderlyAge());
        if (u.cameraName() != null) i.setCameraName(u.cameraName().trim());
        if (u.status() != null && !u.status().equals(i.statusEnum().name())) {
            IncidentStatus next = IncidentStatus.valueOf(u.status());
            if (next.isOpen()) {
                i.setResolvedAt(null);
                i.setResolvedBy(null);
                i.setResolutionNote(null);
            } else {
                markResolved(i, next, u.note(), user);
            }
            i.setStatus(next.name());
        }
        return toDto(saveAndRefreshReport(i));
    }

    public IncidentDto acknowledge(String id, AcknowledgeRequest r, String user) {
        Incident i = find(id);
        if (!i.statusEnum().isOpen()) throw ApiException.conflict("Closed incidents cannot be acknowledged.");
        if (i.getAcknowledgedAt() != null) throw ApiException.conflict("Incident was already acknowledged by " + i.getAcknowledgedBy() + ".");
        i.setAcknowledgedAt(Instant.now());
        i.setAcknowledgedBy(user);
        i.setAcknowledgementNote(r == null || blank(r.note()) ? null : r.note().trim());
        return toDto(saveAndRefreshReport(i));
    }

    public IncidentDto resolve(String id, ResolveRequest r, String user) {
        Incident i = find(id);
        if (!i.statusEnum().isOpen()) throw ApiException.conflict("Incident is already closed.");
        IncidentStatus outcome = r == null || r.outcome() == null ? IncidentStatus.RESOLVED : IncidentStatus.valueOf(r.outcome());
        markResolved(i, outcome, r == null ? null : r.note(), user);
        i.setStatus(outcome.name());
        return toDto(saveAndRefreshReport(i));
    }

    private void markResolved(Incident i, IncidentStatus outcome, String note, String user) {
        Instant now = Instant.now();
        if (i.getAcknowledgedAt() == null) {
            i.setAcknowledgedAt(now);
            i.setAcknowledgedBy(user);
        }
        i.setResolvedAt(now);
        i.setResolvedBy(user);
        i.setResolutionNote(blank(note) ? null : note.trim());
    }

    private Incident saveAndRefreshReport(Incident i) {
        Incident saved = repo.save(i);
        if (writeReport(saved) != null) saved = repo.save(saved);
        return saved;
    }

    public void delete(String id) {
        Incident i = find(id);
        repo.delete(i);
        if (i.getIncidentId() != null && i.getIncidentId().matches("^[A-Za-z0-9_-]{1,64}$")) storage.deleteIncidentFiles(i.getIncidentId());
        log.info("Incident {} deleted", i.getIncidentId());
    }

    /** Returns the report, generating it first when missing. */
    public Path reportFile(String id) {
        Incident i = find(id);
        Path existing = storage.resolveExisting(i.getPdfPath());
        if (existing != null) return existing;
        Path created = writeReport(i);
        if (created == null) throw ApiException.unavailable("The report could not be generated. Please try again.");
        repo.save(i);
        return created;
    }

    public IncidentDto regenerateReport(String id) {
        Incident i = find(id);
        if (writeReport(i) == null) throw ApiException.unavailable("The report could not be generated. Please try again.");
        return toDto(repo.save(i));
    }

    public IncidentDto resendEmail(String id) {
        Incident i = find(id);
        AppSettings s = settings.current();
        return toDto(deliverEmail(i, s, storage.resolveExisting(i.getPdfPath()), true));
    }

    public Path imageFile(String id) {
        Path p = storage.resolveExisting(find(id).getImagePath());
        if (p == null) throw ApiException.notFound("No captured frame is available for this incident.");
        return p;
    }

    // ------------------------------------------------------------------ mapping

    public IncidentDto toDto(Incident i) {
        return new IncidentDto(i.getId(), i.getIncidentId(), i.getDetectionId(), i.getSource(), i.getElderlyName(), i.getElderlyAge(),
                i.getCameraName(), i.getTimestamp(), i.getConfidence(), i.statusEnum().name(),
                storage.resolveExisting(i.getImagePath()) != null, i.getDetection(), i.getAiSummary(), i.getCaregiverRecommendation(),
                i.getAiSource(), i.getAiModel(), storage.resolveExisting(i.getPdfPath()) != null, i.getPdfGeneratedAt(),
                EmailStatus.parse(i.getEmailStatus()).name(), i.getEmailDetail(), i.getEmailAttemptedAt(), i.getAcknowledgedAt(),
                i.getAcknowledgedBy(), i.getAcknowledgementNote(), i.getResolvedAt(), i.getResolvedBy(), i.getResolutionNote(),
                i.getCreatedAt(), i.getUpdatedAt());
    }

    private static String csvCell(String v) {
        if (v == null) return "";
        String s = v.replace("\r", " ").replace("\n", " ");
        if (!s.isEmpty() && "=+-@\t".indexOf(s.charAt(0)) >= 0) s = "'" + s; // spreadsheet formula injection guard
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (!blank(v)) return v.trim();
        return null;
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }

    private static double round2(double v) { return Math.round(v * 1000) / 1000.0; }
}
