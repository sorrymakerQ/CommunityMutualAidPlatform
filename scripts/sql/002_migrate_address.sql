-- ============================================================
-- 地址分离迁移脚本（针对已有库，MySQL 8.0 标准语法）
-- 将 tb_user / tb_help_request 的地址字段拆到 tb_address（address_id 外键）
-- 前置：已执行 001_tb_address.sql（创建并导入 tb_address）
-- 注意：一次性迁移，假定旧列/旧索引存在；重复执行会报错（列已不存在）
-- ============================================================
USE linlibang;

ALTER TABLE tb_user
  DROP INDEX idx_community,
  DROP INDEX idx_lng_lat,
  DROP COLUMN community,
  DROP COLUMN lng,
  DROP COLUMN lat,
  ADD COLUMN address_id BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）' AFTER intro,
  ADD KEY idx_address_id (address_id);

ALTER TABLE tb_help_request
  DROP INDEX idx_lng_lat,
  DROP COLUMN address,
  DROP COLUMN lng,
  DROP COLUMN lat,
  ADD COLUMN address_id BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）' AFTER total_reward,
  ADD KEY idx_address_id (address_id);

-- 存量行回填默认地址（北京·东城区 id=388），避免 address_id 为 NULL
UPDATE tb_user SET address_id = 388 WHERE address_id IS NULL;
UPDATE tb_help_request SET address_id = 388 WHERE address_id IS NULL;
