# ADR-006 — Separate `notification` module for emails
Status: Accepted · Date: 2026-11-12

Decision: all emails (SRS §7 list of 8 mail types) are sent by `notification` listeners reacting to module events,
through a `MailGateway` port (dev: Spring Mail → Mailpit on localhost:1025). Failures are logged and retried later;
they never roll back business transactions (BR-20). Templates in `templates/mail/*.html` (Vietnamese).

## Implementation notes (P04-d, 2026-11-13)
- **Listener annotation.** `@ApplicationModuleListener` lives in `spring-modulith-events-api`, which
  `spring-modulith-starter-core` 2.1.1 does not bring in. Under the "no new dependency" rule the identity mail
  listeners use what that annotation is built from: `@Async` + `@TransactionalEventListener` (AFTER_COMMIT), without
  its `REQUIRES_NEW` transaction (mails need none). Consequence: an event published **outside** a transaction is
  dropped silently, so every publisher must publish inside one (all identity publishers do). Switching to the real
  annotation later = add `spring-modulith-events-api` (BOM-managed) and replace the two annotations.
- **No automatic retry.** "Retried later" above is not implemented in phase 1: a failed mail is logged (event type +
  userId + exception class only, NFR-08) and dropped. The user recovers through the SRS "gửi lại" paths: authenticated
  verification resend (UC-G06, 60 s cooldown) and forgot password (UC-G08). Durable retry would need the Modulith
  event publication registry (`spring-modulith-starter-jpa` + its table) = new dependency + schema change → new ADR.
