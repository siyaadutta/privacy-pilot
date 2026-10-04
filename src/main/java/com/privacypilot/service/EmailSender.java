package com.privacypilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over Spring Mail. In a demo environment without a reachable SMTP
 * server, sending failures are logged rather than propagated so the workflow can
 * proceed (the message is still recorded in the database).
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mailSender;

    public EmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * @return true if the email was dispatched to the SMTP server, false if
     *         sending failed (still safe — the caller has persisted the record).
     */
    public boolean send(String from, String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            return true;
        } catch (Exception e) {
            // Do not log the recipient address (PII) or the body. Log only a
            // redacted recipient domain and the error class for diagnostics.
            log.warn("Email send failed (to-domain={}): {}", redactToDomain(to),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** Keep only the domain of an address for logging, dropping the local part. */
    private static String redactToDomain(String address) {
        if (address == null) {
            return "unknown";
        }
        int at = address.indexOf('@');
        return at >= 0 && at < address.length() - 1 ? "***@" + address.substring(at + 1) : "***";
    }
}
