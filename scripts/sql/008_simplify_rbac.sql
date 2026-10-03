-- ==========================================================
-- 008 简化 RBAC：只保留「管理员 / 普通用户」两种角色
--   · 角色表只留 id=1 admin、id=2 user
--   · 权限表重写：用户业务权限 + 管理员权限（改状态/踢人/用户管理）
--   · 用户角色归一：3(reviewer)→2(user)、4(super_admin)→1(admin)
-- 执行前已用 mysqldump 备份到 008_rbac_backup.sql，本脚本另建 bak_* 表可回滚
-- ==========================================================
SET NAMES utf8mb4;
USE linlibang;

-- ---------- ① 就地备份（回滚用） ----------
DROP TABLE IF EXISTS bak_tb_role_008;
DROP TABLE IF EXISTS bak_tb_permission_008;
DROP TABLE IF EXISTS bak_tb_role_permission_008;
DROP TABLE IF EXISTS bak_tb_user_role_008;
CREATE TABLE bak_tb_role_008            AS SELECT * FROM tb_role;
CREATE TABLE bak_tb_permission_008      AS SELECT * FROM tb_permission;
CREATE TABLE bak_tb_role_permission_008 AS SELECT * FROM tb_role_permission;
CREATE TABLE bak_tb_user_role_008       AS SELECT id, phone, nickname, role_id, is_builtin FROM tb_user WHERE role_id <> 2;

-- ---------- ② 用户角色归一（先改用户，再删角色，避免出现悬空 role_id） ----------
UPDATE tb_user SET role_id = 2 WHERE role_id = 3;   -- 审核员 → 普通用户
UPDATE tb_user SET role_id = 1 WHERE role_id = 4;   -- 超级管理员 → 管理员

-- ---------- ③ 角色表：只留 admin / user ----------
DELETE FROM tb_role WHERE id NOT IN (1, 2);
INSERT IGNORE INTO tb_role (id, code, name, description, status, is_builtin) VALUES
 (1, 'admin', '管理员',   '平台管理员：管理用户、修改/下架任意求助、踢用户下线', 1, 1),
 (2, 'user',  '普通用户', '默认角色：发布求助、接单、发送私信',                 1, 0);
UPDATE tb_role SET code = 'admin', name = '管理员',
       description = '平台管理员：管理用户、修改/下架任意求助、踢用户下线', status = 1, is_builtin = 1
 WHERE id = 1;
UPDATE tb_role SET code = 'user', name = '普通用户',
       description = '默认角色：发布求助、接单、发送私信', status = 1, is_builtin = 0
 WHERE id = 2;

-- ---------- ④ 权限表重写 ----------
-- type=1 表示接口级权限（与 @SaCheckPermission 的取值一一对应）
DELETE FROM tb_role_permission;
DELETE FROM tb_permission;
INSERT INTO tb_permission (id, code, name, type, sort) VALUES
 (1, 'help:publish',   '发布求助',                 1, 1),
 (2, 'order:accept',   '接单',                     1, 2),
 (3, 'message:send',   '发送私信',                 1, 3),
 (4, 'help:manage',    '管理求助（改状态/下架/删除）', 1, 4),
 (5, 'user:kickout',   '踢用户下线',               1, 5),
 (6, 'user:manage',    '用户管理（禁用/启用/改角色）', 1, 6);

-- ---------- ⑤ 角色-权限关联 ----------
-- 管理员：全部 6 项；普通用户：仅业务 3 项
INSERT INTO tb_role_permission (role_id, permission_id) VALUES
 (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6),
 (2, 1), (2, 2), (2, 3);

-- ---------- ⑥ 结果核对 ----------
SELECT r.id AS role_id, r.code, r.name, GROUP_CONCAT(p.code ORDER BY p.id) AS permissions
FROM tb_role r
LEFT JOIN tb_role_permission rp ON rp.role_id = r.id
LEFT JOIN tb_permission p ON p.id = rp.permission_id
GROUP BY r.id, r.code, r.name
ORDER BY r.id;

SELECT role_id, COUNT(*) AS user_count FROM tb_user GROUP BY role_id ORDER BY role_id;
SELECT id, code, name FROM tb_permission ORDER BY id;
