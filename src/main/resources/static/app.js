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

/** 把 ISO 时间转成本地时区的 yyyy-MM-ddTHH:mm，供 datetime-local 与 prompt 回填。 */
function toLocalInputValue(iso) {
  const d = new Date(iso);
  const pad = n => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

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
          <label class="field"><span>密码（${mode === "register" ? "8-16 位" : "至少 8 位"}）</span>
            <input name="password" type="password" required minlength="8"
              maxlength="${mode === "register" ? "16" : "72"}"
              autocomplete="${mode === "register" ? "new-password" : "current-password"}"
              placeholder="${mode === "register" ? "8-16 个字符" : "请输入密码"}">
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
    // 注册侧 8-16 位；登录侧只要求非空，否则第一阶段创建的超过 16 位的旧账号将无法登录。
    if (mode === "register" && (password.length < 8 || password.length > 16)) {
      errBox.textContent = "密码长度必须为 8-16 个字符";
      errBox.classList.add("show");
      return;
    }
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
const NAV_TABS = [
  ["mine", "📋 我的问卷"],
  ["library", "📚 题库"],
  ["create", "✨ 创建问卷"],
  ["fill", "✍️ 填写问卷"],
  ["stats", "📊 统计"],
];

/* 点「创建问卷」时置空；从我的问卷点「编辑」时置为问卷 ID，交给编辑器加载 */
let pendingEditId = null;

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
      ${NAV_TABS.map(([key, label]) =>
        `<button class="btn ${active === key ? "active" : ""}" data-tab="${key}">${label}</button>`).join("")}
    </div>
    <div id="view"></div>`;
  document.getElementById("btn-logout").onclick = () => { store.logout(); renderLogin(); };
  app.querySelectorAll(".nav-tabs .btn").forEach(btn => {
    btn.onclick = () => {
      // 从导航进入创建页一律是「新建」，不能沿用上次编辑的问卷 ID
      if (btn.dataset.tab === "create") pendingEditId = null;
      renderApp(btn.dataset.tab);
    };
  });
  ({
    mine: renderMine,
    library: renderLibrary,
    create: () => renderSurveyEditor(pendingEditId),
    fill: renderFill,
    stats: renderStats,
  }[active])();
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
    // listMine 会回填引用题目的最新版本号，这里统计「有几道题落后于题库」
    const outdated = (s.questions || []).filter(q => q.newerVersionAvailable).length;
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
            ${outdated ? `<span class="badge warn" title="题库中的题目已更新，可进入编辑页手动换绑">🔄 ${outdated} 题有新版本</span>` : ""}
          </div>
        </div>
        ${s.publicId ? `<div class="publicid-box">🔗 填写链接：<code class="inline" data-link>${location.origin}/survey/${esc(s.publicId)}</code>
          <button class="btn btn-sm btn-yellow" data-act="copy" style="margin-left:8px">📋 复制</button></div>` : ""}
        <div class="survey-actions">
          ${s.status === "draft" ? `
            <button class="btn btn-sm btn-purple" data-act="edit">✏️ 编辑</button>
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
    bind("edit", () => { pendingEditId = s.id; renderApp("create"); });
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
  // 必须用本地时区的 yyyy-MM-ddTHH:mm 回填。直接用 ISO 字符串截断取的是 UTC 时间，
  // 用户在 UTC+8 保存 23:59 会看到 15:59，再保存一次又往前跳 8 小时，每存一次漂一次。
  const input = prompt("设置截止时间（格式：2026-12-31T23:59，留空表示取消截止时间）",
    survey.deadline ? toLocalInputValue(survey.deadline) : "");
  if (input === null) return;
  const body = { deadline: input.trim() === "" ? null : new Date(input.trim()).toISOString() };
  if (body.deadline && isNaN(new Date(body.deadline))) { toast("时间格式不对哦", true); return; }
  api("PUT", `/api/surveys/${survey.id}/deadline`, body)
    .then(() => { toast("截止时间已更新"); renderMine(); })
    .catch(e => toast(e.message, true));
}

/* ---------- 创建 / 编辑问卷 ---------- */
const QUESTION_TYPES = [
  ["single", "🔘 单选"],
  ["multiple", "☑️ 多选"],
  ["number", "🔢 数字"],
  ["text", "📝 文本"],
];

/* 为新增题目分配一个不与现有题目冲突的实例 ID。
   跳转规则按实例 ID 引用题目，所以 ID 必须稳定且唯一；
   编辑已有问卷时沿用后端返回的 ID，不能重新编号，否则跳转规则会指向不存在的题目。 */
function allocInstanceId() {
  const used = new Set([...document.querySelectorAll("#questions .question-editor")]
    .map(el => el.dataset.instanceId).filter(Boolean));
  let n = used.size + 1;
  while (used.has("q" + n)) n++;
  return "q" + n;
}

/**
 * 问卷编辑器：新建与编辑共用。
 *
 * @param surveyId 为空表示新建；有值表示编辑草稿（先拉详情再回填）
 */
async function renderSurveyEditor(surveyId) {
  const view = document.getElementById("view");
  const isEdit = !!surveyId;
  if (isEdit) view.innerHTML = `<div class="card"><div class="empty-tip">加载问卷…</div></div>`;

  let survey = null;
  if (isEdit) {
    try {
      survey = await api("GET", `/api/surveys/${surveyId}`);
    } catch (e) { toast(e.message, true); renderApp("mine"); return; }
    if (survey.status !== "draft") {
      view.innerHTML = `<div class="card"><div class="empty-tip">只有草稿状态的问卷可以编辑。已发布的问卷题目与选项被冻结，以保证已收集的答卷口径一致。</div>
        <button class="btn btn-yellow" id="btn-back">返回我的问卷</button></div>`;
      document.getElementById("btn-back").onclick = () => renderApp("mine");
      return;
    }
  }

  view.innerHTML = `
    <div class="card">
      <h2>${isEdit ? "✏️ 编辑问卷" : "✨ 创建新问卷"}</h2>
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
        <button class="btn btn-green" id="btn-add-ref">📚 从题库选题</button>
        <button class="btn btn-pink" id="btn-save">💾 ${isEdit ? "保存修改" : "保存草稿"}</button>
      </div>
    </div>
    <div id="picker-area"></div>`;

  const list = document.getElementById("questions");
  if (survey) {
    document.getElementById("s-title").value = survey.title || "";
    document.getElementById("s-desc").value = survey.description || "";
    document.getElementById("s-anon").checked = !!survey.anonymous;
    document.getElementById("s-multi").checked = !!survey.allowMultiple;
    if (survey.deadline) {
      // datetime-local 需要本地时区的 yyyy-MM-ddTHH:mm，不能直接用 ISO 字符串
      document.getElementById("s-deadline").value = toLocalInputValue(survey.deadline);
    }
    [...(survey.questions || [])].sort((a, b) => a.order - b.order).forEach((q, i) => {
      list.appendChild(q.sourceType === "library" ? referenceCard(q, i, survey.id) : questionEditor(i, q, survey.id));
    });
    if (!list.children.length) list.appendChild(questionEditor(0));
  } else {
    list.appendChild(questionEditor(0));
  }

  document.getElementById("btn-add-q").onclick = () => {
    list.appendChild(questionEditor(list.children.length));
    renumberQuestions();
  };
  document.getElementById("btn-add-ref").onclick = () => openLibraryPicker(surveyId);
  document.getElementById("btn-save").onclick = () => saveSurvey(surveyId);
}

/** 引用题库题目的卡片：题干只读，只有必答性与跳转规则可以在问卷内调整。 */
function referenceCard(q, index, surveyId) {
  const el = $(`
    <div class="question-editor c${index % 5} ref-card">
      <div class="q-head">
        <span class="q-num">Q${index + 1}</span>
        <span class="badge anon">📚 题库 v${esc(q.versionNo)}</span>
        <span class="q-title-static" style="flex:3">${esc(q.title)}</span>
        <label class="check" style="margin:0"><input type="checkbox" class="q-required" ${q.required ? "checked" : ""}> 必答</label>
        <button class="btn btn-sm btn-red" data-del>✕</button>
      </div>
      <div class="ref-meta">
        实例 <code class="inline">${esc(q.id)}</code> · 题目 <code class="inline">${esc(q.questionId)}</code>
        ${q.newerVersionAvailable
          ? `<span class="badge warn">题库已更新到 v${esc(q.latestVersionNo)}</span>
             <button class="btn btn-sm btn-blue" data-rebind>🔄 换绑到 v${esc(q.latestVersionNo)}</button>`
          : ""}
      </div>
      <div class="ref-options">
        ${(q.options || []).map(o => `<span class="opt-chip">${esc(o.label)}<code>${esc(o.id)}</code></span>`).join("")}
      </div>
      <div class="ref-note">题干、选项、约束由题库版本决定，在这里改不了。要改内容请去「📚 题库」编辑并生成新版本，再换绑。</div>
      <div class="q-jumps"></div>
    </div>`);
  el.dataset.ref = "1";
  el.dataset.instanceId = q.id;
  el.dataset.questionId = q.questionId;
  el.dataset.questionVersionId = q.questionVersionId;
  el.dataset.versionNo = q.versionNo;

  el.querySelector("[data-del]").onclick = () => { el.remove(); renumberQuestions(); };
  renderJumpEditor(el.querySelector(".q-jumps"), q.jumps || []);

  const rebind = el.querySelector("[data-rebind]");
  if (rebind) rebind.onclick = () => rebindVersion(surveyId, q);
  return el;
}

/** 换绑到题库当前版本。换绑是独立接口、立即落库，所以会重载编辑器。 */
async function rebindVersion(surveyId, q) {
  if (!surveyId) {
    toast("请先保存问卷，再换绑题目版本", true);
    return;
  }
  const target = q.latestVersionNo;
  if (!confirm(`把这道题从 v${q.versionNo} 换绑到 v${target}？\n\n换绑会立即写入草稿并重新加载页面，其他未保存的修改会丢失。`)) return;
  try {
    await api("PUT", `/api/surveys/${surveyId}/questions/${encodeURIComponent(q.id)}/version?versionNo=${target}`);
    toast(`已换绑到 v${target}`);
    renderSurveyEditor(surveyId);
  } catch (e) { toast(e.message, true); }
}

/**
 * 把问卷里的本地题目存进题库。
 *
 * 后端刻意不改动原问卷：导入只是「把内容存档到题库」，不会把这道题变成题库引用。
 * 所以成功之后按钮置灰而不是重载页面，避免用户误以为题目已经换绑。
 */
async function promoteToLibrary(surveyId, instanceId, btn) {
  if (!confirm("把这道题存进题库？\n\n题库会保存一份内容存档，这道题在本问卷里仍然是独立题目，不会被换绑。之后可以在其他问卷里「从题库选题」复用它。\n\n注意：存入的是已保存的内容，页面上还没保存的修改不会一起进题库。")) return;
  btn.disabled = true;
  try {
    const detail = await api("POST", `/api/surveys/${surveyId}/questions/${encodeURIComponent(instanceId)}/promote`);
    btn.textContent = "✅ 已存入题库";
    toast(`已存入题库：${detail.title} v${detail.currentVersionNo}`);
  } catch (e) {
    btn.disabled = false;
    toast(e.message, true);
  }
}

/** 一道完全空白的本地题目（没有题干、没有选项文字）。新建问卷时编辑器会先放一道占位。 */
function isEmptyQuestion(el) {
  if (el.dataset.ref === "1") return false;
  if ((el.querySelector(".q-title")?.value || "").trim()) return false;
  return [...el.querySelectorAll(".opt-label")].every(i => !i.value.trim());
}

/** 从题库选题：我的题目 + 别人共享给我的题目。 */
async function openLibraryPicker(surveyId) {
  const area = document.getElementById("picker-area");
  if (!area) return;
  area.innerHTML = `<div class="card"><div class="empty-tip">加载题库…</div></div>`;

  let mine = [], shared = [];
  try {
    [mine, shared] = await Promise.all([
      api("GET", "/api/questions?status="),
      api("GET", "/api/questions/shared-with-me"),
    ]);
  } catch (e) { area.innerHTML = ""; toast(e.message, true); return; }

  // 共享记录里已归档的题目不能再选入新问卷（后端也会拒绝）
  const usableShared = shared.filter(s => s.questionStatus === "active");
  const items = [
    ...mine.map(q => ({ id: q.id, title: q.title, type: q.type, versionNo: q.currentVersionNo, owner: null })),
    ...usableShared.map(s => ({ id: s.questionId, title: s.questionTitle, type: null, versionNo: null, owner: s.ownerUsername })),
  ];

  area.innerHTML = `
    <div class="card">
      <div class="survey-head">
        <h2>📚 从题库选题</h2>
        <button class="btn btn-sm" id="picker-close">关闭</button>
      </div>
      ${items.length ? `<div class="picker-list">${items.map(it => `
        <div class="picker-item">
          <div>
            <div class="survey-title">${esc(it.title)}</div>
            <div class="survey-meta">${it.owner ? "共享自 " + esc(it.owner) : "我的题目"}${it.versionNo ? " · 最新 v" + it.versionNo : ""}</div>
          </div>
          <button class="btn btn-sm btn-green" data-pick="${esc(it.id)}">引用最新版</button>
        </div>`).join("")}</div>`
      : `<div class="empty-tip">题库里还没有可用的题目，先去「📚 题库」新建一道吧。</div>`}
    </div>`;

  document.getElementById("picker-close").onclick = () => { area.innerHTML = ""; };

  area.querySelectorAll("[data-pick]").forEach(btn => {
    btn.onclick = async () => {
      const qid = btn.dataset.pick;
      try {
        // 列表接口只给当前版本号，引用需要版本 ID，所以这里取一次详情
        const detail = await api("GET", `/api/questions/${encodeURIComponent(qid)}`);
        const v = detail.currentVersion;
        const list = document.getElementById("questions");
        list.appendChild(referenceCard({
          id: allocInstanceId(),
          questionId: detail.id,
          questionVersionId: v.id,
          versionNo: v.versionNo,
          title: v.title,
          type: v.type,
          options: v.options,
          required: v.required,
          jumps: [],
          latestVersionNo: detail.currentVersionNo,
          newerVersionAvailable: false,
        }, list.children.length, surveyId));
        // 新建问卷时编辑器会先放一道空白题目占位。如果用户第一件事就是「从题库选题」，
        // 那道空题目留着会让保存直接报「第 1 题的标题不能为空」，这里顺手清掉。
        const blanks = [...list.querySelectorAll(".question-editor")]
          .filter(e => e.dataset.ref !== "1" && isEmptyQuestion(e));
        if (blanks.length && list.children.length > blanks.length) blanks.forEach(e => e.remove());
        renumberQuestions();
        area.innerHTML = "";
        toast("已加入问卷，记得点保存");
      } catch (e) { toast(e.message, true); }
    };
  });
}

function questionEditor(index, data = null, surveyId = null) {
  // 只有「已保存问卷里的本地题目」才能存进题库：新建问卷时题目还没有实例 ID，
  // 已引用题库的题目也不需要再存一次（走 referenceCard，不会进到这里）。
  const canPromote = !!(surveyId && data && data.id);
  const el = $(`
    <div class="question-editor c${index % 5}">
      <div class="q-head">
        <span class="q-num">Q${index + 1}</span>
        <select class="q-type">${QUESTION_TYPES.map(([v, l]) => `<option value="${v}">${l}</option>`).join("")}</select>
        <input class="q-title" maxlength="1000" placeholder="题目标题 *（最多 1000 字）" style="flex:3">
        <label class="check" style="margin:0"><input type="checkbox" class="q-required" checked> 必答</label>
        ${canPromote ? `<button class="btn btn-sm btn-green" data-promote title="把这道题的内容存档到题库，之后可以在其他问卷里复用">📚 存入题库</button>` : ""}
        <button class="btn btn-sm btn-red" data-del>✕</button>
      </div>
      <div class="q-body"></div>
      <div class="q-jumps"></div>
    </div>`);
  // 编辑已有问卷时沿用后端给的实例 ID，保证跳转规则引用不断
  el.dataset.instanceId = (data && data.id) ? data.id : allocInstanceId();
  el.querySelector('[data-del]').onclick = () => { el.remove(); renumberQuestions(); };
  const promoteBtn = el.querySelector("[data-promote]");
  if (promoteBtn) promoteBtn.onclick = () => promoteToLibrary(surveyId, el.dataset.instanceId, promoteBtn);

  const typeSel = el.querySelector(".q-type");
  const body = el.querySelector(".q-body");
  if (data && data.type) typeSel.value = data.type;

  const renderBody = (preset) => {
    const t = typeSel.value;
    if (t === "single" || t === "multiple") {
      body.innerHTML = `
        <div class="section-label">选项（至少 2 个）</div>
        <div class="q-options"></div>
        <button class="btn btn-sm" data-add-opt>➕ 添加选项</button>
        ${t === "multiple" ? `<div class="section-label">选择数量限制（可选）</div>
        <div class="constraint-grid">
          <label>最少选择数 <input class="c-minSelect" type="number" min="0" placeholder="不限"></label>
          <label>最多选择数 <input class="c-maxSelect" type="number" min="1" placeholder="不限"></label>
        </div>` : ""}`;
      const opts = body.querySelector(".q-options");
      const addOpt = (opt) => {
        const row = $(`<div class="option-row">
          <input class="opt-id" value="${esc(opt ? opt.id : `o${opts.children.length + 1}_${Math.random().toString(36).slice(2, 6)}`)}" title="选项 ID（跳转规则会引用）" readonly>
          <input class="opt-label" maxlength="500" placeholder="选项文字 *" value="${esc(opt ? opt.label : "")}">
          <button class="btn btn-sm btn-red" data-del-opt>✕</button></div>`);
        row.querySelector("[data-del-opt]").onclick = () => row.remove();
        opts.appendChild(row);
      };
      body.querySelector("[data-add-opt]").onclick = () => addOpt(null);
      if (preset && preset.length) preset.forEach(o => addOpt(o));
      else { addOpt(null); addOpt(null); }
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
    renderJumpEditor(el.querySelector(".q-jumps"), data ? (data.jumps || []) : []);
    if (data) fillConstraints(el, data.constraints);
  };

  typeSel.onchange = () => renderBody(null);
  renderBody(data && (data.type === "single" || data.type === "multiple") ? (data.options || []) : null);
  if (data) {
    el.querySelector(".q-title").value = data.title || "";
    el.querySelector(".q-required").checked = !!data.required;
  }
  return el;
}

/** 回填约束输入框。约束字段分散在不同题型的 DOM 里，逐类判空后赋值。 */
function fillConstraints(el, c) {
  if (!c) return;
  const set = (cls, v) => { const node = el.querySelector("." + cls); if (node && v != null) node.value = v; };
  set("c-minSelect", c.minSelect);
  set("c-maxSelect", c.maxSelect);
  set("c-minValue", c.minValue);
  set("c-maxValue", c.maxValue);
  set("c-minLength", c.minLength);
  set("c-maxLength", c.maxLength);
  const isInt = el.querySelector(".c-isInteger");
  if (isInt && c.isInteger) isInt.value = "true";
}

/** 跳转规则编辑器：本地题目与引用题目共用（跳转规则属于问卷内实例配置，不进版本）。 */
function renderJumpEditor(box, jumps) {
  box.innerHTML = `
    <div class="section-label">跳转规则（可选，命中即跳往目标；只允许向后跳或 END）</div>
    <div class="j-list"></div>
    <button class="btn btn-sm" data-add-jump>➕ 添加跳转规则</button>`;
  const jlist = box.querySelector(".j-list");
  (jumps || []).forEach(j => addJumpRow(jlist, j));
  box.querySelector("[data-add-jump]").onclick = () => addJumpRow(jlist, null);
}

function addJumpRow(jlist, j) {
  const row = $(`<div class="jump-rule">
    <select class="j-op">${["eq", "neq", "contains", "gt", "gte", "lt", "lte"]
      .map(o => `<option ${j && j.op === o ? "selected" : ""}>${o}</option>`).join("")}</select>
    <input class="j-optionId" placeholder="optionId（选项题必填）" value="${esc(j ? j.optionId : "")}">
    <input class="j-value" placeholder="value（文本/数字题用）" value="${esc(j ? j.value : "")}">
    <input class="j-target" placeholder="目标题实例 ID 或 END" value="${esc(j ? j.targetQuestionId : "")}">
    <button class="jump-del" title="删除">✕</button></div>`);
  row.querySelector(".jump-del").onclick = () => row.remove();
  jlist.appendChild(row);
}

function renumberQuestions() {
  document.querySelectorAll("#questions .question-editor").forEach((el, i) => {
    el.querySelector(".q-num").textContent = "Q" + (i + 1);
    // 只替换配色类，不能整体重写 className：引用卡片还带着 ref-card 标记，
    // 整体覆盖会让它的只读样式（浅蓝底、虚线题干框）在增删题目后凭空消失。
    el.classList.remove("c0", "c1", "c2", "c3", "c4");
    el.classList.add("c" + (i % 5));
  });
}

/** 读取一张卡片上的跳转规则，本地题目与引用题目共用。 */
function collectJumps(qEl) {
  const jumps = [];
  qEl.querySelectorAll(".jump-rule").forEach(r => {
    const op = r.querySelector(".j-op").value;
    const optionId = r.querySelector(".j-optionId").value.trim() || null;
    const value = r.querySelector(".j-value").value.trim() || null;
    const target = r.querySelector(".j-target").value.trim();
    if (!target) return;
    jumps.push({
      op,
      ...(optionId && { optionId }),
      ...(value && { value }),
      targetQuestionId: target.toUpperCase() === "END" ? "END" : target,
    });
  });
  return jumps;
}

function collectSurvey() {
  const title = document.getElementById("s-title").value.trim();
  if (!title) throw new Error("问卷标题不能为空");
  const questions = [];
  document.querySelectorAll("#questions .question-editor").forEach((qEl, qi) => {
    const qid = qEl.dataset.instanceId || ("q" + (qi + 1));
    const required = qEl.querySelector(".q-required").checked;
    const jumps = collectJumps(qEl);

    // 引用题库版本：只提交引用字段与实例配置，内容字段一律不传。
    // 传了内容会触发后端「引用即只读」校验（400），这也是有意的约束。
    if (qEl.dataset.ref === "1") {
      questions.push({
        id: qid,
        order: qi + 1,
        questionId: qEl.dataset.questionId,
        questionVersionId: qEl.dataset.questionVersionId,
        versionNo: Number(qEl.dataset.versionNo),
        required,
        jumps,
      });
      return;
    }

    const type = qEl.querySelector(".q-type").value;
    const qTitle = qEl.querySelector(".q-title").value.trim();
    if (!qTitle) throw new Error(`第 ${qi + 1} 题的标题不能为空`);
    const q = { id: qid, order: qi + 1, type, title: qTitle, required, options: [], jumps };
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
    questions.push(q);
  });
  if (!questions.length) throw new Error("至少需要一道题目");
  const deadlineStr = document.getElementById("s-deadline").value;
  return {
    title,
    description: document.getElementById("s-desc").value.trim() || null,
    anonymous: document.getElementById("s-anon").checked,
    allowMultiple: document.getElementById("s-multi").checked,
    ...(deadlineStr && { deadline: new Date(deadlineStr).toISOString() }),
    questions,
  };
}

async function saveSurvey(surveyId) {
  try {
    const body = collectSurvey();
    if (surveyId) {
      await api("PUT", `/api/surveys/${surveyId}`, body);
      toast("修改已保存 ✅");
    } else {
      await api("POST", "/api/surveys", body);
      toast("草稿保存成功！去「我的问卷」发布它 🚀");
    }
    pendingEditId = null;
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
  } catch (e) {
    toast(e.message, true);
    // 只在 toast 里报错会留下一个完全空白的答题区：toast 3 秒后消失，
    // 用户看到的就是「点了开始答卷但什么都没发生」。错误也要落在页面上。
    const area = document.getElementById("fill-area");
    if (area) area.innerHTML = `<div class="card"><div class="empty-tip">${esc(e.message)}</div></div>`;
  }
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

/* ==================== 题库 ==================== */

const TYPE_LABEL = { single: "单选", multiple: "多选", number: "数字", text: "文本" };
function typeLabel(t) { return TYPE_LABEL[t] || t || "—"; }

/** 题库列表的筛选状态，切 tab 再回来时保留。 */
const libState = { status: "", keyword: "" };

async function renderLibrary() {
  const view = document.getElementById("view");
  view.innerHTML = `
    <div class="card">
      <div class="survey-head">
        <h2>📚 题目库</h2>
        <button class="btn btn-sm btn-pink" id="lib-new">➕ 新建题目</button>
      </div>
      <div class="subtitle">题库里的题目可以反复使用。修改题目会生成新版本，已经做好的问卷继续用旧版本，内容不会被改掉。</div>
      <div class="row">
        <input id="lib-kw" placeholder="搜索题干关键词" value="${esc(libState.keyword)}" style="flex:2">
        <select id="lib-status" style="flex:1">
          <option value="">进行中</option>
          <option value="all">全部</option>
          <option value="archived">已归档</option>
        </select>
        <button class="btn btn-blue" id="lib-search">🔍 筛选</button>
      </div>
    </div>
    <div id="lib-form"></div>
    <div id="lib-list"><div class="card"><div class="empty-tip">加载中…</div></div></div>
    <div id="lib-shared"></div>`;

  document.getElementById("lib-status").value = libState.status;
  document.getElementById("lib-search").onclick = () => {
    libState.keyword = document.getElementById("lib-kw").value.trim();
    libState.status = document.getElementById("lib-status").value;
    loadLibraryList();
  };
  document.getElementById("lib-kw").onkeydown = e => { if (e.key === "Enter") document.getElementById("lib-search").click(); };
  document.getElementById("lib-new").onclick = () => openQuestionForm(null);

  loadLibraryList();
}

async function loadLibraryList() {
  const box = document.getElementById("lib-list");
  const sharedBox = document.getElementById("lib-shared");
  if (!box) return;
  box.innerHTML = `<div class="card"><div class="empty-tip">加载中…</div></div>`;

  let mine = [], shared = [];
  try {
    const qs = new URLSearchParams();
    if (libState.status) qs.set("status", libState.status);
    if (libState.keyword) qs.set("keyword", libState.keyword);
    [mine, shared] = await Promise.all([
      api("GET", "/api/questions" + (qs.toString() ? "?" + qs.toString() : "")),
      api("GET", "/api/questions/shared-with-me"),
    ]);
  } catch (e) {
    box.innerHTML = "";
    toast(e.message, true);
    return;
  }

  box.innerHTML = "";
  if (!mine.length) {
    box.innerHTML = `<div class="card"><div class="empty-tip">还没有题目。点「➕ 新建题目」把常用题目存进题库吧。</div></div>`;
  } else {
    mine.forEach((q, i) => box.appendChild(libraryCard(q, i, false)));
  }

  if (sharedBox) {
    sharedBox.innerHTML = "";
    if (shared.length) {
      const head = $(`<div class="card"><div class="survey-head"><h2>🤝 别人共享给我的题目</h2>
        <span class="badge anon">${shared.length} 道</span></div>
        <div class="subtitle">这些题目可以选进你自己的问卷，但不能修改、归档或查看跨问卷统计。</div></div>`);
      sharedBox.appendChild(head);
      shared.forEach((s, i) => sharedBox.appendChild(libraryCard(s, i, true)));
    }
  }
}

function libraryCard(q, index, isShared) {
  // 我的题目用 LibraryQuestionSummary，共享记录用 QuestionShareView，字段名不同
  const id = isShared ? q.questionId : q.id;
  const title = isShared ? q.questionTitle : q.title;
  const status = isShared ? q.questionStatus : q.status;
  const archived = status === "archived";
  const color = COLORS[index % COLORS.length];

  const card = $(`
    <div class="card lib-card" style="border-left:10px solid ${archived ? "#C8C6C6" : color}">
      <div class="survey-head">
        <div>
          <div class="survey-title">${esc(title)}</div>
          <div class="survey-meta">
            ${isShared
              ? `共享自 ${esc(q.ownerUsername)} · 权限 ${q.permission === "edit" ? "可修改" : "可使用"}`
              : `${typeLabel(q.type)} · 当前 v${q.currentVersionNo} · 更新于 ${fmtTime(q.updatedAt)}`}
          </div>
        </div>
        <div>
          ${archived ? '<span class="badge closed">已归档</span>' : ""}
          ${!isShared && q.visibility === "shared" ? '<span class="badge anon">已共享</span>' : ""}
          ${isShared ? `<span class="badge ${q.permission === "edit" ? "published" : "draft"}">${q.permission === "edit" ? "可修改" : "可使用"}</span>` : ""}
        </div>
      </div>
      <div class="survey-actions">
        <button class="btn btn-sm btn-blue" data-act="detail">🔍 详情</button>
        ${!isShared || q.permission === "edit"
          ? `<button class="btn btn-sm btn-yellow" data-act="edit">✏️ 修改</button>` : ""}
        ${isShared ? "" : `<button class="btn btn-sm btn-green" data-act="share">🤝 共享</button>`}
        ${!isShared && !archived ? `<button class="btn btn-sm btn-red" data-act="archive">📦 归档</button>` : ""}
      </div>
    </div>`);

  const bind = (act, fn) => { const b = card.querySelector(`[data-act="${act}"]`); if (b) b.onclick = fn; };
  bind("detail", () => showQuestionDetail(id));
  bind("edit", () => openQuestionForm(id));
  bind("share", () => promptShare(id, title));
  bind("archive", async () => {
    if (!confirm(`归档「${title}」？\n\n归档后不能再选入新问卷，但已经引用它的问卷不受影响，共享关系也保留。`)) return;
    try { await api("DELETE", `/api/questions/${encodeURIComponent(id)}`); toast("已归档"); loadLibraryList(); }
    catch (e) { toast(e.message, true); }
  });
  return card;
}

/** 共享：按用户名授权。重复共享同一人会更新权限级别，不会产生两条记录。 */
async function promptShare(questionId, title) {
  const username = prompt(`把「${title}」共享给谁？\n\n请输入对方用户名：`);
  if (username == null) return;
  if (!username.trim()) { toast("用户名不能为空", true); return; }
  const level = prompt("权限级别：\nuse = 可以使用（能选进自己的问卷，不能改内容）\nedit = 可以修改（能生成新版本）", "use");
  if (level == null) return;
  try {
    await api("POST", `/api/questions/${encodeURIComponent(questionId)}/shares`, {
      username: username.trim(),
      permission: level.trim().toLowerCase() === "edit" ? "edit" : "use",
    });
    toast(`已共享给 ${username.trim()}`);
    // 授权入口有两处：题库列表卡片、详情页的「共享授权」页签。
    // loadLibraryList 只服务于列表页，在详情页里找不到容器会直接返回，
    // 列表就停在授权之前的样子，所以详情页要单独刷新当前页签。
    if (document.getElementById("qd-body")) loadDetailTab(questionId, "shares");
    else loadLibraryList();
  } catch (e) { toast(e.message, true); }
}

/* ---------------- 题目内容表单（新建 / 修改） ---------------- */

async function openQuestionForm(questionId) {
  const box = document.getElementById("lib-form");
  if (!box) return;
  box.innerHTML = `<div class="card"><div class="empty-tip">加载中…</div></div>`;

  let question = null;
  if (questionId) {
    try { question = await api("GET", `/api/questions/${encodeURIComponent(questionId)}`); }
    catch (e) { box.innerHTML = ""; toast(e.message, true); return; }
  }
  const v = question ? question.currentVersion : null;
  const isEdit = !!question;

  box.innerHTML = `
    <div class="card" style="border-top:10px solid #B983FF">
      <div class="survey-head">
        <h2>${isEdit ? `✏️ 修改题目（当前 v${question.currentVersionNo}）` : "➕ 新建题目"}</h2>
        <button class="btn btn-sm" id="qf-close">关闭</button>
      </div>
      ${isEdit ? `<div class="subtitle">保存后会生成 v${question.currentVersionNo + 1}，旧版本保留。已经引用这道题的问卷继续用原版本，不会受影响。</div>` : ""}
      <label class="field"><span>题干 *（最多 1000 字）</span>
        <input id="qf-title" maxlength="1000" value="${esc(v ? v.title : "")}" placeholder="例如：你每周运动几次？"></label>
      <div class="row">
        <label class="field"><span>题型</span>
          <select id="qf-type">${QUESTION_TYPES.map(([val, label]) =>
            `<option value="${val}" ${v && v.type === val ? "selected" : ""}>${label}</option>`).join("")}</select></label>
        <label class="check" style="align-self:end"><input type="checkbox" id="qf-required" ${!v || v.required ? "checked" : ""}> 默认必答</label>
      </div>
      <div id="qf-body"></div>
      <label class="field"><span>改动说明（可选，写入版本历史）</span>
        <input id="qf-note" maxlength="1000" placeholder="例如：把「每周」改成「每月」，避免歧义"></label>
      <div class="row">
        <button class="btn btn-pink" id="qf-save">💾 ${isEdit ? "保存并生成新版本" : "保存到题库"}</button>
      </div>
    </div>`;

  const bodyBox = document.getElementById("qf-body");
  const typeSel = document.getElementById("qf-type");

  const renderBody = (preset) => {
    const t = typeSel.value;
    if (t === "single" || t === "multiple") {
      bodyBox.innerHTML = `
        <div class="section-label">选项（至少 2 个）</div>
        <div class="qf-options"></div>
        <button class="btn btn-sm" id="qf-add-opt">➕ 添加选项</button>
        ${t === "multiple" ? `<div class="section-label">选择数量限制（可选）</div>
        <div class="constraint-grid">
          <label>最少选择数 <input class="c-minSelect" type="number" min="0" placeholder="不限"></label>
          <label>最多选择数 <input class="c-maxSelect" type="number" min="1" placeholder="不限"></label>
        </div>` : ""}`;
      const opts = bodyBox.querySelector(".qf-options");
      const addOpt = (opt) => {
        const row = $(`<div class="option-row">
          <input class="opt-id" value="${esc(opt ? opt.id : `o${opts.children.length + 1}_${Math.random().toString(36).slice(2, 6)}`)}" title="选项 ID" readonly>
          <input class="opt-label" maxlength="500" placeholder="选项文字 *" value="${esc(opt ? opt.label : "")}">
          <button class="btn btn-sm btn-red" data-del-opt>✕</button></div>`);
        row.querySelector("[data-del-opt]").onclick = () => row.remove();
        opts.appendChild(row);
      };
      document.getElementById("qf-add-opt").onclick = () => addOpt(null);
      if (preset && preset.length) preset.forEach(o => addOpt(o));
      else { addOpt(null); addOpt(null); }
    } else if (t === "number") {
      bodyBox.innerHTML = `
        <div class="section-label">数值限制</div>
        <div class="constraint-grid">
          <label>最小值 <input class="c-minValue" type="number" step="any" placeholder="不限"></label>
          <label>最大值 <input class="c-maxValue" type="number" step="any" placeholder="不限"></label>
          <label>只允许整数 <select class="c-isInteger"><option value="">否</option><option value="true">是</option></select></label>
        </div>`;
    } else {
      bodyBox.innerHTML = `
        <div class="section-label">文本长度限制（可选）</div>
        <div class="constraint-grid">
          <label>最小长度 <input class="c-minLength" type="number" min="0" placeholder="不限"></label>
          <label>最大长度 <input class="c-maxLength" type="number" min="0" placeholder="不限"></label>
        </div>`;
    }
    if (v && preset !== undefined && preset !== null) fillConstraints(bodyBox, v.constraints);
  };

  typeSel.onchange = () => renderBody(null);
  renderBody(v ? (v.options || []) : null);
  if (v && (v.type === "number" || v.type === "text")) fillConstraints(bodyBox, v.constraints);

  document.getElementById("qf-close").onclick = () => { box.innerHTML = ""; };
  document.getElementById("qf-save").onclick = async () => {
    const title = document.getElementById("qf-title").value.trim();
    if (!title) { toast("题干不能为空", true); return; }
    const type = typeSel.value;
    const payload = {
      title,
      type,
      required: document.getElementById("qf-required").checked,
      changeNote: document.getElementById("qf-note").value.trim() || null,
      options: [],
    };
    const g = cls => bodyBox.querySelector("." + cls);
    const num = cls => { const node = g(cls); const val = node ? node.value : ""; return val === "" || val == null ? null : Number(val); };
    if (type === "single" || type === "multiple") {
      bodyBox.querySelectorAll(".option-row").forEach((row, oi) => {
        const label = row.querySelector(".opt-label").value.trim();
        if (label) payload.options.push({ id: row.querySelector(".opt-id").value, label, order: oi + 1 });
      });
      if (payload.options.length < 2) { toast("单选/多选至少需要 2 个选项", true); return; }
      const minS = num("c-minSelect"), maxS = num("c-maxSelect");
      if (minS != null || maxS != null) payload.constraints = { ...(minS != null && { minSelect: minS }), ...(maxS != null && { maxSelect: maxS }) };
    } else if (type === "number") {
      const minV = num("c-minValue"), maxV = num("c-maxValue"), isInt = g("c-isInteger")?.value === "true";
      if (minV != null || maxV != null || isInt) payload.constraints = { ...(minV != null && { minValue: minV }), ...(maxV != null && { maxValue: maxV }), ...(isInt && { isInteger: true }) };
    } else {
      const minL = num("c-minLength"), maxL = num("c-maxLength");
      if (minL != null || maxL != null) payload.constraints = { ...(minL != null && { minLength: minL }), ...(maxL != null && { maxLength: maxL }) };
    }

    try {
      if (isEdit) {
        await api("PUT", `/api/questions/${encodeURIComponent(questionId)}`, payload);
        toast("已生成新版本 ✅");
      } else {
        await api("POST", "/api/questions", payload);
        toast("已保存到题库 🎉");
      }
      box.innerHTML = "";
      loadLibraryList();
    } catch (e) { toast(e.message, true); }
  };
}

/* ---------------- 题目详情：版本历史 / 引用 / 统计 / 共享 ---------------- */

/** 当前打开的题目详情是否允许修改内容（owner 或 edit 授权）。供版本历史决定是否显示「恢复」。 */
let qdCanEdit = false;

async function showQuestionDetail(questionId) {
  const view = document.getElementById("view");
  view.innerHTML = `<div class="card"><div class="empty-tip">加载中…</div></div>`;

  let detail;
  try { detail = await api("GET", `/api/questions/${encodeURIComponent(questionId)}`); }
  catch (e) { toast(e.message, true); renderApp("library"); return; }

  // accessLevel 由服务端给出（owner / edit / use）。页面只按它决定按钮出不出现：
  // 前端拿不到自己的用户 ID，无法比对 ownerId，也不该重复实现一遍授权规则。
  const level = detail.accessLevel || "owner";
  const isOwner = level === "owner";
  const canEdit = isOwner || level === "edit";
  const levelText = { owner: "创建者", edit: "被授权（可修改）", use: "被授权（可使用）" }[level] || level;
  qdCanEdit = canEdit;

  view.innerHTML = `
    <div class="card">
      <div class="survey-head">
        <div>
          <h2>${esc(detail.title)}</h2>
          <div class="survey-meta">
            ${typeLabel(detail.type)} · 当前 v${detail.currentVersionNo} · 共 ${detail.versionCount} 个版本 · 被 ${detail.usageCount} 份问卷引用
          </div>
        </div>
        <div>
          <span class="badge ${detail.status === "archived" ? "closed" : "published"}">${detail.status === "archived" ? "已归档" : "进行中"}</span>
          ${detail.visibility === "shared" ? '<span class="badge anon">已共享</span>' : ""}
          ${isOwner ? "" : `<span class="badge draft">我的权限：${esc(levelText)}</span>`}
        </div>
      </div>
      <div class="survey-actions">
        <button class="btn btn-sm" id="qd-back">← 返回题库</button>
        ${canEdit && detail.status !== "archived" ? `<button class="btn btn-sm btn-yellow" id="qd-edit">✏️ 修改（生成新版本）</button>` : ""}
      </div>
      ${isOwner ? "" : `<div class="ref-note">这道题是别人共享给你的。你能查看版本历史并选进自己的问卷${canEdit ? "，也可以修改内容生成新版本" : ""}；归档、转授和跨问卷统计只有创建者能做。</div>`}
    </div>
    <div class="nav-tabs" id="qd-tabs">
      <button class="btn active" data-qtab="versions">🕘 版本历史</button>
      ${isOwner ? `<button class="btn" data-qtab="usages">🔗 被哪些问卷使用</button>
        <button class="btn" data-qtab="stats">📊 跨问卷统计</button>
        <button class="btn" data-qtab="shares">🤝 共享授权</button>` : ""}
    </div>
    <div id="qd-body"></div>`;

  document.getElementById("qd-back").onclick = () => renderApp("library");
  const editBtn = document.getElementById("qd-edit");
  if (editBtn) editBtn.onclick = async () => {
    renderApp("library");
    // 等题库列表渲染完再展开表单
    setTimeout(() => openQuestionForm(questionId), 120);
  };

  const tabs = view.querySelectorAll("#qd-tabs .btn");
  tabs.forEach(btn => {
    btn.onclick = () => {
      tabs.forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      loadDetailTab(questionId, btn.dataset.qtab);
    };
  });
  loadDetailTab(questionId, "versions");
}

async function loadDetailTab(questionId, tab) {
  const box = document.getElementById("qd-body");
  if (!box) return;
  box.innerHTML = `<div class="card"><div class="empty-tip">加载中…</div></div>`;
  try {
    if (tab === "versions") await renderVersionHistory(box, questionId);
    else if (tab === "usages") await renderUsageList(box, questionId);
    else if (tab === "stats") await renderCrossStats(box, questionId);
    else await renderShareList(box, questionId);
  } catch (e) {
    box.innerHTML = "";
    toast(e.message, true);
  }
}

async function renderVersionHistory(box, questionId) {
  const versions = await api("GET", `/api/questions/${encodeURIComponent(questionId)}/versions`);
  const currentNo = versions.length ? Math.max(...versions.map(v => v.versionNo)) : 0;

  box.innerHTML = `
    <div class="card">
      <h2>🕘 版本历史</h2>
      <div class="subtitle">每次修改都会生成新版本，旧版本永久保留。问卷引用的是具体版本，所以改题目不会改掉已发出的问卷。</div>
      <div id="ver-list"></div>
    </div>
    <div id="ver-detail"></div>`;

  const list = document.getElementById("ver-list");
  versions.forEach(v => {
    const row = $(`
      <div class="version-item">
        <div class="version-head">
          <span class="badge ${v.versionNo === currentNo ? "published" : "draft"}">v${v.versionNo}${v.versionNo === currentNo ? " · 当前" : ""}</span>
          <span class="survey-meta">${fmtTime(v.createdAt)}</span>
          ${v.restoredFromVersionId ? '<span class="badge anon">由旧版本恢复</span>' : ""}
        </div>
        <div class="version-title">${esc(v.title)}</div>
        ${v.changeNote ? `<div class="version-note">📝 ${esc(v.changeNote)}</div>` : ""}
        <div class="survey-actions">
          <button class="btn btn-sm" data-view="${v.versionNo}">👁 查看内容</button>
          ${v.versionNo > 1 ? `<button class="btn btn-sm btn-blue" data-diff="${v.versionNo}">🔀 与 v${v.versionNo - 1} 对比</button>` : ""}
          ${v.versionNo !== currentNo && qdCanEdit ? `<button class="btn btn-sm btn-orange" data-restore="${v.versionNo}">↩️ 恢复此版本</button>` : ""}
        </div>
      </div>`);
    row.querySelector(`[data-view]`).onclick = () => showVersionContent(v);
    const diffBtn = row.querySelector("[data-diff]");
    if (diffBtn) diffBtn.onclick = () => showVersionDiff(questionId, v.versionNo - 1, v.versionNo);
    const restoreBtn = row.querySelector("[data-restore]");
    if (restoreBtn) restoreBtn.onclick = async () => {
      if (!confirm(`把 v${v.versionNo} 的内容恢复为最新版本？\n\n不会删除任何版本，而是以旧版本内容生成一个新的版本。`)) return;
      const note = prompt("恢复说明（可选）：", `从 v${v.versionNo} 恢复`);
      try {
        await api("POST", `/api/questions/${encodeURIComponent(questionId)}/versions/${v.versionNo}/restore`
          + (note ? `?changeNote=${encodeURIComponent(note)}` : ""));
        toast("已恢复并生成新版本");
        loadDetailTab(questionId, "versions");
      } catch (e) { toast(e.message, true); }
    };
    list.appendChild(row);
  });
}

function showVersionContent(v) {
  const box = document.getElementById("ver-detail");
  if (!box) return;
  box.innerHTML = "";
  box.appendChild($(`<div class="card" style="border-top:10px solid #4D96FF">
    <div class="survey-head"><h2>v${v.versionNo} 的内容</h2>
      <button class="btn btn-sm" id="vc-close">收起</button></div>
    ${renderVersionContent(v)}
  </div>`));
  document.getElementById("vc-close").onclick = () => { box.innerHTML = ""; };
}

function renderVersionContent(v) {
  return `
    <div class="version-content">
      <div><b>题干</b>：${esc(v.title)}</div>
      <div><b>题型</b>：${typeLabel(v.type)} · <b>默认必答</b>：${v.required ? "是" : "否"}</div>
      ${v.options && v.options.length
        ? `<div><b>选项</b>：${v.options.map(o => `<span class="opt-chip">${esc(o.label)}<code>${esc(o.id)}</code></span>`).join("")}</div>`
        : ""}
      ${renderConstraintsText(v.constraints)}
    </div>`;
}

function renderConstraintsText(c) {
  if (!c) return "";
  const parts = [];
  if (c.minSelect != null) parts.push(`最少选 ${c.minSelect} 项`);
  if (c.maxSelect != null) parts.push(`最多选 ${c.maxSelect} 项`);
  if (c.minValue != null) parts.push(`最小值 ${c.minValue}`);
  if (c.maxValue != null) parts.push(`最大值 ${c.maxValue}`);
  if (c.minLength != null) parts.push(`最少 ${c.minLength} 字`);
  if (c.maxLength != null) parts.push(`最多 ${c.maxLength} 字`);
  if (c.isInteger) parts.push("必须为整数");
  return parts.length ? `<div><b>约束</b>：${esc(parts.join("；"))}</div>` : "";
}

async function showVersionDiff(questionId, from, to) {
  const box = document.getElementById("ver-detail");
  if (!box) return;
  box.innerHTML = `<div class="card"><div class="empty-tip">对比中…</div></div>`;
  let diff;
  try {
    diff = await api("GET", `/api/questions/${encodeURIComponent(questionId)}/versions/${from}/compare/${to}`);
  } catch (e) { box.innerHTML = ""; toast(e.message, true); return; }

  const lines = [];
  const push = (label, from_, to_) => lines.push(`<div><b>${esc(label)}</b>：${esc(from_ ?? "（空）")} → ${esc(to_ ?? "（空）")}</div>`);
  if (diff.type && diff.type.changed) push("题型", typeLabel(diff.type.from), typeLabel(diff.type.to));
  if (diff.title && diff.title.changed) push("题干", diff.title.from, diff.title.to);
  if (diff.required && diff.required.changed) push("默认必答", diff.required.from ? "是" : "否", diff.required.to ? "是" : "否");

  const opt = diff.options || {};
  (opt.added || []).forEach(o => lines.push(`<div><b>新增选项</b>：${esc(o.label)} <code>${esc(o.id)}</code></div>`));
  (opt.removed || []).forEach(o => lines.push(`<div><b>删除选项</b>：${esc(o.label)} <code>${esc(o.id)}</code></div>`));
  (opt.modified || []).forEach(o => lines.push(`<div><b>选项文案</b>：${esc(o.fromLabel)} → ${esc(o.toLabel)}</div>`));
  if (opt.reordered) lines.push(`<div><b>选项顺序</b>：已调整</div>`);

  (diff.constraints || []).filter(c => c.changed)
    .forEach(c => push(`约束 ${c.field}`, c.from, c.to));

  box.innerHTML = "";
  box.appendChild($(`<div class="card" style="border-top:10px solid #FF9F45">
    <div class="survey-head">
      <h2>🔀 v${diff.fromVersionNo} → v${diff.toVersionNo}</h2>
      <button class="btn btn-sm" id="diff-close">收起</button>
    </div>
    ${diff.identical
      ? `<div class="empty-tip">两个版本内容完全一致</div>`
      : `<div class="diff-box">
          ${(diff.summary || []).map(s => `<div class="diff-summary">• ${esc(s)}</div>`).join("")}
          ${lines.join("") || '<div class="empty-tip">没有可展示的差异</div>'}
        </div>`}
  </div>`));
  document.getElementById("diff-close").onclick = () => { box.innerHTML = ""; };
}

async function renderUsageList(box, questionId) {
  const usages = await api("GET", `/api/questions/${encodeURIComponent(questionId)}/usages`);
  box.innerHTML = `
    <div class="card">
      <h2>🔗 被哪些问卷使用</h2>
      <div class="subtitle">共 ${usages.length} 份问卷引用了这道题。标记「落后」的问卷绑定的版本比题库当前版本旧，可以进入该问卷的编辑页手动换绑。</div>
      ${usages.length ? '<div id="usage-list"></div>' : '<div class="empty-tip">还没有问卷引用这道题。</div>'}
    </div>`;
  const list = document.getElementById("usage-list");
  if (!list) return;
  usages.forEach(u => {
    list.appendChild($(`<div class="version-item">
      <div class="version-head">
        <span class="survey-title">${esc(u.surveyTitle)}</span>
        <span class="badge ${u.surveyStatus}">${{ draft: "草稿", published: "已发布", closed: "已关闭" }[u.surveyStatus] || u.surveyStatus}</span>
        ${u.outdated ? '<span class="badge warn">版本落后</span>' : ""}
      </div>
      <div class="survey-meta">
        所有者 ${esc(u.surveyOwnerUsername || "—")} · 引用 ${u.repeatCount} 处
        · 绑定 ${u.mixedVersions ? "多个版本" : "v" + (u.boundVersionNo ?? "?")}
        · 题库当前 v${u.latestVersion}
        · 问卷更新于 ${fmtTime(u.surveyUpdatedAt)}
      </div>
    </div>`));
  });
}

async function renderShareList(box, questionId) {
  const shares = await api("GET", `/api/questions/${encodeURIComponent(questionId)}/shares`);
  box.innerHTML = `
    <div class="card">
      <div class="survey-head">
        <h2>🤝 共享授权</h2>
        <button class="btn btn-sm btn-green" id="share-add">➕ 共享给他人</button>
      </div>
      <div class="subtitle">
        获得「可使用」权限的人能把这道题选进自己的问卷；「可修改」额外允许生成新版本。
        两种权限都不能再转授给别人，也不能归档或查看跨问卷统计。
      </div>
      ${shares.length ? '<div id="share-list"></div>' : '<div class="empty-tip">还没有共享给任何人。</div>'}
    </div>`;
  document.getElementById("share-add").onclick = async () => {
    const detail = await api("GET", `/api/questions/${encodeURIComponent(questionId)}`);
    promptShare(questionId, detail.title);
  };
  const list = document.getElementById("share-list");
  if (!list) return;
  shares.forEach(s => {
    const row = $(`<div class="version-item">
      <div class="version-head">
        <span class="survey-title">👤 ${esc(s.granteeUsername || s.granteeId)}</span>
        <span class="badge ${s.permission === "edit" ? "published" : "draft"}">${s.permission === "edit" ? "可修改" : "可使用"}</span>
      </div>
      <div class="survey-meta">授权于 ${fmtTime(s.createdAt)}</div>
      <div class="survey-actions">
        <button class="btn btn-sm btn-red" data-revoke>撤销</button>
      </div>
    </div>`);
    row.querySelector("[data-revoke]").onclick = async () => {
      if (!confirm(`撤销对 ${s.granteeUsername || s.granteeId} 的授权？\n\n已经用这道题建好的问卷不受影响。`)) return;
      try {
        await api("DELETE", `/api/questions/${encodeURIComponent(questionId)}/shares/${encodeURIComponent(s.granteeId)}`);
        toast("已撤销");
        loadDetailTab(questionId, "shares");
      } catch (e) { toast(e.message, true); }
    };
    list.appendChild(row);
  });
}

/** 跨问卷单题统计：按版本分组展示，不同版本选项集合不同，不能混算比例。 */
async function renderCrossStats(box, questionId) {
  const data = await api("GET", `/api/questions/${encodeURIComponent(questionId)}/stats`);
  box.innerHTML = `
    <div class="card">
      <div class="survey-head">
        <h2>📊 跨问卷统计</h2>
        <span class="badge published">合计 ${data.totalAnswered} 条回答</span>
      </div>
      <div class="survey-meta">被 ${data.surveyCount} 份问卷引用 · 共 ${data.versionCount} 个版本</div>
      <div class="subtitle">
        统计按版本分组：不同版本的选项可能不同，混在一起算比例会让每个选项的占比都偏低，所以每个版本单独算。
      </div>
      ${data.hasEmptyVersion ? '<div class="hint-warn">⚠️ 有版本还没有任何回答，统计只覆盖了部分版本。</div>' : ""}
      ${data.totalAnswered === 0 ? '<div class="empty-tip">还没有人回答过这道题。</div>' : '<div id="cross-versions"></div>'}
    </div>`;

  const host = document.getElementById("cross-versions");
  if (!host) return;

  (data.versions || []).forEach(v => {
    const block = $(`<div class="stat-version">
      <div class="version-head">
        <span class="badge draft">v${v.versionNo}</span>
        <span class="survey-meta">${v.answeredCount} 条回答</span>
      </div>
      <div class="stat-body"></div>
    </div>`);
    const body = block.querySelector(".stat-body");

    if (data.type === "single" || data.type === "multiple") {
      if (!v.answeredCount) {
        body.innerHTML = `<div class="empty-tip">这个版本还没有回答</div>`;
      } else {
        // 用「占比」画条形，最大占比满格，便于跨版本比较
        (v.options || []).forEach((o, oi) => {
          const pct = Math.round((o.ratio || 0) * 1000) / 10;
          body.appendChild($(`<div class="bar-row">
            <div class="bar-label" title="${esc(o.label)}">${esc(o.label)}</div>
            <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${BAR_COLORS[oi % BAR_COLORS.length]}"></div></div>
            <div class="bar-count">${o.count} · ${pct}%</div>
          </div>`));
        });
      }
    } else if (data.type === "number") {
      const n = v.numberStats;
      body.innerHTML = n && n.count
        ? `<div>回答 ${n.count} 条 · 平均 <span class="stat-avg">${n.average != null ? n.average.toFixed(2) : "—"}</span>
            · 最小 ${n.min ?? "—"} · 最大 ${n.max ?? "—"}</div>`
        : `<div class="empty-tip">这个版本还没有数值回答</div>`;
    } else {
      body.innerHTML = v.textSampleTotal
        ? `<div class="subtitle">共 ${v.textSampleTotal} 条，展示前 ${(v.textSamples || []).length} 条</div>
           <div>${(v.textSamples || []).map(t => `<span class="text-bubble">${esc(t)}</span>`).join("")}</div>`
        : `<div class="empty-tip">这个版本还没有文本回答</div>`;
    }
    host.appendChild(block);
  });
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
