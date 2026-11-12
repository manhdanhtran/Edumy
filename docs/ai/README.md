# docs/ai — Bộ tri thức cho AI agent (đọc theo nhu cầu, không nạp hết một lúc)

| File | Đọc khi |
|---|---|
| 01-architecture.md | **cấu trúc thư mục chuẩn** (ADR-007), class mới đặt ở đâu, đặt tên, gọi chéo module, event, transaction |
| 02-domain-glossary.md | cần hiểu nghiệp vụ, trạng thái, BR/D |
| 03-data-model-mysql.md | viết SQL, migration, entity |
| 04-security.md | auth, phân quyền, rate limit |
| 05-api-contract.md | viết controller/DTO, lỗi, cache |
| 06-phase-plan.md | biết endpoint nào thật / stub, Definition of Done |
| 07-testing.md | viết test |
| adr/ | lý do của các quyết định lệch khỏi tài liệu gốc |

Cài vào repo: chép `AGENTS.md`, `CLAUDE.md`, `.cursor/`, `.github/`, `docs/` vào **gốc repo backend**.
- Cursor, GitHub Copilot, Codex, Gemini CLI đọc `AGENTS.md` trực tiếp.
- Claude Code đọc `CLAUDE.md`, file này import `@AGENTS.md` (lần đầu Claude Code hỏi xác nhận import — chọn đồng ý).
- Scoped rules: `.cursor/rules/*.mdc` (Cursor) và `.github/instructions/*.instructions.md` (Copilot) là **hai bản giống nhau**; sửa một thì sửa cả hai (prompt P13).
  Rule theo thư mục: `10` SQL · `20` entity/ + repository/ · `25` service/ · `30` controller/ + dto/ · `35` api/ · `40` test.
