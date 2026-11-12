package com.smartelderly.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelderly.config.AppProperties;
import com.smartelderly.model.DetectionDetails;
import com.smartelderly.model.EmailStatus;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Database-free tests for PDF rendering, fallback analysis, storage safety and legacy value parsing. */
class ReportingTest {

    @TempDir
    Path tmp;

    private AppProperties props(String groqKey) {
        return new AppProperties(tmp.toString(), "Asia/Kolkata", new AppProperties.Cors(List.of("http://localhost:5173")),
                new AppProperties.Security("", 12, true, "", "", "Admin"),
                new AppProperties.Ml("http://localhost:8000", "", 30),
                new AppProperties.Groq(groqKey, "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1/chat/completions", 20),
                new AppProperties.Mail("", ""), new AppProperties.Detection(0.7, 5));
    }

    private Incident sample(IncidentStatus status) {
        Incident i = new Incident();
        i.setIncidentId("INC-20261102-TEST1");
        i.setElderlyName("Margaret Smith");
        i.setElderlyAge(81);
        i.setCameraName("Living Room Camera");
        i.setTimestamp(Instant.parse("2026-11-02T08:15:30Z"));
        i.setConfidence(0.87);
        i.setStatus(status.name());
        i.setEmailStatus(EmailStatus.NOT_CONFIGURED.name());
        DetectionDetails d = new DetectionDetails();
        d.setVerificationStatus("CONFIRMED");
        d.setVerificationSeconds(5.0);
        d.setObservedDownSeconds(4.6);
        d.setFallStartSeconds(23.4);
        d.setPostureBefore("STANDING");
        d.setPostureAfter("LYING");
        d.setMaxPeopleDetected(2);
        d.setFramesAnalyzed(174);
        d.setModelName("yolov8n-pose.pt");
        d.setVideoName("cafe-camera.mp4");
        d.setReasons(List.of("standing -> lying within 1.5 s", "hip dropped 0.31 body-heights"));
        i.setDetection(d);
        return i;
    }

    @Test
    void fallbackAnalysisIsFactualAndDoesNotDiagnose() {
        GroqService groq = new GroqService(props(""), new ObjectMapper());
        GroqService.AiResult verified = groq.analyze(sample(IncidentStatus.VERIFIED));
        assertEquals(GroqService.SOURCE_FALLBACK, verified.source());
        assertTrue(verified.summary().contains("Margaret Smith"));
        assertFalse(verified.summary().toLowerCase().contains("injur"));
        GroqService.AiResult pending = groq.analyze(sample(IncidentStatus.PENDING));
        assertTrue(pending.summary().contains("not sufficient to confirm"));
    }

    @Test
    void pdfIsRenderedWithAndWithoutImage() throws Exception {
        StorageService storage = new StorageService(props(""));
        PdfService pdf = new PdfService(storage, props(""));
        Incident i = sample(IncidentStatus.VERIFIED);
        i.setAiSummary("A fall was detected and verified.");
        i.setCaregiverRecommendation("Check on the resident in person.");
        Path frame = Paths.get("..", "ml-service", "storage", "cli", "frame.jpg");
        if (Files.isRegularFile(frame)) i.setImagePath(storage.saveFrame(i.getIncidentId(), Files.readAllBytes(frame)));

        Path report = pdf.create(i);
        byte[] bytes = Files.readAllBytes(report);
        assertTrue(bytes.length > 1000);
        assertEquals("%PDF", new String(bytes, 0, 4));
        // Copy for manual inspection of the layout.
        Files.createDirectories(Paths.get("target"));
        Files.copy(report, Paths.get("target", "sample-report.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        i.setImagePath("does/not/exist.jpg");
        i.setStatus(IncidentStatus.FALSE_ALARM.name());
        assertTrue(Files.size(pdf.create(i)) > 1000, "missing images are handled gracefully");
    }

    @Test
    void storageRejectsUnsafeIdsAndNonImages() {
        StorageService storage = new StorageService(props(""));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> storage.incidentDir("../../etc"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> storage.saveFrame("INC-1", "not an image".getBytes()));
        assertNull(storage.resolveExisting("../../secret.txt"));
    }

    @Test
    void legacyValuesAreTolerated() {
        assertEquals(EmailStatus.NOT_CONFIGURED, EmailStatus.parse("NOT CONFIGURED"));
        assertEquals(IncidentStatus.PENDING, IncidentStatus.parse(null));
        assertEquals(IncidentStatus.FALSE_ALARM, IncidentStatus.parse("false alarm"));
        assertNotNull(EmailService.mask("caregiver@example.com"));
        assertEquals("c***@example.com", EmailService.mask("caregiver@example.com"));
    }
}
