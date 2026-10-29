"""版本对比与「有新版本可用」提示的端到端验证（第二阶段 Step 2）。

覆盖：v1→v2 差异结构、同版本对比、不存在版本、问卷详情的 newerVersionAvailable 提示、
      手动换绑、换绑后提示消失、实例配置（required/jumps）不随版本变化、
      换绑到不存在版本、已发布问卷禁止换绑、跳转规则引用选项缺失时拒绝换绑。

用法：
    python tools/smoke-version-diff.py [base_url]
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


def opts(pairs):
    return [{"id": i, "label": l, "order": n + 1} for n, (i, l) in enumerate(pairs)]


# ---------------------------------------------------------------- 准备
suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
code, r = call("POST", "/api/auth/register", {
    "username": "diffsmoke_" + suffix, "password": "smoke1234", "displayName": "版本对比冒烟",
})
token = data_of(r).get("token") if code == 200 else None
check("1 注册并取得令牌", code == 200 and bool(token), f"HTTP {code}")
if not token:
    print("注册失败：", r)
    sys.exit(1)

# v1：三个选项
code, r = call("POST", "/api/questions", {
    "title": "您使用本产品的频率",
    "type": "single", "required": True,
    "options": opts([("opt1", "每天"), ("opt2", "每周"), ("opt3", "偶尔")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "初版",
}, token)
qid = (data_of(r) or {}).get("id")
check("2 建题生成 v1", code == 200 and bool(qid), f"HTTP {code} id={qid}")

# v2：题干改 + 新增一个选项
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "您使用本产品的频率（含从不使用）",
    "type": "single", "required": True,
    "options": opts([("opt1", "每天"), ("opt2", "每周"), ("opt3", "偶尔"), ("opt4", "从不使用")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "补充从不使用",
}, token)
check("3 改题生成 v2", code == 200 and (data_of(r) or {}).get("currentVersionNo") == 2,
      f"HTTP {code}")

# v3：删掉 opt1
code, r = call("PUT", f"/api/questions/{qid}", {
    "title": "您使用本产品的频率（含从不使用）",
    "type": "single", "required": True,
    "options": opts([("opt2", "每周"), ("opt3", "偶尔"), ("opt4", "从不使用")]),
    "constraints": {"minSelect": 1, "maxSelect": 1},
    "changeNote": "去掉每天选项",
}, token)
check("4 改题生成 v3（删除 opt1）", code == 200 and (data_of(r) or {}).get("currentVersionNo") == 3,
      f"HTTP {code}")

# ---------------------------------------------------------------- 5 差异结构
code, r = call("GET", f"/api/questions/{qid}/versions/1/compare/2", None, token)
d = data_of(r) or {}
opts_diff = d.get("options") or {}
check("5 v1→v2 差异可读", code == 200 and d.get("identical") is False,
      f"HTTP {code} identical={d.get('identical')}")
check("5b 题型未变、题干已变",
      (d.get("type") or {}).get("changed") is False and (d.get("title") or {}).get("changed") is True,
      f"type.changed={(d.get('type') or {}).get('changed')} title.changed={(d.get('title') or {}).get('changed')}")
check("5c 识别出新增一个选项",
      len(opts_diff.get("added") or []) == 1 and not (opts_diff.get("removed") or [])
      and not (opts_diff.get("modified") or []),
      f"added={[o['label'] for o in (opts_diff.get('added') or [])]} removed={len(opts_diff.get('removed') or [])}")
check("5d 摘要包含题干与新增选项描述",
      any("题干" in s for s in (d.get("summary") or []))
      and any("从不使用" in s for s in (d.get("summary") or [])),
      f"summary={d.get('summary')}")

# ---------------------------------------------------------------- 6 反向差异
code, r = call("GET", f"/api/questions/{qid}/versions/2/compare/1", None, token)
d = data_of(r) or {}
check("6 反向对比识别删除选项",
      code == 200 and len((d.get("options") or {}).get("removed") or []) == 1,
      f"HTTP {code} removed={[o['label'] for o in ((d.get('options') or {}).get('removed') or [])]}")

# ---------------------------------------------------------------- 7 同版本对比
code, r = call("GET", f"/api/questions/{qid}/versions/1/compare/1", None, token)
d = data_of(r) or {}
check("7 同版本对比标记为完全相同",
      code == 200 and d.get("identical") is True
      and (d.get("summary") or []) == ["两个版本内容完全相同"],
      f"HTTP {code} identical={d.get('identical')} summary={d.get('summary')}")

# ---------------------------------------------------------------- 8 不存在的版本
code, r = call("GET", f"/api/questions/{qid}/versions/1/compare/99", None, token)
check("8 对比不存在的版本返回 404", code == 404, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 9 建问卷引用 v1
code, r = call("GET", f"/api/questions/{qid}/versions/1", None, token)
v1 = data_of(r) or {}
code, r = call("POST", "/api/surveys", {
    "title": "版本提示冒烟问卷",
    "questions": [
        {
            "id": "q1", "order": 1,
            "type": v1.get("type"), "title": v1.get("title"), "required": False,
            "questionId": qid, "questionVersionId": v1.get("id"), "versionNo": v1.get("versionNo"),
            "options": [{"id": o["id"], "label": o["label"], "order": o["order"]}
                        for o in (v1.get("options") or [])],
            "constraints": v1.get("constraints"),
            "jumps": [{"op": "eq", "optionId": "opt1", "targetQuestionId": "q2"}],
        },
        {"id": "q2", "order": 2, "type": "text", "title": "补充说明", "required": False,
         "constraints": {"maxLength": 200}},
    ],
}, token)
sid = (data_of(r) or {}).get("id")
check("9 建问卷引用 v1 且带基于选项的跳转", code == 200 and bool(sid), f"HTTP {code} survey={sid}")

# ---------------------------------------------------------------- 10 提示字段
code, r = call("GET", f"/api/surveys/{sid}", None, token)
d = data_of(r) or {}
q1 = (d.get("questions") or [{}])[0]
check("10 问卷详情提示有新版本可用",
      code == 200 and q1.get("versionNo") == 1 and q1.get("latestVersionNo") == 3
      and q1.get("newerVersionAvailable") is True,
      f"HTTP {code} versionNo={q1.get('versionNo')} latest={q1.get('latestVersionNo')} newer={q1.get('newerVersionAvailable')}")
q2 = (d.get("questions") or [{}, {}])[1]
check("10b 本地题目不带版本提示", q2.get("latestVersionNo") is None and q2.get("newerVersionAvailable") is None,
      f"latest={q2.get('latestVersionNo')} newer={q2.get('newerVersionAvailable')}")

# ---------------------------------------------------------------- 11 跳转规则保护
code, r = call("PUT", f"/api/surveys/{sid}/questions/q1/version?versionNo=3", None, token)
check("11 换绑会破坏跳转规则时被拒", code == 409 and "跳转" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 12 正常换绑
code, r = call("PUT", f"/api/surveys/{sid}/questions/q1/version?versionNo=2", None, token)
d = data_of(r) or {}
q1 = (d.get("questions") or [{}])[0]
check("12 换绑到 v2 成功", code == 200 and q1.get("versionNo") == 2 and len(q1.get("options") or []) == 4,
      f"HTTP {code} versionNo={q1.get('versionNo')} 选项数={len(q1.get('options') or [])}")
check("12b 换绑不改动实例配置（required 仍为 false、跳转仍在）",
      q1.get("required") is False and len(q1.get("jumps") or []) == 1,
      f"required={q1.get('required')} jumps={len(q1.get('jumps') or [])}")

# ---------------------------------------------------------------- 13 提示更新
code, r = call("GET", f"/api/surveys/{sid}", None, token)
q1 = ((data_of(r) or {}).get("questions") or [{}])[0]
check("13 换绑后仍有新版本提示（v3 比 v2 新）",
      q1.get("versionNo") == 2 and q1.get("newerVersionAvailable") is True,
      f"versionNo={q1.get('versionNo')} newer={q1.get('newerVersionAvailable')}")

# ---------------------------------------------------------------- 14 换绑到不存在版本
code, r = call("PUT", f"/api/surveys/{sid}/questions/q1/version?versionNo=99", None, token)
check("14 换绑到不存在版本返回 404", code == 404, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 15 本地题目不可换绑
code, r = call("PUT", f"/api/surveys/{sid}/questions/q2/version?versionNo=1", None, token)
check("15 本地题目换绑被拒", code == 409, f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 16 发布后禁止换绑
code, r = call("POST", f"/api/surveys/{sid}/publish", None, token)
check("16 问卷发布成功", code == 200, f"HTTP {code} message={msg_of(r)}")
code, r = call("PUT", f"/api/surveys/{sid}/questions/q1/version?versionNo=1", None, token)
check("17 已发布问卷禁止换绑", code == 409 and "草稿" in msg_of(r),
      f"HTTP {code} message={msg_of(r)}")

# ---------------------------------------------------------------- 18 已发布问卷仍显示提示
code, r = call("GET", f"/api/surveys/{sid}", None, token)
q1 = ((data_of(r) or {}).get("questions") or [{}])[0]
check("18 已发布问卷只提示不升级（版本仍为 2）",
      q1.get("versionNo") == 2 and q1.get("newerVersionAvailable") is True,
      f"versionNo={q1.get('versionNo')} newer={q1.get('newerVersionAvailable')}")

# ---------------------------------------------------------------- 19 权限
code, r = call("POST", "/api/auth/register", {
    "username": "diffsmoke_other_" + suffix, "password": "smoke1234",
})
token2 = data_of(r).get("token") if code == 200 else None
code, r = call("GET", f"/api/questions/{qid}/versions/1/compare/2", None, token2)
check("19 他人无法对比我的题目版本", code in (403, 404), f"HTTP {code} message={msg_of(r)}")
code, r = call("PUT", f"/api/surveys/{sid}/questions/q1/version?versionNo=2", None, token2)
check("20 他人无法换绑我的问卷", code in (403, 404), f"HTTP {code} message={msg_of(r)}")

print()
print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print("失败项：" + "、".join(FAILED))
print(f"清理参考：用户 diffsmoke_{suffix} / 题目 {qid} / 问卷 {sid}")
sys.exit(1 if FAILED else 0)
