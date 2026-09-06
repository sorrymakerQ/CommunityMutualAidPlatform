#!/bin/bash
# 邻里帮 全流程 API 测试脚本
BASE=http://localhost:8080/api
PY="python -c"

jget() { python -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null; }

echo "======== 1. 登录测试用户 A/B + 超管 ========"
TOKEN_A=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"phone":"13800000001","password":"123456"}' | jget "['data']['token']")
TOKEN_B=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"phone":"13800000002","password":"123456"}' | jget "['data']['token']")
TOKEN_C=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"phone":"13800000003","password":"123456"}' | jget "['data']['token']")
TOKEN_SUPER=$(curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"phone":"super","password":"123456"}' | jget "['data']['token']")
echo "A=$TOKEN_A"; echo "B=$TOKEN_B"; echo "SUPER=$TOKEN_SUPER"

echo "-------- 错误密码 --------"
curl -s -X POST $BASE/user/login -H "Content-Type: application/json" -d '{"phone":"13800000001","password":"wrong"}' | head -c 200; echo

echo "======== 2. 注册新用户 ========"
RAND=$((RANDOM))
curl -s -X POST $BASE/user/register -H "Content-Type: application/json" -d "{\"phone\":\"139$RANDOM$RANDOM\",\"password\":\"test123456\",\"nickname\":\"测试客\"}" | head -c 200; echo
echo "-- 重复注册 --"
curl -s -X POST $BASE/user/register -H "Content-Type: application/json" -d "{\"phone\":\"13800000001\",\"password\":\"x\"}" | head -c 200; echo
echo "-- 非法手机号 --"
curl -s -X POST $BASE/user/register -H "Content-Type: application/json" -d '{"phone":"abc","password":"x"}' | head -c 300; echo
echo "-- 弱密码(1位) --"
curl -s -X POST $BASE/user/register -H "Content-Type: application/json" -d "{\"phone\":\"138$RANDOM$RANDOM$RANDOM\",\"password\":\"1\"}" | head -c 200; echo

echo "======== 3. 未登录访问受保护接口 ========"
curl -s -o /dev/null -w "GET /user/me 无token -> HTTP %{http_code}\n" $BASE/user/me
curl -s -o /dev/null -w "GET /admin/stats 普通用户 -> HTTP %{http_code}\n" -H "satoken: $TOKEN_B" $BASE/admin/stats

echo "======== 4. A 发布求助（酬劳 5 元 ×1人） ========"
PUB=$(curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -d '{"categoryId":4,"title":"测试-代取快递","description":"全流程测试用","reward":5,"address":"测试地址1号楼","lng":116.397,"lat":39.916,"helperNum":1}')
echo "$PUB" | head -c 200; echo
HELP_ID=$(echo "$PUB" | jget "['data']")
echo "HELP_ID=$HELP_ID"

echo "-- 幂等重放（同 X-Request-Id）--"
curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -H "X-Request-Id: test-idem-001" -d '{"categoryId":4,"title":"幂等测试","description":"x","reward":1,"address":"x","lng":116.39,"lat":39.91}' | head -c 120; echo
curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -H "X-Request-Id: test-idem-001" -d '{"categoryId":4,"title":"幂等测试","description":"x","reward":1,"address":"x","lng":116.39,"lat":39.91}' | head -c 120; echo

echo "======== 5. 支付 ========"
echo "-- 他人支付（B 想替 A 付）--"
curl -s -X POST $BASE/pay/$HELP_ID -H "satoken: $TOKEN_B" -H "X-Request-Id: pay-b-1" | head -c 200; echo
echo "-- A 正常支付 --"
curl -s -X POST $BASE/pay/$HELP_ID -H "satoken: $TOKEN_A" -H "X-Request-Id: pay-a-1" | head -c 200; echo
echo "-- 重复支付 --"
curl -s -X POST $BASE/pay/$HELP_ID -H "satoken: $TOKEN_A" -H "X-Request-Id: pay-a-2" | head -c 200; echo
echo "-- A 余额（应为 495）--"
curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']"

echo "======== 6. 接单申请与审批 ========"
echo "-- B 申请接单 --"
curl -s -X POST $BASE/order/accept/$HELP_ID -H "satoken: $TOKEN_B" | head -c 200; echo
echo "-- B 重复申请 --"
curl -s -X POST $BASE/order/accept/$HELP_ID -H "satoken: $TOKEN_B" | head -c 200; echo
echo "-- A 自己申请自己的单 --"
curl -s -X POST $BASE/order/accept/$HELP_ID -H "satoken: $TOKEN_A" | head -c 200; echo
echo "-- C 查看他人求助申请列表（越权）--"
curl -s $BASE/order/apply-list/$HELP_ID -H "satoken: $TOKEN_C" | head -c 200; echo
echo "-- A 查看申请列表 --"
APPLY_LIST=$(curl -s $BASE/order/apply-list/$HELP_ID -H "satoken: $TOKEN_A")
echo "$APPLY_LIST" | head -c 400; echo
APPLY_ID=$(echo "$APPLY_LIST" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['list'][0]['id'])")
echo "APPLY_ID=$APPLY_ID"
echo "-- C 越权同意别人的申请 --"
curl -s -X POST $BASE/order/apply/$APPLY_ID/approve -H "satoken: $TOKEN_C" -H "Content-Type: application/json" -d '{}' | head -c 200; echo
echo "-- A 同意 B 的申请 --"
curl -s -X POST $BASE/order/apply/$APPLY_ID/approve -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{"reason":"辛苦了"}' | head -c 200; echo
echo "-- A 重复同意 --"
curl -s -X POST $BASE/order/apply/$APPLY_ID/approve -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{}' | head -c 200; echo
ORDER_ID=$(curl -s $BASE/order/my?role=helper -H "satoken: $TOKEN_B" | python -c "import sys,json;d=json.load(sys.stdin);print([o['id'] for o in d['data']['list'] if o['helpId']=='$HELP_ID' or o.get('helpId')==$HELP_ID][0])" 2>/dev/null)
echo "ORDER_ID=$ORDER_ID"

echo "======== 7. 越权访问他人订单详情 ========"
curl -s $BASE/order/$ORDER_ID -H "satoken: $TOKEN_C" | head -c 200; echo

echo "======== 8. 聊天 ========"
curl -s -X POST $BASE/chat/send -H "satoken: $TOKEN_B" -H "Content-Type: application/json" -d "{\"orderId\":$ORDER_ID,\"content\":\"你好，我马上到\"}" | head -c 200; echo
curl -s $BASE/chat/order/$ORDER_ID -H "satoken: $TOKEN_A" | head -c 300; echo
echo "-- C 越权读聊天 --"
curl -s $BASE/chat/order/$ORDER_ID -H "satoken: $TOKEN_C" | head -c 200; echo

echo "======== 9. 完成 + 评价 ========"
echo "-- B 自己确认完成（应拒绝，只有发布者可）--"
curl -s -X PUT $BASE/order/$ORDER_ID/finish -H "satoken: $TOKEN_B" | head -c 200; echo
echo "-- A 确认完成 --"
curl -s -X PUT $BASE/order/$ORDER_ID/finish -H "satoken: $TOKEN_A" | head -c 200; echo
echo "-- B 余额（应 500+5=505... B seed=500）--"
curl -s $BASE/user/me -H "satoken: $TOKEN_B" | jget "['data']['balance']"
echo "-- 评分 6（非法）--"
curl -s -X PUT $BASE/order/$ORDER_ID/review -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{"score":6,"comment":"x"}' | head -c 200; echo
echo "-- A 评价 B 5星 --"
curl -s -X PUT $BASE/order/$ORDER_ID/review -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{"score":5,"comment":"很快"}' | head -c 200; echo
echo "-- A 重复评价 --"
curl -s -X PUT $BASE/order/$ORDER_ID/review -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{"score":5,"comment":"x"}' | head -c 200; echo
echo "-- B 评价 A 5星 --"
curl -s -X PUT $BASE/order/$ORDER_ID/review -H "satoken: $TOKEN_B" -H "Content-Type: application/json" -d '{"score":5,"comment":"好雇主"}' | head -c 200; echo
sleep 3
echo "-- 订单终态（应为5已评价）--"
curl -s $BASE/order/$ORDER_ID -H "satoken: $TOKEN_A" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['status'],d['data']['publisherScore'],d['data']['helperScore'])"
echo "-- B 信用分（100+10完成+2好评=112）--"
curl -s $BASE/user/me -H "satoken: $TOKEN_B" | jget "['data']['credit']"

echo "======== 10. 取消订单扣信用分 ========"
PUB2=$(curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -d '{"categoryId":4,"title":"测试2-取消流程","description":"x","reward":2,"address":"测试地址","lng":116.397,"lat":39.916}')
H2=$(echo "$PUB2" | jget "['data']")
curl -s -X POST $BASE/pay/$H2 -H "satoken: $TOKEN_A" -H "X-Request-Id: pay-a-3" > /dev/null
curl -s -X POST $BASE/order/accept/$H2 -H "satoken: $TOKEN_C" > /dev/null
AL2=$(curl -s $BASE/order/apply-list/$H2 -H "satoken: $TOKEN_A" | python -c "import sys,json;print(json.load(sys.stdin)['data']['list'][0]['id'])")
curl -s -X POST $BASE/order/apply/$AL2/approve -H "satoken: $TOKEN_A" -H "Content-Type: application/json" -d '{}' | head -c 120; echo
O2=$(curl -s "$BASE/order/my?role=helper" -H "satoken: $TOKEN_C" | python -c "import sys,json;d=json.load(sys.stdin);print([o['id'] for o in d['data']['list'] if o.get('helpId')==$H2][0])")
echo "-- C 取消订单 --"
curl -s -X PUT $BASE/order/$O2/cancel -H "satoken: $TOKEN_C" -H "Content-Type: application/json" -d '{"reason":"临时有事"}' | head -c 200; echo
echo "-- C 信用分（应 100-5=95）--"
curl -s $BASE/user/me -H "satoken: $TOKEN_C" | jget "['data']['credit']"

echo "======== 11. 取消求助 + 退款 ========"
PUB3=$(curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -d '{"categoryId":4,"title":"测试3-退款","description":"x","reward":3,"address":"测试地址","lng":116.397,"lat":39.916}')
H3=$(echo "$PUB3" | jget "['data']")
curl -s -X POST $BASE/pay/$H3 -H "satoken: $TOKEN_A" -H "X-Request-Id: pay-a-4" > /dev/null
BAL_BEFORE=$(curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']")
curl -s -X PUT $BASE/help/$H3/cancel -H "satoken: $TOKEN_A" | head -c 200; echo
BAL_AFTER=$(curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']")
echo "退款前=$BAL_BEFORE 退款后=$BAL_AFTER"
echo "-- B 越权取消 A 的求助 --"
PUB4=$(curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -d '{"categoryId":4,"title":"测试4","description":"x","reward":1,"address":"x","lng":116.397,"lat":39.916}')
H4=$(echo "$PUB4" | jget "['data']")
curl -s -X PUT $BASE/help/$H4/cancel -H "satoken: $TOKEN_B" | head -c 200; echo

echo "======== 12. 负数酬劳漏洞验证（重要） ========"
BAL0=$(curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']")
echo "支付前余额: $BAL0"
PUBN=$(curl -s -X POST $BASE/help/publish -H "Content-Type: application/json" -H "satoken: $TOKEN_A" -d '{"categoryId":4,"title":"负数测试","description":"x","reward":-100,"address":"x","lng":116.397,"lat":39.916}')
HN=$(echo "$PUBN" | jget "['data']")
echo "HELP_ID=$HN"
curl -s -X POST $BASE/pay/$HN -H "satoken: $TOKEN_A" -H "X-Request-Id: pay-neg-1" | head -c 200; echo
BAL1=$(curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']")
echo "支付(-100)后余额: $BAL1  （若 +100 则漏洞成立）"
# 清理：取消求助退款，恢复余额
curl -s -X PUT $BASE/help/$HN/cancel -H "satoken: $TOKEN_A" > /dev/null
BAL2=$(curl -s $BASE/user/me -H "satoken: $TOKEN_A" | jget "['data']['balance']")
echo "取消退款后余额: $BAL2"

echo "======== 13. 分页参数边界 ========"
curl -s -o /dev/null -w "page=0 -> HTTP %{http_code}\n" "$BASE/help/search?keyword=a&page=0" 
curl -s -o /dev/null -w "page=-1 -> HTTP %{http_code}\n" "$BASE/help/search?keyword=a&page=-1"
curl -s -o /dev/null -w "size=99999 -> HTTP %{http_code}\n" "$BASE/help/search?keyword=a&size=99999"

echo "======== 14. 管理端 ========"
curl -s $BASE/admin/stats -H "satoken: $TOKEN_SUPER" | head -c 200; echo
echo "-- super 给普通用户提权为 admin（越权验证 super 可）--"
curl -s -X PUT "$BASE/admin/user/1032/role?roleId=1" -H "satoken: $TOKEN_SUPER" | head -c 200; echo
# 还原角色
mysql -uroot linlibang -e "UPDATE tb_user SET role_id=2 WHERE id=1032;" 2>/dev/null
echo "-- B 访问 admin（应 403）--"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -H "satoken: $TOKEN_B" $BASE/admin/users

echo "======== 测试完成 ========"
