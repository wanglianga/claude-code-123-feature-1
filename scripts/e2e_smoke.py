# -*- coding: utf-8 -*-
"""既有主链路冒烟回归：申请→分流→推荐→排期→笔录→送达→失联登记/恢复"""
import json
import urllib.request
import urllib.error

BASE = "http://localhost:8080/api"
PASS, FAILS = 0, []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, cond, extra=""):
    global PASS
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAILS.append(name)
        print(f"  [FAIL] {name} {extra}")


def login(u, p):
    return call("POST", "/auth/login", body={"username": u, "password": p})[1]["data"]["token"]


tw = login("window", "window123")
tp = login("party", "party123")

# 1) 当事人新建申请
st, b = call("POST", "/cases", tp, {
    "caseType": "LOAN", "urgency": "NORMAL",
    "applicantName": "陈小明", "applicantPhone": "1380000001",
    "respondentName": "冒烟测试某", "respondentPhone": "13900000000",
    "respondentAddress": "本市测试路1号", "claimAmount": 1000,
    "claimSummary": "失联功能上线后主链路冒烟", "evidenceList": "借条", "onlineWilling": True})
check("新建申请成功", b["code"] == 0 and b["data"]["status"] == "PENDING_TRIAGE")
cid = b["data"]["id"]

# 2) 窗口分流
st, b = call("POST", f"/cases/{cid}/triage", tw,
             {"mediatorId": 4, "channel": "ONLINE", "triageOpinion": "冒烟分流", "evidenceComplete": True})
check("窗口分流成功", b["code"] == 0 and b["data"]["status"] == "TRIAGED", json.dumps(b, ensure_ascii=False))

# 3) 推荐调解员
st, b = call("GET", f"/cases/{cid}/mediator-suggest?start=2026-10-10T10:00:00&end=2026-10-10T11:00:00", tw)
check("智能推荐返回调解员列表", b["code"] == 0 and len(b["data"]) >= 1, json.dumps(b, ensure_ascii=False)[:200])

# 4) 排期
st, b = call("POST", f"/cases/{cid}/schedules", tw,
             {"startTime": "2026-10-10T10:00:00", "endTime": "2026-10-10T11:00:00",
              "channel": "ONLINE", "location": "线上调解室"})
check("排期成功", b["code"] == 0, json.dumps(b, ensure_ascii=False)[:200])

# 5) 调解员笔录
tz = login("zhao", "mediator123")
st, b = call("POST", f"/cases/{cid}/records", tz,
             {"focusPoints": "冒烟", "processNote": "主链路验证", "outcome": "CONTINUE"})
check("笔录保存成功", b["code"] == 0 and b["data"]["roundNo"] == 1)

# 6) 电子送达
st, b = call("POST", f"/cases/{cid}/deliveries", tz,
             {"target": "RESPONDENT", "documentName": "冒烟通知", "channel": "SMS"})
check("电子送达成功", b["code"] == 0 and b["data"]["status"] == "SENT")

# 7) 手工失联登记 + 恢复
st, b = call("POST", f"/cases/{cid}/missing", tz, {"missing": True, "note": "冒烟失联"})
check("失联登记成功", b["code"] == 0)
st, b = call("GET", f"/cases/{cid}", tz)
check("案件标记失联", b["data"]["case"]["respondentMissing"] is True)
st, b = call("POST", f"/cases/{cid}/missing", tz, {"missing": False, "note": "冒烟恢复"})
check("恢复联系成功", b["code"] == 0)
st, b = call("GET", f"/cases/{cid}", tz)
check("恢复后失联标记清除且计数归零",
      b["data"]["case"]["respondentMissing"] is False
      and (b["data"]["case"]["failedContactCount"] or 0) == 0)

# 8) 材料卷导出仍可用
req = urllib.request.Request(BASE + f"/cases/{cid}/dossier")
req.add_header("Authorization", "Bearer " + tw)
with urllib.request.urlopen(req) as r:
    text = r.read().decode()
check("材料卷导出成功并含案号", "调解编号" in text)

print(f"\n==== 冒烟回归通过 {PASS} 项，失败 {len(FAILS)} 项 ====")
if FAILS:
    raise SystemExit(1)
print("SMOKE_PASSED")
