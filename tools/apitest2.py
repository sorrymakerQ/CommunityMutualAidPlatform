# -*- coding: utf-8 -*-
"""邻里帮 全流程 API 测试（UTF-8 安全版）"""
import json, random, string, sys, time
import urllib.request

BASE = "http://localhost:8080/api"
OUT = []
def log(msg):
    OUT.append(str(msg))

def req(method, path, token=None, body=None, headers=None, raw=False):
    url = BASE + path
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    if token: r.add_header("satoken", token)
    if headers:
        for k, v in headers.items(): r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            content = resp.read().decode("utf-8")
            code = resp.status
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8", "replace")
        code = e.code
    if raw: return code, content
    try: return code, json.loads(content)
    except: return code, {"_raw": content}

def dget(res, *keys):
    d = res
    for k in keys:
        if isinstance(d, dict) and k in d: d = d[k]
        else: return None
    return d

# ============ 1. 登录 ============
log("=" * 20 + " 1.登录 " + "=" * 20)
_, rA = req("POST", "/user/login", body={"phone": "13800000001", "password": "123456"})
_, rB = req("POST", "/user/login", body={"phone": "13800000002", "password": "123456"})
_, rC = req("POST", "/user/login", body={"phone": "13800000003", "password": "123456"})
_, rS = req("POST", "/user/login", body={"phone": "super", "password": "123456"})
TA, TB, TC, TS = dget(rA, "data", "token"), dget(rB, "data", "token"), dget(rC, "data", "token"), dget(rS, "data", "token")
IDA = dget(rA, "data", "userInfo", "id")
IDB = dget(rB, "data", "userInfo", "id")
IDC = dget(rC, "data", "userInfo", "id")
log(f"A(id={IDA}) B(id={IDB}) C(id={IDC}) super 登录: {'OK' if all([TA,TB,TC,TS]) else 'FAIL'}")
code, r = req("POST", "/user/login", body={"phone": "13800000001", "password": "wrong"})
log(f"错误密码: {r.get('message')} (HTTP {code})")

# ============ 2. 注册 ============
log("=" * 20 + " 2.注册 " + "=" * 20)
suffix = "".join(random.choices(string.digits, k=8))
code, r = req("POST", "/user/register", body={"phone": "1" + suffix, "password": "test123456", "nickname": "测试客"})
log(f"正常注册: {r.get('message')}")
code, r = req("POST", "/user/register", body={"phone": "13800000001", "password": "x12345678"})
log(f"重复注册: {r.get('message')}")
code, r = req("POST", "/user/register", body={"phone": "abc", "password": "x"})
log(f"非法手机号: {r.get('message')} (HTTP {code})")
code, r = req("POST", "/user/register", body={"phone": "199" + suffix, "password": "1"})
log(f"1位弱密码注册: HTTP {code} msg={r.get('message')} {'[允许!] ' if code==200 else ''}")
# 登录弱密码账号确认
code, r = req("POST", "/user/login", body={"phone": "199" + suffix, "password": "1"})
log(f"弱密码账号可登录: {r.get('success')}")

# ============ 3. 越权/未登录 ============
log("=" * 20 + " 3.越权检查 " + "=" * 20)
code, _ = req("GET", "/user/me", raw=True)
log(f"无 token 访问 /user/me: HTTP {code} (期望401)")
code, r = req("GET", "/admin/stats", token=TB)
log(f"普通用户访问 /admin/stats: HTTP {code} (期望403)")
code, r = req("GET", "/user/" + str(IDB))
log(f"公开用户接口泄露字段: phone={dget(r,'data','phone')} balance={dget(r,'data','balance')} lng={dget(r,'data','lng')} roleName={dget(r,'data','roleName')}")

# ============ 4. 发布（含幂等） ============
log("=" * 20 + " 4.发布求助 " + "=" * 20)
code, r = req("POST", "/help/publish", token=TA, body={
    "categoryId": 4, "title": "测试-代取快递", "description": "全流程测试",
    "reward": 5, "address": "测试地址1号楼", "lng": 116.397, "lat": 39.916, "helperNum": 1})
HELP1 = dget(r, "data")
log(f"发布求助: {r.get('message')} id={HELP1}")
hdr = {"X-Request-Id": "idem-test-001"}
body1 = {"categoryId": 4, "title": "幂等测试", "description": "x", "reward": 1, "address": "x", "lng": 116.39, "lat": 39.91}
code, r1 = req("POST", "/help/publish", token=TA, body=body1, headers=hdr)
code, r2 = req("POST", "/help/publish", token=TA, body=body1, headers=hdr)
log(f"同 X-Request-Id 两次发布: 第一次={r1.get('message')} 第二次={r2.get('message')} {'[幂等OK]' if r2.get('success')==False else '[未拦截!]'}")
# 双击场景（不同 Request-Id）
code, r3 = req("POST", "/help/publish", token=TA, body=body1)
code, r4 = req("POST", "/help/publish", token=TA, body=body1)
log(f"双击(不同Request-Id)两次发布: 均成功={r3.get('success') and r4.get('success')} 产生了两条求助 id={dget(r3,'data')},{dget(r4,'data')}")

# ============ 5. 支付 ============
log("=" * 20 + " 5.支付 " + "=" * 20)
_, balA0 = dget(req("GET", "/user/me", token=TA)[1], "data", "balance"), None
code, rA_me = req("GET", "/user/me", token=TA)
balA0 = dget(rA_me, "data", "balance")
code, r = req("POST", f"/pay/{HELP1}", token=TB)
log(f"他人支付: {r.get('message')} (期望: 只能支付自己的)")
code, r = req("POST", f"/pay/{HELP1}", token=TA)
log(f"A 正常支付: {r.get('message')}")
code, r = req("POST", f"/pay/{HELP1}", token=TA)
log(f"重复支付: {r.get('message')} (期望: 已支付勿重复)")
code, rA_me = req("GET", "/user/me", token=TA)
balA1 = dget(rA_me, "data", "balance")
log(f"A 余额: {balA0} -> {balA1} (期望 -5)")

# ============ 6. 申请与审批 ============
log("=" * 20 + " 6.接单申请与审批 " + "=" * 20)
code, r = req("POST", f"/order/accept/{HELP1}", token=TB)
log(f"B 申请接单: {r.get('message')}")
code, r = req("POST", f"/order/accept/{HELP1}", token=TB)
log(f"B 重复申请: {r.get('message')}")
code, r = req("POST", f"/order/accept/{HELP1}", token=TA)
log(f"A 申请自己的单: {r.get('message')}")
code, r = req("GET", f"/order/apply-list/{HELP1}", token=TC)
log(f"C 越权看 A 的申请列表: {r.get('message')} (期望: 无权)")
code, r = req("GET", f"/order/apply-list/{HELP1}", token=TA)
items = dget(r, "data", "list") or []
APPLY1 = items[0]["id"] if items else None
log(f"A 看申请列表: {len(items)} 条, APPLY_ID={APPLY1}")
code, r = req("POST", f"/order/apply/{APPLY1}/approve", token=TC, body={})
log(f"C 越权同意: {r.get('message')} (期望: 无权操作)")
code, r = req("POST", f"/order/apply/{APPLY1}/approve", token=TA, body={"reason": "辛苦了"})
log(f"A 同意 B: {r.get('message')}")
ORDER1 = dget(r, "data")
code, r = req("POST", f"/order/apply/{APPLY1}/approve", token=TA, body={})
log(f"A 重复同意: {r.get('message')}")
log(f"ORDER_ID={ORDER1}")

# ============ 7. 订单详情越权 ============
log("=" * 20 + " 7.订单越权 " + "=" * 20)
code, r = req("GET", f"/order/{ORDER1}", token=TC)
log(f"C 越权看订单详情: HTTP {code} {r.get('message')} (期望403)")

# ============ 8. 聊天 ============
log("=" * 20 + " 8.聊天 " + "=" * 20)
code, r = req("POST", "/chat/send", token=TB, body={"orderId": ORDER1, "content": "你好，我马上到"})
log(f"B 发消息: {r.get('message')}")
code, r = req("GET", f"/chat/order/{ORDER1}", token=TA)
log(f"A 读消息: {len(dget(r,'data') or [])} 条")
code, r = req("GET", f"/chat/order/{ORDER1}", token=TC)
log(f"C 越权读聊天: {r.get('message')}")

# ============ 9. 完成+评价 ============
log("=" * 20 + " 9.完成与评价 " + "=" * 20)
_, rb_me = req("GET", "/user/me", token=TB)
balB0, credB0 = dget(rb_me, "data", "balance"), dget(rb_me, "data", "credit")
code, r = req("PUT", f"/order/{ORDER1}/finish", token=TB)
log(f"B 自行确认完成: {r.get('message')} (期望: 只有发布者可)")
code, r = req("PUT", f"/order/{ORDER1}/finish", token=TA)
log(f"A 确认完成: {r.get('message')}")
code, rb_me = req("GET", "/user/me", token=TB)
balB1, credB1 = dget(rb_me, "data", "balance"), dget(rb_me, "data", "credit")
log(f"B 余额 {balB0}->{balB1} (期望+5) 信用分 {credB0}->{credB1} (期望+10)")
code, r = req("PUT", f"/order/{ORDER1}/review", token=TA, body={"score": 6, "comment": "x"})
log(f"评分6: {r.get('message')}")
code, r = req("PUT", f"/order/{ORDER1}/review", token=TA, body={"score": 5, "comment": "很快"})
log(f"A 评 B 5星: {r.get('message')}")
code, r = req("PUT", f"/order/{ORDER1}/review", token=TA, body={"score": 5, "comment": "x"})
log(f"A 重复评价: {r.get('message')}")
code, r = req("PUT", f"/order/{ORDER1}/review", token=TB, body={"score": 5, "comment": "好雇主"})
log(f"B 评 A 5星: {r.get('message')}")
time.sleep(3)
code, r = req("GET", f"/order/{ORDER1}", token=TA)
log(f"订单终态 status={dget(r,'data','status')} pub={dget(r,'data','publisherScore')} helper={dget(r,'data','helperScore')} (期望5/5/5)")
_, rb_me = req("GET", "/user/me", token=TB)
log(f"B 信用分终值 {dget(rb_me,'data','credit')} (期望 {credB1}+2)")
_, ra_me = req("GET", "/user/me", token=TA)
log(f"A 信用分(评5星被+2): {dget(ra_me,'data','credit')}")

# ============ 10. 多人求助审批误拒 bug 验证 ============
log("=" * 20 + " 10.多人求助审批(疑有bug) " + "=" * 20)
code, r = req("POST", "/help/publish", token=TA, body={
    "categoryId": 4, "title": "多人测试", "description": "x", "reward": 2,
    "address": "x", "lng": 116.397, "lat": 39.916, "helperNum": 3})
H2 = dget(r, "data")
req("POST", f"/pay/{H2}", token=TA, headers={"X-Request-Id": "pay-multi-1"})
req("POST", f"/order/accept/{H2}", token=TB)
req("POST", f"/order/accept/{H2}", token=TC)
code, r = req("GET", f"/order/apply-list/{H2}", token=TA)
items = dget(r, "data", "list") or []
apB = next((i["id"] for i in items if i.get("helperId") == IDB), None)
apC = next((i["id"] for i in items if i.get("helperId") == IDC), None)
code, r = req("POST", f"/order/apply/{apB}/approve", token=TA, body={})
log(f"A 同意 B(3人单还剩2名额): {r.get('message')}")
code, r = req("GET", f"/order/apply-list/{H2}", token=TA)
items = dget(r, "data", "list") or []
stC = next((i["status"] for i in items if i.get("helperId") == IDC), None)
acceptedNum = dget(r, "data", "acceptedNum")
log(f"此时 C 的申请状态={stC} (0=待确认 2=已拒绝)  acceptedNum={acceptedNum}/3")
log(f"{'[BUG确认] 未满员却自动拒绝了其他申请人!' if stC == 2 else '[正常] C 仍待确认'}")

# ============ 11. 取消订单扣分 ============
log("=" * 20 + " 11.取消订单 " + "=" * 20)
code, r = req("POST", "/help/publish", token=TA, body={
    "categoryId": 4, "title": "取消测试", "description": "x", "reward": 2,
    "address": "x", "lng": 116.397, "lat": 39.916})
H3 = dget(r, "data")
req("POST", f"/pay/{H3}", token=TA, headers={"X-Request-Id": "pay-cancel-1"})
req("POST", f"/order/accept/{H3}", token=TC)
code, r = req("GET", f"/order/apply-list/{H3}", token=TA)
lst3 = dget(r, "data", "list") or []
log(f"[debug] H3={H3} apply-list http={code} data={json.dumps(dget(r,'data'), ensure_ascii=False)[:300]}")
apC2 = lst3[0]["id"] if lst3 else None
if not apC2:
    code, r = req("POST", f"/order/accept/{H3}", token=TC)
    log(f"[debug] C 重新申请: {r.get('message')}")
    code, r = req("GET", f"/order/apply-list/{H3}", token=TA)
    lst3 = dget(r, "data", "list") or []
    apC2 = lst3[0]["id"] if lst3 else None
if not apC2:
    log("[跳过] 第11节无法继续"); apC2 = -1
req("POST", f"/order/apply/{apC2}/approve", token=TA, body={}) if apC2 and apC2 > 0 else None
code, rc_me = req("GET", "/user/me", token=TC)
credC0 = dget(rc_me, "data", "credit")
code, r = req("GET", "/order/my?role=helper", token=TC)
oC = next((o["id"] for o in (dget(r, "data", "list") or []) if o.get("helpId") == H3), None) if apC2 and apC2 > 0 else None
if oC:
    code, r = req("PUT", f"/order/{oC}/cancel", token=TC, body={"reason": "临时有事"})
    log(f"C 取消订单: {r.get('message')}")
    code, rc_me = req("GET", "/user/me", token=TC)
    credC1 = dget(rc_me, "data", "credit")
    log(f"C 信用分 {credC0}->{credC1} (期望-5)")
    code, r = req("PUT", f"/order/{oC}/cancel", token=TC, body={})
    log(f"重复取消: {r.get('message')}")
    code, r = req("GET", f"/help/{H3}")
    log(f"求助状态={dget(r,'data','status')} acceptedNum={dget(r,'data','acceptedNum')} (期望回到招募中1/0)")

# ============ 12. 取消求助退款 ============
log("=" * 20 + " 12.取消求助退款 " + "=" * 20)
code, r = req("POST", "/help/publish", token=TA, body={
    "categoryId": 4, "title": "退款测试", "description": "x", "reward": 3,
    "address": "x", "lng": 116.397, "lat": 39.916})
H4 = dget(r, "data")
req("POST", f"/pay/{H4}", token=TA, headers={"X-Request-Id": "pay-refund-1"})
_, ra_me = req("GET", "/user/me", token=TA)
b0 = dget(ra_me, "data", "balance")
code, r = req("PUT", f"/help/{H4}/cancel", token=TA)
_, ra_me = req("GET", "/user/me", token=TA)
b1 = dget(ra_me, "data", "balance")
log(f"取消求助: {r.get('message')} 余额 {b0}->{b1} (期望+3)")
code, r = req("PUT", f"/help/{H4}/cancel", token=TA)
log(f"重复取消: {r.get('message')}")

# ============ 13. 负数酬劳漏洞 ============
log("=" * 20 + " 13.负数酬劳漏洞验证 " + "=" * 20)
_, ra_me = req("GET", "/user/me", token=TA)
b0 = dget(ra_me, "data", "balance")
code, r = req("POST", "/help/publish", token=TA, body={
    "categoryId": 4, "title": "负数测试", "description": "x", "reward": -100,
    "address": "x", "lng": 116.397, "lat": 39.916})
HN = dget(r, "data")
log(f"reward=-100 发布: success={r.get('success')} id={HN} {'[漏洞!接受负数]' if r.get('success') else '[已拦截]'}")
if r.get("success"):
    code, r = req("POST", f"/pay/{HN}", token=TA, headers={"X-Request-Id": "pay-neg-1"})
    _, ra_me = req("GET", "/user/me", token=TA)
    b1 = dget(ra_me, "data", "balance")
    log(f"支付-100后余额 {b0}->{b1} {'[资金漏洞确认:支付负数反而+100]' if b1 > b0 else ''}")
    req("PUT", f"/help/{HN}/cancel", token=TA)

# ============ 14. 分页边界 ============
log("=" * 20 + " 14.分页边界 " + "=" * 20)
for q in ["page=0", "page=-1", "size=99999"]:
    code, _r = req("GET", f"/help/search?keyword=a&{q}", raw=True)
    log(f"{q}: HTTP {code} {'[500异常!]' if code == 500 else ''}")

# ============ 15. 管理端 ============
log("=" * 20 + " 15.管理端 " + "=" * 20)
code, r = req("GET", "/admin/stats", token=TS)
log(f"super 看 stats: {r.get('message')} {dget(r,'data')}")
code, r = req("GET", "/admin/users?page=1&size=5", token=TS)
users = dget(r, "data", "list") or []
leak = users[0].get("password") if users else "n/a"
log(f"admin 用户列表: 密码字段={leak} {'[泄露密码哈希!]' if leak else '[已脱敏OK]'}")
code, r = req("PUT", f"/admin/user/{IDB}/status?status=0", token=TS)
log(f"super 禁用 B: {r.get('message')}")
code, r = req("POST", "/user/login", body={"phone": "13800000002", "password": "123456"})
log(f"被禁用的 B 再登录: {r.get('message')}")
code, r = req("PUT", f"/admin/user/{IDB}/status?status=1", token=TS)
log(f"恢复 B: {r.get('message')}")
code, r = req("PUT", f"/admin/user/{IDA}/role?roleId=1", token=TS)
log(f"super 把 A 提为 admin: {r.get('message')}")
# 还原
import subprocess
subprocess.run(["mysql", "-uroot", f"-p1234", "linlibang", "-e", f"UPDATE tb_user SET role_id=2 WHERE id={IDA};"], capture_output=True)
code, r = req("GET", "/admin/helps?page=1&size=3&status=1", token=TS)
log(f"admin 求助列表: {len(dget(r,'data','list') or [])} 条")

with open(r"C:\Users\22296\Desktop\linlibang\tools\test_result.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(OUT))
print("\n".join(OUT))
