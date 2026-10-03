-- ============================================================
-- 社区互助平台 · 百万级造数脚本（地址分离版）
-- 目标：tb_user / tb_user_credit / tb_help_request / tb_order 各 100 万条
-- 前置：tb_address 已导入（区县 id 388~2931，共 2544 个）
--
-- 三表强关联（同一下标 n 对应）：
--   用户 n（id=1000000+n）发布 求助 n（id=2000000+n）
--   订单 n（id=3000000+n）→ 求助 n，发布者=用户 n，接单者=用户 (n%1M+1)
-- 地址：address_id = 388 + (n-1) % 2544，全国区县均匀分布；
--       求助 n 与发布者用户 n 同区县（数据相通）。
--
-- 状态分布（MOD(n,100)）：见各 CASE
--   0~29 评价(订单5/求助3)  30~59 完成(订单3/求助3)  60~79 进行中(订单2/求助2)
--   80~89 已接单(订单1/求助2)  90~99 取消(订单4/求助1, seq=0)
-- 说明：所有测试用户密码均为 123456（BCrypt 哈希）。
-- ============================================================

SET NAMES utf8mb4;
USE linlibang;
SET SESSION cte_max_recursion_depth = 1000001;

-- 幂等：清掉本脚本上次写入的 ID 区间（远离种子数据）
DELETE FROM tb_order        WHERE id      BETWEEN 3000001 AND 4000000;
DELETE FROM tb_help_request WHERE id      BETWEEN 2000001 AND 3000000;
DELETE FROM tb_user_credit  WHERE user_id BETWEEN 1000001 AND 2000000;
DELETE FROM tb_user         WHERE id      BETWEEN 1000001 AND 2000000;

-- 数字表 1..1000000（临时表，会话结束自动消失）
DROP TEMPORARY TABLE IF EXISTS nums;
CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO nums
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000)
SELECT n FROM seq;

-- ============================================================
-- 1. 用户表 100 万条
-- ============================================================
INSERT INTO tb_user
  (id, phone, password, nickname, avatar, gender, address_id, help_count, balance, intro,
   role_id, status, is_builtin, version, create_time, update_time, is_deleted)
SELECT
  1000000 + n,
  CONCAT('13', LPAD(n, 9, '0')),
  '$2a$10$LhunkeJYTLwzqaep.dxzbOMlvG8wrDUU1ctBomo2on6zq4/rv3RXC',
  CONCAT('邻居', n),
  CONCAT('https://api.dicebear.com/7.x/avataaars/svg?seed=u', n),
  MOD(n, 3),
  388 + MOD(n - 1, 2544),
  IF(MOD(IF(n=1,1000000,n-1),100) < 60, 1, 0),
  IF(MOD(n,10)=0, 0, MOD(n*37+5,296)+5) + MOD(n*53,500) + 50,
  CONCAT('社区互助平台注册用户，编号', n, '，乐于助人'),
  2, 1, 0, 0,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR,
  0
FROM nums;

-- ============================================================
-- 2. 用户信用分表 100 万条（1:1 对应用户，85~100 分）
-- ============================================================
INSERT INTO tb_user_credit (user_id, credit)
SELECT 1000000 + n, 85 + MOD(n*29, 16)
FROM nums;

-- ============================================================
-- 3. 求助信息表 100 万条
-- ============================================================
INSERT INTO tb_help_request
  (id, user_id, category_id, title, description, images, reward, address_id,
   status, helper_num, accepted_num, total_reward, urgent, view_count, version,
   create_time, update_time, is_deleted)
SELECT
  2000000 + n,
  1000000 + n,
  MOD(n-1,10)+1,
  CONCAT(ELT(MOD(n-1,10)+1,'家电维修','管道疏通','搬家搬运','代取快递','宠物照看','家教辅导','电脑维修','家政保洁','老人陪护','其他帮助'),
         '，',
         ELT(MOD(n,9)+1,'求上门帮忙','急需师傅','找人搭把手','有偿求助','求热心邻居','急！求帮忙','求代劳','求专业师傅','求助各位邻居')),
  CONCAT(ELT(MOD(n-1,10)+1,'家电维修','管道疏通','搬家搬运','代取快递','宠物照看','家教辅导','电脑维修','家政保洁','老人陪护','其他帮助'),
         ELT(MOD(n,9)+1,'求上门帮忙','急需师傅','找人搭把手','有偿求助','求热心邻居','急！求帮忙','求代劳','求专业师傅','求助各位邻居'),
         '，小区内可上门，时间灵活，具体可私信详谈。'),
  NULL,
  IF(MOD(n,10)=0, 0, MOD(n*37+5,296)+5),
  388 + MOD(n - 1, 2544),
  CASE WHEN MOD(n,100) < 60 THEN 3 WHEN MOD(n,100) < 90 THEN 2 ELSE 1 END,
  1,
  CASE WHEN MOD(n,100) < 90 THEN 1 ELSE 0 END,
  IF(MOD(n,10)=0, 0, MOD(n*37+5,296)+5),
  IF(MOD(n,7)=0, 1, 0),
  MOD(n*127, 1000),
  0,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR,
  0
FROM nums;

-- ============================================================
-- 4. 订单表 100 万条
-- ============================================================
INSERT INTO tb_order
  (id, help_id, publisher_id, helper_id, status, cancel_reason, accept_time, finish_time,
   publisher_score, helper_score, publisher_comment, helper_comment, version, is_deleted, seq,
   create_time, update_time)
SELECT
  3000000 + n,
  2000000 + n,
  1000000 + n,
  1000000 + MOD(n,1000000) + 1,
  CASE WHEN MOD(n,100)<30 THEN 5
       WHEN MOD(n,100)<60 THEN 3
       WHEN MOD(n,100)<80 THEN 2
       WHEN MOD(n,100)<90 THEN 1
       ELSE 4 END,
  CASE WHEN MOD(n,100)>=90
       THEN ELT(MOD(n,6)+1,'临时有事，取消接单','需求已自行解决','时间对不上','发布者取消了求助','距离太远，取消','多次联系不上')
       ELSE NULL END,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR + INTERVAL (MOD(n*13,72)+1) HOUR,
  CASE WHEN MOD(n,100)<60
       THEN NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR
            + INTERVAL (MOD(n*13,72)+1 + MOD(n*29,48)+1) HOUR
       ELSE NULL END,
  CASE WHEN MOD(n,100)<30 THEN 4 + MOD(n,2)    ELSE NULL END,
  CASE WHEN MOD(n,100)<30 THEN 4 + MOD(n*13,2) ELSE NULL END,
  CASE WHEN MOD(n,100)<30
       THEN ELT(MOD(n,5)+1,'师傅技术很好，五星好评！','态度热情，干活麻利，非常满意','很专业，解决了大问题，感谢！','准时守信，值得信赖','帮忙很及时，谢谢！')
       ELSE NULL END,
  CASE WHEN MOD(n,100)<30
       THEN ELT(MOD(n,5)+1,'邻居人很好，配合默契','很愉快的合作','发布者很客气，好邻居','沟通顺畅，五星好评','乐于助人，合作愉快')
       ELSE NULL END,
  0,
  0,
  CASE WHEN MOD(n,100)>=90 THEN 0 ELSE 1 END,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR + INTERVAL (MOD(n*13,72)+1) HOUR,
  NOW() - INTERVAL MOD(n*97+13,365) DAY - INTERVAL MOD(n*31+7,24) HOUR + INTERVAL (MOD(n*13,72)+1) HOUR
FROM nums;

-- ============================================================
-- 校验（应各为 1000000）
-- ============================================================
SELECT 'tb_user'         AS tbl, COUNT(*) AS cnt FROM tb_user;
SELECT 'tb_user_credit'  AS tbl, COUNT(*) AS cnt FROM tb_user_credit;
SELECT 'tb_help_request' AS tbl, COUNT(*) AS cnt FROM tb_help_request;
SELECT 'tb_order'        AS tbl, COUNT(*) AS cnt FROM tb_order;
