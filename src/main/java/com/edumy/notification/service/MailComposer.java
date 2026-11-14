package com.edumy.notification.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.config.EdumyProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the Vietnamese mails of the SRS mail list (§7) from {@code templates/mail/<name>.html} (Q4: plain HTML with
 * {@code ${name}} placeholders, no template engine). Templates are read once (UTF-8) when the bean is created.
 * <p>Every value is HTML-escaped. A placeholder without a value, a value without a placeholder, or a malformed
 * {@code ${…}} in a template throws {@link IllegalStateException} (fail fast); the message names the template and
 * the placeholder only, never a value (values may be one-time codes, NFR-08).
 * <p>Links point to the SPA (Q5, docs/ai/05 "Front-end routes used in emails"):
 * {@code {edumy.frontend.base-url}/verify-email?token=} and {@code /reset-password?token=}, code URL-encoded;
 * {@code /instructor/invitations} (no code: the invitee logs in and answers there); review results (UC-A10…A12):
 * {@code /courses/{slug}} (public course page) and {@code /instructor/courses/{courseId}} (the instructor's course).
 */
@Service
public class MailComposer {

    static final String VERIFY_EMAIL = "verify-email";
    static final String PASSWORD_RESET = "password-reset";
    static final String PASSWORD_CHANGED = "password-changed";
    static final String CO_INSTRUCTOR_INVITED = "co-instructor-invited";
    /** SPA page listing the caller's pending invitations (UC-I14 / UC-I15), docs/ai/05 "Front-end routes". */
    static final String INVITATIONS_PATH = "/instructor/invitations";
    static final String COURSE_APPROVED = "course-approved";
    static final String COURSE_REJECTED = "course-rejected";
    static final String COURSE_HIDDEN = "course-hidden";
    /** SPA public course page (UC-G04), docs/ai/05 "Front-end routes". */
    static final String PUBLIC_COURSE_PATH = "/courses/";
    /** SPA page of one course of the instructor (UC-I02 / UC-I03 / UC-I18), docs/ai/05 "Front-end routes". */
    static final String INSTRUCTOR_COURSE_PATH = "/instructor/courses/";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter CHANGED_AT =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy '(giờ Việt Nam)'").withZone(VIETNAM);

    private final String baseUrl;
    private final Map<String, String> templates;

    public MailComposer(EdumyProperties properties) {
        this.baseUrl = stripTrailingSlashes(properties.frontend().baseUrl());
        this.templates = Map.of(
                VERIFY_EMAIL, load(VERIFY_EMAIL),
                PASSWORD_RESET, load(PASSWORD_RESET),
                PASSWORD_CHANGED, load(PASSWORD_CHANGED),
                CO_INSTRUCTOR_INVITED, load(CO_INSTRUCTOR_INVITED),
                COURSE_APPROVED, load(COURSE_APPROVED),
                COURSE_REJECTED, load(COURSE_REJECTED),
                COURSE_HIDDEN, load(COURSE_HIDDEN));
    }

    /** UC-G05 step 6 / UC-G06: link with the 24 h email-verification code. */
    public MailMessage emailVerification(String to, String fullName, String rawCode) {
        return compose(VERIFY_EMAIL, to, "Xác thực địa chỉ email tài khoản Edumy", Map.of(
                "fullName", fullName,
                "verifyUrl", link("/verify-email", rawCode)));
    }

    /** UC-G08 step 3: link with the 30 min password-reset code. */
    public MailMessage passwordReset(String to, String fullName, String rawCode) {
        return compose(PASSWORD_RESET, to, "Đặt lại mật khẩu tài khoản Edumy", Map.of(
                "fullName", fullName,
                "resetUrl", link("/reset-password", rawCode)));
    }

    /** UC-G08 step 6 / UC-G12: notice that the password changed; carries no code. */
    public MailMessage passwordChanged(String to, String fullName, Instant changedAt) {
        return compose(PASSWORD_CHANGED, to, "Mật khẩu tài khoản Edumy đã được thay đổi", Map.of(
                "fullName", fullName,
                "changedAt", CHANGED_AT.format(Objects.requireNonNull(changedAt, "changedAt"))));
    }

    /**
     * UC-I13 step 5 (SRS §7 "Lời mời cộng tác"): {@code fullName} (invitee) is invited by {@code inviterName} to
     * co-author {@code courseTitle}; links to the SPA invitations page. The course title stays out of the subject.
     */
    public MailMessage coInstructorInvited(String to, String fullName, String inviterName, String courseTitle) {
        return compose(CO_INSTRUCTOR_INVITED, to, "Lời mời làm đồng giảng viên khóa học trên Edumy", Map.of(
                "fullName", fullName,
                "inviterName", inviterName,
                "courseTitle", courseTitle,
                "invitationsUrl", baseUrl + INVITATIONS_PATH));
    }

    /**
     * UC-A10 step 5 (SRS §7 "Kết quả duyệt"): {@code courseTitle} is published; links to the public course page
     * ({@code slug} is {@code [a-z0-9-]} only, no encoding needed).
     */
    public MailMessage courseApproved(String to, String fullName, String courseTitle, String slug) {
        return compose(COURSE_APPROVED, to, "Khóa học của bạn đã được phê duyệt trên Edumy", Map.of(
                "fullName", fullName,
                "courseTitle", courseTitle,
                "courseUrl", baseUrl + PUBLIC_COURSE_PATH + Objects.requireNonNull(slug, "slug")));
    }

    /** UC-A11 (SRS §7 "từ chối", D-16): the reason, and a link to the course to edit and resubmit (UC-I17). */
    public MailMessage courseRejected(String to, String fullName, String courseTitle, Long courseId, String reason) {
        return compose(COURSE_REJECTED, to, "Khóa học của bạn chưa được phê duyệt trên Edumy", Map.of(
                "fullName", fullName,
                "courseTitle", courseTitle,
                "reason", reason,
                "courseUrl", instructorCourseUrl(courseId)));
    }

    /** UC-A12 step 6 (SRS §7 "ẩn khóa"): the reason; enrolled students keep their access (BR-31). */
    public MailMessage courseHidden(String to, String fullName, String courseTitle, Long courseId, String reason) {
        return compose(COURSE_HIDDEN, to, "Khóa học của bạn đã bị ẩn trên Edumy", Map.of(
                "fullName", fullName,
                "courseTitle", courseTitle,
                "reason", reason,
                "courseUrl", instructorCourseUrl(courseId)));
    }

    private String instructorCourseUrl(Long courseId) {
        return baseUrl + INSTRUCTOR_COURSE_PATH + Objects.requireNonNull(courseId, "courseId");
    }

    private MailMessage compose(String template, String to, String subject, Map<String, String> values) {
        return new MailMessage(template, to, subject, fill(template, templates.get(template), values));
    }

    private String link(String path, String rawCode) {
        return baseUrl + path + "?token=" + URLEncoder.encode(Objects.requireNonNull(rawCode, "rawCode"),
                StandardCharsets.UTF_8);
    }

    /**
     * Replaces every {@code ${name}} of {@code template} by the HTML-escaped value, in one pass (a value that itself
     * contains {@code ${…}} is not expanded again).
     *
     * @throws IllegalStateException placeholder without value, value without placeholder, malformed {@code ${}
     */
    static String fill(String templateName, String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        Set<String> used = new HashSet<>();
        int placeholders = 0;
        while (matcher.find()) {
            placeholders++;
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException(
                        "Mail template " + templateName + ": no value for placeholder ${" + name + "}");
            }
            used.add(name);
            matcher.appendReplacement(out, Matcher.quoteReplacement(HtmlUtils.htmlEscape(value, "UTF-8")));
        }
        matcher.appendTail(out);
        if (countOccurrences(template, "${") != placeholders) {
            throw new IllegalStateException("Mail template " + templateName + ": malformed ${...} placeholder");
        }
        Set<String> unused = new TreeSet<>(values.keySet());
        unused.removeAll(used);
        if (!unused.isEmpty()) {
            throw new IllegalStateException("Mail template " + templateName + ": no placeholder for " + unused);
        }
        return out.toString();
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    private static String load(String name) {
        ClassPathResource resource = new ClassPathResource("templates/mail/" + name + ".html");
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Mail template missing: templates/mail/" + name + ".html", e);
        }
    }

    private static String stripTrailingSlashes(String url) {
        String value = Objects.requireNonNull(url, "edumy.frontend.base-url").strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException("edumy.frontend.base-url must not be blank");
        }
        return value;
    }
}
