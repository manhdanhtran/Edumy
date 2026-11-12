# Thư viện prompt — Edumy Backend

Mỗi file là **một bước**, chạy theo thứ tự. Phần prompt viết tiếng Anh (AI bám sát hơn, đỡ token); phần hướng dẫn cho người viết tiếng Việt.
Rule nền (AGENTS.md + docs/ai) đã được tool tự nạp, nên prompt chỉ nêu **nhiệm vụ + ràng buộc riêng của bước**.

| Bước | File | Kết quả | Ai review |
|---|---|---|---|
| 0 | P00-bootstrap.md | Project chạy được, skeleton module, ModularityTest xanh | Trưởng nhóm |
| 1 | P01-flyway-schema.md | V1–V6: 21 bảng, trigger, roles | Người phụ trách DB |
| 2 | P02-dev-seed.md | Seed dev phủ mọi trạng thái | Cả nhóm |
| 3 | P03-entities-repositories.md | Entity + repo từng module, `validate` pass | Owner module |
| 4 | P04-security.md | JWT + refresh token + method security | Trưởng nhóm |
| 5 | P05-read-endpoints.md | Toàn bộ GET chạy dữ liệu thật | Owner module + FE |
| 6 | P06-simple-writes.md | Write đơn giản chạy thật | Owner module |
| 7 | P07-stubs.md | Endpoint phức tạp trả mẫu đúng contract | Owner module + FE |
| 8 | P08-caching.md | Caffeine cache + eviction | Trưởng nhóm |
| 9 | P09-tests.md | Test ràng buộc, IDOR, module | Cả nhóm |
| — | P10-code-review.md | Review PR theo rule dự án | Dùng mỗi PR |
| — | P11-debug.md | Sửa lỗi có kỷ luật | Khi gặp bug |
| — | P12-implement-use-case.md | Thay một STUB-P1 bằng logic thật (giai đoạn 2) | Owner module |
| — | P13-update-decisions.md | Ghi nhận quyết định mới vào ADR/AGENTS | Trưởng nhóm |

## Cách dùng
1. Mỗi bước mở **phiên chat mới** (context sạch), dán prompt, thay các `{{…}}`.
2. Với Claude Code: thêm `@docs/ai/03-data-model-mysql.md` (hoặc file liên quan) vào prompt. Cursor: `@file`. Copilot: `#file`.
3. Bước có chữ "PLAN FIRST": bắt AI trình bày kế hoạch, **bạn duyệt rồi mới cho viết code**.
4. AI kết thúc phải báo: file đổi, UC/BR, test đã chạy + kết quả. Không có phần này = chưa xong.
5. Commit theo từng bước nhỏ: `feat(authoring): add chapter reorder [UC-I08]`.

## Đưa tài liệu nghiệp vụ vào repo
Đặt bản gốc vào `docs/requirements/` (SRS v3.0, DDD v1.0). Bản DDD hiện thiếu mục 10–11 và Phụ lục A–B; nếu tìm được bản đủ thì thay vào và cập nhật `docs/ai/01-architecture.md` §1.11.
