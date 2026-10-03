-- ==========================================================
-- 010 支付单补「商户支付单号 pay_no」与「支付渠道 channel」
--   · pay_no：对外唯一标识，作支付宝 out_trade_no。
--             发起支付时塞给支付宝，异步通知里原样带回来，
--             靠它 selectByPayNo 定位支付单（此前错用 tb_order.order_no，
--             而 tb_order 在支付阶段还没有这行，回调必然查不到）。
--   · channel：0余额 1支付宝；NULL=尚未支付（渠道在支付成功那一刻才确定）。
--   · 历史数据一并回填，保证新旧数据口径一致（pay_no 非空且唯一）。
--   · 执行前把原表数据就地备份到 bak_tb_pay_order_010，可整体回滚。
--   · 回滚方式（结构与数据都回到执行前）：
--       DROP TABLE tb_pay_order;
--       CREATE TABLE tb_pay_order (
--           id BIGINT NOT NULL AUTO_INCREMENT COMMENT '支付订单ID',
--           help_id BIGINT NOT NULL COMMENT '关联求助ID',
--           publisher_id BIGINT NOT NULL COMMENT '发布者ID',
--           amount DECIMAL(10,2) NOT NULL COMMENT '支付总额',
--           status TINYINT DEFAULT 0 COMMENT '状态 0待支付 1已支付 2已取消',
--           pay_time DATETIME DEFAULT NULL COMMENT '支付时间',
--           create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
--           PRIMARY KEY (id), UNIQUE KEY uk_help_id (help_id),
--           KEY idx_publisher_id (publisher_id), KEY idx_status (status)
--       ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单表（余额支付）';
--       INSERT INTO tb_pay_order (id, help_id, publisher_id, amount, status, pay_time, create_time)
--         SELECT id, help_id, publisher_id, amount, status, pay_time, create_time
--         FROM bak_tb_pay_order_010;
-- ==========================================================
SET NAMES utf8mb4;
USE linlibang;

-- ---------- ① 就地备份（回滚用） ----------
DROP TABLE IF EXISTS bak_tb_pay_order_010;
CREATE TABLE bak_tb_pay_order_010 AS SELECT * FROM tb_pay_order;

-- ---------- ② 加列 ----------
-- 先允许 NULL：表里已有数据，直接加非空列会失败
ALTER TABLE tb_pay_order
    ADD COLUMN pay_no VARCHAR(32) NULL COMMENT '商户支付单号（对外唯一，作支付宝 out_trade_no）' AFTER id,
    ADD COLUMN channel TINYINT DEFAULT NULL COMMENT '支付渠道 0余额 1支付宝；NULL=尚未支付（渠道在支付成功时才确定）' AFTER status;

-- ---------- ③ 回填历史数据 ----------
-- 规则：LB + create_time(秒精度) + id 左补零 6 位
--   与 OrderNoUtils 生成的新单号同风格（LB 前缀 + 时间 + 随机/序号），
--   因内嵌自增 id 而天然唯一，且一眼能看出创建时间，便于排查。
--   历史行全部是未支付（status=0、pay_time 为 NULL），channel 保持 NULL 即为正确口径。
UPDATE tb_pay_order
SET pay_no = CONCAT('LB', DATE_FORMAT(IFNULL(create_time, NOW()), '%Y%m%d%H%i%s'), LPAD(id, 6, '0'))
WHERE pay_no IS NULL OR pay_no = '';

-- 渠道回填：channel 的语义是"NULL = 尚未支付"，所以已支付(1)/已取消(2)的历史单不能留空。
-- 历史数据只可能走余额：支付宝链路当时尚未跑通（alipay() 取错表直接 NPE、notify 改的是
-- tb_order 且永远返回 false），退款也只有余额原路退回一条路。故一律回填 0=余额。
UPDATE tb_pay_order SET channel = 0 WHERE status IN (1, 2) AND channel IS NULL;

-- ---------- ④ 收紧约束：pay_no 非空 + 唯一索引 ----------
ALTER TABLE tb_pay_order
    MODIFY COLUMN pay_no VARCHAR(32) NOT NULL COMMENT '商户支付单号（对外唯一，作支付宝 out_trade_no）',
    ADD UNIQUE KEY uk_pay_no (pay_no);

-- ---------- ⑤ 校验 ----------
-- 应满足：空单号 = 0；总行数 = 不同单号数；已支付/已取消但渠道为空 = 0（NULL 只能配 status=0）
SELECT COUNT(*) AS `空单号数(应为0)` FROM tb_pay_order WHERE pay_no IS NULL OR pay_no = '';
SELECT COUNT(*) AS `总行数`, COUNT(DISTINCT pay_no) AS `不同单号数` FROM tb_pay_order;
SELECT COUNT(*) AS `已支付却无渠道(应为0)` FROM tb_pay_order WHERE status <> 0 AND channel IS NULL;
SELECT status, channel, COUNT(*) AS `行数` FROM tb_pay_order GROUP BY status, channel ORDER BY status;
SELECT id, pay_no, help_id, amount, status, channel, pay_time FROM tb_pay_order ORDER BY id DESC LIMIT 5;
