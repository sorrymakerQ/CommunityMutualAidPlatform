-- ==========================================
-- 社区互助平台 数据库建表脚本
-- ==========================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS linlibang
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE linlibang;

-- ==========================================
-- 用户表
-- ==========================================
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `phone` VARCHAR(11) NOT NULL COMMENT '手机号',
    `password` VARCHAR(128) NOT NULL COMMENT '密码（加密存储）',
    `nickname` VARCHAR(32) DEFAULT NULL COMMENT '昵称',
    `avatar` VARCHAR(256) DEFAULT NULL COMMENT '头像URL',
    `gender` TINYINT DEFAULT 0 COMMENT '性别 0未知 1男 2女',
    `address_id` BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）',
    `credit` INT DEFAULT 100 COMMENT '信用分，默认100',
    `help_count` INT DEFAULT 0 COMMENT '累计帮助次数',
    `balance` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额（支付求助总额用，简单余额体系）',
    `intro` VARCHAR(256) DEFAULT NULL COMMENT '个人简介',
    `role_id` BIGINT NOT NULL DEFAULT 2 COMMENT '角色ID（关联 tb_role，默认2=普通用户）',
    `status` TINYINT DEFAULT 1 COMMENT '状态 0禁用 1正常',
    `is_builtin` TINYINT DEFAULT 0 COMMENT '系统内置账号（1=内置，不可禁用/删除，如超级管理员）',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（每次修改+1，信用分/帮助次数并发更新时校验）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ==========================================
-- 角色表（RBAC：身份，"你是谁"）
-- ==========================================
DROP TABLE IF EXISTS `tb_role`;
CREATE TABLE `tb_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `code` VARCHAR(32) NOT NULL COMMENT '角色编码（Sa-Token 校验用，小写）：admin/user',
    `name` VARCHAR(32) NOT NULL COMMENT '角色名称：超级管理员/管理员/普通用户/审核员',
    `description` VARCHAR(128) DEFAULT NULL COMMENT '描述',
    `status` TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
    `is_builtin` TINYINT DEFAULT 0 COMMENT '系统内置角色（1=内置，不可删除），如超级管理员',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表（身份）';

-- 初始角色数据（只有两种角色，id 固定：1管理员 2普通用户）
INSERT INTO `tb_role` (`id`, `code`, `name`, `description`, `is_builtin`) VALUES
(1, 'admin', '管理员',   '平台管理员：管理用户、修改/下架任意求助、踢用户下线', 1),
(2, 'user',  '普通用户', '默认角色：发布求助、接单、发送私信', 0);

-- ==========================================
-- 权限表（RBAC：功能点，"你能做什么"）
-- ==========================================
DROP TABLE IF EXISTS `tb_permission`;
CREATE TABLE `tb_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `code` VARCHAR(64) NOT NULL COMMENT '权限码（对应 @SaCheckPermission 的值）：help:publish 等',
    `name` VARCHAR(32) NOT NULL COMMENT '功能名：发布求助/接单/审核求助',
    `type` TINYINT DEFAULT 1 COMMENT '类型 1接口 2按钮 3菜单',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表（功能点）';

-- 初始权限数据（与代码中的 @SaCheckPermission 权限码一一对应）
--   1~3 业务权限（用户 + 管理员都有）
--   4~6 管理权限（仅管理员）
INSERT INTO `tb_permission` (`id`, `code`, `name`, `type`, `sort`) VALUES
(1, 'help:publish',  '发布求助',                    1, 1),
(2, 'order:accept',  '接单',                        1, 2),
(3, 'message:send',  '发送私信',                    1, 3),
(4, 'help:manage',   '管理求助（改状态/下架/删除）', 1, 4),
(5, 'user:kickout',  '踢用户下线',                  1, 5),
(6, 'user:manage',   '用户管理（禁用/启用/改角色）', 1, 6);

-- ==========================================
-- 角色-权限关联表（RBAC：什么角色能用什么功能）
-- ==========================================
DROP TABLE IF EXISTS `tb_role_permission`;
CREATE TABLE `tb_role_permission` (
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- 初始关联：admin 全权限（业务 + 管理）；user 仅业务三项
INSERT INTO `tb_role_permission` (`role_id`, `permission_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6),
(2, 1), (2, 2), (2, 3);

-- ==========================================
-- 求助分类表
-- ==========================================
DROP TABLE IF EXISTS `tb_category`;
CREATE TABLE `tb_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name` VARCHAR(32) NOT NULL COMMENT '分类名称',
    `icon` VARCHAR(256) DEFAULT NULL COMMENT '分类图标',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='求助分类表';

-- 初始分类数据
INSERT INTO `tb_category` (`name`, `icon`, `sort`) VALUES
('家电维修', '🔧', 1),
('管道疏通', '🚰', 2),
('搬家搬运', '📦', 3),
('代取快递', '📬', 4),
('宠物照看', '🐱', 5),
('家教辅导', '📚', 6),
('电脑维修', '💻', 7),
('家政保洁', '🧹', 8),
('老人陪护', '👴', 9),
('其他帮助', '🤝', 10);

-- ==========================================
-- 求助信息表
-- ==========================================
DROP TABLE IF EXISTS `tb_help_request`;
CREATE TABLE `tb_help_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '求助ID',
    `user_id` BIGINT NOT NULL COMMENT '发布者ID',
    `category_id` BIGINT NOT NULL COMMENT '分类ID',
    `title` VARCHAR(128) NOT NULL COMMENT '求助标题',
    `description` TEXT COMMENT '详细描述',
    `images` VARCHAR(1024) DEFAULT NULL COMMENT '图片URL列表，逗号分隔',
    `reward` DECIMAL(10,2) DEFAULT 0 COMMENT '每人单价（酬劳，支付总额=单价×需要人数）',
    `address_id` BIGINT DEFAULT NULL COMMENT '地址ID（关联 tb_address 三级行）',
    `address_detail` VARCHAR(256) DEFAULT NULL COMMENT '详细地址（街道/小区/门牌等，省市区之后）',
    `status` TINYINT DEFAULT 0 COMMENT '状态 0待支付(未上首页) 1招募中 2已满员(进行中) 3已完成 4已取消',
    `helper_num` INT NOT NULL DEFAULT 1 COMMENT '需要人数（多人求助，默认1人）',
    `accepted_num` INT NOT NULL DEFAULT 0 COMMENT '已接人数（达到 helper_num 后状态自动转2）',
    `total_reward` DECIMAL(10,2) DEFAULT 0 COMMENT '支付总额（单价×需要人数，发布时计算）',
    `urgent` TINYINT DEFAULT 0 COMMENT '是否紧急 0否 1是',
    `view_count` INT DEFAULT 0 COMMENT '浏览次数',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（每次修改+1，用于并发更新的版本校验）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='求助信息表';

-- ==========================================
-- 订单表（接单记录）
-- ==========================================
DROP TABLE IF EXISTS `tb_order`;
CREATE TABLE `tb_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `help_id` BIGINT NOT NULL COMMENT '求助ID',
    `publisher_id` BIGINT NOT NULL COMMENT '发布者ID',
    `helper_id` BIGINT NOT NULL COMMENT '接单者ID',
    `total_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '订单总金额（下单时求助发布总金额快照 = 每人单价×需要人数）',
    `status` TINYINT DEFAULT 1 COMMENT '状态 1已接单 2进行中 3已完成 4已取消 5已评价',
    `cancel_reason` VARCHAR(256) DEFAULT NULL COMMENT '取消原因',
    `accept_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '接单时间',
    `finish_time` DATETIME DEFAULT NULL COMMENT '完成时间',
    `publisher_score` TINYINT DEFAULT NULL COMMENT '发布者评分 1-5',
    `helper_score` TINYINT DEFAULT NULL COMMENT '接单者评分 1-5',
    `publisher_comment` VARCHAR(512) DEFAULT NULL COMMENT '发布者评价',
    `helper_comment` VARCHAR(512) DEFAULT NULL COMMENT '接单者评价',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（每次修改+1，评价/取消/完成时用于并发更新校验）',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除（业务禁止物理删除订单，一律状态迁移）',
    `seq` TINYINT NOT NULL DEFAULT 0 COMMENT '接单序号（唯一键 uk_help_helper_seq 组成部分）：活跃订单=1，取消订单=0（释放键位，同一人可重新接同一求助）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_help_id` (`help_id`),
    KEY `idx_publisher_id` (`publisher_id`),
    KEY `idx_helper_id` (`helper_id`),
    KEY `idx_status` (`status`),
    UNIQUE KEY `uk_help_helper_seq` (`help_id`, `helper_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约单（接单时生成，一个求助最多 helper_num 条）';

-- ==========================================
-- 订单状态审计表（谁在何时把订单从什么状态改成什么状态）
-- ==========================================
DROP TABLE IF EXISTS `tb_order_status_log`;
CREATE TABLE `tb_order_status_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    `order_id` BIGINT NOT NULL COMMENT '订单ID',
    `from_status` TINYINT DEFAULT NULL COMMENT '变更前状态（创建时为NULL）',
    `to_status` TINYINT NOT NULL COMMENT '变更后状态',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作人ID（系统操作为NULL）',
    `operator_type` VARCHAR(16) NOT NULL COMMENT '操作方：USER-用户 SYSTEM-系统',
    `reason` VARCHAR(256) DEFAULT NULL COMMENT '变更原因',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_operator_id` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态审计表';

-- ==========================================
-- 支付订单表（发起求助时生成，支付成功后求助才上首页）
-- ==========================================
DROP TABLE IF EXISTS `tb_pay_order`;
CREATE TABLE `tb_pay_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '支付订单ID',
    `pay_no` VARCHAR(32) NOT NULL COMMENT '商户支付单号（对外唯一，作支付宝 out_trade_no）',
    `help_id` BIGINT NOT NULL COMMENT '关联求助ID',
    `publisher_id` BIGINT NOT NULL COMMENT '发布者ID',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '支付总额（单价×需要人数）',
    `status` TINYINT DEFAULT 0 COMMENT '状态 0待支付 1已支付 2已取消',
    `channel` TINYINT DEFAULT NULL COMMENT '支付渠道 0余额 1支付宝；NULL=尚未支付（渠道在支付成功时才确定）',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_no` (`pay_no`),
    UNIQUE KEY `uk_help_id` (`help_id`),
    KEY `idx_publisher_id` (`publisher_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单表（余额/支付宝）';

-- ==========================================
-- 评价表（独立评价模块：订单双方互评，与用户/订单业务解耦）
-- 每笔订单最多两条评价：发布者评接单者、接单者评发布者
-- ==========================================
DROP TABLE IF EXISTS `tb_review`;
CREATE TABLE `tb_review` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评价ID',
    `order_id` BIGINT NOT NULL COMMENT '关联订单ID',
    `from_user_id` BIGINT NOT NULL COMMENT '评价人ID',
    `to_user_id` BIGINT NOT NULL COMMENT '被评价人ID',
    `score` TINYINT NOT NULL COMMENT '评分 1-5',
    `comment` VARCHAR(512) DEFAULT NULL COMMENT '评价内容',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_from` (`order_id`, `from_user_id`),
    KEY `idx_to_user` (`to_user_id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价表（独立评价模块）';

-- ==========================================
-- 信用分流水表（信用分增减全记录，缺分/漏分可查）
-- ==========================================
DROP TABLE IF EXISTS `tb_credit_log`;
CREATE TABLE `tb_credit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `delta` INT NOT NULL COMMENT '变动值（正加负减）',
    `reason` VARCHAR(64) NOT NULL COMMENT '变动原因：评价5星/评价低分/完成订单/取消订单',
    `order_id` BIGINT DEFAULT NULL COMMENT '关联订单ID（可空）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '变动时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分流水表';

-- 已有库升级（MySQL 8.0.13+，避免重复执行）：
-- ④ 余额 + 支付订单（v3 升级）：
-- ALTER TABLE tb_user ADD COLUMN balance DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额' AFTER help_count;
-- ALTER TABLE tb_help_request
--   ADD COLUMN total_reward DECIMAL(10,2) DEFAULT 0 COMMENT '支付总额' AFTER accepted_num;
-- ALTER TABLE tb_help_request MODIFY COLUMN reward DECIMAL(10,2) DEFAULT 0 COMMENT '每人单价';
-- CREATE TABLE IF NOT EXISTS tb_pay_order (...见上方结构...);
-- ⑤ 评价模块（v5 升级）：tb_review / tb_credit_log 结构见上方，直接复制 CREATE TABLE 执行
-- ⑥ 接单去审批（v6 升级，即接即录用）：申请/审批环节已删除，申请表不再需要
-- DROP TABLE IF EXISTS tb_help_apply;
-- ⑦ 支付单号 + 支付渠道（v7 升级）：支付单补商户单号 pay_no（作支付宝 out_trade_no）与渠道 channel
--    完整脚本（含历史数据回填与备份）见 scripts/sql/010_pay_order_pay_no_channel.sql
-- ALTER TABLE tb_pay_order
--   ADD COLUMN pay_no VARCHAR(32) NULL COMMENT '商户支付单号（对外唯一，作支付宝 out_trade_no）' AFTER id,
--   ADD COLUMN channel TINYINT DEFAULT NULL COMMENT '支付渠道 0余额 1支付宝；NULL=尚未支付' AFTER status;
-- UPDATE tb_pay_order SET pay_no = CONCAT('LB', DATE_FORMAT(IFNULL(create_time, NOW()), '%Y%m%d%H%i%s'), LPAD(id, 6, '0')) WHERE pay_no IS NULL;
-- ALTER TABLE tb_pay_order
--   MODIFY COLUMN pay_no VARCHAR(32) NOT NULL COMMENT '商户支付单号（对外唯一，作支付宝 out_trade_no）',
--   ADD UNIQUE KEY uk_pay_no (pay_no);
-- ⑧ 订单号归位（v8 升级）：履约单 tb_order 不再持有自己的编号，「订单号」由支付单 tb_pay_order.pay_no 承载
--    完整脚本（含备份）见 scripts/sql/011_drop_order_no_from_tb_order.sql
--    ⚠️ 必须先删唯一索引，否则 INSTANT 会被拒绝（ERROR 1845 ALGORITHM=INSTANT is not supported）
-- ALTER TABLE tb_order DROP INDEX uk_order_no;
-- ALTER TABLE tb_order DROP COLUMN order_no, ALGORITHM=INSTANT;

-- ==========================================
-- 消息通知表
-- ==========================================
DROP TABLE IF EXISTS `tb_notification`;
CREATE TABLE `tb_notification` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id` BIGINT NOT NULL COMMENT '接收者ID',
    `title` VARCHAR(128) NOT NULL COMMENT '通知标题',
    `content` VARCHAR(512) DEFAULT NULL COMMENT '通知内容',
    `type` TINYINT DEFAULT 1 COMMENT '类型 1系统通知 2订单通知 3评价通知',
    `is_read` TINYINT DEFAULT 0 COMMENT '是否已读 0未读 1已读',
    `related_id` BIGINT DEFAULT NULL COMMENT '关联业务ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '通知时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_is_read` (`is_read`),
    UNIQUE KEY `uk_user_related_type` (`user_id`, `related_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通知表';

-- 已有库升级（唯一键含 user_id，同一订单/求助可分别通知双方，避免一人收到后另一方漏发）：
-- ALTER TABLE tb_notification DROP INDEX uk_related_type, ADD UNIQUE KEY uk_user_related_type (user_id, related_id, type);

-- ==========================================
-- 聊天消息表
-- ==========================================
DROP TABLE IF EXISTS `tb_chat_message`;
CREATE TABLE `tb_chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `help_id` BIGINT DEFAULT NULL COMMENT '关联求助ID（求助私信场景，订单聊天时为NULL）',
    `order_id` BIGINT DEFAULT NULL COMMENT '关联订单ID（求助私信场景为NULL）',
    `sender_id` BIGINT NOT NULL COMMENT '发送者ID',
    `receiver_id` BIGINT NOT NULL COMMENT '接收者ID',
    `content` VARCHAR(500) NOT NULL COMMENT '消息内容',
    `is_read` TINYINT DEFAULT 0 COMMENT '是否已读 0未读 1已读',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_help_id` (`help_id`),
    KEY `idx_sender_id` (`sender_id`),
    KEY `idx_receiver_id` (`receiver_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';

-- ==========================================
-- 存量库 RBAC 升级（MySQL 8.0.13+，避免重复执行）：
-- ==========================================
-- ① 建三张 RBAC 表（结构见上方 CREATE TABLE，可单独复制执行）
-- ② 用户表：删 permissions/role，加 role_id（旧 role=2 管理员映射为角色1）
-- ALTER TABLE tb_user
--   ADD COLUMN role_id BIGINT NOT NULL DEFAULT 2 COMMENT '角色ID' AFTER intro,
--   ADD KEY idx_role_id (role_id);
-- UPDATE tb_user SET role_id = 1 WHERE role = 2;
-- ALTER TABLE tb_user
--   DROP COLUMN permissions,
--   DROP COLUMN role;
-- ③ 内置账号标记（v2 升级）：
-- ALTER TABLE tb_role
--   ADD COLUMN is_builtin TINYINT DEFAULT 0 COMMENT '系统内置角色(1=内置不可删除)' AFTER status;
-- ALTER TABLE tb_user
--   ADD COLUMN is_builtin TINYINT DEFAULT 0 COMMENT '系统内置账号(1=内置不可禁用/删除)' AFTER status;
-- ④ 简化 RBAC：只保留 admin/user，重写权限表（见 scripts/sql/008_simplify_rbac.sql）：
-- UPDATE tb_user SET role_id = 2 WHERE role_id = 3;   -- 审核员 → 普通用户
-- UPDATE tb_user SET role_id = 1 WHERE role_id = 4;   -- 超级管理员 → 管理员
-- DELETE FROM tb_role WHERE id NOT IN (1, 2);
--   （权限码：1 help:publish / 2 order:accept / 3 message:send /
--     4 help:manage / 5 user:kickout / 6 user:manage）

