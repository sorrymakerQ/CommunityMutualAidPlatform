-- 006_fake_address.sql
-- 把 tb_user / tb_help_request 的地址数据随机捏造：
--   1. address_id 均匀随机铺到全部 2544 个区县（去掉之前 388 默认值的集中）
--   2. tb_help_request.address_detail 填上多样的假门牌（街道/小区/栋/单元/室）
USE linlibang;

-- 1. tb_user：地址ID 随机化
UPDATE tb_user
SET address_id = 388 + FLOOR(RAND() * 2544);

-- 2. tb_help_request：地址ID 随机化 + 详细地址造假（全量覆盖，避免旧数据串号）
UPDATE tb_help_request
SET address_id = 388 + FLOOR(RAND() * 2544),
    address_detail = CONCAT(
        ELT(1 + FLOOR(RAND() * 12),
            '中山路','解放路','人民路','建设路','和平路','文化路',
            '学院路','滨河路','幸福路','朝阳路','红旗路','光明路'),
        FLOOR(1 + RAND() * 260), '号',
        ELT(1 + FLOOR(RAND() * 12),
            '万科城市','阳光花园','幸福里','翡翠城','金地小区','碧桂园',
            '绿城小区','龙湖花园','保利家园','中海雅苑','万达华府','和园'),
        FLOOR(1 + RAND() * 30), '栋',
        FLOOR(1 + RAND() * 4), '单元',
        FLOOR(101 + RAND() * 1200), '室'
    );
