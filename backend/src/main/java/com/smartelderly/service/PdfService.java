package com.smartelderly.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.smartelderly.config.AppProperties;
import com.smartelderly.model.DetectionDetails;
import com.smartelderly.model.EmailStatus;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders the incident report PDF with OpenPDF. */
@Service
public class PdfService {
    private static final Color NAVY = new Color(16, 42, 67);
    private static final Color BLUE = new Color(39, 105, 217);
    private static final Color PALE = new Color(243, 247, 252);
    private static final Color LINE = new Color(223, 230, 239);
    private static final Color MUTED = new Color(98, 114, 135);
    private static final Color BODY = new Color(52, 68, 90);
    private static final Color GREEN = new Color(22, 132, 100);
    private static final Color AMBER = new Color(184, 120, 19);
    private static final Color RED = new Color(200, 63, 74);

    private static final Font BRAND = new Font(Font.HELVETICA, 13.5f, Font.BOLD, Color.WHITE);
    private static final Font BRAND_SUB = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, new Color(196, 214, 235));
    private static final Font TITLE = new Font(Font.HELVETICA, 17, Font.BOLD, NAVY);
    private static final Font H2 = new Font(Font.HELVETICA, 10.5f, Font.BOLD, NAVY);
    private static final Font LABEL = new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED);
    private static final Font VALUE = new Font(Font.HELVETICA, 10, Font.NORMAL, NAVY);
    private static final Font TEXT = new Font(Font.HELVETICA, 10, Font.NORMAL, BODY);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font CHIP = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);

    private final StorageService storage;
    private final ZoneId zone;
    private final DateTimeFormatter dateTime;

    public PdfService(StorageService storage, AppProperties props) {
        this.storage = storage;
        this.zone = ZoneId.of(props.timezone());
        this.dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z", Locale.ENGLISH).withZone(zone);
    }

    /** Writes the report atomically (temp file + move) and returns its path. */
    public Path create(Incident i) throws Exception {
        Path target = storage.reportPath(i.getIncidentId());
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "report", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                render(i, out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private void render(Incident i, OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4, 40, 40, 34, 50);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        String generated = dateTime.format(Instant.now());
        writer.setPageEvent(new Footer(i.getIncidentId(), generated));
        doc.addTitle("Fall Incident Report " + i.getIncidentId());
        doc.addAuthor("Smart Elderly Fall Detection Portal");
        doc.open();

        // Brand header band
        PdfPTable header = new PdfPTable(new float[]{3.4f, 1.6f});
        header.setWidthPercentage(100);
        PdfPCell brand = cell(null, NAVY, 14);
        brand.addElement(new Paragraph("SMART ELDERLY FALL DETECTION PORTAL", BRAND));
        brand.addElement(new Paragraph("Smarter Monitoring. Faster Response. Safer Lives.", BRAND_SUB));
        PdfPCell meta = cell(null, NAVY, 14);
        Paragraph metaText = new Paragraph("FALL INCIDENT REPORT\nGenerated " + generated, BRAND_SUB);
        metaText.setAlignment(Element.ALIGN_RIGHT);
        meta.addElement(metaText);
        header.addCell(brand);
        header.addCell(meta);
        doc.add(header);
        doc.add(spacer(12));

        // Title + status chip
        IncidentStatus status = i.statusEnum();
        PdfPTable titleRow = new PdfPTable(new float[]{4, 1.4f});
        titleRow.setWidthPercentage(100);
        PdfPCell title = cell(null, null, 2);
        title.addElement(new Paragraph("Incident " + i.getIncidentId(), TITLE));
        title.addElement(new Paragraph(statusSentence(status), SMALL));
        PdfPCell chip = cell(new Phrase(status.name().replace('_', ' '), CHIP), statusColor(status), 8);
        chip.setHorizontalAlignment(Element.ALIGN_CENTER);
        chip.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleRow.addCell(title);
        titleRow.addCell(chip);
        doc.add(titleRow);
        doc.add(spacer(10));

        // Details grid
        section(doc, "INCIDENT DETAILS");
        DetectionDetails d = i.getDetection();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"INCIDENT ID", i.getIncidentId(), "STATUS", status.name().replace('_', ' ')});
        rows.add(new String[]{"RESIDENT", nz(i.getElderlyName()), "AGE", i.getElderlyAge() == null ? "Not recorded" : i.getElderlyAge() + " years"});
        rows.add(new String[]{"CAMERA", nz(i.getCameraName()), "DATE & TIME", i.getTimestamp() == null ? "-" : dateTime.format(i.getTimestamp())});
        rows.add(new String[]{"DETECTION CONFIDENCE", Math.round(i.getConfidence() * 100) + "%", "EMAIL NOTIFICATION",
                EmailStatus.parse(i.getEmailStatus()).name().replace('_', ' ')});
        if (d != null) {
            rows.add(new String[]{"VERIFICATION", nz(d.getVerificationStatus()),
                    "TIME ON GROUND", d.getObservedDownSeconds() == null ? "-" : String.format(Locale.ROOT, "%.1f s (window %.0f s)",
                    d.getObservedDownSeconds(), d.getVerificationSeconds() == null ? 0 : d.getVerificationSeconds())});
            rows.add(new String[]{"POSTURE BEFORE / AFTER", nz(d.getPostureBefore()) + " / " + nz(d.getPostureAfter()),
                    "SOURCE VIDEO", d.getVideoName() == null ? "-" : d.getVideoName()
                    + (d.getFallStartSeconds() == null ? "" : String.format(Locale.ROOT, " @ %.1f s", d.getFallStartSeconds()))});
        }
        PdfPTable details = new PdfPTable(new float[]{1.1f, 1.5f, 1.1f, 1.5f});
        details.setWidthPercentage(100);
        for (String[] r : rows) {
            for (int c = 0; c < 4; c++) {
                PdfPCell cell = cell(new Phrase(r[c], c % 2 == 0 ? LABEL : VALUE), c % 2 == 0 ? PALE : Color.WHITE, 7);
                cell.setBorder(Rectangle.BOX);
                cell.setBorderColor(LINE);
                details.addCell(cell);
            }
        }
        doc.add(details);
        doc.add(spacer(12));

        // Captured frame
        section(doc, "CAPTURED FALL FRAME");
        Path image = storage.resolveExisting(i.getImagePath());
        if (image != null) {
            try {
                Image img = Image.getInstance(image.toString());
                img.scaleToFit(515, 215);
                img.setAlignment(Element.ALIGN_CENTER);
                doc.add(img);
                doc.add(new Paragraph("Frame captured by the ML service at the onset of the detected fall, with pose keypoints overlaid.", SMALL));
            } catch (Exception e) {
                doc.add(notice("The captured frame could not be rendered."));
            }
        } else {
            doc.add(notice("No captured frame is available for this incident."));
        }
        doc.add(spacer(12));

        // AI analysis
        boolean ai = GroqService.SOURCE_GROQ.equals(i.getAiSource());
        section(doc, "INCIDENT SUMMARY");
        doc.add(new Paragraph(i.getAiSummary() == null ? "Summary unavailable." : i.getAiSummary(), TEXT));
        doc.add(spacer(8));
        section(doc, "CAREGIVER RECOMMENDATION");
        doc.add(new Paragraph(i.getCaregiverRecommendation() == null ? "Check on the resident in person." : i.getCaregiverRecommendation(), TEXT));
        doc.add(new Paragraph(ai ? "Generated by AI (" + nz(i.getAiModel()) + " via Groq) from detection data."
                : "Generated by the portal's built-in template (AI service unavailable or not configured).", SMALL));
        doc.add(spacer(10));

        // Detection evidence
        if (d != null && d.getReasons() != null && !d.getReasons().isEmpty()) {
            section(doc, "DETECTION EVIDENCE");
            for (String reason : d.getReasons()) {
                Paragraph p = new Paragraph();
                p.add(new Chunk("•  ", TEXT));
                p.add(new Chunk(reason, TEXT));
                doc.add(p);
            }
            doc.add(new Paragraph(String.format(Locale.ROOT, "Model: %s · Frames analysed: %s · People detected: %s",
                    nz(d.getModelName()), d.getFramesAnalyzed() == null ? "-" : d.getFramesAnalyzed(),
                    d.getMaxPeopleDetected() == null ? "-" : d.getMaxPeopleDetected()), SMALL));
            doc.add(spacer(10));
        }

        // Response timeline
        if (i.getAcknowledgedAt() != null || i.getResolvedAt() != null) {
            section(doc, "CAREGIVER RESPONSE");
            if (i.getAcknowledgedAt() != null) {
                doc.add(new Paragraph("Acknowledged " + dateTime.format(i.getAcknowledgedAt()) + " by " + nz(i.getAcknowledgedBy())
                        + (blank(i.getAcknowledgementNote()) ? "" : " — " + i.getAcknowledgementNote()), TEXT));
            }
            if (i.getResolvedAt() != null) {
                doc.add(new Paragraph((status == IncidentStatus.FALSE_ALARM ? "Marked as false alarm " : "Resolved ")
                        + dateTime.format(i.getResolvedAt()) + " by " + nz(i.getResolvedBy())
                        + (blank(i.getResolutionNote()) ? "" : " — " + i.getResolutionNote()), TEXT));
            }
            doc.add(spacer(10));
        }

        PdfPTable disclaimer = new PdfPTable(1);
        disclaimer.setWidthPercentage(100);
        PdfPCell dc = cell(new Phrase("Safety disclaimer: This report was generated automatically from video analysis. "
                + "Automated fall detection can produce false alarms and can miss events. The summary is informational and is "
                + "not a medical assessment or diagnosis. Always verify the resident's condition in person and contact "
                + "emergency services when in doubt.", new Font(Font.HELVETICA, 8, Font.ITALIC, MUTED)), PALE, 10);
        disclaimer.addCell(dc);
        doc.add(disclaimer);
        doc.close();
    }

    private static String statusSentence(IncidentStatus s) {
        return switch (s) {
            case VERIFIED -> "Fall confirmed by temporal verification. Caregiver response required.";
            case PENDING -> "Potential fall with inconclusive evidence. Awaiting caregiver review.";
            case RESOLVED -> "Incident handled and closed by a caregiver.";
            case FALSE_ALARM -> "Reviewed by a caregiver and determined not to be a fall.";
        };
    }

    private static Color statusColor(IncidentStatus s) {
        return switch (s) {
            case VERIFIED -> RED;
            case PENDING -> AMBER;
            case RESOLVED -> GREEN;
            case FALSE_ALARM -> MUTED;
        };
    }

    private static void section(Document doc, String title) throws Exception {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingAfter(5);
        doc.add(p);
    }

    private static PdfPCell cell(Phrase phrase, Color bg, float padding) {
        PdfPCell c = phrase == null ? new PdfPCell() : new PdfPCell(phrase);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(padding);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private static PdfPTable notice(String text) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.addCell(cell(new Phrase(text, TEXT), PALE, 16));
        return t;
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ", new Font(Font.HELVETICA, 2));
        p.setSpacingAfter(height);
        return p;
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }

    private static String nz(String v) { return blank(v) ? "-" : v; }

    private static final class Footer extends PdfPageEventHelper {
        private final String incidentId;
        private final String generated;

        Footer(String incidentId, String generated) {
            this.incidentId = incidentId;
            this.generated = generated;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 22;
            cb.setColorStroke(LINE);
            cb.moveTo(document.left(), y + 12);
            cb.lineTo(document.right(), y + 12);
            cb.stroke();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Smart Elderly Fall Detection Portal · " + incidentId + " · " + generated, SMALL), document.left(), y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), SMALL), document.right(), y, 0);
        }
    }
}
