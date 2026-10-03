-- ==========================================================
-- 009 接单去审批：删除「老板审批制」接单申请表
--   · 业务变更：接单由「提交申请 → 发布者审批同意/拒绝」改为「即接即录用」，
--     接单即生成订单并占用名额，申请表 tb_help_apply（0待确认/1已同意/2已拒绝）
--     及其审批理由字段不再有任何业务含义
--   · 执行前把原表数据就地备份到 bak_tb_help_apply_009，需要时可回滚
--   · 回滚方式：
--       CREATE TABLE tb_help_apply AS SELECT * FROM bak_tb_help_apply_009;
-- ==========================================================
SET NAMES utf8mb4;
USE linlibang;

-- ---------- ① 就地备份（回滚用） ----------
DROP TABLE IF EXISTS bak_tb_help_apply_009;
CREATE TABLE bak_tb_help_apply_009 AS SELECT * FROM tb_help_apply;

-- ---------- ② 删除申请表 ----------
DROP TABLE IF EXISTS tb_help_apply;
