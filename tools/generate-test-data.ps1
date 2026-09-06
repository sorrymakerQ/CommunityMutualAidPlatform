# ============================================================
# 生成演示数据 SQL：30 个新用户 + 40 条求助 + 46 条订单
# 规则：
#   - 用户 id 1001~1030（手机号 13800001001~13800001030，密码统一 123456）
#   - 求助 id 2001~2040：发布者从用户池轮询；需要人数 1/2/3 分布
#   - 求助状态分布：0-17 招募中(accepted=0)、18-29 进行中(已满员)、
#     30-37 已完成(accepted=helper_num)、38-39 已取消
#   - 订单 id 3001 起：进行中/已完成的求助按名额生成订单，
#     helper 从用户池确定性选取（排除发布者、同求助不重复，保证 uk_help_helper 不冲突）
#   - 全部外键（user_id/publisher_id/helper_id）引用真实存在的用户
# 输出：.tmp-gen.sql（UTF-8 无 BOM）
# ============================================================
$ErrorActionPreference = 'Stop'

$sqlPath = Join-Path $PSScriptRoot '.tmp-gen.sql'
$sb = New-Object System.Text.StringBuilder

[void]$sb.AppendLine('USE linlibang;')
[void]$sb.AppendLine('-- ===== 1. 清理求助与订单（及上次生成的用户）=====')
[void]$sb.AppendLine('DELETE FROM tb_order;')
[void]$sb.AppendLine('DELETE FROM tb_help_request;')
[void]$sb.AppendLine('DELETE FROM tb_user WHERE id >= 1001;')
[void]$sb.AppendLine('ALTER TABLE tb_order AUTO_INCREMENT = 3001;')
[void]$sb.AppendLine('ALTER TABLE tb_help_request AUTO_INCREMENT = 2001;')

# ===== 2. 用户 =====
$hash = '$2a$10$LhunkeJYTLwzqaep.dxzbOMlvG8wrDUU1ctBomo2on6zq4/rv3RXC' # BCrypt(123456)
$names = @('张伟','李娜','王强','刘洋','陈静','杨帆','赵磊','黄敏','周杰','吴倩',
           '徐明','孙悦','马超','朱琳','胡军','郭芳','何平','高翔','林峰','罗雪',
           '郑爽','梁涛','谢东','唐颖','许志','邓超','冯刚','曾倩','肖磊','董琳')
$communities = @('阳光花园小区','翠竹苑小区','金色家园小区','望湖居小区','锦绣江南小区','学府雅苑小区')

[void]$sb.AppendLine('-- ===== 2. 新用户 1001~1030（密码 123456）=====')
for ($i = 0; $i -lt 30; $i++) {
    $id = 1001 + $i
    $phone = '1380000' + ('{0:D4}' -f (1001 + $i))
    $nick = $names[$i] + ('{0:D2}' -f $id)
    $gender = ($i % 2) + 1
    $community = $communities[$i % 6]
    $lng = [math]::Round(116.397 + (($i * 7) % 50) / 10000, 6)
    $lat = [math]::Round(39.916 + (($i * 13) % 40) / 10000, 6)
    $credit = 90 + ($i % 11)
    $helpCount = ($i * 5) % 30
    $intro = $names[$i] + '是小区热心邻居，乐于助人。'
    [void]$sb.AppendLine("INSERT INTO tb_user (id, phone, password, nickname, avatar, gender, community, lng, lat, credit, help_count, intro, role_id, status, is_builtin) VALUES ($id, '$phone', '$hash', '$nick', 'https://api.dicebear.com/7.x/avataaars/svg?seed=$nick', $gender, '$community', $lng, $lat, $credit, $helpCount, '$intro', 2, 1, 0);")
}

# ===== 3. 求助 40 条 =====
$titlePool = @('热水器不出热水了，求师傅上门看看','帮忙取个快递，驿站快关门了','搬家缺人手，帮忙搬几个箱子',
               '猫咪需要照看两天，求靠谱邻居','孩子初三数学需要辅导一次','电脑蓝屏开不了机，求懂行的看看',
               '擦玻璃够不着，求搭把手','代买生活用品，腿脚不太方便','组装书桌缺工具，求借电钻用一下',
               '帮忙看一下燃气灶，打不着火了')
$descPool = @('东西有点多，需要两个人一起搬。','时间比较灵活，随时都可以。','报酬可商量，感谢帮忙。',
              '家里有老人需要照顾，希望邻居帮帮忙。','工具比较专业，不会用的话可以教。')

[void]$sb.AppendLine('-- ===== 3. 求助 2001~2040 =====')
for ($i = 0; $i -lt 40; $i++) {
    $id = 2001 + $i
    $pubIdx = $i % 30
    $publisher = 1001 + $pubIdx
    $helperNum = [math]::Floor($i / 20) + 1   # 0-19 -> 1人；20-29 -> 2人；30-39 -> 3人
    if ($i -lt 18)      { $status = 1; $accepted = 0 }                       # 招募中
    elseif ($i -lt 30)  { $status = 2; $accepted = $helperNum }              # 进行中(已满员)
    elseif ($i -lt 38)  { $status = 3; $accepted = $helperNum }              # 已完成
    else                { $status = 4; $accepted = 0 }                       # 已取消
    $title = $titlePool[$i % 10]
    $desc = $descPool[$i % 5] + '【求助编号' + $id + '】'
    $community = $communities[$i % 6]
    $address = $community + ($i % 8 + 1) + '栋' + ($i % 6 + 1) + '单元' + ($i % 20 + 1) + '室'
    $lng = [math]::Round(116.397 + (($i * 11) % 60) / 10000, 6)
    $lat = [math]::Round(39.916 + (($i * 17) % 50) / 10000, 6)
    $reward = (($i * 13) % 16) * 10
    $urgent = if ($i % 5 -eq 0) { 1 } else { 0 }
    $viewCount = (($i * 37) % 300) + 5
    $daysAgo = ($i * 3) % 15
    [void]$sb.AppendLine("INSERT INTO tb_help_request (id, user_id, category_id, title, description, images, reward, address, lng, lat, status, helper_num, accepted_num, urgent, view_count, version, create_time, update_time, is_deleted) VALUES ($id, $publisher, $($i % 10 + 1), '$title', '$desc', NULL, $reward, '$address', $lng, $lat, $status, $helperNum, $accepted, $urgent, $viewCount, 0, DATE_SUB(NOW(), INTERVAL $daysAgo DAY), DATE_SUB(NOW(), INTERVAL $daysAgo DAY), 0);")
}

# ===== 4. 订单：进行中(18-29)与已完成(30-37)的求助按名额生成 =====
[void]$sb.AppendLine('-- ===== 4. 订单（引用有效用户，同求助 helper 不重复）=====')
$orderId = 3001
for ($i = 18; $i -lt 38; $i++) {
    $helpId = 2001 + $i
    $pubIdx = $i % 30
    $helperNum = [math]::Floor($i / 20) + 1
    $orderStatus = if ($i -lt 30) { 2 } else { 3 }
    $acceptedDays = (($i * 3) % 10) + 2
    $picked = @()
    for ($j = 0; $j -lt $helperNum; $j++) {
        $start = ($i * 7 + $j * 11) % 30
        $k = 0
        do {
            $idx = ($start + $k) % 30
            $k++
        } while ($idx -eq $pubIdx -or $picked -contains $idx)
        $picked += $idx
        $helper = 1001 + $idx
        # finish_time：已完成订单为完成时刻，进行中为 NULL
        if ($orderStatus -eq 3) {
            $finishHours = 2 + ($i % 8)
            $finishVal = "DATE_ADD(DATE_SUB(NOW(), INTERVAL $acceptedDays DAY), INTERVAL $finishHours HOUR)"
        } else {
            $finishVal = 'NULL'
        }
        [void]$sb.AppendLine("INSERT INTO tb_order (id, help_id, publisher_id, helper_id, status, cancel_reason, accept_time, finish_time, publisher_score, helper_score, publisher_comment, helper_comment, version, create_time, update_time) VALUES ($orderId, $helpId, $(1001 + $pubIdx), $helper, $orderStatus, NULL, DATE_SUB(NOW(), INTERVAL $acceptedDays DAY), $finishVal, NULL, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL $acceptedDays DAY), DATE_SUB(NOW(), INTERVAL $acceptedDays DAY));")
        $orderId++
    }
}

# ===== 5. 验证语句 =====
[void]$sb.AppendLine('-- ===== 5. 验证 =====')
[void]$sb.AppendLine('SELECT (SELECT COUNT(*) FROM tb_user) AS users, (SELECT COUNT(*) FROM tb_help_request) AS helps, (SELECT COUNT(*) FROM tb_order) AS orders;')
[void]$sb.AppendLine('-- 悬空外键检查（应全为 0）')
[void]$sb.AppendLine("SELECT 'help_user' AS chk, COUNT(*) AS bad FROM tb_help_request h LEFT JOIN tb_user u ON u.id = h.user_id WHERE u.id IS NULL UNION ALL SELECT 'order_pub', COUNT(*) FROM tb_order o LEFT JOIN tb_user u ON u.id = o.publisher_id WHERE u.id IS NULL UNION ALL SELECT 'order_helper', COUNT(*) FROM tb_order o LEFT JOIN tb_user u ON u.id = o.helper_id WHERE u.id IS NULL;")
[void]$sb.AppendLine('-- 同一求助同一接单者重复检查（应全为 0）')
[void]$sb.AppendLine('SELECT COUNT(*) AS dup FROM (SELECT help_id, helper_id FROM tb_order GROUP BY help_id, helper_id HAVING COUNT(*) > 1) d;')
[void]$sb.AppendLine('-- 已接人数与订单数一致性（accepted_num 应等于 status IN (1,2) 的订单数）')
[void]$sb.AppendLine('SELECT h.id, h.status, h.helper_num, h.accepted_num, (SELECT COUNT(*) FROM tb_order o WHERE o.help_id = h.id AND o.status IN (1,2)) AS active_orders FROM tb_help_request h ORDER BY h.id LIMIT 12;')

[System.IO.File]::WriteAllText($sqlPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Output "SQL 已生成: $sqlPath"
Write-Output "用户: 30 条 | 求助: 40 条 | 订单: $($orderId - 3001) 条"
