/* ===== Survey Pop! 前端逻辑 ===== */
"use strict";

const API = "";  // 同源，直接走相对路径
const store = {
  get token() { return localStorage.getItem("sp_token"); },
  get username() { return localStorage.getItem("sp_username"); },
  login(token, username) {
    localStorage.setItem("sp_token", token);
    localStorage.setItem("sp_username", username);
  },
  logout() { localStorage.removeItem("sp_token"); localStorage.removeItem("sp_username"); },
  get isLogin() { return !!localStorage.getItem("sp_token"); },
};

const COLORS = ["#FF6B9D", "#4D96FF", "#6BCB77", "#B983FF", "#FF9F45"];

/* ---------- 工具 ---------- */
function $(html) { const t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstElementChild; }
function esc(s) { return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function toast(msg, isErr = false) {
  const box = document.getElementById("toast-box");
  const el = $(`<div class="toast ${isErr ? "err" : ""}"></div>`);
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 2800);
}
function fmtTime(iso) { return iso ? new Date(iso).toLocaleString("zh-CN", { hour12: false }) : "—"; }

/* 网络层错误（fetch 直接 reject）说明请求根本没到服务器，给出可操作的提示 */
function netHint() {
  if (location.protocol === "file:") {
    return "页面是用文件方式打开的（file://），浏览器无法请求后端接口。请先启动后端，再访问 http://localhost:8081";
  }
  return `连不上后端服务（${location.origin}）。请确认 Spring Boot 已启动并监听 8081 端口`;
}

async function api(method, path, body) {
  const headers = { "Content-Type": "application/json" };
  if (store.token) headers["Authorization"] = "Bearer " + store.token;
  let resp;
  try {
    resp = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (_) {
    throw new Error(netHint());
  }
  let json = null;
  try { json = await resp.json(); } catch (_) { /* 非 JSON 响应 */ }
  if (resp.status === 401 && store.token) {
    store.logout();
    renderLogin();
    toast("登录已失效，请重新登录", true);
    throw new Error(json?.message || "未授权");
  }
  if (resp.status >= 400) throw new Error(json?.message || `请求失败 (${resp.status})`);
  return json?.data;
}

/* ---------- 后端连通性探测 ---------- */
/* 只要 fetch 能拿到任何 HTTP 响应（含 401/500）就说明服务在线；reject 才是真的连不上 */
async function probeBackend() {
  if (location.protocol === "file:") return "file";
  try {
    await fetch(API + "/api/surveys/mine", { method: "GET", cache: "no-store" });
    return "up";
  } catch (_) {
    return "down";
  }
}

function paintNetBanner(state) {
  const box = document.getElementById("net-banner");
  if (!box || state === "up") return;
  box.innerHTML = state === "file"
    ? `<div class="net-banner">⚠️ <b>页面是用文件方式打开的</b>（地址栏是 file:// 开头），浏览器无法请求后端接口。<br>
       请先启动后端，再访问 <code>http://localhost:8081</code></div>`
    : `<div class="net-banner">⚠️ <b>连不上后端服务</b>：${esc(location.host)} 上没有响应。<br>
       请在项目根目录执行 <code>.\\mvnw spring-boot:run</code>，看到 <code>Started SurveySystemApplication</code> 后刷新本页。</div>`;
}

/* ---------- 登录 / 注册 ---------- */
function renderLogin(mode = "login") {
  const app = document.getElementById("app");
  app.innerHTML = `
    <div class="login-wrap">
      <div class="logo"><div class="big">🎈</div><h1>Survey Pop!</h1><div class="subtitle">色彩缤纷的问卷小系统</div></div>
      <div id="net-banner"></div>
      <div class="card">
        <div class="auth-tabs">
          <button class="btn ${mode === "login" ? "active" : ""}" id="tab-login">登录</button>
          <button class="btn ${mode === "register" ? "active" : ""}" id="tab-register">注册</button>
        </div>
        <div class="form-error" id="auth-err"></div>
        <form id="auth-form">
          <label class="field"><span>用户名（3-40 位字母/数字/_/./-）</span>
            <input name="username" required maxlength="40" autocomplete="username" placeholder="例如 survey_fan">
          </label>
          <label class="field"><span>密码（至少 8 位）</span>
            <input name="password" type="password" required minlength="8" maxlength="72" autocomplete="current-password" placeholder="至少 8 个字符">
          </label>
          <button class="btn btn-pink btn-block" type="submit">${mode === "login" ? "🚀 登录" : "🎉 注册并登录"}</button>
        </form>
      </div>
    </div>`;
  document.getElementById("tab-login").onclick = () => renderLogin("login");
  document.getElementById("tab-register").onclick = () => renderLogin("register");
  probeBackend().then(paintNetBanner);
  document.getElementById("auth-form").onsubmit = async (e) => {
    e.preventDefault();
    const errBox = document.getElementById("auth-err");
    errBox.classList.remove("show");
    const fd = new FormData(e.target);
    const username = fd.get("username").trim(), password = fd.get("password");
    try {
      if (mode === "register") await api("POST", "/api/auth/register", { username, password });
      const data = await api("POST", "/api/auth/login", { username, password });
      store.login(data.token, data.username);
      toast(`欢迎回来，${data.username}！`);
      // 从分享链接进来时 hash 已带有 #/fill/xxx，登录后直接进入填写页
      const fillMatch = (location.hash || "").match(/^#\/fill\/([A-Za-z0-9]+)/);
      if (fillMatch) {
        renderApp("fill");
        setTimeout(() => startFill(fillMatch[1]), 80);
      } else {
        renderApp();
      }
    } catch (err) {
      errBox.textContent = err.message;
      errBox.classList.add("show");
    }
  };
}

/* ---------- 应用外壳 ---------- */
function renderApp(active = "mine") {
  const app = document.getElementById("app");
  app.innerHTML = `
    <div class="topbar">
      <div class="who">🎈 Survey Pop!</div>
      <div class="row" style="gap:10px">
        <span class="who">👤 ${esc(store.username)}</span>
        <button class="btn btn-sm btn-red" id="btn-logout">退出</button>
      </div>
    </div>
    <div class="nav-tabs">
      <button class="btn ${active === "mine" ? "active" : ""}" data-tab="mine">📋 我的问卷</button>
      <button class="btn ${active === "create" ? "active" : ""}" data-tab="create">✨ 创建问卷</button>
      <button class="btn ${active === "fill" ? "active" : ""}" data-tab="fill">✍️ 填写问卷</button>
      <button class="btn ${active === "stats" ? "active" : ""}" data-tab="stats">📊 统计</button>
    </div>
    <div id="view"></div>`;
  document.getElementById("btn-logout").onclick = () => { store.logout(); renderLogin(); };
  app.querySelectorAll(".nav-tabs .btn").forEach(btn => {
    btn.onclick = () => renderApp(btn.dataset.tab);
  });
  ({ mine: renderMine, create: renderCreate, fill: renderFill, stats: renderStats }[active])();
}

/* ---------- 我的问卷 ---------- */
async function renderMine() {
  const view = document.getElementById("view");
  view.innerHTML = `<div class="card"><div class="empty-tip">加载中…</div></div>`;
  let surveys;
  try { surveys = await api("GET", "/api/surveys/mine"); } catch (e) { return; }
  if (!surveys.length) {
    view.innerHTML = `<div class="card"><div class="empty-tip">还没有问卷，点上面的 ✨ 创建问卷 开始吧！</div></div>`;
    return;
  }
  view.innerHTML = "";
  surveys.forEach((s, i) => {
    const color = COLORS[i % COLORS.length];
    const card = $(`
      <div class="card survey-card" style="border-top:10px solid ${color}">
        <div class="survey-head">
          <div>
            <div class="survey-title">${esc(s.title)}</div>
            <div class="survey-meta">创建于 ${fmtTime(s.createdAt)}${s.deadline ? " · 截止 " + fmtTime(s.deadline) : ""}</div>
          </div>
          <div>
            <span class="badge ${s.status}">${{ draft: "草稿", published: "已发布", closed: "已关闭" }[s.status]}</span>
            ${s.anonymous ? '<span class="badge anon">匿名</span>' : ""}
            ${s.allowMultiple ? '<span class="badge anon" style="background:#FF9F45">可多次填写</span>' : ""}
          </div>
        </div>
        ${s.publicId ? `<div class="publicid-box">🔗 填写链接：<code class="inline" data-link>${location.origin}/survey/${esc(s.publicId)}</code>
          <button class="btn btn-sm btn-yellow" data-act="copy" style="margin-left:8px">📋 复制</button></div>` : ""}
        <div class="survey-actions">
          ${s.status === "draft" ? `
            <button class="btn btn-sm btn-green" data-act="publish">🚀 发布</button>
            <button class="btn btn-sm btn-blue" data-act="deadline">⏰ 截止时间</button>
            <button class="btn btn-sm btn-red" data-act="delete">🗑 删除</button>` : ""}
          ${s.status === "published" ? `
            <button class="btn btn-sm btn-orange" data-act="fill">✍️ 去填写</button>
            <button class="btn btn-sm btn-blue" data-act="deadline">⏰ 截止时间</button>
            <button class="btn btn-sm btn-red" data-act="close">🔒 关闭</button>` : ""}
          <button class="btn btn-sm btn-purple" data-act="stats">📊 查看统计</button>
        </div>
      </div>`);
    // 各状态卡片按钮不同，绑定前必须判空，否则渲染中断。
    const bind = (act, fn) => { const b = card.querySelector(`[data-act="${act}"]`); if (b) b.onclick = fn; };
    bind("publish", async () => {
      try {
        const data = await api("POST", `/api/surveys/${s.id}/publish`);
        toast(`发布成功！填写链接 publicId：${data.publicId}`);
        renderMine();
      } catch (e) { toast(e.message, true); }
    });
    bind("close", async () => {
      if (!confirm("确定关闭该问卷？关闭后无法再填写。")) return;
      try { await api("POST", `/api/surveys/${s.id}/close`); toast("问卷已关闭"); renderMine(); }
      catch (e) { toast(e.message, true); }
    });
    bind("delete", async () => {
      if (!confirm("确定删除该草稿？此操作不可恢复。")) return;
      try { await api("DELETE", `/api/surveys/${s.id}`); toast("草稿已删除"); renderMine(); }
      catch (e) { toast(e.message, true); }
    });
    bind("deadline", () => promptDeadline(s));
    bind("copy", async () => {
      const link = card.querySelector("[data-link]").textContent;
      try {
        await navigator.clipboard.writeText(link);
        toast("链接已复制");
      } catch (_) {
        // 非 https / 无剪贴板权限时退化为选中文本，由用户手动复制
        const range = document.createRange();
        range.selectNodeContents(card.querySelector("[data-link]"));
        const sel = window.getSelection();
        sel.removeAllRanges(); sel.addRange(range);
        toast("已选中链接，请按 Ctrl+C 复制");
      }
    });
    bind("fill", () => { renderApp("fill"); setTimeout(() => startFill(s.publicId), 50); });
    bind("stats", () => { renderApp("stats"); setTimeout(() => showStats(s.id), 50); });
    view.appendChild(card);
  });
}

function promptDeadline(survey) {
  const input = prompt("设置截止时间（格式：2026-12-31T23:59，留空表示取消截止时间）",
    survey.deadline ? survey.deadline.slice(0, 16) : "");
  if (input === null) return;
  const body = { deadline: input.trim() === "" ? null : new Date(input.trim()).toISOString() };
  if (body.deadline && isNaN(new Date(body.deadline))) { toast("时间格式不对哦", true); return; }
  api("PUT", `/api/surveys/${survey.id}/deadline`, body)
    .then(() => { toast("截止时间已更新"); renderMine(); })
    .catch(e => toast(e.message, true));
}

/* ---------- 创建问卷 ---------- */
const QUESTION_TYPES = [
  ["single", "🔘 单选"],
  ["multiple", "☑️ 多选"],
  ["number", "🔢 数字"],
  ["text", "📝 文本"],
];

function renderCreate() {
  const view = document.getElementById("view");
  view.innerHTML = `
    <div class="card">
      <h2>✨ 创建新问卷</h2>
      <label class="field"><span>问卷标题 *（最多 200 字）</span><input id="s-title" maxlength="200" placeholder="例如：编程课程满意度调查"></label>
      <label class="field"><span>问卷说明（可选，最多 5000 字）</span><textarea id="s-desc" rows="2" maxlength="5000" placeholder="简单介绍这份问卷的用途"></textarea></label>
      <div class="row">
        <label class="check"><input type="checkbox" id="s-anon"> 匿名问卷（不记录填写人身份）</label>
        <label class="check"><input type="checkbox" id="s-multi" checked> 允许同一人多次填写</label>
      </div>
      <label class="field"><span>截止时间（可选）</span><input type="datetime-local" id="s-deadline"></label>
      <div class="section-label">题目列表</div>
      <div id="questions"></div>
      <div class="row">
        <button class="btn btn-yellow" id="btn-add-q">➕ 添加题目</button>
        <button class="btn btn-pink" id="btn-save">💾 保存草稿</button>
      </div>
    </div>`;
  const list = document.getElementById("questions");
  document.getElementById("btn-add-q").onclick = () => list.appendChild(questionEditor(list.children.length));
  document.getElementById("btn-add-q").click();
  document.getElementById("btn-save").onclick = saveSurvey;
}

function questionEditor(index) {
  const el = $(`
    <div class="question-editor c${index % 5}">
      <div class="q-head">
        <span class="q-num">Q${index + 1}</span>
        <select class="q-type">${QUESTION_TYPES.map(([v, l]) => `<option value="${v}">${l}</option>`).join("")}</select>
        <input class="q-title" maxlength="1000" placeholder="题目标题 *（最多 1000 字）" style="flex:3">
        <label class="check" style="margin:0"><input type="checkbox" class="q-required" checked> 必答</label>
        <button class="btn btn-sm btn-red" data-del>✕</button>
      </div>
      <div class="q-body"></div>
      <div class="q-jumps"></div>
    </div>`);
  el.querySelector('[data-del]').onclick = () => { el.remove(); renumberQuestions(); };
  const typeSel = el.querySelector(".q-type");
  const body = el.querySelector(".q-body");
  const renderBody = () => {
    const t = typeSel.value;
    if (t === "single" || t === "multiple") {
      body.innerHTML = `
        <div class="section-label">选项（至少 2 个）</div>
        <div class="q-options"></div>
        <button class="btn btn-sm" data-add-opt>➕ 添加选项</button>
        <div class="section-label">选择数量限制（可选）</div>
        <div class="constraint-grid">
          ${t === "multiple" ? '<label>最少选择数 <input class="c-minSelect" type="number" min="0" placeholder="不限"></label><label>最多选择数 <input class="c-maxSelect" type="number" min="1" placeholder="不限"></label>' : ""}
        </div>`;
      const opts = body.querySelector(".q-options");
      const addOpt = () => {
        const row = $(`<div class="option-row">
          <input class="opt-id" value="o${opts.children.length + 1}_${Math.random().toString(36).slice(2, 6)}" title="选项 ID（跳转规则会引用）" readonly>
          <input class="opt-label" maxlength="500" placeholder="选项文字 *">
          <button class="btn btn-sm btn-red" data-del-opt>✕</button></div>`);
        row.querySelector("[data-del-opt]").onclick = () => row.remove();
        opts.appendChild(row);
      };
      body.querySelector("[data-add-opt]").onclick = addOpt;
      addOpt(); addOpt();
    } else if (t === "number") {
      body.innerHTML = `
        <div class="section-label">数值限制</div>
        <div class="constraint-grid">
          <label>最小值 <input class="c-minValue" type="number" step="any" placeholder="不限"></label>
          <label>最大值 <input class="c-maxValue" type="number" step="any" placeholder="不限"></label>
          <label>只允许整数 <select class="c-isInteger"><option value="">否</option><option value="true">是</option></select></label>
        </div>`;
    } else if (t === "text") {
      body.innerHTML = `
        <div class="section-label">文本长度限制（可选）</div>
        <div class="constraint-grid">
          <label>最小长度 <input class="c-minLength" type="number" min="0" placeholder="不限"></label>
          <label>最大长度 <input class="c-maxLength" type="number" min="0" placeholder="不限"></label>
        </div>`;
    }
    renderJumps();
  };
  const renderJumps = () => {
    const jumps = el.querySelector(".q-jumps");
    const canJump = ["single", "multiple", "number", "text"].includes(typeSel.value);
    jumps.innerHTML = canJump ? `
      <div class="section-label">跳转规则（可选，命中即跳往目标；只允许向后跳或 END）</div>
      <div class="j-list"></div>
      <button class="btn btn-sm" data-add-jump>➕ 添加跳转规则</button>` : "";
    const jlist = jumps.querySelector(".j-list");
    if (!jlist) return;
    jumps.querySelector("[data-add-jump]").onclick = () => addJumpRow(jlist);
  };
  const addJumpRow = (jlist) => {
    const row = $(`<div class="jump-rule">
      <select class="j-op">${["eq", "neq", "contains", "gt", "gte", "lt", "lte"].map(o => `<option>${o}</option>`).join("")}</select>
      <input class="j-optionId" placeholder="optionId（选项题必填）">
      <input class="j-value" placeholder="value（文本/数字题用）">
      <input class="j-target" placeholder="目标题 ID 或 END">
      <button class="jump-del" title="删除">✕</button></div>`);
    row.querySelector(".jump-del").onclick = () => row.remove();
    jlist.appendChild(row);
  };
  typeSel.onchange = renderBody;
  renderBody();
  return el;
}

function renumberQuestions() {
  document.querySelectorAll("#questions .question-editor").forEach((el, i) => {
    el.querySelector(".q-num").textContent = "Q" + (i + 1);
    el.className = `question-editor c${i % 5}`;
  });
}

function collectSurvey() {
  const title = document.getElementById("s-title").value.trim();
  if (!title) throw new Error("问卷标题不能为空");
  const questions = [];
  document.querySelectorAll("#questions .question-editor").forEach((qEl, qi) => {
    const qid = "q" + (qi + 1);
    const type = qEl.querySelector(".q-type").value;
    const qTitle = qEl.querySelector(".q-title").value.trim();
    if (!qTitle) throw new Error(`第 ${qi + 1} 题的标题不能为空`);
    const q = { id: qid, order: qi + 1, type, title: qTitle, required: qEl.querySelector(".q-required").checked, options: [], jumps: [] };
    const g = cls => qEl.querySelector("." + cls);
    const num = cls => { const v = g(cls)?.value; return v === "" || v == null ? null : Number(v); };
    if (type === "single" || type === "multiple") {
      qEl.querySelectorAll(".option-row").forEach((row, oi) => {
        const label = row.querySelector(".opt-label").value.trim();
        if (!label) throw new Error(`第 ${qi + 1} 题有选项缺少文字`);
        q.options.push({ id: row.querySelector(".opt-id").value, label, order: oi + 1 });
      });
      if (q.options.length < 2) throw new Error(`第 ${qi + 1} 题（单选/多选）至少需要 2 个选项`);
      if (type === "multiple") {
        const minS = num("c-minSelect"), maxS = num("c-maxSelect");
        if (minS != null || maxS != null) q.constraints = { ...(minS != null && { minSelect: minS }), ...(maxS != null && { maxSelect: maxS }) };
      }
    } else if (type === "number") {
      const minV = num("c-minValue"), maxV = num("c-maxValue"), isInt = g("c-isInteger")?.value === "true";
      if (minV != null || maxV != null || isInt) q.constraints = { ...(minV != null && { minValue: minV }), ...(maxV != null && { maxValue: maxV }), ...(isInt && { isInteger: true }) };
    } else if (type === "text") {
      const minL = num("c-minLength"), maxL = num("c-maxLength");
      if (minL != null || maxL != null) q.constraints = { ...(minL != null && { minLength: minL }), ...(maxL != null && { maxLength: maxL }) };
    }
    qEl.querySelectorAll(".jump-rule").forEach(r => {
      const op = r.querySelector(".j-op").value;
      const optionId = r.querySelector(".j-optionId").value.trim() || null;
      const value = r.querySelector(".j-value").value.trim() || null;
      const target = r.querySelector(".j-target").value.trim();
      if (!target) return;
      q.jumps.push({ op, ...(optionId && { optionId }), ...(value && { value }), targetQuestionId: target.toUpperCase() === "END" ? "END" : target });
    });
    questions.push(q);
  });
  if (!questions.length) throw new Error("至少需要一道题目");
  const deadlineStr = document.getElementById("s-deadline").value;
  return {
    title, description: document.getElementById("s-desc").value.trim() || null,
    anonymous: document.getElementById("s-anon").checked,
    allowMultiple: document.getElementById("s-multi").checked,
    ...(deadlineStr && { deadline: new Date(deadlineStr).toISOString() }),
    questions,
  };
}

async function saveSurvey() {
  try {
    const body = collectSurvey();
    const data = await api("POST", "/api/surveys", body);
    toast("草稿保存成功！去「我的问卷」发布它 🚀");
    renderApp("mine");
  } catch (e) { toast(e.message, true); }
}

/* ---------- 填写问卷 ---------- */
const fillState = { responseId: null, current: null, completed: false, visited: [] };

function renderFill(prefill) {
  const view = document.getElementById("view");
  view.innerHTML = `
    <div class="card">
      <h2>✍️ 填写问卷</h2>
      <label class="field"><span>输入问卷的 publicId（创建者在发布后提供）</span>
        <input id="fill-pid" placeholder="例如 P44ilLmF9BU9" value="${esc(prefill || "")}">
      </label>
      <button class="btn btn-blue" id="btn-start">🎬 开始答卷</button>
    </div>
    <div id="fill-area"></div>`;
  document.getElementById("btn-start").onclick = () => startFill(document.getElementById("fill-pid").value.trim());
  if (prefill) startFill(prefill);
}

async function startFill(publicId) {
  if (!publicId) { toast("请输入 publicId", true); return; }
  try {
    const data = await api("POST", `/api/surveys/${encodeURIComponent(publicId)}/start`);
    fillState.responseId = data.responseId;
    fillState.current = data.question;
    fillState.completed = data.completed;
    fillState.visited = [];
    if (data.question) fillState.visited.push(data.question.id);
    renderFillSession(data.surveyTitle, data.message);
  } catch (e) { toast(e.message, true); }
}

function renderFillSession(title, message) {
  const area = document.getElementById("fill-area");
  const q = fillState.current;
  area.innerHTML = `
    <div class="card">
      <div class="survey-head"><h2>📝 ${esc(title)}</h2><span class="badge published">进行中</span></div>
      <div class="fill-progress">${fillState.visited.map((id, i) =>
        `<span class="dot ${id === (q && q.id) ? "current" : "visited"}" title="${esc(id)}"></span>`).join("")}</div>
      <div class="subtitle">${esc(message || "")}</div>
      <div id="q-area"></div>
      <div class="row" id="fill-actions"></div>
    </div>`;
  const qArea = document.getElementById("q-area");
  const actions = document.getElementById("fill-actions");

  if (fillState.completed) {
    qArea.innerHTML = `<div class="empty-tip">🎉 所有题目已完成！点击下方按钮提交答卷。</div>`;
    actions.innerHTML = `<button class="btn btn-pink btn-block" id="btn-submit">📨 提交答卷</button>`;
    document.getElementById("btn-submit").onclick = submitFill;
    return;
  }
  if (!q) { qArea.innerHTML = `<div class="empty-tip">暂无题目</div>`; return; }

  qArea.appendChild(renderQuestionInput(q));
  actions.innerHTML = `<button class="btn btn-pink" id="btn-answer">✅ 提交本题</button>
    ${q.required ? "" : `<button class="btn" id="btn-skip">⏭ 跳过（非必答）</button>`}`;
  // 注意不能用 onclick = submitAnswer：事件对象会被误当成 skip 参数。
  document.getElementById("btn-answer").onclick = () => submitAnswer(false);
  const skip = document.getElementById("btn-skip");
  if (skip) skip.onclick = () => submitAnswer(true);
}

function renderQuestionInput(q) {
  const wrap = document.createElement("div");
  const typeLabel = { single: "单选", multiple: "多选", number: "数字", text: "文本" }[q.type] || q.type;
  const head = $(`<div class="section-label">${typeLabel}${q.required ? " · 必答" : " · 可跳过"}</div><h2>${esc(q.title)}</h2>`);
  wrap.appendChild(head);
  if (q.type === "single" || q.type === "multiple") {
    const box = $(`<div></div>`);
    q.options.forEach(o => {
      const label = $(`<label class="choice-label"><input type="${q.type === "single" ? "radio" : "checkbox"}" name="opt" value="${esc(o.id)}"><span>${esc(o.label)}</span></label>`);
      label.querySelector("input").onchange = () => {
        if (q.type === "single") box.querySelectorAll(".choice-label").forEach(l => l.classList.remove("picked"));
        label.classList.toggle("picked", label.querySelector("input").checked);
      };
      box.appendChild(label);
    });
    wrap.appendChild(box);
  } else if (q.type === "number") {
    const c = q.constraints || {};
    wrap.appendChild($(`<input type="number" id="q-input" step="any" ${c.minValue != null ? `min="${c.minValue}"` : ""} ${c.maxValue != null ? `max="${c.maxValue}"` : ""} ${c.isInteger ? 'step="1"' : 'step="any"'} placeholder="${c.minValue != null || c.maxValue != null ? `范围 ${c.minValue ?? "-∞"} ~ ${c.maxValue ?? "+∞"}` : "请输入数字"}">`));
  } else {
    const c = q.constraints || {};
    wrap.appendChild($(`<textarea id="q-input" rows="4" maxlength="${c.maxLength ?? 5000}" placeholder="${c.minLength ? `至少 ${c.minLength} 字` : "请输入内容"}"></textarea>`));
  }
  return wrap;
}

async function submitAnswer(skip = false) {
  const q = fillState.current;
  if (!q) return;
  let answer = null;
  if (!skip) {
    if (q.type === "single") {
      const picked = document.querySelector('input[name="opt"]:checked');
      if (!picked) { toast("请选择一个选项", true); return; }
      answer = picked.value;
    } else if (q.type === "multiple") {
      answer = [...document.querySelectorAll('input[name="opt"]:checked')].map(i => i.value);
      if (!answer.length) { toast("请至少选择一个选项", true); return; }
    } else if (q.type === "number") {
      const raw = document.getElementById("q-input").value;
      if (raw === "") { toast("请输入数字", true); return; }
      answer = Number(raw);
      if (isNaN(answer)) { toast("请输入有效数字", true); return; }
    } else {
      answer = document.getElementById("q-input").value;
      if (!answer.trim()) { toast("请输入内容", true); return; }
    }
  }
  try {
    const data = await api("POST", `/api/responses/${fillState.responseId}/answer`, { questionId: q.id, answer });
    fillState.completed = data.completed;
    fillState.current = data.nextQuestion || null;
    if (fillState.completed) {
      toast(data.message || "答题完成！");
    } else if (fillState.current && !fillState.visited.includes(fillState.current.id)) {
      fillState.visited.push(fillState.current.id);
    }
    renderFillSession(data.surveyTitle, data.message);
  } catch (e) { toast(e.message, true); }
}

async function submitFill() {
  try {
    const data = await api("POST", `/api/responses/${fillState.responseId}/submit`);
    toast("📨 提交成功，感谢参与！");
    document.getElementById("fill-area").innerHTML = `
      <div class="card" style="background:var(--green)">
        <h2>🎉 提交成功！</h2>
        <div class="subtitle">答卷编号：${esc(data.responseId)}<br>提交时间：${fmtTime(data.submittedAt)}</div>
        <button class="btn btn-yellow" onclick="renderApp('mine')">📋 返回我的问卷</button>
      </div>`;
  } catch (e) { toast(e.message, true); }
}

/* ---------- 统计 ---------- */
const BAR_COLORS = ["#FF6B9D", "#4D96FF", "#6BCB77", "#FFD93D", "#B983FF", "#FF9F45", "#FF5C5C"];

async function renderStats() {
  statsCache = null; // 每次进入统计页都重新拉取，避免看到旧数据
  const view = document.getElementById("view");
  view.innerHTML = `<div class="card"><div class="empty-tip">加载问卷列表…</div></div>`;
  let surveys;
  try { surveys = await api("GET", "/api/surveys/mine"); } catch (e) { return; }
  const usable = surveys.filter(s => s.status !== "draft");
  view.innerHTML = `
    <div class="card">
      <h2>📊 问卷统计</h2>
      <label class="field"><span>选择问卷</span>
        <select id="stats-select">
          ${usable.length ? usable.map(s => `<option value="${s.id}">${esc(s.title)}（${{ published: "已发布", closed: "已关闭" }[s.status]}）</option>`).join("") : "<option>暂无可统计的问卷（需先发布）</option>"}
        </select>
      </label>
      <button class="btn btn-purple" id="btn-load-stats">📈 查看统计</button>
    </div>
    <div id="stats-area"></div>`;
  document.getElementById("btn-load-stats").onclick = () => {
    const sid = document.getElementById("stats-select").value;
    if (sid && !sid.startsWith("暂无")) showStats(sid);
  };
  if (usable.length) showStats(usable[0].id);
}

/* 缓存整卷统计，切换单题视图时不必重复请求 */
let statsCache = null;

async function showStats(surveyId, questionId = null) {
  const area = document.getElementById("stats-area");
  if (!area) return;
  if (!questionId) area.innerHTML = `<div class="card"><div class="empty-tip">统计中…</div></div>`;

  let data;
  try {
    if (statsCache && statsCache.surveyId === surveyId) {
      data = statsCache.data;
    } else {
      data = await api("GET", `/api/surveys/${surveyId}/stats`);
      statsCache = { surveyId, data };
    }
  } catch (e) { area.innerHTML = ""; toast(e.message, true); return; }

  // 单题视图：调用单题统计接口，只返回这一道题的聚合结果
  let questions = data.questions;
  if (questionId) {
    try {
      const one = await api("GET", `/api/surveys/${surveyId}/stats/${encodeURIComponent(questionId)}`);
      questions = [one];
    } catch (e) { toast(e.message, true); return; }
  }

  const card = $(`
    <div class="card">
      <div class="survey-head">
        <h2>📈 ${esc(data.surveyTitle)}</h2>
        <span class="badge published">已提交 ${data.submittedCount} 份</span>
      </div>
      <label class="field"><span>查看范围</span>
        <select id="stat-q-select">
          <option value="">整卷统计（全部 ${data.questions.length} 道题）</option>
          ${data.questions.map((q, i) => `<option value="${esc(q.questionId)}">Q${i + 1}. ${esc(q.title)}</option>`).join("")}
        </select>
      </label>
      <div id="stat-questions"></div>
    </div>`);
  const box = card.querySelector("#stat-questions");
  const select = card.querySelector("#stat-q-select");
  select.value = questionId || "";
  select.onchange = () => showStats(surveyId, select.value || null);

  // 题号按整卷中的位置计算；单题视图返回的是新对象，不能用 indexOf
  questions.forEach(q => {
    const index = data.questions.findIndex(item => item.questionId === q.questionId);
    box.appendChild(renderQuestionStat(q, index < 0 ? 0 : index));
  });

  area.innerHTML = "";
  area.appendChild(card);
}

function renderQuestionStat(q, index) {
  const qEl = $(`<div class="stat-q">
    <h3>Q${index + 1}. ${esc(q.title)} <span class="badge draft">${{ single: "单选", multiple: "多选", number: "数字", text: "文本" }[q.type] || q.type}</span></h3>
    <div class="subtitle">回答人数：${q.answeredCount}</div>
    <div class="q-stat-body"></div>
  </div>`);
  const body = qEl.querySelector(".q-stat-body");
  const maxCount = Math.max(1, ...q.options.map(o => o.count));
  if (q.type === "single" || q.type === "multiple") {
    q.options.forEach((o, oi) => {
      const pct = (o.count / maxCount) * 100;
      body.appendChild($(`<div class="bar-row">
        <div class="bar-label" title="${esc(o.label)}">${esc(o.label)}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${BAR_COLORS[oi % BAR_COLORS.length]}"></div></div>
        <div class="bar-count">${o.count}</div>
      </div>`));
    });
  } else if (q.type === "number") {
    body.innerHTML = q.averageNumber != null
      ? `<div>平均分：<span class="stat-avg">${q.averageNumber.toFixed(2)}</span></div>`
      : `<div class="empty-tip">暂无数值回答</div>`;
  } else if (q.type === "text") {
    if (!q.textAnswers.length) {
      body.innerHTML = `<div class="empty-tip">暂无文本回答</div>`;
    } else {
      const more = q.textAnswerCount > q.textAnswers.length
        ? `<div class="subtitle">共 ${q.textAnswerCount} 条，仅展示前 ${q.textAnswers.length} 条</div>` : "";
      body.innerHTML = `<div>${q.textAnswers.map(t => `<span class="text-bubble">${esc(t)}</span>`).join("")}</div>${more}`;
    }
  }
  return qEl;
}

/* ---------- 入口 & hash 路由 ---------- */
window.addEventListener("DOMContentLoaded", () => {
  const hash = location.hash || "";
  const fillMatch = hash.match(/^#\/fill\/([A-Za-z0-9]+)/);
  if (!store.isLogin) { renderLogin(); return; }
  if (fillMatch) {
    renderApp("fill");
    setTimeout(() => startFill(fillMatch[1]), 80);
  } else {
    renderApp("mine");
  }
});
