# ADR-005 — Phase 1: real reads, real simple writes, stubbed complex flows
Status: Accepted · Date: 2026-11-12

Context: the course step asks for "API mockup" so the front end can start; the team chose to use real MySQL data.
Decision: every GET is backed by MySQL (dev seed). Simple writes are real. T1, QR/payment webhook, order cancel,
reconciliation, certificate PDF and schedulers are `STUB-P1` endpoints with the final contract (see `06-phase-plan.md`).
Consequences: the front end never needs to change URLs or DTOs in phase 2; remaining work is discoverable with
`grep -rn "STUB-P1"`.

**Closed (2026-11-16):** every phase-1 stub was replaced — commerce in P11 (ADR-011), the certificate PDF in P12
(ADR-013), schedulers in P11-e (ADR-012). `StubSupport` and the `X-Edumy-Stub` header were removed in P13;
`grep -rn "STUB-P1" src/main` is empty.
