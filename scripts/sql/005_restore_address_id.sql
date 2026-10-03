-- ============================================================
-- 回退地址模型：字符串 address → address_id + tb_address（外键/字典表）
-- 保留 tb_help_request.address_detail（详细地址，省市区之后的街道/小区/门牌）
-- 前置：已执行 001_tb_address.sql（重建 tb_address，2931 行）
-- 回填策略：address 与 tb_address.full_name 精确匹配回填；
--          匹配不到的（如生成的非直辖市区县名）回退默认 388（北京·东城区）
-- ============================================================
USE linlibang;

-- 给 full_name 加临时索引，加速 200 万行的 LEFT JOIN 回填
ALTER TABLE tb_address ADD KEY idx_full_name (full_name);

-- 1. tb_user：加 address_id → 回填 → 删 address
ALTER TABLE tb_user ADD COLUMN address_id BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）' AFTER intro;
UPDATE tb_user u LEFT JOIN tb_address a ON a.full_name = u.address SET u.address_id = a.id;
UPDATE tb_user SET address_id = 388 WHERE address_id IS NULL;
ALTER TABLE tb_user ADD KEY idx_address_id (address_id);
ALTER TABLE tb_user DROP COLUMN address;

-- 2. tb_help_request：加 address_id → 回填 → 删 address（保留 address_detail）
ALTER TABLE tb_help_request ADD COLUMN address_id BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）' AFTER total_reward;
UPDATE tb_help_request h LEFT JOIN tb_address a ON a.full_name = h.address SET h.address_id = a.id;
UPDATE tb_help_request SET address_id = 388 WHERE address_id IS NULL;
ALTER TABLE tb_help_request ADD KEY idx_address_id (address_id);
ALTER TABLE tb_help_request DROP COLUMN address;

-- 移除临时索引，保持 tb_address 与 001 原始结构一致
ALTER TABLE tb_address DROP KEY idx_full_name;
