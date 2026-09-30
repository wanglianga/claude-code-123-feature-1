# -*- coding: utf-8 -*-
"""被申请人失联处置 端到端接口回归"""
import json
import urllib.request
import urllib.error

BASE = "http://localhost:8080/api"
PASS, FAILS = 0, []


def call(method, path, token=None, body=None, expect_http=200):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
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
        FAILS.append(name + (" " + extra if extra else ""))
        print(f"  [FAIL] {name} {extra}")


def login(u, p):
    st, b = call("POST", "/auth/login", body={"username": u, "password": p})
    return b["data"]["token"]


# ---------- 登录 ----------
tw = login("window", "window123")
tz = login("zhang", "mediator123")
ta = login("assistant", "assistant123")
check("窗口/调解员/法官助理登录成功", bool(tw and tz and ta))

st, b = call("GET", "/cases/1")
check("无令牌访问被拦截 401", st == 401, str(st))

# ---------- 种子数据：失联案件 c9 已有 3 次联系尝试 ----------
st, b = call("GET", "/cases/9", tw)
d9 = b["data"]
check("种子失联案件返回 contactAttempts", len(d9.get("contactAttempts", [])) == 3,
      str(len(d9.get("contactAttempts", []))))
methods9 = [a["method"] for a in d9["contactAttempts"]]
check("种子尝试覆盖电话/短信/地址核验", set(methods9) == {"PHONE", "SMS", "ADDRESS_CHECK"}, str(methods9))
check("种子案件窗口已核验地址", d9["case"].get("addressVerified") is True)
check("种子案件已安排线下送达", d9["case"].get("offlineDelivery") is True)
check("种子案件窗口已建议转立案", d9["case"].get("transferSuggested") is True)
check("种子案件失败计数=3", d9["case"].get("failedContactCount") == 3,
      str(d9["case"].get("failedContactCount")))
ml9 = (d9.get("transferMaterial") or {}).get("materialList", "")
check("种子材料包标明联系过程记录及次数", "联系过程记录" in ml9 and "共3次" in ml9, ml9)

# 导出连续过程材料卷：含每次联系结果（文件下载，纯文本）
req = urllib.request.Request(BASE + "/cases/9/dossier")
req.add_header("Authorization", "Bearer " + tw)
with urllib.request.urlopen(req) as r:
    dossier9 = r.read().decode()
check("材料卷含失联联系过程专节", "被申请人失联联系过程记录" in dossier9)
check("材料卷逐次标明结果（停机/空号）", "停机/空号" in dossier9)
check("材料卷逐次标明结果（短信未回复）", "短信未回复" in dossier9)
check("材料卷逐次标明结果（地址不详）", "查无此人/地址不详" in dossier9)
check("材料卷标明窗口处置情况", "窗口处置" in dossier9 and "已安排" in dossier9)

# ---------- 完整流程：在张调解员承办的调解中案件 c5 上演练 ----------
st, b = call("GET", "/cases/5", tz)
d5 = b["data"]
cid = d5["case"]["id"]
check("进入张调解员承办案件 c5", d5["case"]["mediatorId"] is not None)
old_failed = d5["case"].get("failedContactCount") or 0
check("新流程案件初始失败计数为0", old_failed == 0, str(old_failed))

# 第1次：电话无人接听
st, b = call("POST", f"/cases/{cid}/contact-attempts", tz,
             {"method": "PHONE", "result": "NO_ANSWER", "note": "工作时间三次拨打无人接听"})
check("登记第1次电话尝试成功", b["code"] == 0 and b["data"]["roundNo"] == 1, json.dumps(b, ensure_ascii=False))

# 第2次：短信停机
st, b = call("POST", f"/cases/{cid}/contact-attempts", tz,
             {"method": "SMS", "result": "SHUTDOWN", "contactValue": "13911110005",
              "note": "短信平台返回号码停机"})
check("登记第2次短信尝试成功", b["code"] == 0 and b["data"]["roundNo"] == 2)

st, b = call("GET", f"/cases/{cid}", tz)
c = b["data"]["case"]
check("累计失败2次", c.get("failedContactCount") == 2, str(c.get("failedContactCount")))
check("未达阈值不预警", c.get("missingThresholdAlerted") is not True)
check("未达阈值不自动标记失联", c.get("respondentMissing") is not True)

# 非法方式被拒
st, b = call("POST", f"/cases/{cid}/contact-attempts", tz, {"method": "WECHAT", "result": "NO_ANSWER"})
check("非法联系方式被拒绝", b["code"] != 0)

# 第3次：地址核验查无此人 → 达阈值
st, b = call("POST", f"/cases/{cid}/contact-attempts", tw,
             {"method": "ADDRESS_CHECK", "result": "NOT_FOUND", "note": "物业称该住户已搬离半年"})
check("登记第3次地址核验成功", b["code"] == 0 and b["data"]["roundNo"] == 3)

st, b = call("GET", f"/cases/{cid}", tz)
c = b["data"]["case"]
check("累计失败3次", c.get("failedContactCount") == 3, str(c.get("failedContactCount")))
check("达阈值自动标记失联", c.get("respondentMissing") is True)
check("达阈值产生窗口核验提醒", c.get("missingThresholdAlerted") is True)

# 每次尝试都进入时间线
tl = b["data"]["timeline"]
contact_events = [e for e in tl if e["type"] == "CONTACT"]
check("时间线含3条联系尝试事件", len(contact_events) == 3, str(len(contact_events)))
check("时间线标明每次方式与结果",
      all("联系结果：" in e["detail"] for e in contact_events))
alert_events = [e for e in tl if "失联预警" in e.get("title", "")]
check("时间线含达阈值预警事件", len(alert_events) == 1, str(len(alert_events)))

# ---------- 窗口待办出现"核验送达地址" ----------
st, b = call("GET", "/todos", tw)
mine = [t for t in b["data"]["todos"] if t["caseId"] == cid]
check("窗口待办提示核验送达地址",
      any("核验送达地址" in t["action"] for t in mine), json.dumps(mine, ensure_ascii=False))

# ---------- 窗口处置 ----------
# 1) 补充联系方式
st, b = call("POST", f"/cases/{cid}/contact-supplement", tw,
             {"phone": "13700000999", "address": "本市幸福里5-302（现住其父母家：本市朝阳路18号2-201）",
              "note": "申请人提供的被申请人配偶电话及新住址"})
check("窗口补充联系方式成功", b["code"] == 0)
check("案件电话已更新为补充号码", b["data"]["respondentPhone"] == "13700000999",
      b["data"].get("respondentPhone"))

st, b = call("POST", f"/cases/{cid}/contact-supplement", tw, {"note": "无电话无地址"})
check("空补充被拒绝", b["code"] != 0)

# 2) 核验送达地址
st, b = call("POST", f"/cases/{cid}/address-verify", tw,
             {"address": "本市朝阳路18号2-201", "note": "社区协查确认其配偶常住，可留置送达"})
check("窗口核验地址成功", b["code"] == 0 and b["data"]["addressVerified"] is True)
st, b = call("GET", f"/cases/{cid}", tw)
c = b["data"]["case"]
check("核验后达阈值预警消除", c.get("missingThresholdAlerted") is False)

# 3) 改为线下送达
st, b = call("POST", f"/cases/{cid}/offline-delivery", tw,
             {"note": "交送达组三日内上门，留置送达并拍照"})
check("窗口改线下送达成功", b["code"] == 0 and b["data"]["offlineDelivery"] is True)

# 4) 建议转立案
st, b = call("POST", f"/cases/{cid}/suggest-transfer", tw,
             {"note": "三种方式联系均失败，建议按失联转立案"})
check("窗口建议转立案成功", b["code"] == 0 and b["data"]["transferSuggested"] is True)

# ---------- 转立案材料包：前期联系证据自动进入，标明每次结果 ----------
st, b = call("POST", f"/cases/{cid}/transfer", ta,
             {"reasonType": "MISSING", "reasonDetail": "被申请人失联，调解无法进行"})
tm = b["data"]
check("失联转立案材料包生成成功", b["code"] == 0 and tm["status"] == "PREPARED")
check("材料清单含失联联系过程记录", "被申请人失联联系过程记录" in tm["materialList"], tm["materialList"])
check("材料清单标明尝试次数", "共3次" in tm["materialList"], tm["materialList"])
cc = tm["compiledContent"]
check("材料包正文含联系过程专节", "被申请人失联联系过程记录" in cc)
check("材料包逐次标明第1/2/3次", all(f"第{i}次" in cc for i in (1, 2, 3)))
check("材料包标明每次结果",
      all(x in cc for x in ("无人接听", "停机/空号", "查无此人/地址不详")), cc[:300])
check("材料包含窗口补充的新联系方式", "13700000999" in cc)
check("材料包标明窗口地址核验/线下送达/建议转立案",
      "送达地址已核验" in cc and "线下送达已安排" in cc and "建议转立案是" in cc)

# 窗口接收
st, b = call("POST", f"/cases/{cid}/transfer/accept", tw,
             {"litigationCaseNo": "(2026)诉前调失联字第001号"})
check("窗口接收转立案成功", b["data"]["status"] == "TRANSFERRED", json.dumps(b, ensure_ascii=False))

# ---------- 联系成功后计数归零（恢复联系语义） ----------
# 在尚未失联的 c4（赵调解员承办）上验证 REACHED 清零路径
tc = login("zhao", "mediator123")
st, b = call("GET", "/cases/4", tc)
cid4 = b["data"]["case"]["id"]
call("POST", f"/cases/{cid4}/contact-attempts", tc,
     {"method": "PHONE", "result": "NO_ANSWER", "note": "第一次未接"})
st, b = call("POST", f"/cases/{cid4}/contact-attempts", tc,
             {"method": "PHONE", "result": "REACHED", "note": "次日回电，确认到庭"})
check("登记已联系上成功", b["code"] == 0)
st, b = call("GET", f"/cases/{cid4}", tc)
check("联系成功后失败计数归零", b["data"]["case"]["failedContactCount"] == 0,
      str(b["data"]["case"]["failedContactCount"]))
check("联系成功后未标记失联预警", b["data"]["case"]["missingThresholdAlerted"] is not True)

# ---------- 看板/待办仍可用 ----------
st, b = call("GET", "/dashboard", tw)
check("看板失联 KPI 正常返回", "missing" in b["data"]["kpi"])

print(f"\n==== 通过 {PASS} 项，失败 {len(FAILS)} 项 ====")
if FAILS:
    for f in FAILS:
        print(" -", f)
    raise SystemExit(1)
print("ALL_ASSERTIONS_PASSED")
