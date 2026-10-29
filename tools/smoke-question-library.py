"""题库后端端到端冒烟脚本（第二阶段 Step 1 验证用）。

覆盖：建题 / 改题（追加版本）/ 版本历史 / 恢复旧版本 / 详情聚合 / 列表与关键词过滤
      / 问卷引用题库版本 / 引用即只读（非法内容拒绝）/ 反向引用计数 / 加入题库幂等
      / 归档 / 归档后禁止修改 / 默认列表排除归档 / 无令牌 401

用法：
    python tools/smoke-question-library.py [base_url]

默认 base_url 为 http://localhost:18081。
脚本会创建一次性用户与数据，不修改任何既有数据。
"""
import json
import random
import string
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18081"
FAILED = []
PASSED = []


def call(method, path, body=None, token=None):
    """发起请求，返回 (HTTP 状态码, 解析后的 JSON 或原始文本)。"""
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


def option(label, oid):
    return {"id": oid, "label": label, "order": int(oid[-1]) if oid[-1].isdigit() else 1}


# ---------------------------------------------------------------- 1 注册
suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
code, r = call("POST", "/api/auth/register", {
    "username": "libsmoke_" + suffix,
    "password": "smoke1234",
    "displayName": "题库冒烟",
})
token = data_of(r).get("token") if code == 200 else None
check("1 注册并取得令牌", code == 200 and bool(token), f"HTTP {code}")

if not token:
    print("\n注册失败，终止。原始响应：", r)
    sys.exit(1)

# ---------------------------------------------------------------- 2 建题
opts_v1 = [option("完全满意", "opt1"), option("基本满意", "opt2"), option("不满意", "opt3")]
code, r = call("POST", "/api/questions", {
    "title": "您对本次服务的整体满意度如何",
    "type": "single",
    "required": True,
    "options": opts_v1,
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "首次创建",
}, token)
d = data_of(r) or {}
qid = d.get("id")
check("2 建题生成 v1", code == 200 and d.get("currentVersionNo") == 1,
      f"HTTP {code} id={qid} v={d.get('currentVersionNo')} 版本数={d.get('versionCount')}")

# ---------------------------------------------------------------- 3 改题
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "您对本次服务的整体满意度如何（含非常满意）",
    "type": "single",
    "required": True,
    "options": [option("非常满意", "opt1"), option("满意", "opt2"),
                option("一般", "opt3"), option("不满意", "opt4")],
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "补充非常满意与不满意两档",
}, token)
d = data_of(r) or {}
cv = d.get("currentVersion") or {}
check("3 改题追加 v2 且不改动 v1", code == 200 and d.get("currentVersionNo") == 2
      and d.get("versionCount") == 2 and cv.get("parentVersionId"),
      f"HTTP {code} 当前版本={d.get('currentVersionNo')} 版本数={d.get('versionCount')}")

# ---------------------------------------------------------------- 4 版本历史
code, r = call("GET", f"/api/questions/{qid}/versions", None, token)
vers = data_of(r) or []
check("4 版本历史按版本号倒序", code == 200 and [v["versionNo"] for v in vers] == [2, 1],
      f"HTTP {code} 版本号={[v['versionNo'] for v in vers]}")

# ---------------------------------------------------------------- 5 恢复旧版本
note = quote("回到初版措辞")
code, r = call("POST", f"/api/questions/{qid}/versions/1/restore?changeNote={note}", None, token)
d = data_of(r) or {}
cv = d.get("currentVersion") or {}
check("5 恢复 v1 生成 v3 并记录来源", code == 200 and d.get("currentVersionNo") == 3
      and cv.get("restoredFromVersionId"),
      f"HTTP {code} 当前版本={d.get('currentVersionNo')} restoredFrom={bool(cv.get('restoredFromVersionId'))}")
check("5b 恢复后内容回到初版三选项", len(cv.get("options") or []) == 3,
      f"选项数={len(cv.get('options') or [])}")

# ---------------------------------------------------------------- 6 详情
code, r = call("GET", f"/api/questions/{qid}", None, token)
d = data_of(r) or {}
check("6 详情聚合版本数与引用数", code == 200 and d.get("versionCount") == 3
      and d.get("usageCount") == 0,
      f"HTTP {code} 版本数={d.get('versionCount')} 引用数={d.get('usageCount')}")

# ---------------------------------------------------------------- 7 列表与关键词
code, r = call("GET", "/api/questions", None, token)
rows = data_of(r) or []
check("7 默认列表含该题", code == 200 and any(x["id"] == qid for x in rows),
      f"HTTP {code} 条数={len(rows)}")

kw = quote("满意度")
code, r = call("GET", f"/api/questions?keyword={kw}", None, token)
rows = data_of(r) or []
check("7b 关键词命中", code == 200 and any(x["id"] == qid for x in rows),
      f"HTTP {code} 条数={len(rows)}")

kw = quote("不存在的关键词xyz")
code, r = call("GET", f"/api/questions?keyword={kw}", None, token)
rows = data_of(r) or []
check("7c 关键词未命中返回空", code == 200 and not any(x["id"] == qid for x in rows),
      f"HTTP {code} 条数={len(rows)}")

# ---------------------------------------------------------------- 8 建问卷并引用题库版本（v1）
code, r = call("GET", f"/api/questions/{qid}/versions/1", None, token)
v1 = data_of(r) or {}
code, r = call("POST", "/api/surveys", {
    "title": "题库引用冒烟问卷",
    "description": "验证引用即快照",
    "questions": [{
        "id": "q_local_1",
        "type": v1.get("type"),
        "title": v1.get("title"),
        "questionId": qid,
        "questionVersionId": v1.get("id"),
        "versionNo": v1.get("versionNo"),
        "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                    for o in (v1.get("options") or [])],
        "constraints": v1.get("constraints"),
    }],
}, token)
d = data_of(r) or {}
sid = d.get("id")
qs = d.get("questions") or []
q0 = qs[0] if qs else {}
check("8 问卷引用题库版本成功", code == 200 and q0.get("sourceType") == "library"
      and q0.get("questionVersionId") == v1.get("id"),
      f"HTTP {code} survey={sid} sourceType={q0.get('sourceType')} versionNo={q0.get('versionNo')}")

# ---------------------------------------------------------------- 8b 只传版本 ID 即可引用
code, r = call("POST", "/api/surveys", {
    "title": "只传版本 ID 的问卷",
    "questions": [{"id": "q_min", "questionVersionId": v1.get("id")}],
}, token)
d = data_of(r) or {}
q0 = (d.get("questions") or [{}])[0]
check("8b 引用题库题目只需传 questionVersionId", code == 200
      and q0.get("sourceType") == "library" and q0.get("title") == v1.get("title")
      and len(q0.get("options") or []) == len(v1.get("options") or []),
      f"HTTP {code} title={q0.get('title')} 选项数={len(q0.get('options') or [])}")
# 立刻删掉这份临时问卷，否则后面「引用数」的断言会被它影响。
call("DELETE", f"/api/surveys/{(d or {}).get('id')}", None, token)

# ---------------------------------------------------------------- 8c 本地题目仍然必填题干
code, r = call("POST", "/api/surveys", {
    "title": "本地题目缺题干",
    "questions": [{"type": "text"}],
}, token)
check("8c 本地题目缺题干仍被拒", code == 400 and "题目标题不能为空" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

code, r = call("POST", "/api/surveys", {
    "title": "本地题目缺题型",
    "questions": [{"title": "只有题干"}],
}, token)
check("8d 本地题目缺题型仍被拒", code == 400 and "题型不能为空" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 9 快照独立：题库已到 v3，问卷仍绑 v1
code, r = call("GET", f"/api/surveys/{sid}", None, token)
d = data_of(r) or {}
q0 = (d.get("questions") or [{}])[0]
check("9 问卷快照仍是 v1 内容（未随题库改版漂移）",
      code == 200 and q0.get("versionNo") == 1 and len(q0.get("options") or []) == 3
      and q0.get("title") == v1.get("title"),
      f"HTTP {code} versionNo={q0.get('versionNo')} 选项数={len(q0.get('options') or [])}")

# ---------------------------------------------------------------- 10 反向引用计数
code, r = call("GET", f"/api/questions/{qid}", None, token)
d = data_of(r) or {}
check("10 引用数变为 1", code == 200 and d.get("usageCount") == 1,
      f"HTTP {code} 引用数={d.get('usageCount')}")

# ---------------------------------------------------------------- 11 引用即只读：改内容被拒
code, r = call("POST", "/api/surveys", {
    "title": "非法覆盖尝试",
    "questions": [{
        "type": v1.get("type"),
        "title": "被篡改的题干",
        "questionId": qid,
        "questionVersionId": v1.get("id"),
        "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                    for o in (v1.get("options") or [])],
    }],
}, token)
check("11 问卷内篡改题库题目内容被拒", code == 400 and "题库" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 12 引用不存在的版本
code, r = call("POST", "/api/surveys", {
    "title": "引用不存在版本",
    "questions": [{"type": "single", "title": "x", "questionVersionId": "no_such_version"}],
}, token)
check("12 引用不存在版本被拒", code in (400, 404),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 13 加入题库：从本地题目提升
code, r = call("POST", "/api/surveys", {
    "title": "含本地题目的问卷",
    "questions": [{
        "id": "q_local_2",
        "type": "text",
        "title": "还有哪些建议",
        "required": False,
        "constraints": {"maxLength": 500},
    }],
}, token)
d = data_of(r) or {}
sid2 = d.get("id")
check("13 建含本地题目的问卷", code == 200 and (d.get("questions") or [{}])[0].get("sourceType") == "local",
      f"HTTP {code} survey={sid2}")

note = quote("来自问卷沉淀")
code, r = call("POST", f"/api/surveys/{sid2}/questions/q_local_2/promote?changeNote={note}", None, token)
d = data_of(r) or {}
promoted_id = d.get("id")
check("14 本地题目加入题库生成新题", code == 200 and d.get("currentVersionNo") == 1,
      f"HTTP {code} 新题={promoted_id} v={d.get('currentVersionNo')}")

# ---------------------------------------------------------------- 15 重复加入题库
code, r = call("POST", f"/api/surveys/{sid2}/questions/q_local_2/promote", None, token)
check("15 同一题重复加入题库被拒", code == 409,
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 16 归档
code, r = call("DELETE", f"/api/questions/{promoted_id}", None, token)
check("16 归档题目", code == 200, f"HTTP {code}")

code, r = call("PUT", f"/api/questions/{promoted_id}", {
    "title": "归档后还想改", "type": "text",
}, token)
check("17 归档后禁止修改", code == 409 and "归档" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

code, r = call("GET", "/api/questions", None, token)
rows = data_of(r) or []
check("18 默认列表排除归档题目", code == 200 and not any(x["id"] == promoted_id for x in rows),
      f"HTTP {code} 条数={len(rows)}")

code, r = call("GET", "/api/questions?status=archived", None, token)
rows = data_of(r) or []
check("19 archived 列表含归档题目", code == 200 and any(x["id"] == promoted_id for x in rows),
      f"HTTP {code} 条数={len(rows)}")

code, r = call("GET", "/api/questions?status=all", None, token)
rows = data_of(r) or []
check("20 all 列表同时含在用与归档", code == 200
      and any(x["id"] == promoted_id for x in rows) and any(x["id"] == qid for x in rows),
      f"HTTP {code} 条数={len(rows)}")

# ---------------------------------------------------------------- 21 权限边界
code, r = call("POST", "/api/auth/register", {
    "username": "libsmoke_other_" + suffix,
    "password": "smoke1234",
})
token2 = data_of(r).get("token") if code == 200 else None
code, r = call("GET", f"/api/questions/{qid}", None, token2)
check("21 他人无法读取我的题目", code in (403, 404),
      f"HTTP {code} message={msg_of(r)}")

code, r = call("PUT", f"/api/questions/{qid}", {"title": "劫持", "type": "text"}, token2)
check("22 他人无法修改我的题目", code in (403, 404),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 23 未认证
code, r = call("GET", "/api/questions", None, None)
check("23 无令牌访问题库被拒", code == 401, f"HTTP {code}")

# ---------------------------------------------------------------- 24 密码规则
code, r = call("POST", "/api/auth/register", {
    "username": "libsmoke_pw_" + suffix, "password": "1234567",
})
check("24 7 位密码被拒", code == 400 and "8-16" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")
code, r = call("POST", "/api/auth/register", {
    "username": "libsmoke_pw2_" + suffix, "password": "12345678901234567",
})
check("25 17 位密码被拒", code == 400 and "8-16" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")
code, r = call("POST", "/api/auth/register", {
    "username": "libsmoke_pw3_" + suffix, "password": "12345678",
})
check("26 8 位密码通过", code == 200, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 汇总
print()
print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print("失败项：" + "、".join(FAILED))
print(f"清理参考：用户 libsmoke_{suffix} / 题目 {qid}、{promoted_id} / 问卷 {sid}、{sid2}")
sys.exit(1 if FAILED else 0)
