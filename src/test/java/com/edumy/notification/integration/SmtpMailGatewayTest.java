package com.edumy.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import com.edumy.shared.config.EdumyProperties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;

/** Spring Mail adapter: UTF-8 HTML with the configured From; drops with a WARN when no mail host is configured. */
@ExtendWith(OutputCaptureExtension.class)
class SmtpMailGatewayTest {

    static final String FROM = "no-reply@edumy.test";
    static final MailMessage MAIL = new MailMessage("verify-email", "hoc.vien@edumy.test",
            "Xác thực địa chỉ email tài khoản Edumy", "<p>Xin chào Nguyễn Văn Ấ, mã secret-code-123</p>");

    @Test
    void send_noJavaMailSender_dropsWithWarnNamingTemplateOnly(CapturedOutput output) {
        SmtpMailGateway gateway = new SmtpMailGateway(
                new DefaultListableBeanFactory().getBeanProvider(JavaMailSender.class), properties());

        gateway.send(MAIL); // no exception

        assertThat(output.getAll())
                .contains("Mail dropped, no mail host configured: template=verify-email")
                .doesNotContain("hoc.vien@edumy.test")
                .doesNotContain("secret-code-123");
    }

    @Test
    void send_withJavaMailSender_utf8HtmlFromConfiguredAddress() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mailSender", sender);

        new SmtpMailGateway(beans.getBeanProvider(JavaMailSender.class), properties()).send(MAIL);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage mime = sent.getValue();
        mime.saveChanges();
        assertThat(mime.getFrom()).containsExactly(new InternetAddress(FROM));
        assertThat(mime.getRecipients(Message.RecipientType.TO))
                .containsExactly(new InternetAddress("hoc.vien@edumy.test"));
        assertThat(mime.getSubject()).isEqualTo("Xác thực địa chỉ email tài khoản Edumy");
        assertThat(mime.getContentType()).startsWith("text/html").containsIgnoringCase("charset=UTF-8");
        assertThat(mime.getContent()).isEqualTo(MAIL.htmlBody());
    }

    private static EdumyProperties properties() {
        return new EdumyProperties(null, null, null, null, new EdumyProperties.Frontend("http://localhost:5173"),
                new EdumyProperties.Mail(FROM));
    }
}
