-- =====================================================================================================================
-- V6 — reference data required in every environment (EDU-DDD-001 §5.2 "nạp bằng dữ liệu khởi tạo", §7 roles.code).
-- The 3 system roles; names are the Vietnamese meanings of DDD §7. ids come from AUTO_INCREMENT: explicit,
-- deterministic ids are a rule of the dev seed (db/seed, docs/ai/03 §3.7), not of db/migration — code looks roles up
-- by code (uq_roles_code), never by id. Allowed codes are enforced by ck_roles_code (V1). File encoding: UTF-8.
-- =====================================================================================================================

-- 5.2 roles — Vai trò: student = Học viên, instructor = Giảng viên, admin = Quản trị viên
INSERT INTO roles (code, name) VALUES
    ('student', 'Học viên'),
    ('instructor', 'Giảng viên'),
    ('admin', 'Quản trị viên');
