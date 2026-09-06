# -*- coding: utf-8 -*-
import json, random, string, urllib.request, urllib.error

BASE = "http://localhost:8080/api"

def req(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    if token: r.add_header("satoken", token)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except: return e.code, {}

# A + B
_, rA = req("POST", "/user/login", body={"phone": "13800000001", "password": "123456"})
_, rB = req("POST", "/user/login", body={"phone": "13800000002", "password": "123456"})
TA, TB = rA["data"]["token"], rB["data"]["token"]
IDA, IDB = rA["data"]["userInfo"]["id"], rB["data"]["userInfo"]["id"]

# C: use seed user 4
_, rC = req("POST", "/user/login", body={"phone": "13800000004", "password": "123456"})
if not rC.get("success"):
    ph = "186" + "".join(random.choices(string.digits, k=8))
    _, rr = req("POST", "/user/register", body={"phone": ph, "password": "pass12345"})
    _, rC = req("POST", "/user/login", body={"phone": ph, "password": "pass12345"})
TC, IDC = rC["data"]["token"], rC["data"]["userInfo"]["id"]
print(f"C = user {IDC}")

# publish 3-slot help
_, r = req("POST", "/help/publish", token=TA, body={"categoryId": 4, "title": "multi-slot-test",
    "description": "x", "reward": 1, "address": "x", "lng": 116.397, "lat": 39.916, "helperNum": 3})
HM = r["data"]
c1, pr = req("POST", f"/pay/{HM}", token=TA)
print(f"publish {HM} pay: {pr.get('message')}")

# B and C apply
_, r = req("POST", f"/order/accept/{HM}", token=TB); print("B apply:", r.get("message"))
_, r = req("POST", f"/order/accept/{HM}", token=TC); print("C apply:", r.get("message"))

_, r = req("GET", f"/order/apply-list/{HM}", token=TA)
items = r["data"]["list"]
print("before:", [(i["helperId"], i["status"]) for i in items])
apB = next(i["id"] for i in items if i["helperId"] == IDB)
_, r = req("POST", f"/order/apply/{apB}/approve", token=TA, body={})
print("approve B:", r.get("message"))
_, r = req("GET", f"/order/apply-list/{HM}", token=TA)
d = r["data"]
print("after:", [(i["helperId"], i["status"]) for i in d["list"]],
      f"acceptedNum={d['acceptedNum']}/{d['helperNum']}")
stC = next((i["status"] for i in d["list"] if i["helperId"] == IDC), None)
print("BUG CONFIRMED: pending applicant auto-rejected before slots full!"
      if stC == 2 else "OK: C still pending (status=0)" if stC == 0 else f"C status={stC}")

# ---- cancel order credit test ----
# C applies to fresh help, approved, then cancels -> credit -5
_, r = req("POST", "/help/publish", token=TA, body={"categoryId": 4, "title": "cancel-credit-test",
    "description": "x", "reward": 1, "address": "x", "lng": 116.397, "lat": 39.916})
HC = r["data"]
req("POST", f"/pay/{HC}", token=TA)
req("POST", f"/order/accept/{HC}", token=TC)
_, r = req("GET", f"/order/apply-list/{HC}", token=TA)
apC = r["data"]["list"][0]["id"]
_, r = req("POST", f"/order/apply/{apC}/approve", token=TA, body={})
OC = r["data"]
_, rC_me = req("GET", "/user/me", token=TC)
c0 = rC_me["data"].get("credit")
_, r = req("PUT", f"/order/{OC}/cancel", token=TC, body={"reason": "test"})
print("cancel:", r.get("message"))
time.sleep(0.3) if False else None
_, rC_me = req("GET", "/user/me", token=TC)
c1 = rC_me["data"].get("credit")
print(f"C credit: {c0} -> {c1} (expect -5 on cancel)")
_, r = req("GET", f"/help/{HC}")
print(f"help after cancel: status={r['data']['status']} acceptedNum={r['data']['acceptedNum']} (expect 1/0)")
