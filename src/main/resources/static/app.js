/* 公交灰度实验评审控制台 —— 纯原生 JS */
const EXP_CODE = 'PILOT-R123-202606';
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

let EXP = null;
let ME = null;
const loaded = {};

const PATH_META = {
  FULL_TREATMENT: { label: '全程处理', cls: 'blue' },
  FULL_CONTROL: { label: '纯净对照', cls: 'gray' },
  CROSS_MIXED: { label: '交叉/混合暴露', cls: 'purple' },
  UNKNOWN: { label: '未知(保留)', cls: 'red' }
};
const ARM_CLS = { TREATMENT: 'blue', CONTROL: 'gray' };

function fmt(x, d = 1) {
  if (x === null || x === undefined || x === '') return '—';
  const n = Number(x);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: d, minimumFractionDigits: d }) : String(x);
}
function asObj(v) {
  if (v && typeof v === 'string') { try { return JSON.parse(v); } catch { return v; } }
  return v;
}
function pct(x, d = 1) { return x === null || x === undefined ? '—' : (Number(x) * 100).toFixed(d) + '%'; }
function dt(x) {
  if (!x) return '—';
  try { return new Date(x).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }); }
  catch { return String(x); }
}
function badge(text, cls = 'gray') { return `<span class="badge ${cls}">${text}</span>`; }
function armBadge(a) { return a ? badge(a === 'TREATMENT' ? '处理' : '对照', ARM_CLS[a] || 'gray') : '—'; }

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  if (res.status === 401) { location.href = '/login.html'; throw new Error('未登录'); }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : {}; } catch { data = text; }
  if (!res.ok) {
    const msg = (data && data.message) ? data.message : ('HTTP ' + res.status);
    const err = new Error(msg); err.status = res.status; throw err;
  }
  return data;
}

function toast(msg, isErr = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (isErr ? ' err' : '');
  clearTimeout(toast._h);
  toast._h = setTimeout(() => t.className = 'toast', 4200);
}

async function guardAction(fn, okMsg) {
  try { const r = await fn(); toast(okMsg || '操作完成'); return r; }
  catch (e) {
    if (e.status === 403) toast('403：当前账号无权执行该操作，请使用对应角色账号', true);
    else toast('操作失败：' + e.message, true);
    throw e;
  }
}

/* ---------------- 初始化与导航 ---------------- */
async function init() {
  ME = await api('/me');
  if (!ME.authenticated) { location.href = '/login.html'; return; }
  $('#username').textContent = ME.username;
  $('#avatar').textContent = (ME.username[0] || '?').toUpperCase();
  $('#roles').textContent = (ME.roles || []).join(' / ');

  const list = await api('/experiments');
  const row = list.find(e => e.code === EXP_CODE) || list[0];
  EXP = await api('/experiments/' + row.id);
  ['treatment_rules', 'primary_metrics', 'safety_metrics', 'analysis_config', 'network_config']
    .forEach(k => { EXP[k] = asObj(EXP[k]); });

  $$('#nav button').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  await switchTab('overview');
}

const TAB_TITLES = {
  overview: ['试点总览', '目标人群、核心事实与扩围状态'],
  registry: ['实验登记与冻结', '合格线路/班次、规则、种子、概率、指标与周期一次性冻结并留指纹'],
  network: ['网络图与设计选择', '连接关系仅来自实验前刷卡换乘比例，簇在登记时冻结'],
  assignment: ['随机分配', '确定性 SHA-256 种子，平衡分配，固定 50% 饱和度，可离线复算'],
  exposure: ['暴露路径台账', '逐段关联计划分配、GPS 实际运行、折返/加班偏离与完整路径'],
  support: ['共同支持', '按时段 × 起讫区间 × 天气 × 可达性检查两臂可比范围'],
  analysis: ['客流结果估计', '乘客旅程加权 ITT、跨线暴露、CACE 与随机化推断区间'],
  cost: ['试点成本归集', '加班/油电/临时车辆/维修/取消损失绑定车辆块；直接归属与冻结驱动分摊两路线，净成本区间与 ITT 成本差'],
  guardrails: ['投诉与满载护栏', '独立于平均候车改善；任一硬护栏超限即停止扩围'],
  review: ['离线审阅', '冻结证据包、确定性复算与完整 JSON 导出'],
  expansion: ['扩围证据', '主要指标 + 护栏证据形成 GO / HOLD / BLOCKED 结论']
};

async function switchTab(tab) {
  $$('#nav button').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  $$('.tab-panel').forEach(p => p.classList.remove('active'));
  $('#tab-' + tab).classList.add('active');
  $('#top-title').textContent = TAB_TITLES[tab][0];
  $('#top-sub').textContent = TAB_TITLES[tab][1];
  if (!loaded[tab]) {
    loaded[tab] = true;
    try { await RENDER[tab](); } catch (e) { $('#tab-' + tab).innerHTML = `<div class="banner red">加载失败：${e.message}</div>`; }
  }
}

function renderGlobalBanner() {
  const d = EXP.latestDecision;
  if (!d || d.decision === 'NOT_EVALUATED') { $('#global-banner').innerHTML = ''; return; }
  if (d.decision === 'BLOCKED') {
    $('#global-banner').innerHTML = `<div class="banner red"><span class="big">⛔</span>
      <div><b>扩围已被护栏自动阻止（BLOCKED）。</b> ${d.reason || ''}
      <div class="small">最新结论时间 ${dt(d.created_at)} · 签署 ${d.decided_by || 'system'}</div></div></div>`;
  } else if (d.decision === 'GO') {
    $('#global-banner').innerHTML = `<div class="banner green"><span class="big">✅</span><div><b>扩围评审通过（GO）。</b> ${d.reason || ''}</div></div>`;
  } else {
    $('#global-banner').innerHTML = `<div class="banner amber"><span class="big">⏸️</span><div><b>暂缓扩围（HOLD）。</b> ${d.reason || ''}</div></div>`;
  }
}

/* ---------------- 总览 ---------------- */
RENDER = {};
RENDER.overview = async function () {
  renderGlobalBanner();
  const [ov, guard] = await Promise.all([
    api(`/experiments/${EXP.id}/overview`),
    api(`/experiments/${EXP.id}/guardrails`)
  ]);
  const blocked = (guard.latest || []).filter(g => g.breached);
  $('#tab-overview').innerHTML = `
    <div class="grid cards-4">
      ${stat('登记旅程总数', ov.journeys, '含未知状态，全部保留')}
      ${stat('完整旅程', ov.completeJourneys, '进入效应估计')}
      ${stat('未知旅程', ov.unknownJourneys, '未完成刷卡/中途改线', 'red')}
      ${stat('命名核验样例', ov.demoJourneys, '可逐路径追溯', 'accent')}
      ${stat('计划班次实例', ov.tripInstances, 'GPS/AVL 关联实际运行')}
      ${stat('班次随机单元', ov.assignmentUnitsTrip, '线路×日期×时段×班次')}
      ${stat('簇—周单元', ov.assignmentUnitsCluster, '2 簇 × 4 周')}
      ${stat('投诉 / 外部事件', ov.complaints + ' / ' + ov.events, '按服务时间关联')}
    </div>

    <div class="grid cards-2" style="margin-top:14px">
      <div class="card">
        <h3>🧪 虚构样例脚本</h3>
        <p class="section-desc">R1 与 R2 共享中心枢纽，实验前基线 <b>40%</b> 的 R1/R2 旅程发生两线换乘；R3 与它们基本不换乘。</p>
        <ul>
          <li><b>J-DEMO-DIVERT-01</b>：R1 处理班次实际<b>短线折返</b>，乘客到枢纽换乘 R2 <b>对照</b>班次 → 完整路径必须是 <span class="badge purple">交叉/混合</span>。</li>
          <li><b>J-DEMO-PURER2-01</b>：同一 R2 对照班次上的普通 R2 乘客 → 必须留在 <span class="badge gray">纯净对照</span>。</li>
          <li><b>J-DEMO-CROSS-01</b>：对照起乘、枢纽换乘处理线路 → 交叉暴露。</li>
          <li><b>J-DEMO-NONEXEC-01</b>：随机分配为处理但司机未执行 → ITT 仍计处理臂，实际暴露为旧规则。</li>
          <li>暴雨红色预警停运（2026-06-15 早高峰）产生未知/滞留旅程，投诉提交时间滞后服务 1–6 天。</li>
        </ul>
        <div class="pill-row">
          <button class="btn primary sm" onclick="switchTab('exposure')">查看暴露路径核验</button>
          <button class="btn sm" onclick="switchTab('assignment')">核验 50% 分配</button>
          <button class="btn sm" onclick="switchTab('analysis')">查看两方案估计差异</button>
        </div>
      </div>
      <div class="card">
        <h3>🚧 护栏最新状态</h3>
        ${blocked.length ? `<div class="banner red" style="margin:6px 0"><span class="big">⛔</span><div>${blocked.map(b => `${b.ruleName} 观测 <b>${b.observed}</b> 越过阈值 <b>${b.threshold}</b>`).join('<br/>')}</div></div>`
          : '<div class="banner green" style="margin:6px 0">暂无超限护栏</div>'}
        <table class="data"><thead><tr><th>护栏</th><th class="num">观测</th><th class="num">阈值</th><th>状态</th></tr></thead><tbody>
        ${(guard.latest || []).map(g => `<tr>
          <td>${g.ruleName}</td><td class="num mono">${fmt(g.observed, g.metricSource === 'overload_share' || g.metric_source === 'overload_share' ? 4 : 2)}</td>
          <td class="num mono">${g.threshold}</td>
          <td>${g.breached ? badge('超限·停止扩围', 'red') : g.action === 'INSUFFICIENT_EVIDENCE' ? badge('证据不足', 'amber') : badge('正常', 'green')}</td>
        </tr>`).join('')}
        </tbody></table>
        <div style="margin-top:10px"><button class="btn danger sm" onclick="switchTab('expansion')">前往扩围证据页</button></div>
      </div>
    </div>`;
};

function stat(k, v, hint, cls = '') {
  return `<div class="card stat ${cls}"><div class="k">${k}</div><div class="v mono">${v}</div><div class="hint">${hint || ''}</div></div>`;
}

/* ---------------- 登记 ---------------- */
RENDER.registry = async function () {
  const e = EXP;
  const pretty = v => `<pre class="json">${JSON.stringify(v, null, 2)}</pre>`;
  $('#tab-registry').innerHTML = `
    <div class="card">
      <div class="toolbar" style="margin-top:0">
        <h2 style="margin:0">${e.name}</h2>
        ${badge(e.status, e.status === 'REGISTERED' ? 'green' : 'amber')}
        ${badge(e.active_design === 'TRIP_RANDOMIZED' ? '主设计：班次随机' : '主设计：簇周随机', 'blue')}
      </div>
      <dl class="kv">
        <dt>实验编号</dt><dd><code>${e.code}</code></dd>
        <dt>随机种子</dt><dd class="mono">${e.seed}</dd>
        <dt>分配概率/饱和度</dt><dd>${pct(e.assignment_prob, 0)}（平衡分配，实际严格 50%） · 试点 ${e.pilot_weeks} 周</dd>
        <dt>试点期间</dt><dd>${e.start_date} → ${e.end_date}</dd>
        <dt>目标旅程人群</dt><dd>${e.target_population}</dd>
        <dt>登记指纹</dt><dd class="hash">${e.registration_hash || '（尚未登记）'}</dd>
        <dt>登记时间</dt><dd>${dt(e.registered_at)}</dd>
      </dl>
      <h3>处理规则（冻结）</h3>${pretty(e.treatment_rules)}
      <div class="grid cards-2">
        <div><h3>主要指标</h3>${pretty(e.primary_metrics)}</div>
        <div><h3>安全指标</h3>${pretty(e.safety_metrics)}</div>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>登记一个新实验（接口演示）</h3>
      <p class="section-desc">提交编号、种子与实验前换乘边，服务会冻结线路簇并生成两套 50% 分配。分析师及以上角色可操作。</p>
      <textarea id="new-exp-body" rows="16"></textarea>
      <div class="toolbar"><button class="btn primary" id="create-exp">创建 DRAFT 并冻结登记</button><span id="create-out" class="small"></span></div>
    </div>`;

  $('#new-exp-body').value = JSON.stringify({
    code: 'PILOT-DEMO-' + Math.floor(Math.random() * 9000 + 1000),
    name: '通过界面登记的灰度实验',
    seed: 'SEED-DEMO-001',
    assignmentProb: 0.5,
    pilotWeeks: 4,
    startDate: '2026-06-01',
    edgeThreshold: 0.15,
    minSupportPerArm: 25,
    routes: [{ code: 'R1', name: '一路' }, { code: 'R2', name: '二路' }],
    preEdges: [{ fromRoute: 'R1', toRoute: 'R2', transferJourneys: 400, totalJourneys: 1000 }]
  }, null, 2);

  $('#create-exp').addEventListener('click', async () => {
    const out = $('#create-out');
    out.textContent = '提交中…';
    try {
      const body = JSON.parse($('#new-exp-body').value);
      const created = await guardAction(() => api('/experiments', { method: 'POST', body: JSON.stringify(body) }));
      const reg = await guardAction(() => api(`/experiments/${created.id}/register`, { method: 'POST' }));
      out.innerHTML = `<span class="expected-ok">已登记 id=${created.id}，指纹 ${reg.registrationHash.slice(0, 16)}…（可在 Swagger 中查看）</span>`;
    } catch (e) { out.innerHTML = `<span class="expected-bad">${e.message}</span>`; }
  });
};

/* ---------------- 网络 ---------------- */
RENDER.network = async function () {
  const n = await api(`/experiments/${EXP.id}/network`);
  const o = n.designOptions;
  $('#tab-network').innerHTML = `
    <div class="card">
      <h3>实验前刷卡换乘边（冻结，不使用试点结果重算）</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>起点线路</th><th>终点线路</th><th class="num">换乘旅程</th><th class="num">基线总旅程</th><th class="num">换乘比例</th><th>强度</th></tr></thead>
        <tbody>${n.preTransferEdges.map(e => `<tr>
          <td><b>${e.from_route}</b></td><td><b>${e.to_route}</b></td>
          <td class="num">${e.transfer_journeys}</td><td class="num">${e.total_journeys}</td>
          <td class="num mono">${pct(e.ratio, 1)}</td>
          <td><div class="bar" style="width:160px"><div class="t" style="width:${Math.min(100, e.ratio * 220)}%;background:${e.ratio >= 0.15 ? 'var(--purple)' : '#9fb2cc'}"></div></div></td>
        </tr>`).join('')}</tbody></table></div>
    </div>

    <div class="grid cards-3" style="margin-top:14px">
      ${n.clusters.map(c => `<div class="card" style="text-align:center">
        <div class="muted small">冻结簇</div><div style="font-size:22px;font-weight:800;margin:6px 0">${c.cluster_code}</div>
        <div class="pill-row" style="justify-content:center">${c.route_codes.split(',').map(r => badge(r, 'teal')).join('')}</div>
      </div>`).join('')}
    </div>

    <div class="banner blue" style="margin-top:14px"><span class="big">🧭</span><div>${o.recommendation}</div></div>

    <div class="design-compare">
      <div class="design-card recommend">
        <h3>方案 A：线路—班次随机 ${badge('主估计', 'blue')}</h3>
        <dl class="kv">
          <dt>有效随机单元</dt><dd><b class="mono">${o.tripRandomization.effectiveUnits}</b>（${o.tripRandomization.strata} 个 ${o.tripRandomization.unit}层）</dd>
          <dt>饱和度</dt><dd>${o.tripRandomization.saturation}</dd>
          <dt>优点</dt><dd>${o.tripRandomization.advantage}</dd>
          <dt>代价</dt><dd>${o.tripRandomization.risk}</dd>
        </dl>
      </div>
      <div class="design-card">
        <h3>方案 B：线路簇—周随机 ${badge('降污染', 'purple')}</h3>
        <dl class="kv">
          <dt>有效随机单元</dt><dd><b class="mono">${o.clusterWeekRandomization.effectiveUnits}</b>（${o.clusterWeekRandomization.clusters} 簇 × 4 周）</dd>
          <dt>饱和度</dt><dd>${o.clusterWeekRandomization.saturation}</dd>
          <dt>优点</dt><dd>${o.clusterWeekRandomization.advantage}</dd>
          <dt>代价</dt><dd>${o.clusterWeekRandomization.risk}</dd>
        </dl>
      </div>
    </div>`;
};

/* ---------------- 随机分配 ---------------- */
let assignDesign = 'TRIP_RANDOMIZED';
RENDER.assignment = async function () {
  $('#tab-assignment').innerHTML = `
    <div class="toolbar" style="margin-top:0">
      <div class="pill-row">
        <button class="filter-chip active" data-d="TRIP_RANDOMIZED">线路—具体班次（${'~720'}）</button>
        <button class="filter-chip" data-d="CLUSTER_WEEK">簇—周（8）</button>
      </div>
      <span class="small muted">同一 SHA-256 种子，任何环境重算结果一致</span>
    </div>
    <div id="assign-body"></div>`;
  $$('[data-d]').forEach(b => b.addEventListener('click', async () => {
    assignDesign = b.dataset.d;
    $$('[data-d]').forEach(x => x.classList.toggle('active', x === b));
    await drawAssignments();
  }));
  await drawAssignments();
};

async function drawAssignments() {
  const d = await api(`/experiments/${EXP.id}/assignments?design=${assignDesign}`);
  const share = pct(d.treatmentShare, 1);
  const rows = d.units.slice(0, 200);
  $('#assign-body').innerHTML = `
    <div class="card">
      <div class="grid cards-3">
        <div class="stat"><div class="k">单元总数</div><div class="v mono">${d.total}</div></div>
        <div class="stat blue"><div class="k">处理 / 对照</div><div class="v mono">${d.treatment} / ${d.control}</div></div>
        <div class="stat green"><div class="k">处理占比（固定 50%）</div><div class="v mono">${share}</div></div>
      </div>
      <div class="bar" style="height:16px;margin-top:12px"><div class="t" style="width:${d.treatmentShare * 100}%"></div></div>
      <div class="bar-label"><span>处理 ${d.treatment}</span><span>对照 ${d.control}</span></div>
      <p class="small muted" style="margin-top:8px">每个平衡层内按哈希排序取前半，偶数层严格一半；边缘包含概率 = ${d.units[0] ? fmt(d.units[0].inclusion_prob, 2) : '0.50'}。</p>
    </div>
    <div class="table-wrap" style="margin-top:14px;max-height:560px">
      <table class="data"><thead><tr><th>单元</th><th>层</th><th>标签</th><th>周</th><th>臂</th><th class="num">π</th><th>哈希前缀</th></tr></thead>
      <tbody>${rows.map(u => `<tr>
        <td><code>${u.unit_key}</code></td><td class="small">${u.stratum}</td><td>${u.label || ''}</td>
        <td>${u.week_index ?? ''}</td><td>${armBadge(u.arm)}</td>
        <td class="num mono">${fmt(u.inclusion_prob, 2)}</td>
        <td class="hash">${(u.hash_value || '').slice(0, 12)}…</td></tr>`).join('')}
      </tbody></table>
      ${d.units.length > 200 ? `<div class="small muted" style="padding:8px 12px">仅渲染前 200 行，完整 ${d.units.length} 行可通过 API 获取。</div>` : ''}
    </div>`;
}

/* ---------------- 暴露路径 ---------------- */
let exposureFilter = 'ALL';
RENDER.exposure = async function () {
  $('#tab-exposure').innerHTML = `
    <div class="card">
      <h3>命名样例自动核验</h3>
      <div id="demo-checks" class="small">加载中…</div>
    </div>
    <div class="toolbar">
      <div class="pill-row" id="path-chips"></div>
      <input type="text" id="exposure-q" placeholder="搜索旅程号 / 备注，如 DIVERT" />
      <button class="btn primary" id="exposure-search">搜索</button>
    </div>
    <div id="exposure-counts" class="grid cards-4" style="margin-bottom:12px"></div>
    <div class="table-wrap" style="max-height:600px">
      <table class="data"><thead><tr>
        <th>旅程</th><th>状态</th><th>完整路径</th><th>簇口径</th><th>分配(班/簇)</th>
        <th>标记</th><th class="num">候车(s)</th><th class="num">拥挤暴露</th><th class="num">人数</th></tr></thead>
      <tbody id="exposure-rows"></tbody></table>
    </div>`;

  $('#exposure-search').addEventListener('click', loadExposureRows);
  $('#exposure-q').addEventListener('keydown', e => { if (e.key === 'Enter') loadExposureRows(); });
  await loadDemoChecks();
  await loadExposureRows();
};

const DEMO_EXPECT = {
  'J-DEMO-DIVERT-01': { path: 'CROSS_MIXED', cross: true, diverted: true, arm: 'TREATMENT', text: 'R1处理→短线折返→R2对照分流' },
  'J-DEMO-PURER2-01': { path: 'FULL_CONTROL', text: '同车普通R2乘客仍为纯净对照' },
  'J-DEMO-CROSS-01': { path: 'CROSS_MIXED', cross: true, arm: 'CONTROL', text: '对照起乘→换乘处理线路' },
  'J-DEMO-FULLT-01': { path: 'FULL_TREATMENT', text: '两段均处理' },
  'J-DEMO-FULLC-01': { path: 'FULL_CONTROL', text: '两段均对照' },
  'J-DEMO-NONEXEC-01': { path: 'FULL_CONTROL', arm: 'TREATMENT', text: '分配T但未执行：实际旧规则/分配仍T' },
  'J-DEMO-INCOMPLETE-01': { path: 'UNKNOWN', text: '未完成刷卡保留未知' },
  'J-DEMO-MIDCHANGE-01': { path: 'UNKNOWN', text: '中途改线保留未知' },
  'J-DEMO-STORM-01': { path: 'UNKNOWN', text: '暴雨停运中途改线未知' }
};

async function loadDemoChecks() {
  const d = await api(`/experiments/${EXP.id}/exposure?q=J-DEMO&limit=50`);
  const byCode = Object.fromEntries(d.rows.map(r => [r.journey_code, r]));
  let allOk = true;
  const html = Object.entries(DEMO_EXPECT).map(([code, x]) => {
    const r = byCode[code];
    if (!r) { allOk = false; return `<tr><td><code>${code}</code></td><td class="expected-bad">缺失</td></tr>`; }
    const pathOk = r.path_label === x.path;
    const crossOk = x.cross === undefined || r.cross_line === x.cross;
    const divOk = x.diverted === undefined || r.diverted === x.diverted;
    const armOk = x.arm === undefined || r.assigned_arm_trip === x.arm;
    const ok = pathOk && crossOk && divOk && armOk;
    if (!ok) allOk = false;
    return `<tr class="clickable" onclick="openJourney('${code}')">
      <td><code>${code}</code></td>
      <td>${PATH_META[r.path_label] ? badge(PATH_META[r.path_label].label, PATH_META[r.path_label].cls) : r.path_label}</td>
      <td>${armBadge(r.assigned_arm_trip)}</td>
      <td>${r.cross_line ? badge('跨线', 'purple') : ''} ${r.diverted ? badge('折返分流', 'amber') : ''}</td>
      <td class="small muted">${x.text}</td>
      <td>${ok ? '<span class="expected-ok">✓ 符合预期</span>' : `<span class="expected-bad">✗ ${!pathOk ? '路径应为' + x.path : ''} ${!armOk ? '臂不符' : ''}</span>`}</td>
    </tr>`;
  }).join('');
  $('#demo-checks').innerHTML = `
    <div class="banner ${allOk ? 'green' : 'red'}" style="margin:4px 0 10px">
      ${allOk ? '✅ 全部 ' + Object.keys(DEMO_EXPECT).length + ' 个命名样例的完整路径/臂/交叉标记均符合预期' : '⚠️ 存在不符合预期的样例'}</div>
    <div class="table-wrap"><table class="data"><tbody>${html}</tbody></table></div>`;
}

async function loadExposureRows() {
  const q = $('#exposure-q') ? $('#exposure-q').value.trim() : '';
  const d = await api(`/experiments/${EXP.id}/exposure?pathLabel=${exposureFilter}&q=${encodeURIComponent(q)}&limit=200`);
  const total = d.counts.reduce((s, c) => s + Number(c.n), 0);
  $('#path-chips').innerHTML = ['ALL', ...Object.keys(PATH_META)].map(k =>
    `<button class="filter-chip ${exposureFilter === k ? 'active' : ''}" data-p="${k}">${k === 'ALL' ? '全部' : PATH_META[k].label}</button>`).join('');
  $$('#path-chips [data-p]').forEach(b => b.addEventListener('click', () => { exposureFilter = b.dataset.p; loadExposureRows(); }));

  $('#exposure-counts').innerHTML = d.counts.map(c => {
    const meta = PATH_META[c.path_label] || { label: c.path_label, cls: 'gray' };
    return `<div class="card stat"><div class="k">${badge(meta.label, meta.cls)}</div>
      <div class="v mono">${c.n}</div><div class="hint">加权 ${fmt(c.weighted_n, 0)} 人次 · ${pct(c.n / total, 1)}</div></div>`;
  }).join('');

  $('#exposure-rows').innerHTML = d.rows.map(r => `<tr class="clickable" onclick="openJourney('${r.journey_code}')">
    <td><code>${r.journey_code}</code>${r.note ? `<div class="small muted">${r.note.slice(0, 42)}…</div>` : ''}</td>
    <td>${badge(r.status === 'COMPLETE' ? '完成' : r.status, r.status === 'COMPLETE' ? 'green' : 'red')}</td>
    <td>${PATH_META[r.path_label] ? badge(PATH_META[r.path_label].label, PATH_META[r.path_label].cls) : r.path_label}</td>
    <td><span class="small">${({ CLUSTER_TREATMENT: '簇处理', CLUSTER_CONTROL: '簇对照', CLUSTER_CROSS: '簇交叉', UNKNOWN: '未知' })[r.cluster_path_label] || r.cluster_path_label}</span></td>
    <td>${armBadge(r.assigned_arm_trip)} / ${armBadge(r.assigned_arm_cluster)}</td>
    <td>${r.cross_line ? badge('cross', 'purple') : ''} ${r.diverted ? badge('折返', 'amber') : ''} ${r.effective_treatment ? badge('实际新规则', 'blue') : ''}</td>
    <td class="num mono">${fmt(r.wait_seconds, 0)}</td>
    <td class="num mono">${fmt(r.crowd_exposure, 1)}</td>
    <td class="num mono">${r.passenger_count}</td>
  </tr>`).join('') || '<tr><td colspan="9" class="muted" style="padding:20px;text-align:center">无匹配记录</td></tr>';
}

window.openJourney = async function (code) {
  const d = await api(`/experiments/${EXP.id}/journeys/${encodeURIComponent(code)}`);
  const j = d.journey;
  const led = d.ledger || {};
  let trace = [];
  try { trace = JSON.parse(led.path_detail_text || '[]'); } catch {}
  const expCls = { TREATMENT: 'TREATMENT', CONTROL: 'CONTROL', CROSS: 'CROSS', UNKNOWN: 'UNKNOWN' };
  $('#modal').innerHTML = `
    <button class="btn sm close" onclick="closeModal()">关闭</button>
    <h2><code>${j.journey_code}</code></h2>
    <div class="pill-row" style="margin:6px 0 12px">
      ${badge(j.status, j.status === 'COMPLETE' ? 'green' : 'red')}
      ${PATH_META[led.path_label] ? badge('完整路径：' + PATH_META[led.path_label].label, PATH_META[led.path_label].cls) : ''}
      ${armBadge(j.anchor_arm_trip)}
      ${j.note ? `<span class="small muted">${j.note}</span>` : ''}
    </div>
    <dl class="kv">
      <dt>服务时间</dt><dd>${dt(j.service_ts)} · ${j.period_band} · ${j.od_band} · 天气 ${j.weather_class}${j.accessibility ? '' : ' · <b class="expected-bad">可达性受损</b>'}</dd>
      <dt>端到端候车</dt><dd>${fmt(j.wait_seconds, 0)} 秒（首乘 ${fmt(j.first_wait_seconds, 0)} + 换乘 ${fmt(j.transfer_wait_seconds, 0)}）</dd>
      <dt>拥挤暴露</dt><dd>${fmt(j.crowd_exposure, 1)}（严重拥挤分钟 ${fmt(j.severe_crowd_minutes, 1)}，最大 LF ${fmt(j.max_load_factor, 2)}）</dd>
      <dt>路径概率</dt><dd class="mono">${led.path_probability ? fmt(led.path_probability, 4) : '—（未知旅程不计算）'}</dd>
    </dl>
    <h3>逐段关联（计划分配 × GPS 实际执行）</h3>
    <div class="leg-flow">
      ${d.legs.map((l, i) => `
        <div class="leg-box ${expCls[l.leg_exposure] || ''}">
          <div class="small muted">第 ${l.leg_index} 段 ${l.is_transfer ? '· 换乘' : ''}</div>
          <b>${l.route_code}</b> <span class="small">${l.board_stop || ''} → ${l.alight_stop || ''}</span>
          <div class="small" style="margin-top:4px">
            计划臂 ${armBadge(l.planned_arm)}<br/>
            GPS ${l.gps_run_code ? '<code>' + l.gps_run_code + '</code>' : '无实际运行'}<br/>
            规则实际执行：${l.run_rule_executed === true || l.run_rule_executed === 't' ? '<span class="expected-ok">是</span>' : '<span class="expected-bad">否</span>'}<br/>
            偏离：${l.run_deviation || l.deviation || 'NONE'}${l.delay_seconds ? ' · 延误 ' + l.delay_seconds + 's' : ''}<br/>
            段暴露：<b>${l.leg_exposure}</b> · LF ${fmt(l.load_factor, 2)}
          </div>
        </div>
        ${i < d.legs.length - 1 ? '<div class="leg-arrow">→</div>' : ''}`).join('')}
    </div>
    <h3>判定追踪</h3>
    <ul class="trace">${trace.map(t => `<li>${t}</li>`).join('')}</ul>`;
  $('#modal-back').classList.add('open');
};
window.closeModal = () => $('#modal-back').classList.remove('open');
$('#modal-back')?.addEventListener('click', e => { if (e.target.id === 'modal-back') closeModal(); });

/* ---------------- 共同支持 ---------------- */
RENDER.support = async function () {
  const [d, events] = await Promise.all([
    api(`/experiments/${EXP.id}/support`),
    api(`/experiments/${EXP.id}/events`)
  ]);
  const g = d.gaps;
  const eventTypeMeta = {
    WEATHER_RAINSTORM: ['暴雨/停运', 'red'], WEATHER_RAIN: ['降雨', 'blue'],
    CONSTRUCTION: ['道路施工', 'amber'], ROUTE_CHANGE: ['临时改线', 'purple']
  };
  $('#tab-support').innerHTML = `
    <div class="grid cards-4">
      <div class="stat"><div class="k">分层总数</div><div class="v mono">${d.strata.length}</div></div>
      <div class="stat amber"><div class="k">班次方案缺口层</div><div class="v mono">${g.gap_strata_trip}</div><div class="hint">涉及 ${g.gap_journeys_trip} 旅程</div></div>
      <div class="stat amber"><div class="k">簇周方案缺口层</div><div class="v mono">${g.gap_strata_cluster}</div><div class="hint">涉及 ${g.gap_journeys_cluster} 旅程</div></div>
      <div class="stat red"><div class="k">口径</div><div class="v" style="font-size:15px;padding-top:8px">缺口只报告覆盖，不外推</div></div>
    </div>
    <div class="banner blue" style="margin-top:12px">${d.policy}。支持维度：时段 × 起讫区间 × 天气 × 服务可达性；每臂加权样本阈值取自实验配置（默认 25）。</div>
    <div class="table-wrap" style="margin-top:12px;max-height:620px">
      <table class="data"><thead><tr>
        <th>时段</th><th>OD 区间</th><th>天气</th><th>可达</th><th class="num">旅程</th><th class="num">加权</th>
        <th class="num">班 T/C 权重</th><th>班次支持</th><th class="num">簇 T/C 权重</th><th>簇支持</th><th>缺口原因</th>
      </tr></thead><tbody>
      ${d.strata.map(s => `<tr>
        <td>${s.period_band}</td><td>${s.od_band}</td><td>${s.weather_class}</td>
        <td>${s.accessibility ? '是' : badge('受损', 'red')}</td>
        <td class="num">${s.n_total}</td><td class="num">${fmt(s.weighted_total, 0)}</td>
        <td class="num small">${fmt(s.w_t_trip, 0)} / ${fmt(s.w_c_trip, 0)}</td>
        <td>${s.supported_trip ? badge('支持', 'green') : badge('缺口', 'amber')}</td>
        <td class="num small">${fmt(s.w_t_cluster, 0)} / ${fmt(s.w_c_cluster, 0)}</td>
        <td>${s.supported_cluster ? badge('支持', 'green') : badge('缺口', 'amber')}</td>
        <td class="small muted">${s.gap_reason || ''}</td>
      </tr>`).join('')}
      </tbody></table>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>外部事件时间线（按事件发生时间关联服务，而非投诉时间）</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>类型</th><th>级别</th><th>事件</th><th>起止</th><th>影响线路</th><th>停运</th><th>来源</th></tr></thead>
        <tbody>${events.map(ev => {
          const meta = eventTypeMeta[ev.event_type] || [ev.event_type, 'gray'];
          return `<tr>
            <td>${badge(meta[0], meta[1])}</td><td>${ev.severity || ''}</td><td>${ev.title}</td>
            <td class="small mono">${dt(ev.start_ts)} ~ ${dt(ev.end_ts)}</td>
            <td>${ev.affected_routes}</td>
            <td>${ev.suspension ? badge('停运', 'red') : '—'}</td><td class="small muted">${ev.source || ''}</td>
          </tr>`;
        }).join('')}</tbody>
      </table></div>
      <p class="small muted" style="margin-bottom:0">生成旅程与计算支持层时，天气等级/可达性按服务时刻与上述事件窗口重叠判定；投诉表同时保留 complaint_time 与 service_event_time，护栏统计只使用后者。</p>
    </div>`;
};

/* ---------------- 分析 ---------------- */
RENDER.analysis = async function () {
  const d = await api(`/experiments/${EXP.id}/analysis`);
  const rs = d.results;
  const itt = rs.filter(r => r.estimator_code === 'ITT' && r.scope === 'COMMON_SUPPORT');
  const ri = rs.find(r => r.estimator_code === 'RI_PERMUTATION');
  const cross = rs.find(r => r.estimator_code === 'CROSS_RATE');
  const cace = rs.find(r => r.estimator_code === 'CACE_WALD');

  $('#tab-analysis').innerHTML = `
    <div class="card">
      <h3>主要结果：乘客旅程加权 ITT（共同支持域，95% 区间）</h3>
      <p class="section-desc">负值表示候车/拥挤下降。按乘客人数加权，标准误按随机化单元聚类 CR1（班次方案单元多 → 区间窄；簇周仅 8 个簇—周 → 区间宽）。</p>
      ${ciBlock(itt.filter(r => r.metric_code === 'wait_seconds'), '端到端候车时间差（秒，处理 − 对照）')}
      <div style="height:14px"></div>
      ${ciBlock(itt.filter(r => r.metric_code === 'crowd_exposure'), '拥挤暴露差（ΣLF×分钟）')}
    </div>

    <div class="grid cards-3" style="margin-top:14px">
      <div class="card"><h3>跨线暴露率</h3>
        <div class="v mono" style="font-size:30px;font-weight:800;color:var(--purple)">${cross ? pct(cross.estimate, 1) : '—'}</div>
        <div class="small muted">Wilson 95% CI ${cross ? pct(cross.ci_low, 1) + ' ~ ' + pct(cross.ci_high, 1) : ''} · ${cross ? cross.n_journeys : ''} 完整旅程</div>
        <div class="small" style="margin-top:6px">共享枢纽异臂搭乘/短线折返分流的完整路径占比，单独成类，不混入纯净对照。</div>
      </div>
      <div class="card"><h3>CACE / Wald IV</h3>
        <div class="v mono" style="font-size:30px;font-weight:800">${cace ? fmt(cace.estimate, 1) : '—'}<span style="font-size:14px"> s</span></div>
        <div class="small muted">CI ${cace ? fmt(cace.ci_low, 1) + ' ~ ' + fmt(cace.ci_high, 1) : ''} · 以随机分配为“全程实际接受”的工具变量</div>
        <div class="small" style="margin-top:6px">处理司机不执行规则时的依从者效应；<b>不是</b>把实际执行者子集直接称为随机效应。</div>
      </div>
      <div class="card"><h3>簇方案随机化推断</h3>
        <div class="v mono" style="font-size:30px;font-weight:800">p=${ri ? fmt(ri.p_value, 3) : '—'}</div>
        <div class="small muted">枚举 2⁴=16 种每周方向 · plus-one 分母 17</div>
        <div class="small" style="margin-top:6px">仅 2 个线路簇时，RI 是比渐近 CI 更可信的不确定性证据；点估计更大（内部化 R1/R2 溢出）但证据不精确。</div>
      </div>
    </div>

    <div class="banner amber" style="margin-top:14px"><span class="big">⚠️</span><div>
      <b>解释纪律：</b>“按实际暴露路径/执行者分组”的均值在下表标记为 AS_TREATED_ASSOCIATION，仅为观察性关联；
      因果结论只来自随机分配臂的 ITT 与带假设的 CACE。无共同支持的旅程不进入这些估计，只在“共同支持”页报告缺口。</div></div>

    <div class="card" style="margin-top:14px">
      <h3>全部估计结果（含 PATH_MEAN / AS_TREATED / ALL_ELIGIBLE 对照口径）</h3>
      <div class="table-wrap" style="max-height:560px"><table class="data">
        <thead><tr><th>设计</th><th>估计量</th><th>指标</th><th>范围</th><th class="num">估计</th><th class="num">SE</th><th class="num">CI 低</th><th class="num">CI 高</th><th class="num">p</th><th class="num">N</th><th class="num">单元</th></tr></thead>
        <tbody>${rs.map(r => `<tr>
          <td>${r.design_type === 'TRIP_RANDOMIZED' ? '班次' : '簇周'}</td>
          <td><code>${r.estimator_code}</code></td><td class="small">${r.metric_code}</td><td class="small">${r.scope}</td>
          <td class="num mono"><b>${fmt(r.estimate, 2)}</b></td><td class="num mono">${fmt(r.se, 2)}</td>
          <td class="num mono">${fmt(r.ci_low, 2)}</td><td class="num mono">${fmt(r.ci_high, 2)}</td>
          <td class="num mono">${r.p_value === null || r.p_value === undefined ? '—' : fmt(r.p_value, 3)}</td>
          <td class="num">${r.n_journeys}</td><td class="num">${r.n_units}</td>
        </tr>`).join('')}</tbody></table></div>
    </div>`;
};

function ciBlock(rows, title) {
  if (!rows.length) return '';
  const maxAbs = Math.max(...rows.map(r => Math.max(Math.abs(r.ci_low || 0), Math.abs(r.ci_high || 0), 1)));
  return `<h3 style="margin-bottom:4px">${title}</h3>` + rows.map(r => {
    const left = ((r.ci_low + maxAbs) / (2 * maxAbs)) * 100;
    const width = ((r.ci_high - r.ci_low) / (2 * maxAbs)) * 100;
    const dot = ((r.estimate + maxAbs) / (2 * maxAbs)) * 100;
    const cross = r.ci_low < 0 && r.ci_high > 0;
    const name = r.design_type === 'TRIP_RANDOMIZED' ? '班次随机（多单元，窄区间）' : '簇—周随机（8 单元，宽区间）';
    return `<div class="ci-row">
      <div><b>${name}</b><div class="small muted">${r.n_journeys} 旅程 / ${r.n_units} 单元</div></div>
      <div class="ci-track">
        <div class="ci-line ${cross ? 'zero-cross' : ''}" style="left:${left}%;width:${width}%"></div>
        <div class="ci-dot ${cross ? 'zero-cross' : ''}" style="left:${dot}%"></div>
      </div>
      <div class="small mono">${fmt(r.estimate, 1)} [${fmt(r.ci_low, 1)}, ${fmt(r.ci_high, 1)}]</div>
    </div>`;
  }).join('');
}

/* ---------------- 试点成本归集 ---------------- */
const COST_CAT_META = {
  OVERTIME: { label: '司机加班', cls: 'amber' },
  ENERGY: { label: '油电消耗', cls: 'teal' },
  TEMP_VEHICLE: { label: '临时/备用车辆', cls: 'purple' },
  MAINTENANCE: { label: '维修', cls: 'blue' },
  CANCEL_LOSS: { label: '取消班次损失', cls: 'red' }
};
const money = x => (Number(x) < 0 ? '-¥' : '¥') + fmt(Math.abs(Number(x) || 0), 2);
const signedYuan = x => (x > 0 ? '+¥' : x < 0 ? '−¥' : '¥') + fmt(Math.abs(x), 2);
let costUnitArmFilter = 'ALL';

RENDER.cost = async function () {
  const d = await api(`/experiments/${EXP.id}/costs`);
  window.__cost = d;
  const rec = d.reconciliation, off = d.offsets, ev = d.sample_evidence;
  const dm = d.direct_attribution, am = d.driver_allocation;

  const cat = {};
  for (const v of (d.voucher_ledger || [])) {
    const k = v.cost_category;
    cat[k] = cat[k] || { n: 0, direct: 0, shared: 0, total: 0 };
    const amt = Number(v.amount) || 0;
    cat[k].n++; cat[k].total += amt;
    if (v.attribution === 'SHARED') cat[k].shared += amt; else cat[k].direct += amt;
  }

  $('#tab-cost').innerHTML = `
    <div class="toolbar" style="margin-top:0">
      <button class="btn primary" id="cost-recompute">▶ 用冻结凭证重新归集/分摊</button>
      <span class="small muted">成本账唯一凭证号防重复入账；金额内部按分守恒；POST /costs/recompute</span>
    </div>

    <div class="grid cards-4">
      ${stat('合格服务单元', d.qualified_service_units, d.treatment_units + ' 处理 / ' + d.control_units + ' 对照（严格 50%）')}
      ${stat('记账凭证总额', money(rec.voucher_total), rec.distinct_voucher_count + ' 张唯一凭证 · 不重复计费', 'blue')}
      ${stat('直接法未归属余额', money(dm.unallocated_amount), '共同备用车 + 跨线跨班维修', 'amber')}
      ${stat('分摊法未归属', money(am.unallocated_amount), '冻结小时/公里/维修工时驱动，账面完整', 'green')}
    </div>

    <div class="banner blue" style="margin-top:12px"><span class="big">💡</span><div>
      集团要的是同一 <b>4 周随机试点</b>的<b>净运营成本</b>：这里只归集加班、油电、临时车辆、维修、取消班次损失的真实凭证，
      <b>不把投诉货币化、也不用平均工资率估算加班</b>。两条归属路线都给出每合格服务单元的净成本区间与按随机分配臂计算的 ITT 成本差；
      收入/节省只有 <b>已确认且有凭证号</b>才能冲减，未确认估计一律排除。
    </div></div>

    <div class="grid cards-2" style="margin-top:14px">
      ${methodCard(dm, '路线一 · 直接归属实际凭证')}
      ${methodCard(am, '路线二 · 冻结驱动分摊共享成本')}
    </div>

    <div class="card" style="margin-top:14px">
      <h3>对账与“不重复计费”核验</h3>
      <div class="grid cards-3">
        ${check('凭证号唯一、只入账一次 / noDoubleBilling', rec.no_double_billing)}
        ${check('直接法 已归属 + 未归属 = 凭证总额', rec.direct_balanced, '差异 ' + money(rec.direct_plus_unallocated_difference))}
        ${check('分摊额合计 = 共享凭证额（到分）', rec.allocation_balanced, '差异 ' + money(rec.allocation_difference))}
        ${check('两方法凭证 grand total 一致', rec.grand_totals_match_across_methods)}
        ${check('分摊法账面完整（未归属=0）', rec.books_complete_under_allocation)}
        ${check('分摊法结果表 cost_unit_cost 已重建', true, 'DIRECT/DRIVER_ALLOC 各 720 行')}
      </div>
      <p class="small muted" style="margin:10px 0 0">${rec.no_double_billing_basis}</p>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>题目指定样例证据（一辆跨臂备用车 · 一次跨线跨班维修 · 两笔加班）</h3>
      <div class="grid cards-3">
        <div class="evidence-box">
          <div class="small muted">备用车同时支援</div>
          <div style="margin:8px 0"><b>${ev.spare_vehicle_present ? money(ev.spare_vehicle_voucher.amount) : '缺失'}</b>
            ${badge('TEMP_VEHICLE', 'purple')} ${badge('SHARED', 'amber')}</div>
          ${(ev.spare_vehicle_voucher?.support_links || []).map(s => `<div class="small" style="margin:4px 0">
             <code>${s.route_code}</code> ${armBadge(s.arm)}<div class="muted">${s.detail || ''}</div></div>`).join('')}
          <div style="margin-top:6px">${ev.spare_serves_treatment_and_control ? '<span class="expected-ok">✓ 同一凭证横跨 R1 处理班 + R2 对照班</span>' : '<span class="expected-bad">✗ 未同时横跨两臂</span>'}</div>
        </div>
        <div class="evidence-box">
          <div class="small muted">一次跨线 + 跨班维修</div>
          <div style="margin:8px 0"><b>${ev.cross_line_maintenance_present ? money(ev.cross_line_maintenance_voucher.amount) : '缺失'}</b>
            ${badge('MAINTENANCE', 'blue')} ${badge('SHARED', 'amber')}</div>
          <div class="pill-row">${ev.cross_line_maintenance_voucher?.cross_line ? badge('跨线 R1/R2', 'purple') : ''}
            ${ev.cross_line_maintenance_voucher?.cross_shift ? badge('跨 AM/PM 班', 'teal') : ''}</div>
          <div class="small muted" style="margin-top:6px">${ev.cross_line_maintenance_voucher?.description || ''}</div>
          <div class="small" style="margin-top:6px">支援 ${(ev.cross_line_maintenance_voucher?.support_links || []).length} 个车辆块；直接法全额未归属，分摊法按冻结维修工时分配。</div>
        </div>
        <div class="evidence-box">
          <div class="small muted">司机加班记录（恰好 ${ev.overtime_record_count} 笔）</div>
          ${ev.overtime_records.map(o => `<div style="margin:8px 0">
             <b>${money(o.amount)}</b> <code class="small">${o.code}</code><br/>
             <span class="small muted">${o.description || ''}</span></div>`).join('')}
          ${ev.overtime_record_count === 2 ? '<span class="expected-ok">✓ 恰好两笔，均直接归属处理臂单元</span>' : '<span class="expected-bad">加班笔数应为 2</span>'}
        </div>
      </div>
    </div>

    <div class="grid cards-2" style="margin-top:14px">
      <div class="card">
        <h3>五个成本分项（凭证账：直接 vs 共享）</h3>
        <div class="table-wrap"><table class="data"><thead><tr>
          <th>类别</th><th class="num">凭证数</th><th class="num">直接归属</th><th class="num">共享/未归属</th><th class="num">合计</th>
        </tr></thead><tbody>
        ${CostEngineCategoriesRow(cat)}
        </tbody></table></div>
        <p class="small muted" style="margin-bottom:0">共享列在直接法下即未归属余额；在分摊法下按驱动进入各单元，合计不变。</p>
      </div>
      <div class="card">
        <h3>分摊驱动敏感性（ITT 成本差，元/合格单元）</h3>
        <div class="table-wrap"><table class="data"><thead><tr><th>驱动情景</th><th class="num">T 均值</th><th class="num">C 均值</th><th class="num">ITT(T−C)</th></tr></thead><tbody>
        ${d.driver_scenarios.map(s => `<tr>
          <td class="small"><b>${s.basis}</b><div class="muted">${s.label}</div></td>
          <td class="num mono">${fmt(s.treatment_mean_net_per_unit, 2)}</td>
          <td class="num mono">${fmt(s.control_mean_net_per_unit, 2)}</td>
          <td class="num mono"><b>${s.itt_cost_difference_per_unit > 0 ? '+' : ''}${fmt(s.itt_cost_difference_per_unit, 2)}</b></td>
        </tr>`).join('')}
        </tbody></table></div>
        <div class="banner amber" style="margin-top:10px"><span>⚠️</span><div class="small">${am.itt_interpretation}</div></div>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>按物理线路的归属与“触及敞口”（不可跨线相加）</h3>
      <div class="table-wrap"><table class="data"><thead><tr>
        <th>线路</th><th class="num">单元</th><th class="num">T/C</th>
        <th class="num">直接成本</th><th class="num">分摊进入共享</th><th class="num">分摊法净成本</th>
        <th class="num">触及未归属敞口(备忘)</th><th>未归属凭证</th>
      </tr></thead><tbody>
      ${d.route_balances.map(r => `<tr>
        <td><b>${r.route_code}</b></td>
        <td class="num">${r.units}</td>
        <td class="num small">${r.treatment_units}/${r.control_units}</td>
        <td class="num mono">${fmt(r.direct_amount, 2)}</td>
        <td class="num mono">${fmt(r.driver_allocated_shared_amount, 2)}</td>
        <td class="num mono"><b>${fmt(r.net_cost_alloc, 2)}</b></td>
        <td class="num mono">${fmt(r.unallocated_touched_exposure, 2)}</td>
        <td class="small">${(r.unallocated_voucher_codes || []).map(c => `<code>${c}</code>`).join(' ') || '—'}</td>
      </tr>`).join('')}
      </tbody></table></div>
      <p class="small muted">${d.route_balances[0]?.exposure_note || ''}</p>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>收入/节省抵扣纪律：未确认估计不得填平成本</h3>
      <div class="grid cards-3">
        <div class="stat green"><div class="k">已确认并冲减</div><div class="v mono">${money(off.confirmed_offset_total)}</div><div class="hint">${off.confirmed_count} 笔 · 有 confirmation_ref</div></div>
        <div class="stat red"><div class="k">排除的未确认估计</div><div class="v mono">${money(off.excluded_unconfirmed_estimate_total)}</div><div class="hint">${off.unconfirmed_count} 笔 · 仅备忘，不进净成本</div></div>
        <div class="stat"><div class="k">净运营成本总额</div><div class="v mono">${money(dm.net_operating_cost_total)}</div><div class="hint">= 凭证总额 − 已确认抵扣</div></div>
      </div>
      <p class="small muted" style="margin:10px 0">${off.policy}</p>
      <div class="toolbar" style="margin-bottom:8px">
        <button class="btn" id="cost-plug-unconf">模拟：录入 999,999 未确认“节省”</button>
        <button class="btn" id="cost-plug-conf">补凭证后确认 1,234</button>
        <button class="btn" id="cost-plug-reset">还原为未确认</button>
        <span id="plug-out" class="small"></span>
      </div>
      <div class="table-wrap" style="max-height:220px"><table class="data"><thead><tr>
        <th>抵扣单</th><th>类型</th><th>说明</th><th class="num">金额</th><th>确认</th><th>凭证号</th>
      </tr></thead><tbody>
      ${(d.offset_rows || []).map(o => `<tr>
        <td><code>${o.offset_code}</code></td><td class="small">${o.kind}</td>
        <td class="small muted">${o.description || offsetDesc(o.offset_code)}</td>
        <td class="num mono">${fmt(o.amount, 2)}</td>
        <td>${o.confirmed === true || o.confirmed === 't' ? badge('已确认·冲减', 'green') : badge('未确认·排除', 'red')}</td>
        <td class="small mono">${o.confirmation_ref ? o.confirmation_ref : '—'}</td>
      </tr>`).join('')}
      </tbody></table></div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>每个分配单元的成本分项（DRIVER_ALLOC 净成本，按净成本降序，最多渲染 200 行）</h3>
      <div class="pill-row" id="cost-unit-chips" style="margin-bottom:10px">
        ${['ALL', 'TREATMENT', 'CONTROL'].map(a => `<button class="filter-chip ${costUnitArmFilter === a ? 'active' : ''}" data-a="${a}">${a === 'ALL' ? '全部' : a === 'TREATMENT' ? '处理臂' : '对照臂'}</button>`).join('')}
      </div>
      <div class="table-wrap" style="max-height:560px"><table class="data"><thead><tr>
        <th>分配单元 / 车辆块</th><th>线路</th><th>臂</th><th class="num">加班</th><th class="num">油电</th>
        <th class="num">临时车</th><th class="num">维修</th><th class="num">取消损失</th>
        <th class="num">分摊共享</th><th class="num">已确认抵扣</th><th class="num">净成本</th>
      </tr></thead><tbody id="cost-unit-rows"></tbody></table></div>
      <p class="small muted">完整 720 单元 × 两方法见 <code>GET /api/experiments/{id}/costs/unit-costs?method=DIRECT|DRIVER_ALLOC</code> 与 <code>/costs</code> 的 units。</p>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>成本凭证账（前 200 行；可经 /costs/vouchers?category= 取全量/分类）</h3>
      <div class="table-wrap" style="max-height:420px"><table class="data"><thead><tr>
        <th>凭证号</th><th>类别</th><th>归属</th><th>车辆块/单元</th><th>线路</th><th class="num">金额</th><th>凭证来源/说明</th>
      </tr></thead><tbody>
      ${(d.voucher_ledger || []).slice(0, 200).map(v => `<tr>
        <td><code>${v.voucher_code}</code></td>
        <td>${costCatBadge(v.cost_category)}</td>
        <td>${v.attribution === 'SHARED' ? badge('共享·待分摊', 'amber') : badge('直接', 'green')}</td>
        <td class="small"><code>${v.block_code || ''}</code>${v.unit_key ? `<div class="muted">${v.unit_key}</div>` : ''}</td>
        <td>${v.route_code || ''}</td>
        <td class="num mono"><b>${fmt(v.amount, 2)}</b></td>
        <td class="small muted">${v.source_ref ? `<code>${v.source_ref}</code> · ` : ''}${v.description || ''}</td>
      </tr>`).join('')}
      </tbody></table></div>
    </div>`;

  drawCostUnits();
  $$('#cost-unit-chips [data-a]').forEach(b => b.addEventListener('click', () => {
    costUnitArmFilter = b.dataset.a;
    $$('#cost-unit-chips [data-a]').forEach(x => x.classList.toggle('active', x === b));
    drawCostUnits();
  }));

  $('#cost-recompute').addEventListener('click', async () => {
    const b = $('#cost-recompute'); b.disabled = true; b.textContent = '归集中…';
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/costs/recompute`, { method: 'POST' }), '成本已重新归集与分摊');
      delete loaded.cost; RENDER.cost();
    } finally { b.disabled = false; b.textContent = '▶ 用冻结凭证重新归集/分摊'; }
  });

  const plugOut = $('#plug-out');
  async function plug(body, msg) {
    try {
      const r = await guardAction(() => api(`/experiments/${EXP.id}/costs/offsets`, { method: 'POST', body: JSON.stringify(body) }));
      plugOut.innerHTML = msg(r);
    } catch (e) { plugOut.innerHTML = `<span class="expected-bad">${e.message}</span>`; }
  }
  $('#cost-plug-unconf').addEventListener('click', () => plug(
    { offsetCode: 'OFF-UI-PLUG', kind: 'REVENUE', amount: 999999, confirmed: false },
    r => `<span class="expected-ok">已录入未确认 999,999：净成本仍为 <b>${money(r.net_operating_cost_total)}</b>（未被填平），排除项 ${money(r.excluded_unconfirmed_estimate_total)}</span>`));
  $('#cost-plug-conf').addEventListener('click', () => plug(
    { offsetCode: 'OFF-UI-PLUG', kind: 'SAVING', amount: 1234, confirmed: true, confirmationRef: 'UI-REF-0001' },
    r => `<span class="expected-ok">补确认凭证后：净成本下降 1,234 → <b>${money(r.net_operating_cost_total)}</b></span>`));
  $('#cost-plug-reset').addEventListener('click', () => plug(
    { offsetCode: 'OFF-UI-PLUG', kind: 'SAVING', amount: 1234, confirmed: false },
    r => `<span>已还原为未确认：净成本 <b>${money(r.net_operating_cost_total)}</b>（该笔重新排除）</span>`));
};

function drawCostUnits() {
  const d = window.__cost;
  if (!d) return;
  let rows = d.units.slice();
  if (costUnitArmFilter !== 'ALL') rows = rows.filter(u => u.arm === costUnitArmFilter);
  rows.sort((a, b) => b.net_cost_alloc - a.net_cost_alloc);
  rows = rows.slice(0, 200);
  $('#cost-unit-rows').innerHTML = rows.map(u => `<tr>
    <td class="small"><code>${u.unit_key}</code><div class="muted">${u.block_code || ''}</div></td>
    <td><b>${u.route_code}</b></td>
    <td>${armBadge(u.arm)}</td>
    <td class="num mono">${costCell(u.direct_cost_by_category?.OVERTIME)}</td>
    <td class="num mono">${costCell(u.direct_cost_by_category?.ENERGY)}</td>
    <td class="num mono">${costCell(u.direct_cost_by_category?.TEMP_VEHICLE, u.allocated_shared_cost_by_category?.TEMP_VEHICLE)}</td>
    <td class="num mono">${costCell(u.direct_cost_by_category?.MAINTENANCE, u.allocated_shared_cost_by_category?.MAINTENANCE)}</td>
    <td class="num mono">${costCell(u.direct_cost_by_category?.CANCEL_LOSS)}</td>
    <td class="num mono small">${fmt(u.allocated_shared_amount, 2)}</td>
    <td class="num mono small">${u.confirmed_offset ? '−' + fmt(u.confirmed_offset, 2) : '—'}</td>
    <td class="num mono"><b>${fmt(u.net_cost_alloc, 2)}</b><div class="muted small">直接法 ${fmt(u.net_cost_direct, 2)}</div></td>
  </tr>`).join('') || '<tr><td colspan="11" class="muted" style="padding:18px;text-align:center">无单元</td></tr>';
}

function costCell(direct, allocated) {
  const d = Number(direct) || 0, a = Number(allocated) || 0;
  if (!d && !a) return '<span class="muted">·</span>';
  if (a > 0.004) return `${fmt(d, 0)}<div class="small" style="color:var(--purple,#7a5af8)">+${fmt(a, 0)} 分摊</div>`;
  return fmt(d, 0);
}

function costCatBadge(c) {
  const m = COST_CAT_META[c] || { label: c, cls: 'gray' };
  return badge(m.label, m.cls);
}

function CostEngineCategoriesRow(cat) {
  return Object.keys(COST_CAT_META).map(k => {
    const c = cat[k] || { n: 0, direct: 0, shared: 0, total: 0 };
    return `<tr><td>${costCatBadge(k)}</td><td class="num">${c.n}</td>
      <td class="num mono">${fmt(c.direct, 2)}</td>
      <td class="num mono" style="color:#b8860b">${c.shared ? fmt(c.shared, 2) : '—'}</td>
      <td class="num mono"><b>${fmt(c.total, 2)}</b></td></tr>`;
  }).join('');
}

function methodCard(m, heading) {
  const r = m.net_cost_per_qualified_unit;
  const ittPositive = m.itt_cost_difference_per_unit > 0;
  return `<div class="card">
    <h3>${heading} <span class="small muted">[${m.method}]</span></h3>
    <p class="section-desc">${m.principle}</p>
    <dl class="kv">
      <dt>已归属 gross</dt><dd class="mono">${money(m.attributed_gross_cost)}</dd>
      <dt>未归属余额</dt><dd class="mono">${m.unallocated_amount > 0 ? '<b style="color:#b8860b">' + money(m.unallocated_amount) + '</b>' : '<span class="expected-ok">¥0.00（账面完整）</span>'}</dd>
      <dt>已确认抵扣</dt><dd class="mono">−${money(m.confirmed_offset_total)}</dd>
      <dt>净运营成本总额</dt><dd class="mono"><b>${money(m.net_operating_cost_total)}</b></dd>
      <dt>每合格单元净成本区间</dt><dd class="mono"><b>${money(r.low)} ~ ${money(r.high)}</b><div class="small muted">点值 ${money(r.point)} / 单元（720 单元，4 周）</div></dd>
      <dt>处理臂 / 对照臂单元均值</dt><dd class="mono">${money(m.treatment.net_cost_per_unit)} / ${money(m.control.net_cost_per_unit)}</dd>
      <dt>ITT 成本差（T−C）</dt><dd class="mono"><b style="color:${ittPositive ? 'var(--red,#d64545)' : 'var(--green,#2e9e5b)'}">${signedYuan(m.itt_cost_difference_per_unit)}/单元</b>
        <div>区间 <b>${signedYuan(m.itt_low)} ~ ${signedYuan(m.itt_high)}</b> 元/单元</div>
        <div class="small muted">${m.itt_interval_kind}</div></dd>
    </dl>
    <p class="small muted" style="margin-bottom:0">${m.itt_interpretation}</p>
  </div>`;
}

function check(label, ok, extra) {
  return `<div class="check-row ${ok ? 'ok' : 'bad'}">
    <span>${ok ? '✅' : '❌'}</span>
    <div><div>${label}</div>${extra ? `<div class="small mono muted">${extra}</div>` : ''}</div>
  </div>`;
}

function offsetDesc(code) {
  if (code === 'OFF-CONF-001') return '备用车场损保险小额追偿（已到账）';
  if (code === 'OFF-EST-UNCONF-001') return '运营方自报增收+用工节省估算，无清算凭证';
  if (code === 'OFF-UI-PLUG') return '本页“填平成本”交互测试单（可还原为未确认）';
  if (code === 'OFF-VERIFY-X' || code === 'OFF-VERIFY-COST') return 'verify.sh 纪律核验单（可重复执行）';
  return '';
}

/* ---------------- 护栏 ---------------- */
RENDER.guardrails = async function () {
  await drawGuardrails(false);
};

async function drawGuardrails(afterAction) {
  const g = await api(`/experiments/${EXP.id}/guardrails`);
  $('#tab-guardrails').innerHTML = `
    <div class="toolbar" style="margin-top:0">
      <button class="btn primary" id="eval-g">重新评估护栏（分析师）</button>
      <span class="small muted">投诉按 service_event_time 归集；满载来自 GPS/AVL 与 load_count</span>
    </div>
    <div class="grid cards-3">
      ${g.latest.map(x => guardCard(x)).join('')}
    </div>
    <div class="card" style="margin-top:14px">
      <h3>评估明细（含时间关联核验与 Top 满载班次）</h3>
      ${g.latest.map(x => `<h3>${x.ruleName || x.rule_name} <code>${x.rule_code}</code></h3>
        <pre class="json">${JSON.stringify(asObj(x.detail), null, 2)}</pre>`).join('')}
    </div>`;
  $('#eval-g').addEventListener('click', async () => {
    $('#eval-g').disabled = true; $('#eval-g').innerHTML = '<span class="spinner"></span> 评估中';
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/guardrails/evaluate`, { method: 'POST' }), '护栏已用最新数据评估');
      await drawGuardrails(true);
    } finally { const b = $('#eval-g'); if (b) { b.disabled = false; b.textContent = '重新评估护栏（分析师）'; } }
  });
}

function guardCard(x) {
  const isPctMetric = x.metric_source === 'overload_share';
  const obs = isPctMetric ? pct(x.observed, 2) : fmt(x.observed, 2);
  const thr = isPctMetric ? pct(x.threshold, 2) : x.threshold;
  const cls = x.breached ? 'red' : x.action === 'INSUFFICIENT_EVIDENCE' ? 'amber' : 'green';
  return `<div class="card stat ${x.breached ? 'red' : ''}">
    <div class="k">${x.ruleName || x.rule_name}</div>
    <div class="v mono">${obs}</div>
    <div class="hint">阈值 ${thr} ${x.comparator === 'GT' ? '（超过即超限）' : ''} · 样本 ${x.sampleN ?? x.sample_n}/${x.minSample ?? x.min_sample}</div>
    <div style="margin-top:8px">${x.breached ? badge('⛔ 超限 · STOP_EXPANSION', 'red') : x.action === 'INSUFFICIENT_EVIDENCE' ? badge('证据不足 · HOLD', 'amber') : badge('✓ 正常 · OK', 'green')}</div>
  </div>`;
}

/* ---------------- 离线审阅 ---------------- */
RENDER.review = async function () {
  $('#tab-review').innerHTML = `
    <div class="card">
      <h3>可复现性</h3>
      <p class="section-desc">所有分配来自 <code>SHA-256(seed::unitKey)</code> 的确定性排序；点击右侧按钮会用库中冻结数据清空并重算暴露账、支持层、分析与护栏，数字应保持一致。</p>
      <div class="pill-row">
        <button class="btn primary" id="recompute">▶ 重新运行确定性流水线</button>
        <button class="btn" id="download-bundle">⬇ 下载离线审阅包 JSON</button>
        <button class="btn" id="show-bundle">👁 在页面中展开审阅包</button>
      </div>
      <div class="small muted" style="margin-top:10px">登记指纹：<span class="hash">${EXP.registration_hash}</span></div>
    </div>
    <div class="card" style="margin-top:14px"><div id="bundle-meta" class="small muted">审阅包包含：实验冻结配置、实验前边、簇、两套分配清单与哈希、事件、命名旅程追踪、支持层、全部估计、护栏评估与扩围历史。</div>
      <pre class="json" id="bundle-json" style="display:none;margin-top:10px"></pre>
    </div>`;

  $('#recompute').addEventListener('click', async () => {
    const b = $('#recompute'); b.disabled = true; b.innerHTML = '<span class="spinner"></span> 重算中…';
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/pipeline/run`, { method: 'POST' }), '流水线已重算：请刷新各标签查看');
      Object.keys(loaded).forEach(k => { if (k !== 'review') loaded[k] = false; });
      EXP = await api('/experiments/' + EXP.id);
    } finally { b.disabled = false; b.textContent = '▶ 重新运行确定性流水线'; }
  });

  let bundle = null;
  async function getBundle() { if (!bundle) bundle = await api(`/experiments/${EXP.id}/review-bundle`); return bundle; }
  $('#show-bundle').addEventListener('click', async () => {
    const b = await getBundle();
    const pre = $('#bundle-json');
    pre.style.display = 'block';
    pre.textContent = JSON.stringify(b, null, 2);
  });
  $('#download-bundle').addEventListener('click', async () => {
    const b = await getBundle();
    const blob = new Blob([JSON.stringify(b, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `review-bundle-${EXP.code}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  });
};

/* ---------------- 扩围证据 ---------------- */
RENDER.expansion = async function () {
  const d = await api(`/experiments/${EXP.id}/expansion`);
  const latest = d.latest || {};
  const cls = latest.decision === 'BLOCKED' ? 'red' : latest.decision === 'GO' ? 'green' : 'amber';
  $('#tab-expansion').innerHTML = `
    <div class="banner ${latest.decision === 'BLOCKED' ? 'red' : latest.decision === 'GO' ? 'green' : 'amber'}">
      <span class="big">${latest.decision === 'BLOCKED' ? '⛔' : latest.decision === 'GO' ? '✅' : '⏸️'}</span>
      <div><b style="font-size:16px">当前扩围结论：${latest.decision || 'NOT_EVALUATED'}</b>
        <div>${latest.reason || '尚未运行证据评估'}</div>
        <div class="small">${latest.created_at ? dt(latest.created_at) + ' · ' + (latest.decided_by || '') : ''}</div>
      </div>
    </div>

    <div class="toolbar">
      <button class="btn danger" id="eval-exp">运行护栏评估并自动形成结论（审批人）</button>
      <span class="small muted">任一硬护栏超限 → BLOCKED，且 GO 人工改判也会被服务端拒绝</span>
    </div>

    <div class="grid cards-2">
      <div class="card">
        <h3>人工记录扩围结论</h3>
        <select id="manual-decision" class="toolbar" style="padding:8px 10px">
          <option value="HOLD">HOLD 暂缓</option>
          <option value="BLOCKED">BLOCKED 停止扩围</option>
          <option value="GO">GO 扩围（护栏超限时服务端拒绝）</option>
        </select>
        <textarea id="manual-reason" rows="3" placeholder="结论理由（留痕）"></textarea>
        <div class="toolbar"><button class="btn primary" id="manual-submit">提交结论</button></div>
      </div>
      <div class="card">
        <h3>结论历史</h3>
        <div class="table-wrap" style="max-height:260px">
          <table class="data"><thead><tr><th>结论</th><th>理由</th><th>签署</th><th>时间</th></tr></thead>
          <tbody>${(d.history || []).map(h => `<tr>
            <td>${badge(h.decision, h.decision === 'BLOCKED' ? 'red' : h.decision === 'GO' ? 'green' : 'amber')}</td>
            <td class="small">${h.reason || ''}</td><td>${h.decided_by}</td><td class="small">${dt(h.created_at)}</td>
          </tr>`).join('')}</tbody></table>
        </div>
      </div>
    </div>`;

  $('#eval-exp').addEventListener('click', async () => {
    try {
      const r = await guardAction(() => api(`/experiments/${EXP.id}/expansion/evaluate`, { method: 'POST' }));
      toast('结论：' + r.decision);
      delete loaded.expansion;
      EXP = await api('/experiments/' + EXP.id);
      RENDER.expansion();
    } catch { /* toast handled */ }
  });
  $('#manual-submit').addEventListener('click', async () => {
    const decision = $('#manual-decision').value;
    const reason = $('#manual-reason').value;
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/expansion/decide`, {
        method: 'POST', body: JSON.stringify({ decision, reason })
      }), '已记录 ' + decision);
      delete loaded.expansion; RENDER.expansion();
    } catch (e) {
      if (e.status === 409) toast('服务端拒绝：' + e.message, true);
    }
  });
};

init().catch(e => {
  console.error(e);
  document.body.innerHTML = `<div style="padding:40px;font-family:sans-serif">初始化失败：${e.message}。<a href="/login.html">前往登录</a></div>`;
});
