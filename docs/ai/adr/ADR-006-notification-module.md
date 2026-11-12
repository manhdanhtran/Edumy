# ADR-006 — Separate `notification` module for emails
Status: Accepted · Date: 2026-11-12

Decision: all emails (SRS §7 list of 8 mail types) are sent by `notification` listeners reacting to module events,
through a `MailGateway` port (dev: Spring Mail → Mailpit on localhost:1025). Failures are logged and retried later;
they never roll back business transactions (BR-20). Templates in `templates/mail/*.html` (Vietnamese).
