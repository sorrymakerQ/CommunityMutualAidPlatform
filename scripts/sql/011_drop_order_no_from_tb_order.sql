-- ==========================================================
-- 011 订单号归位：从履约单 tb_order 移除 order_no
--   · 业务变更：「订单号」这一概念由支付单 tb_pay_order.pay_no 承载
--     （它同时就是支付宝的 out_trade_no），履约单不再持有自己的编号。
--   · 理由：tb_order 是「接单」时才生成的履约单（一个求助最多 helper_num 条），
--     它的 order_no 既不参与支付链路、前端也从未展示，
--     支付回调按它反查必然失败（支付阶段该表还没有行）。
--   · 备份只存 (id, order_no) 两列（约 30 MB），足以按 id 回填还原；
--     整表 340 MB / 98.7 万行没必要复制。
--   · 因 C 盘仅剩 1.8 GB、而 MySQL 临时目录在 C:\WINDOWS\TEMP，
--     这里显式使用 ALGORITHM=INSTANT（MySQL 8.0.29+ 支持 DROP COLUMN 即时化）：
--     只改数据字典、不重建表、几乎不占临时空间。
--   · 回滚方式：
--       ALTER TABLE tb_order ADD COLUMN order_no VARCHAR(32) NULL COMMENT '订单号' AFTER id;
--       UPDATE tb_order o JOIN bak_tb_order_orderno_011 b ON o.id = b.id SET o.order_no = b.order_no;
--       ALTER TABLE tb_order MODIFY COLUMN order_no VARCHAR(32) NOT NULL COMMENT '订单号（随机生成，对外展示/对账用；与求助号无关）',
--         ADD UNIQUE KEY uk_order_no (order_no);
-- ==========================================================
SET NAMES utf8mb4;
USE linlibang;

-- ---------- ① 备份（只存要删的列 + 主键，回滚够用） ----------
DROP TABLE IF EXISTS bak_tb_order_orderno_011;
CREATE TABLE bak_tb_order_orderno_011 AS
SELECT id, order_no FROM tb_order;

-- ---------- ② 先删唯一索引 ----------
-- 二级索引删除不需要重建表，几乎瞬时。
-- ⚠️ 必须放在删列之前：order_no 参与索引时 INSTANT 会被拒绝
--    （ERROR 1845: ALGORITHM=INSTANT is not supported for this operation）
ALTER TABLE tb_order DROP INDEX uk_order_no;

-- ---------- ③ 删列 ----------
ALTER TABLE tb_order DROP COLUMN order_no, ALGORITHM=INSTANT;

-- ---------- ④ 校验 ----------
SELECT COUNT(*) AS `备份行数` FROM bak_tb_order_orderno_011;
SELECT COUNT(*) AS `tb_order 行数` FROM tb_order;
SELECT 'order_no 是否还在' AS 检查, COUNT(*) AS 剩余列数
FROM information_schema.columns
WHERE table_schema = 'linlibang' AND table_name = 'tb_order' AND column_name = 'order_no';
SHOW INDEX FROM tb_order;
