"""反向使用查询与跨问卷单题统计的端到端验证（第二阶段 Step 5 / Step 6）。

覆盖：「某题被哪些问卷使用」的列表与版本落后标记、只有题目创建者能看、
      跨问卷统计按版本分组的选项分布、未答题版本标记、权限边界。

脚本会真实提交答卷（同一用户重复填答），以便统计有数据可算。

用法：
    python tools/smoke-question-usage-stats.py [base_url]
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


def fill(token, public_id, option_id):
    """完整走一遍填答：start → answer → submit。"""
    code, r = call("POST", f"/api/surveys/{public_id}/start", None, token)
    if code != 200:
        return code, None
    response_id = (data_of(r) or {}).get("responseId")
    current = (data_of(r) or {}).get("question") or {}
    code, r = call("POST", f"/api/responses/{response_id}/answer",
                   {"questionId": current.get("id"), "answer": option_id}, token)
    if code != 200:
        return code, response_id
    code, r = call("POST", f"/api/responses/{response_id}/submit", None, token)
    return code, response_id


# ---------------------------------------------------------------- 准备
suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
token_a, name_a, c1 = register("usageown", suffix)
token_b, name_b, c2 = register("usagefill", suffix)
token_d, name_d, c3 = register("usageout", suffix)
check("1 注册题目创建者/问卷创建者/无关用户", c1 == 200 and c2 == 200 and c3 == 200,
      f"HTTP {c1}/{c2}/{c3}")
if not (token_a and token_b and token_d):
    sys.exit(1)

# ---------------------------------------------------------------- 2 建题
code, r = call("POST", "/api/questions", {
    "title": "您对本课程的整体评价",
    "type": "single", "required": True,
    "options": opts([("o1", "很好"), ("o2", "一般"), ("o3", "较差")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "初版",
}, token_a)
qid = (data_of(r) or {}).get("id")
check("2 A 建题 v1", code == 200 and bool(qid), f"HTTP {code} id={qid}")

# ---------------------------------------------------------------- 3 共享 + 建问卷
code, r = call("POST", f"/api/questions/{qid}/shares", {"username": name_b, "permission": "use"}, token_a)
check("3 A 把题共享给 B", code == 200, f"HTTP {code}")

code, r = call("GET", f"/api/questions/{qid}/versions/1", None, token_b)
v1 = data_of(r) or {}
code, r = call("POST", "/api/surveys", {
    "title": "B 用共享题目做的问卷",
    "allowMultiple": True,
    "questions": [{
        "id": "bq1", "type": v1.get("type"), "title": v1.get("title"),
        "questionId": qid, "questionVersionId": v1.get("id"), "versionNo": v1.get("versionNo"),
        "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                    for o in (v1.get("options") or [])],
        "constraints": v1.get("constraints"),
    }],
}, token_b)
sid = (data_of(r) or {}).get("id")
check("4 B 建问卷引用 v1", code == 200 and bool(sid), f"HTTP {code} survey={sid}")

code, r = call("POST", f"/api/surveys/{sid}/publish", None, token_b)
public_id = (data_of(r) or {}).get("publicId")
check("5 发布问卷取得公开短码", code == 200 and bool(public_id), f"HTTP {code} publicId={public_id}")

# ---------------------------------------------------------------- 6 填三份答卷
results = [fill(token_b, public_id, "o1"), fill(token_b, public_id, "o1"), fill(token_b, public_id, "o3")]
check("6 提交三份答卷（o1/o1/o3）", all(code == 200 for code, _ in results),
      f"HTTP={[code for code, _ in results]}")

# ---------------------------------------------------------------- 7 使用列表
code, r = call("GET", f"/api/questions/{qid}/usages", None, token_a)
rows = data_of(r) or []
row = rows[0] if rows else {}
check("7 题目创建者能看到使用它的问卷", code == 200 and len(rows) == 1
      and row.get("surveyId") == sid,
      f"HTTP {code} 条数={len(rows)} survey={row.get('surveyId')}")
check("7b 标出问卷创建者与绑定版本",
      row.get("surveyOwnerUsername") == name_b and row.get("boundVersionNo") == 1
      and row.get("latestVersion") == 1 and row.get("outdated") is False,
      f"owner={row.get('surveyOwnerUsername')} bound={row.get('boundVersionNo')} latest={row.get('latestVersion')} outdated={row.get('outdated')}")

# ---------------------------------------------------------------- 8 权限边界
code, r = call("GET", f"/api/questions/{qid}/usages", None, token_b)
check("8 被授权者不能查看使用情况", code == 403, f"HTTP {code} message={msg_of(r)}")
code, r = call("GET", f"/api/questions/{qid}/usages", None, token_d)
check("8b 无关用户不能查看使用情况", code == 403, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 9 跨问卷统计
code, r = call("GET", f"/api/questions/{qid}/stats", None, token_a)
d = data_of(r) or {}
versions = d.get("versions") or []
v1_stats = versions[0] if versions else {}
option_map = {o["optionId"]: o for o in (v1_stats.get("options") or [])}
check("9 统计返回问卷数与版本数", code == 200 and d.get("surveyCount") == 1
      and d.get("versionCount") == 1 and d.get("totalAnswered") == 3,
      f"HTTP {code} surveyCount={d.get('surveyCount')} versionCount={d.get('versionCount')} totalAnswered={d.get('totalAnswered')}")
check("9b 选项计数正确（o1=2, o3=1, o2=0）",
      option_map.get("o1", {}).get("count") == 2
      and option_map.get("o3", {}).get("count") == 1
      and option_map.get("o2", {}).get("count") == 0,
      f"o1={option_map.get('o1', {}).get('count')} o2={option_map.get('o2', {}).get('count')} o3={option_map.get('o3', {}).get('count')}")
check("9c 没人选的选项也返回且占比为 0",
      option_map.get("o2", {}).get("ratio") == 0 and "o2" in option_map,
      f"o2.ratio={option_map.get('o2', {}).get('ratio')}")
check("9d 占比以本版本回答数为分母",
      abs(option_map.get("o1", {}).get("ratio", 0) - 2 / 3) < 1e-9,
      f"o1.ratio={option_map.get('o1', {}).get('ratio')}")

code, r = call("GET", f"/api/questions/{qid}/stats", None, token_b)
check("10 被授权者不能查看跨问卷统计", code == 403 and "创建者" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")
code, r = call("GET", f"/api/questions/{qid}/stats", None, token_d)
check("10b 无关用户不能查看跨问卷统计", code == 403, f"HTTP {code}")

# ---------------------------------------------------------------- 11 改版后
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "您对本课程的整体评价（含未上过）",
    "type": "single", "required": True,
    "options": opts([("o1", "很好"), ("o2", "一般"), ("o3", "较差"), ("o4", "没上过")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "补充没上过",
}, token_a)
check("11 A 改题生成 v2", code == 200 and (data_of(r) or {}).get("currentVersionNo") == 2,
      f"HTTP {code}")

code, r = call("GET", f"/api/questions/{qid}/usages", None, token_a)
row = (data_of(r) or [{}])[0]
check("12 使用列表标出该问卷落后于最新版本",
      row.get("boundVersionNo") == 1 and row.get("latestVersion") == 2 and row.get("outdated") is True,
      f"bound={row.get('boundVersionNo')} latest={row.get('latestVersion')} outdated={row.get('outdated')}")

code, r = call("GET", f"/api/questions/{qid}/stats", None, token_a)
d = data_of(r) or {}
versions = d.get("versions") or []
by_no = {v.get("versionNo"): v for v in versions}
check("13 统计按版本分组且 v2 无回答",
      len(versions) == 2 and by_no.get(1, {}).get("answeredCount") == 3
      and by_no.get(2, {}).get("answeredCount") == 0,
      f"版本={[v.get('versionNo') for v in versions]} v1回答={by_no.get(1, {}).get('answeredCount')} v2回答={by_no.get(2, {}).get('answeredCount')}")
check("13b 标记存在未回答的版本", d.get("hasEmptyVersion") is True,
      f"hasEmptyVersion={d.get('hasEmptyVersion')}")
check("13c v2 的选项含新增的 o4 且计数为 0",
      any(o["optionId"] == "o4" and o["count"] == 0
          for o in (by_no.get(2, {}).get("options") or [])),
      f"v2选项={[o['optionId'] for o in (by_no.get(2, {}).get('options') or [])]}")
check("13d 合计回答数不受版本数影响", d.get("totalAnswered") == 3,
      f"totalAnswered={d.get('totalAnswered')}")

# ---------------------------------------------------------------- 14 空统计
code, r = call("POST", "/api/questions", {
    "title": "从没人答过的题", "type": "single",
    "options": opts([("x1", "甲"), ("x2", "乙")]),
}, token_a)
empty_qid = (data_of(r) or {}).get("id")
code, r = call("GET", f"/api/questions/{empty_qid}/stats", None, token_a)
d = data_of(r) or {}
check("14 无引用的题目统计为全零",
      code == 200 and d.get("surveyCount") == 0 and d.get("totalAnswered") == 0
      and d.get("versionCount") == 1,
      f"HTTP {code} surveyCount={d.get('surveyCount')} totalAnswered={d.get('totalAnswered')}")
code, r = call("GET", f"/api/questions/{empty_qid}/usages", None, token_a)
check("14b 无引用时使用列表为空", code == 200 and (data_of(r) or []) == [],
      f"条数={len(data_of(r) or [])}")

print()
print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print("失败项：" + "、".join(FAILED))
print(f"清理参考：用户 {name_a} / {name_b} / {name_d} / 题目 {qid} / {empty_qid} / 问卷 {sid}")
sys.exit(1 if FAILED else 0)
