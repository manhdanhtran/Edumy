# P12 — Thay STUB-P1 bằng logic thật (giai đoạn 2) — PLAN FIRST

```text
Task: replace the stub for {{UC}} ({{ENDPOINT}}) with the real implementation.
Read: SRS UC {{UC}} (main/alternative/exception flows), related BR/D, docs/ai/01 §1.11 (transactions),
docs/ai/03 (tables), docs/ai/04 (authorization).
Plan first: (a) sequence of steps inside the transaction with locks (SELECT … FOR UPDATE) and idempotency key;
(b) every precondition → ErrorCode; (c) state transitions; (d) events published after commit;
(e) cross-module calls through api only (EnrollmentApi propagation MANDATORY for payment, ADR-004);
(f) tests: happy, each exception flow, concurrency (two threads same student/order → exactly one effect),
replay idempotency (send same result 10× → 1 effect, NFR-04).
Wait for approval, implement, remove the STUB-P1 marker and header, update docs/ai/06 stub list, run tests, report.
```
