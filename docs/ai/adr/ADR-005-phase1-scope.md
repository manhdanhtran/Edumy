# ADR-005 — Phase 1: real reads, real simple writes, stubbed complex flows
Status: Accepted · Date: 2026-11-12

Context: the course step asks for "API mockup" so the front end can start; the team chose to use real MySQL data.
Decision: every GET is backed by MySQL (dev seed). Simple writes are real. T1, QR/payment webhook, order cancel,
reconciliation, certificate PDF and schedulers are `STUB-P1` endpoints with the final contract (see `06-phase-plan.md`).
Consequences: the front end never needs to change URLs or DTOs in phase 2; remaining work is discoverable with
`grep -rn "STUB-P1"`.
