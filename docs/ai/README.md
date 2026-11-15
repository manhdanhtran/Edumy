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
| 09-constraint-coverage.md | biết ràng buộc nào của DDD §8 (hàng 1-31) do test nào chứng minh; mỗi bảng có test hàng hợp lệ nào |
| 10-idor-coverage.md | biết endpoint nào có path id thuộc người dùng, quan hệ chủ sở hữu và test nào chứng minh 404 giống hệt id không tồn tại (NFR-01); các hàng 403 hợp lệ và endpoint miễn trừ |
| modulith/ | sơ đồ module và quan hệ phụ thuộc/sự kiện do Spring Modulith sinh ra (`components.puml`, `module-*.adoc`); tái tạo bằng `./mvnw -Dtest=ModularityTest test` sau mọi thay đổi module/api |
| adr/ | lý do của các quyết định lệch khỏi tài liệu gốc |

Cài vào repo: chép `AGENTS.md`, `CLAUDE.md`, `.cursor/`, `.github/`, `docs/` vào **gốc repo backend**.
- Cursor, GitHub Copilot, Codex, Gemini CLI đọc `AGENTS.md` trực tiếp.
- Claude Code đọc `CLAUDE.md`, file này import `@AGENTS.md` (lần đầu Claude Code hỏi xác nhận import — chọn đồng ý).
- Scoped rules: `.cursor/rules/*.mdc` (Cursor) và `.github/instructions/*.instructions.md` (Copilot) là **hai bản giống nhau**; sửa một thì sửa cả hai (prompt P13).
  Rule theo thư mục: `10` SQL · `20` entity/ + repository/ · `25` service/ · `30` controller/ + dto/ · `35` api/ · `40` test.

## Cấu hình email và route front-end (hợp đồng với nhóm SPA)
| Biến môi trường | Thuộc tính | Mặc định | Ý nghĩa |
|---|---|---|---|
| `EDUMY_FRONTEND_BASE_URL` | `edumy.frontend.base-url` | `http://localhost:5173` | gốc SPA dùng để tạo liên kết trong email |
| `EDUMY_MAIL_FROM` | `edumy.mail.from` | `no-reply@edumy.test` | địa chỉ `From:` của mọi email |

Gửi thư cần `spring.mail.host` (profile dev: Mailpit `localhost:1025`, xem thư tại `http://localhost:8025`); không có
host (profile test) thì thư bị bỏ qua kèm một dòng WARN. Hai route SPA mà email trỏ tới (chi tiết:
`05-api-contract.md` "Front-end routes used in emails"):
- `{base}/verify-email?token=…` → SPA gọi `POST /api/v1/auth/email-verification/confirm` `{token}`.
- `{base}/reset-password?token=…` → SPA hiện form mật khẩu mới rồi gọi `POST /api/v1/auth/password/reset` `{token,newPassword}`.
