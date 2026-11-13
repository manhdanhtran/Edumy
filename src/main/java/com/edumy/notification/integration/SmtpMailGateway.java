package com.edumy.notification.integration;

import java.nio.charset.StandardCharsets;

import com.edumy.shared.config.EdumyProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends UTF-8 HTML mails through Spring Mail ({@code JavaMailSender}; Mailpit on localhost:1025 in dev),
 * {@code From:} = {@code edumy.mail.from}. Boot creates the {@code JavaMailSender} only when {@code spring.mail.host}
 * is set: without it (test profile) every mail is dropped with a WARN naming the template only (no recipient, no
 * body: NFR-08).
 */
@Component
public class SmtpMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailGateway.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public SmtpMailGateway(ObjectProvider<JavaMailSender> mailSender, EdumyProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.mail().from();
    }

    @Override
    public void send(MailMessage message) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("Mail dropped, no mail host configured: template={}", message.template());
            return;
        }
        MimeMessage mime = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);
        } catch (MessagingException e) {
            // message names the template only: never the recipient or the body (NFR-08)
            throw new MailPreparationException("Could not build mail: template=" + message.template(), e);
        }
        sender.send(mime);
    }
}
