package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.model.EmailStatus;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends the emergency e-mail through Spring Mail (Gmail SMTP). Never throws: the outcome is returned so the
 * incident records exactly what happened. SENT is only reported after the SMTP server accepted the message.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String host;
    private final String username;
    private final String password;
    private final String from;
    private final DateTimeFormatter dateTime;

    public record Result(EmailStatus status, String detail) {}

    public EmailService(ObjectProvider<JavaMailSender> senderProvider, AppProperties props,
                        @Value("${spring.mail.host:}") String host,
                        @Value("${spring.mail.username:}") String username,
                        @Value("${spring.mail.password:}") String password) {
        this.senderProvider = senderProvider;
        this.host = host;
        this.username = username;
        this.password = password;
        this.from = props.mail().from();
        this.dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of(props.timezone()));
    }

    public boolean smtpConfigured() {
        return notBlank(host) && notBlank(username) && notBlank(password) && senderProvider.getIfAvailable() != null;
    }

    public Result send(Incident i, String recipient, Path pdf) {
        if (!smtpConfigured()) {
            return new Result(EmailStatus.NOT_CONFIGURED, "SMTP credentials (MAIL_USERNAME / MAIL_PASSWORD) are not configured.");
        }
        if (!notBlank(recipient)) {
            return new Result(EmailStatus.NOT_CONFIGURED, "No emergency email address is configured.");
        }
        try {
            JavaMailSender sender = senderProvider.getObject();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper m = new MimeMessageHelper(message, true, "UTF-8");
            m.setTo(recipient);
            m.setFrom(notBlank(from) ? from : username);
            boolean pending = i.statusEnum() == IncidentStatus.PENDING;
            m.setSubject((pending ? "[Review needed] Potential fall - " : "[URGENT] Fall detected - ")
                    + nz(i.getElderlyName()) + " - " + i.getIncidentId());
            m.setText(plainText(i, pending), htmlText(i, pending));
            if (pdf != null) m.addAttachment("fall-report-" + i.getIncidentId() + ".pdf", new FileSystemResource(pdf));
            sender.send(message);
            log.info("Emergency email sent for incident {}", i.getIncidentId());
            return new Result(EmailStatus.SENT, "Delivered to the SMTP server for " + mask(recipient) + ".");
        } catch (Exception e) {
            log.warn("Email for incident {} failed: {}", i.getIncidentId(), e.getClass().getSimpleName());
            return new Result(EmailStatus.FAILED, "SMTP error: " + e.getClass().getSimpleName());
        }
    }

    private String plainText(Incident i, boolean pending) {
        return (pending ? "A potential fall was detected and needs review.\n\n" : "EMERGENCY: A fall has been detected and verified.\n\n")
                + "Incident ID: " + i.getIncidentId() + "\n"
                + "Resident: " + nz(i.getElderlyName()) + (i.getElderlyAge() == null ? "" : " (age " + i.getElderlyAge() + ")") + "\n"
                + "Camera: " + nz(i.getCameraName()) + "\n"
                + "Time: " + (i.getTimestamp() == null ? "-" : dateTime.format(i.getTimestamp())) + "\n"
                + "Detection confidence: " + Math.round(i.getConfidence() * 100) + "%\n"
                + "Status: " + i.statusEnum().name() + "\n\n"
                + (i.getAiSummary() == null ? "" : "Summary: " + i.getAiSummary() + "\n\n")
                + (i.getCaregiverRecommendation() == null ? "" : "Recommended action: " + i.getCaregiverRecommendation() + "\n\n")
                + "Please check on the resident immediately. The full report is attached when available.\n\n"
                + "This is an automated alert from the Smart Elderly Fall Detection Portal. It is not a medical assessment.";
    }

    private String htmlText(Incident i, boolean pending) {
        String accent = pending ? "#b87813" : "#c83f4a";
        String row = "<tr><td style=\"padding:6px 12px;color:#62728a;font-size:12px\">%s</td><td style=\"padding:6px 12px;color:#102a43;font-weight:600\">%s</td></tr>";
        return "<div style=\"font-family:Arial,sans-serif;max-width:600px;margin:auto;border:1px solid #e5ebf3;border-radius:10px;overflow:hidden\">"
                + "<div style=\"background:#102a43;color:#fff;padding:16px 20px\"><b>Smart Elderly Fall Detection Portal</b></div>"
                + "<div style=\"background:" + accent + ";color:#fff;padding:12px 20px;font-weight:700\">"
                + (pending ? "Potential fall detected - review needed" : "EMERGENCY - Fall detected and verified") + "</div>"
                + "<table style=\"width:100%;border-collapse:collapse;margin:12px 0\">"
                + String.format(row, "Incident ID", esc(i.getIncidentId()))
                + String.format(row, "Resident", esc(nz(i.getElderlyName())) + (i.getElderlyAge() == null ? "" : " (age " + i.getElderlyAge() + ")"))
                + String.format(row, "Camera", esc(nz(i.getCameraName())))
                + String.format(row, "Time", i.getTimestamp() == null ? "-" : esc(dateTime.format(i.getTimestamp())))
                + String.format(row, "Confidence", Math.round(i.getConfidence() * 100) + "%")
                + String.format(row, "Status", i.statusEnum().name())
                + "</table>"
                + (i.getAiSummary() == null ? "" : "<p style=\"padding:0 20px;color:#34445a\"><b>Summary.</b> " + esc(i.getAiSummary()) + "</p>")
                + (i.getCaregiverRecommendation() == null ? "" : "<p style=\"padding:0 20px;color:#34445a\"><b>Recommended action.</b> " + esc(i.getCaregiverRecommendation()) + "</p>")
                + "<p style=\"padding:0 20px;color:#34445a\"><b>Please check on the resident immediately.</b></p>"
                + "<p style=\"padding:12px 20px;background:#f5f8fc;color:#62728a;font-size:11px;margin:0\">Automated alert. "
                + "Not a medical assessment. Verify the resident's condition in person.</p></div>";
    }

    private static String esc(String v) { return HtmlUtils.htmlEscape(v == null ? "" : v); }

    private static String nz(String v) { return v == null || v.isBlank() ? "Resident" : v; }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
