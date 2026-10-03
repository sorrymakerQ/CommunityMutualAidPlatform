SET NAMES utf8mb4;
USE linlibang;



DELETE FROM tb_order WHERE id >= 401;
DELETE FROM tb_help_request WHERE id >= 301;
DELETE FROM tb_user WHERE phone LIKE '138000%';
ALTER TABLE tb_order AUTO_INCREMENT = 401;
ALTER TABLE tb_help_request AUTO_INCREMENT = 301;
ALTER TABLE tb_user AUTO_INCREMENT = 117;

-- ==========================================
-- 1. 测试用户 - 8人 (id=101~108)
--    注意：信用分已拆分到 tb_user_credit（见下方第 5 节）
-- ==========================================
INSERT INTO `tb_user` (`id`, `phone`, `password`, `nickname`, `avatar`, `gender`, `address_id`, `help_count`, `intro`, `role_id`, `status`) VALUES
(109, '13800000001', '$2a$10$Pzba09X/CciE/sFDkfFbo.Bb958m1s5Cc9dlvvM4079Rkqk109Bxu', '张大爷',   'https://api.dicebear.com/7.x/avataaars/svg?seed=zhang', 1, 388, 12, '退休工人，热心肠，啥都能帮',         2, 1),
(110, '13800000002', '$2a$10$/Rj0v11LYCCz9liyoJOAbO2YusIIpyK52TauiTNCUpoIbGSUKR4YK', '小李子',   'https://api.dicebear.com/7.x/avataaars/svg?seed=li',    2, 388, 5,  '大学生，课余时间帮邻居跑腿',         2, 1),
(111, '13800000003', '$2a$10$QWzHjmqO2dMyeUk3X4r2iebyypMPwabEe4XyMbdxddbzSmIkCoo6m', '王大妈',   'https://api.dicebear.com/7.x/avataaars/svg?seed=wang',   2, 390, 20, '社区志愿者，擅长家政保洁',           2, 1),
(112, '13800000004', '$2a$10$brBHl3eWZOOk2bCy6jkr6OCdeAJc7mJEUYYC72zYT8j8LKL24gf3.', '赵师傅',   'https://api.dicebear.com/7.x/avataaars/svg?seed=zhao',   1, 390, 35, '专业维修工，家电水管都能修',         2, 1),
(113, '13800000005', '$2a$10$ZbWaSW4h5Y39NnVlP8I1tOAUGVbp/dHbvMwa97.IHFYWj5FUq.H76', '小陈同学', 'https://api.dicebear.com/7.x/avataaars/svg?seed=chen',   1, 388, 3,  '计算机专业在读，修电脑找我',         2, 1),
(114, '13800000006', '$2a$10$tdfHHgRQA1DEZ9oW51G9LuEBns5NGhN9KlMPV29TSoxrA2iZjS9ZK', '刘阿姨',   'https://api.dicebear.com/7.x/avataaars/svg?seed=liu',    2, 391, 8,  '全职妈妈，擅长照顾老人小孩',         2, 1),
(115, '13800000007', '$2a$10$BFrwz5vwDNXFdlbOg9Wr3.ArVeM0gSx8KX2vyTDdED3/Dpul.7spq', '周跑腿',   'https://api.dicebear.com/7.x/avataaars/svg?seed=zhou',   1, 391, 15, '专业跑腿，风雨无阻',                 2, 1),
(116, '13800000008', '$2a$10$CxeK2W6KRQj7LqCwNK24Y.7rMuosIcizHJLX3R5c1Lepu9Pw0ReU.', '管理员阿明','https://api.dicebear.com/7.x/avataaars/svg?seed=admin', 1, 388, 0,  '平台管理员',                         1, 1);

-- ==========================================
-- 2.1 内置管理员账号（系统内置：不可禁用/删除，拥有全部权限）
-- 账号 super / 密码 123456
-- ==========================================
INSERT INTO `tb_user` (`phone`, `password`, `nickname`, `avatar`, `gender`, `address_id`, `help_count`, `intro`, `role_id`, `status`, `is_builtin`) VALUES
('super', '$2a$10$LhunkeJYTLwzqaep.dxzbOMlvG8wrDUU1ctBomo2on6zq4/rv3RXC', '内置管理员', 'https://api.dicebear.com/7.x/avataaars/svg?seed=super', 0, 388, 0, '系统内置管理员，拥有全部权限', 1, 1, 1);

-- ==========================================
-- 2. 求助信息 - 15条 (id=301~315)
-- ==========================================
INSERT INTO `tb_help_request` (`id`, `user_id`, `category_id`, `title`, `description`, `images`, `reward`, `address_id`, `status`, `urgent`, `view_count`) VALUES
(301, 109, 1, '热水器坏了，求师傅上门看看',         '家里热水器突然不出热水了，显示屏也不亮，怀疑是电路问题。急需修理，希望有经验的师傅上门看看。', NULL, 50.00, 388, 1, 1, 120),
(302, 111, 8, '想找个人擦一下客厅的吊灯够不着',     '客厅水晶吊灯太高了，家里没有合适的梯子，想找个人帮忙擦一下，大概半小时就够了。', NULL, 30.00, 390, 1, 0, 34),
(303, 110, 6, '求教初三数学，函数这块不太懂',       '马上中考了，二次函数和三角函数总是搞混，想找一位有耐心的老师帮忙梳理一下知识点，1-2小时即可。', NULL, 60.00, 388, 1, 0, 42),
(304, 112, 7, '求帮忙装个监控摄像头，不会接线',     '网上买了个监控摄像头，说明书看不懂，电源线网线不会接，求懂弱电的师傅上门装一下。', NULL, 40.00, 390, 1, 0, 17),
(305, 114, 9, '陪我家老太太下楼晒太阳散步',         '老太太八十多了腿脚不便，我在家带孩子走不开。每天下午推轮椅下楼走半小时就行，就在小区里面。', NULL, 50.00, 391, 2, 0, 89),
(306, 109, 2, '厨房水槽下水管漏水，急需疏通',       '水槽下面一直在滴水，橱柜都湿了。应该是下水管接口松了或者堵了，求师傅上门处理。', NULL, 30.00, 388, 2, 1, 203),
(307, 116, 4, '帮我取个降压药，社区医院药房已开好', '药已经开好了直接去窗口报名字拿就行，我在外面开会回不来，下午3点前能送到最好。', NULL, 10.00, 388, 2, 1, 88),
(308, 113, 10, '需要一个移动硬盘拷数据，借半天',    '毕业论文数据要备份，自己的移动硬盘坏了，借一个500G以上的用半天就行，绝对小心使用。', NULL, 0.00, 388, 2, 0, 34),
(309, 113, 7, '笔记本电脑蓝屏开不了机，求助！',     '联想笔记本，按开机键风扇转一下就没反应了，屏幕一直黑的。急用电脑写论文！', NULL, 40.00, 388, 3, 1, 310),
(310, 114, 5, '出差三天，帮忙照看一下我家猫咪',     '布偶猫一只，性格温顺。需要每天来喂一次猫粮+换水+铲猫砂。钥匙放门卫处。', NULL, 60.00, 391, 3, 0, 245),
(311, 111, 8, '年前大扫除，找两个人一起干',         '三室两厅的房子，主要是擦窗户+拖地+清理厨房卫生间。两个人配合大约3-4小时。', NULL, 150.00, 390, 3, 0, 192),
(312, 110, 4, '帮忙取个快递，菜鸟驿站3号柜',       '顺丰快递到了是个小包裹（文件类），我在公司回不去，驿站晚上8点关门。', NULL, 8.00, 388, 3, 1, 135),
(313, 115, 3, '帮搬一个双开门冰箱上楼，缺人手',     '从一楼搬上4楼（无电梯），冰箱比较重需要两个壮汉。我一个人搞不定。', NULL, 100.00, 391, 4, 0, 43),
(314, 110, 10, '需要一个梯子借用一天',             '装窗帘用，家里的梯子不够高。借用一天晚上归还。', NULL, 0.00, 388, 4, 0, 55),
(315, 115, 1, '电风扇不转了，有没有人会修',         '台扇按了开关没反应，怀疑是电机烧了或者电源线断了。', NULL, 15.00, 391, 4, 0, 27);

-- ==========================================
-- 3. 订单记录 - 11条 (id=401~411)
-- ==========================================
INSERT INTO `tb_order` (`id`, `help_id`, `publisher_id`, `helper_id`, `status`, `cancel_reason`, `accept_time`, `finish_time`, `publisher_score`, `helper_score`, `publisher_comment`, `helper_comment`) VALUES
(401, 305, 114, 111, 2, NULL, '2026-07-03 15:00:00', NULL, NULL, NULL, NULL, NULL),
(402, 306, 109, 112, 2, NULL, '2026-07-03 14:00:00', NULL, NULL, NULL, NULL, NULL),
(403, 307, 116, 110, 1, NULL, '2026-07-04 14:30:00', NULL, NULL, NULL, NULL, NULL),
(404, 308, 113, 110, 2, NULL, '2026-07-04 09:00:00', NULL, NULL, NULL, NULL, NULL),
(405, 309, 113, 112, 3, NULL, '2026-06-28 10:00:00', '2026-06-28 16:30:00', NULL, NULL, NULL, NULL),
(406, 310, 114, 115, 3, NULL, '2026-06-25 08:00:00', '2026-06-28 20:00:00', NULL, NULL, NULL, NULL),
(407, 311, 111, 114, 5, NULL, '2026-06-20 09:00:00', '2026-06-20 17:00:00', 5, 5, '刘阿姨干活麻利，窗户擦得跟新的一样，太满意了！', '王大妈人很好，中午还请我吃了顿饭'),
(408, 312, 110, 115, 5, NULL, '2026-06-15 14:00:00', '2026-06-15 14:20:00', 5, 5, '小哥特别快！十分钟就送到了，还帮我带了瓶水', '邻居很热情，取件码给得很清楚'),
(409, 313, 115, 113, 4, '冰箱太大了两个人也搬不动，放弃了', '2026-06-12 08:00:00', NULL, NULL, NULL, NULL, NULL),
(410, 314, 110, 112, 4, '梯子被楼上装修的借走了还没还', '2026-06-10 08:00:00', NULL, NULL, NULL, NULL, NULL),
(411, 315, 115, 112, 4, '拆开看了是电源线被老鼠咬断了，已经自己接好', '2026-06-18 13:00:00', NULL, NULL, NULL, NULL, NULL);

-- ==========================================
-- 4. 回填：支付总额（单价×人数）与用户余额
-- ==========================================
UPDATE tb_help_request SET total_reward = reward * helper_num WHERE total_reward = 0;
UPDATE tb_user SET balance = 500.00 WHERE balance = 0 AND is_builtin = 0;
UPDATE tb_user SET balance = 1000.00 WHERE phone = 'super';

-- ==========================================
-- 5. 信用分（已从 tb_user 垂直拆分到 tb_user_credit，一对一）
-- ==========================================
INSERT INTO `tb_user_credit` (`user_id`, `credit`) VALUES
(109, 98), (110, 100), (111, 95), (112, 100), (113, 100),
(114, 100), (115, 88), (116, 100),
((SELECT id FROM tb_user WHERE phone = 'super'), 100);


