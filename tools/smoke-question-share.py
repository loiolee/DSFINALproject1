"""题目共享授权的端到端验证（第二阶段 Step 4）。

覆盖：按用户名授权、被授权方读取、无关用户拒绝、use 权限可选题不可改题、
      升级为 edit 后可改题、被授权方不能转授/归档、撤销后读取被拒但已有问卷快照不受影响、
      共享给自己/不存在的用户/非法权限级别的错误处理、visibility 标志的维护。

用法：
    python tools/smoke-question-share.py [base_url]
"""
import json
import random
import string
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18081"
FAILED = []
PASSED = []


def call(method, path, body=None, token=None):
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def check(step, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    (PASSED if cond else FAILED).append(step)
    print(f"{mark}  {step}" + (f"  | {detail}" if detail else ""))
    return cond


def data_of(resp):
    return resp.get("data") if isinstance(resp, dict) else None


def msg_of(resp):
    return resp.get("message") if isinstance(resp, dict) else str(resp)


def register(prefix, suffix):
    name = f"{prefix}_{suffix}"
    code, r = call("POST", "/api/auth/register", {
        "username": name, "password": "smoke1234", "displayName": name,
    })
    return (data_of(r) or {}).get("token"), name, code


def opts(pairs):
    return [{"id": i, "label": l, "order": n + 1} for n, (i, l) in enumerate(pairs)]


# ---------------------------------------------------------------- 准备三个账号
suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
token_a, name_a, c1 = register("shareown", suffix)
token_b, name_b, c2 = register("shareget", suffix)
token_c, name_c, c3 = register("shareout", suffix)
check("1 注册创建者/被授权者/无关用户", c1 == 200 and c2 == 200 and c3 == 200,
      f"HTTP {c1}/{c2}/{c3} A={name_a} B={name_b} C={name_c}")
if not (token_a and token_b and token_c):
    sys.exit(1)

# ---------------------------------------------------------------- 2 建题
code, r = call("POST", "/api/questions", {
    "title": "您对客服响应的满意度",
    "type": "single", "required": True,
    "options": opts([("o1", "满意"), ("o2", "一般"), ("o3", "不满意")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "初版",
}, token_a)
qid = (data_of(r) or {}).get("id")
check("2 A 建题", code == 200 and bool(qid), f"HTTP {code} id={qid}")

# ---------------------------------------------------------------- 3 共享前
code, r = call("GET", f"/api/questions/{qid}", None, token_b)
check("3 共享前 B 无法读取", code == 403, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 4 授权
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_b, "permission": "use"}, token_a)
d = data_of(r) or {}
check("4 A 把题共享给 B（use）", code == 200 and d.get("granteeUsername") == name_b
      and d.get("permission") == "use",
      f"HTTP {code} grantee={d.get('granteeUsername')} permission={d.get('permission')}")

code, r = call("GET", f"/api/questions/{qid}", None, token_a)
check("4b 题目可见性变为 shared", code == 200 and (data_of(r) or {}).get("visibility") == "shared",
      f"visibility={(data_of(r) or {}).get('visibility')}")

# ---------------------------------------------------------------- 5 被授权方读
code, r = call("GET", f"/api/questions/{qid}", None, token_b)
check("5 B 可以读取题目详情", code == 200, f"HTTP {code}")
code, r = call("GET", f"/api/questions/{qid}/versions", None, token_b)
check("5b B 可以读取版本历史", code == 200 and len(data_of(r) or []) == 1,
      f"HTTP {code} 版本数={len(data_of(r) or [])}")
code, r = call("GET", f"/api/questions/{qid}/versions/1/compare/1", None, token_b)
check("5c B 可以对比版本", code == 200 and (data_of(r) or {}).get("identical") is True,
      f"HTTP {code}")

# ---------------------------------------------------------------- 6 无关用户
code, r = call("GET", f"/api/questions/{qid}", None, token_c)
check("6 无关用户 C 无法读取", code == 403, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 7 B 选题
code, r = call("GET", f"/api/questions/{qid}/versions/1", None, token_b)
v1 = data_of(r) or {}
code, r = call("POST", "/api/surveys", {
    "title": "B 引用 A 的题目",
    "questions": [{
        "id": "bq1", "type": v1.get("type"), "title": v1.get("title"),
        "questionId": qid, "questionVersionId": v1.get("id"), "versionNo": v1.get("versionNo"),
        "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                    for o in (v1.get("options") or [])],
        "constraints": v1.get("constraints"),
    }],
}, token_b)
sid_b = (data_of(r) or {}).get("id")
check("7 B 能把共享来的题目选进自己的问卷", code == 200 and bool(sid_b),
      f"HTTP {code} survey={sid_b}")

# ---------------------------------------------------------------- 8 C 不能白拿
code, r = call("POST", "/api/surveys", {
    "title": "C 试图引用未授权的题目",
    "questions": [{
        "type": v1.get("type"), "title": v1.get("title"),
        "questionId": qid, "questionVersionId": v1.get("id"), "versionNo": 1,
        "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                    for o in (v1.get("options") or [])],
    }],
}, token_c)
check("8 未授权的 C 无法引用该题目", code == 403 and "无权使用" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 9 use 权限边界
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "B 想改题", "type": "single",
    "options": opts([("o1", "满意"), ("o2", "一般")]),
}, token_b)
check("9 use 权限不能修改题目", code == 403, f"HTTP {code} message={msg_of(r)}")

code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_c, "permission": "use"}, token_b)
check("10 被授权方不能转授给第三人", code == 403, f"HTTP {code} message={msg_of(r)}")

code, r = call("DELETE", f"/api/questions/{qid}", None, token_b)
check("11 被授权方不能归档题目", code == 403, f"HTTP {code} message={msg_of(r)}")

code, r = call("GET", f"/api/questions/{qid}/shares", None, token_b)
check("12 被授权方不能查看授权列表", code == 403, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 13 升级为 edit
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_b, "permission": "edit"}, token_a)
d = data_of(r) or {}
check("13 重复共享视为更新权限级别", code == 200 and d.get("permission") == "edit",
      f"HTTP {code} permission={d.get('permission')}")
code, r = call("GET", f"/api/questions/{qid}/shares", None, token_a)
check("13b 授权列表仍只有一条记录", code == 200 and len(data_of(r) or []) == 1,
      f"条数={len(data_of(r) or [])}")

# ---------------------------------------------------------------- 14 edit 可改题
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "您对客服响应的满意度（改）",
    "type": "single",
    "options": opts([("o1", "满意"), ("o2", "一般"), ("o3", "不满意"), ("o4", "非常满意")]),
    "changeNote": "B 追加非常满意",
}, token_b)
d = data_of(r) or {}
check("14 edit 权限可以创建新版本", code == 200 and d.get("currentVersionNo") == 2,
      f"HTTP {code} 当前版本={d.get('currentVersionNo')}")

code, r = call("DELETE", f"/api/questions/{qid}", None, token_b)
check("15 edit 权限仍不能归档", code == 403, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 16 版本提示
code, r = call("GET", f"/api/surveys/{sid_b}", None, token_b)
q1 = ((data_of(r) or {}).get("questions") or [{}])[0]
check("16 B 的问卷提示有 v2 可用", q1.get("versionNo") == 1 and q1.get("newerVersionAvailable") is True,
      f"versionNo={q1.get('versionNo')} latest={q1.get('latestVersionNo')}")

# ---------------------------------------------------------------- 17 我的共享列表
code, r = call("GET", "/api/questions/shared-with-me", None, token_b)
rows = data_of(r) or []
check("17 B 的「共享给我的」列表含该题", code == 200 and any(x["questionId"] == qid for x in rows),
      f"HTTP {code} 条数={len(rows)}")
code, r = call("GET", "/api/questions/shared-with-me", None, token_c)
check("17b C 的「共享给我的」为空", code == 200 and not (data_of(r) or []),
      f"条数={len(data_of(r) or [])}")

# ---------------------------------------------------------------- 18 撤销
code, r = call("GET", f"/api/questions/{qid}/shares", None, token_a)
grantee_id = (data_of(r) or [{}])[0].get("granteeId")
code, r = call("DELETE", f"/api/questions/{qid}/shares/{grantee_id}", None, token_a)
check("18 A 撤销 B 的授权", code == 200, f"HTTP {code}")

code, r = call("GET", f"/api/questions/{qid}", None, token_b)
check("19 撤销后 B 无法再读取", code == 403, f"HTTP {code} message={msg_of(r)}")
code, r = call("GET", f"/api/questions/{qid}", None, token_a)
check("19b 无授权后可见性回退为 private", code == 200 and (data_of(r) or {}).get("visibility") == "private",
      f"visibility={(data_of(r) or {}).get('visibility')}")

# ---------------------------------------------------------------- 20 快照不受影响
code, r = call("GET", f"/api/surveys/{sid_b}", None, token_b)
q1 = ((data_of(r) or {}).get("questions") or [{}])[0]
check("20 撤销授权不影响 B 已有问卷的题目快照",
      code == 200 and q1.get("title") == v1.get("title") and len(q1.get("options") or []) == 3,
      f"HTTP {code} title={q1.get('title')} 选项数={len(q1.get('options') or [])}")

# ---------------------------------------------------------------- 21 错误处理
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_a}, token_a)
check("21 不能共享给自己", code == 400 and "自己" in msg_of(r), f"HTTP {code} message={msg_of(r)}")
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": "no_such_user_xyz"}, token_a)
check("22 共享给不存在的用户返回 404", code == 404, f"HTTP {code} message={msg_of(r)}")
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_c, "permission": "admin"}, token_a)
check("23 非法权限级别被拒", code == 400 and "授权级别" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": ""}, token_a)
check("24 空用户名被校验拦截", code == 400, f"HTTP {code} message={msg_of(r)}")

print()
print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print("失败项：" + "、".join(FAILED))
print(f"清理参考：用户 {name_a} / {name_b} / {name_c} / 题目 {qid} / 问卷 {sid_b}")
sys.exit(1 if FAILED else 0)
