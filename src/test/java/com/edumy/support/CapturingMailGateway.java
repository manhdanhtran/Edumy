package com.edumy.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.edumy.notification.integration.MailGateway;
import com.edumy.notification.integration.MailMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;

/**
 * The {@code @Primary} {@link MailGateway} of {@link MySqlSpringBootTest} tests: records every mail the notification
 * listeners send instead of dropping it, and can be told to fail the next sends to one address
 * ({@link #failNextSendsTo}). Mails are sent asynchronously after commit, so tests wait with {@link #awaitMail} /
 * {@link #awaitFailuresConsumed} (Awaitility, bounded). Filter by recipient (UUID-unique emails): late mails of an
 * earlier test may still arrive. Cleared after every test by {@link CapturingMailGatewayResetExtension}.
 */
@Primary
public class CapturingMailGateway implements MailGateway {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    private final List<MailMessage> failed = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> pendingFailures = new ConcurrentHashMap<>();

    @Override
    public void send(MailMessage message) {
        String to = normalize(message.to());
        boolean[] fail = {false};
        pendingFailures.computeIfPresent(to, (key, left) -> {
            fail[0] = true;
            return left > 1 ? left - 1 : null;
        });
        if (fail[0]) {
            failed.add(message);
            // deliberately quotes the recipient and the body: proves the listener never logs exception messages
            throw new MailSendException("Simulated SMTP failure for " + message.to() + ": " + message.htmlBody());
        }
        sent.add(message);
    }

    /** The next {@code times} sends to {@code to} throw {@link MailSendException}. */
    public void failNextSendsTo(String to, int times) {
        pendingFailures.put(normalize(to), times);
    }

    /** Mails successfully sent to {@code to} so far, oldest first. */
    public List<MailMessage> sentTo(String to) {
        String normalized = normalize(to);
        return sent.stream().filter(m -> normalize(m.to()).equals(normalized)).toList();
    }

    /** Mails to {@code to} whose send was made to fail, oldest first. */
    public List<MailMessage> failedTo(String to) {
        String normalized = normalize(to);
        return failed.stream().filter(m -> normalize(m.to()).equals(normalized)).toList();
    }

    /** Waits until {@code count} mails of {@code template} were sent to {@code to}; returns them, oldest first. */
    public List<MailMessage> awaitMails(String to, String template, int count) {
        await().atMost(TIMEOUT).until(() -> ofTemplate(to, template).size() >= count);
        return ofTemplate(to, template);
    }

    /** Waits for the first mail of {@code template} sent to {@code to}. */
    public MailMessage awaitMail(String to, String template) {
        return awaitMails(to, template, 1).getFirst();
    }

    /** Waits until every failure armed by {@link #failNextSendsTo} for {@code to} was used. */
    public void awaitFailuresConsumed(String to) {
        String normalized = normalize(to);
        await().atMost(TIMEOUT).until(() -> !pendingFailures.containsKey(normalized));
    }

    public void reset() {
        sent.clear();
        failed.clear();
        pendingFailures.clear();
    }

    private List<MailMessage> ofTemplate(String to, String template) {
        return sentTo(to).stream().filter(m -> m.template().equals(template)).toList();
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
