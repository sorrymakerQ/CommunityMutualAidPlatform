-- ============================================================
-- 反向迁移：address_id + tb_address → 字符串 address + address_detail
-- 将 tb_user / tb_help_request 的 address_id 外键模型还原为内联字符串地址
-- 前置：tb_address 表仍在（用于回填 full_name）
-- 说明：只动地址相关三处，不重建表，保留其它数据
-- ============================================================
USE linlibang;

-- 1. tb_user：加 address 列 → 回填 full_name → 删 address_id
ALTER TABLE tb_user ADD COLUMN address VARCHAR(192) DEFAULT NULL COMMENT '所在地址（省市区）' AFTER intro;
UPDATE tb_user u LEFT JOIN tb_address a ON a.id = u.address_id SET u.address = a.full_name;
ALTER TABLE tb_user DROP INDEX idx_address_id, DROP COLUMN address_id;

-- 2. tb_help_request：加 address + address_detail → 回填 → 删 address_id
ALTER TABLE tb_help_request ADD COLUMN address VARCHAR(192) DEFAULT NULL COMMENT '求助地址（省市区）' AFTER total_reward;
ALTER TABLE tb_help_request ADD COLUMN address_detail VARCHAR(256) DEFAULT NULL COMMENT '详细地址（街道/小区/门牌等）' AFTER address;
UPDATE tb_help_request h LEFT JOIN tb_address a ON a.id = h.address_id SET h.address = a.full_name;
ALTER TABLE tb_help_request DROP INDEX idx_address_id, DROP COLUMN address_id;

-- 3. 删除不再使用的 tb_address 表
DROP TABLE IF EXISTS tb_address;
