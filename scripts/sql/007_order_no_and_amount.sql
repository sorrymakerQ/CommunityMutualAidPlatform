-- 007_order_no_and_amount.sql
-- tb_order 增加两个字段（已有数据库执行本脚本；全新库看 db/schema.sql 即可）：
--   1. order_no     订单号：随机生成、对外展示 / 对账用，与求助号（tb_help_request.id）彻底分开
--   2. total_amount 订单总金额：下单时把求助的发布总金额快照下来（每人单价 × 需要人数）
-- 对应代码：OrderNoUtils（生成）、OrderMapper.insert（写入）、OrderServiceImpl.approveApply（下单时赋值）
USE linlibang;

-- ---------------------------------------------------------------------------
-- 1. 订单号：先加可空列 → 回填历史数据 → 再收紧为 NOT NULL + 唯一索引
--    （老数据用"下单时间 + 补零ID"派生：ID 唯一 ⇒ 生成的订单号一定唯一，
--      这样下面加唯一索引不会因为历史数据冲突而失败）
-- ---------------------------------------------------------------------------
ALTER TABLE tb_order
    ADD COLUMN order_no VARCHAR(32) NULL COMMENT '订单号（随机生成，对外展示/对账用；与求助号无关）' AFTER id;

UPDATE tb_order
SET order_no = CONCAT(
        'LB',
        DATE_FORMAT(COALESCE(create_time, NOW()), '%Y%m%d%H%i%s'),
        LPAD(id, 10, '0')
    )
WHERE order_no IS NULL OR order_no = '';

ALTER TABLE tb_order
    MODIFY COLUMN order_no VARCHAR(32) NOT NULL COMMENT '订单号（随机生成，对外展示/对账用；与求助号无关）';

ALTER TABLE tb_order
    ADD UNIQUE KEY `uk_order_no` (`order_no`);

-- ---------------------------------------------------------------------------
-- 2. 订单总金额：默认 0，再用求助的发布总金额回填
-- ---------------------------------------------------------------------------
ALTER TABLE tb_order
    ADD COLUMN total_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '订单总金额（下单时求助发布总金额快照 = 每人单价×需要人数）' AFTER helper_id;

UPDATE tb_order o
    JOIN tb_help_request h ON h.id = o.help_id
SET o.total_amount = COALESCE(h.total_reward, 0);

-- ---------------------------------------------------------------------------
-- 3. 自检（可选，执行后人工看一眼）
-- ---------------------------------------------------------------------------
-- 订单号是否有重复（应为 0 行）：
-- SELECT order_no, COUNT(*) c FROM tb_order GROUP BY order_no HAVING c > 1;
-- 订单号/总金额样例：
-- SELECT id, order_no, help_id, total_amount, status FROM tb_order ORDER BY id DESC LIMIT 10;
