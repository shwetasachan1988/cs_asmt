'use strict';

const API = '/api/orchestrator';
const STATUS_COLOR = {
  PENDING: 'var(--s-pending)', READY: 'var(--s-ready)', RUNNING: 'var(--s-running)',
  AWAITING_APPROVAL: 'var(--s-await)', SUCCEEDED: 'var(--s-ok)', FAILED: 'var(--s-fail)',
  ROLLED_BACK: 'var(--s-rolled)', CANCELLED: 'var(--s-cancel)'
};
const $ = (id) => document.getElementById(id);
const state = { scenarios: [], scenario: null, runs: [], runId: null, data: null, task: null, tab: 'timeline',
  panelSig: '', tabSig: '', artifactCache: [], viewing: null };

/* ---------- helpers ---------- */
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'text') el.textContent = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
const svg = (tag, attrs = {}, ...kids) => {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  kids.flat().forEach((k) => el.append(k.nodeType ? k : document.createTextNode(String(k))));
  return el;
};
async function api(path, opts = {}) {
  const res = await fetch(API + path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  const type = res.headers.get('content-type') || '';
  const body = type.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new Error(body && body.detail ? body.detail : `HTTP ${res.status}`);
  return body;
}
const post = (path, body) => api(path, { method: 'POST', body: JSON.stringify(body) });
const reviewer = () => ($('reviewer').value || '').trim() || 'reviewer';
const fmtMs = (ms) => ms == null ? '-' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
const pct = (v) => v == null ? '-' : `${Math.round(v * 1000) / 10}%`;
const clock = (iso) => iso ? new Date(iso).toISOString().slice(11, 23) : '';
const pill = (s) => h('span', { class: `pill ${s}` }, s.replace('_', ' '));

/* ---------- scenarios / start ---------- */
async function loadScenarios() {
  state.scenarios = await api('/scenarios');
  const box = $('scenarios');
  box.replaceChildren(...state.scenarios.map((s) => h('button', {
    class: 'scenario', role: 'radio', 'aria-checked': 'false', 'data-id': s.id,
    onclick: () => pickScenario(s.id)
  }, h('span', { class: 't' }, h('span', { class: 'badge' }, s.kind), s.title.replace(/^[A-Za-z]+ - /, '')),
    h('span', { class: 'd' }, s.description))));
  if (state.scenarios.length) pickScenario(state.scenarios[0].id);
}
function pickScenario(id) {
  state.scenario = state.scenarios.find((s) => s.id === id);
  document.querySelectorAll('.scenario').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.id === id)));
  $('requirement').value = state.scenario.defaultRequirement;
}
async function startRun() {
  $('startError').textContent = '';
  $('startBtn').disabled = true;
  try {
    const run = await post('/runs', { scenario: state.scenario.id, requirement: $('requirement').value,
      failureMode: $('failureMode').value, actor: reviewer() });
    await loadRuns();
    selectRun(run.id);
  } catch (e) { $('startError').textContent = e.message; }
  finally { $('startBtn').disabled = false; }
}

/* ---------- run list / selection ---------- */
async function loadRuns() {
  state.runs = await api('/runs');
  $('runList').replaceChildren(...state.runs.slice(0, 30).map((r) => h('li', {},
    h('button', { class: `runitem${r.id === state.runId ? ' sel' : ''}`, onclick: () => selectRun(r.id) },
      h('span', {}, r.id, h('small', {}, r.scenario.replace(/-/g, ' '))),
      pill(r.status)))));
}
function selectRun(id, task = null) {
  state.runId = id; state.task = task; state.panelSig = ''; state.tabSig = ''; state.viewing = null;
  history.replaceState(null, '', `#run=${id}${task ? `&task=${task}` : ''}`);
  $('empty').hidden = true; $('runView').hidden = false;
  refreshRun(true);
  loadRuns();
}
async function refreshRun(force) {
  if (!state.runId) return;
  try {
    state.data = await api(`/runs/${state.runId}`);
  } catch (e) { return; }
  const { run, metrics } = state.data;
  renderBanner(run);
  renderMetrics(metrics);
  renderGraph(run);
  renderTaskPanel(run, force);
  renderTab(force);
}

/* ---------- banner + controls ---------- */
function renderBanner(run) {
  const awaiting = Object.values(run.tasks).filter((t) => t.status === 'AWAITING_APPROVAL').length;
  const terminal = run.status === 'SUCCEEDED' || run.status === 'FAILED';
  const actions = h('div', { class: 'actions' });
  if (!terminal && run.status !== 'SAFE_STOPPED') {
    actions.append(h('button', { class: 'btn danger', onclick: () => control('safe-stop', 'Reason for safe stop') }, 'Safe stop'));
  }
  if (run.status === 'SAFE_STOPPED') {
    actions.append(h('button', { class: 'btn ok', onclick: () => control('resume', 'Resume comment') }, 'Resume'));
  }
  $('banner').replaceChildren(
    h('div', {}, h('strong', {}, run.id), ' ', pill(run.status),
      awaiting ? h('span', { class: 'tag' }, `${awaiting} awaiting your decision`) : null,
      run.safeStopReason ? h('div', { class: 'bad-text' }, `Safe-stopped: ${run.safeStopReason}`) : null,
      h('div', { class: 'req' }, run.requirement),
      h('div', { class: 'req' }, `plan v${run.planVersion} · ${run.plan.length} tasks · scenario ${run.scenario}`
        + (Object.keys(run.chaos).length ? ` · chaos ${JSON.stringify(run.chaos)}` : ''))),
    actions);
}
async function control(action, label) {
  const reason = prompt(label + ':');
  if (reason == null) return;
  try { await post(`/runs/${state.runId}/${action}`, { actor: reviewer(), reason }); }
  catch (e) { alert(e.message); }
  refreshRun(true);
}
function renderMetrics(m) {
  const items = [['End-to-end', fmtMs(m.endToEndMillis)], ['Human wait', fmtMs(m.humanWaitMillis)],
    ['Active time', fmtMs(m.activeMillis)], ['Executions', m.taskExecutions], ['Failures', m.taskFailures],
    ['Retries', m.retries], ['Fallbacks', m.fallbacks], ['Rollbacks', m.rollbacks], ['Policy blocks', m.policyBlocks],
    ['Re-plans', m.replans], ['Invalidated', m.invalidations], ['MTTR', fmtMs(m.mttrMillis)]];
  $('runMetrics').replaceChildren(...items.map(([k, v]) => h('div', { class: 'metric' }, h('b', {}, v), h('span', {}, k))));
}

/* ---------- DAG graph ---------- */
function renderGraph(run) {
  const W = 172, H = 48, GX = 46, GY = 16;
  const byId = Object.fromEntries(run.plan.map((t) => [t.id, t]));
  const depth = {};
  const d = (id) => depth[id] ?? (depth[id] = byId[id].dependsOn.length ? 1 + Math.max(...byId[id].dependsOn.map(d)) : 0);
  run.plan.forEach((t) => d(t.id));
  const cols = {};
  run.plan.forEach((t) => (cols[depth[t.id]] ||= []).push(t.id));
  const pos = {};
  Object.entries(cols).forEach(([c, ids]) => ids.forEach((id, i) => { pos[id] = { x: 14 + c * (W + GX), y: 14 + i * (H + GY) }; }));
  const maxCol = Math.max(...Object.keys(cols).map(Number));
  const maxRows = Math.max(...Object.values(cols).map((a) => a.length));
  const g = $('graph');
  g.setAttribute('width', 28 + (maxCol + 1) * (W + GX) - GX);
  g.setAttribute('height', 28 + maxRows * (H + GY) - GY);
  const kids = [];
  run.plan.forEach((t) => t.dependsOn.forEach((dep) => {
    const a = pos[dep], b = pos[t.id], x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2, mx = (x1 + x2) / 2;
    const active = run.tasks[dep].status === 'SUCCEEDED' && run.tasks[t.id].status !== 'PENDING';
    kids.push(svg('path', { class: `edge${active ? ' active' : ''}`, d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}` }));
  }));
  run.plan.forEach((t) => {
    const s = run.tasks[t.id], p = pos[t.id], color = STATUS_COLOR[s.status] || 'var(--s-pending)';
    const node = svg('g', { class: `node${state.task === t.id ? ' sel' : ''}`, tabindex: 0, role: 'button',
      'aria-label': `${t.id} ${s.status}`, transform: `translate(${p.x},${p.y})` },
      svg('title', {}, `${t.title}\nagent: ${s.agentUsed || t.agent}\nrisk: ${t.risk}, approval: ${t.approval}\n${s.status}`),
      svg('rect', { width: W, height: H, rx: 8, stroke: color }),
      svg('rect', { width: 6, height: H, rx: 3, fill: color }),
      svg('text', { x: 14, y: 20, 'font-weight': 600 }, t.id.length > 22 ? t.id.slice(0, 21) + '…' : t.id),
      svg('text', { x: 14, y: 36, class: 'st' }, `${s.status.replace('_', ' ')}${s.attempts > 1 ? ` · try ${s.attempts}` : ''}${s.onFallback ? ' · fallback' : ''}${s.staleReason && s.status === 'PENDING' ? ' · stale' : ''}`));
    if (t.approval !== 'NONE') {
      node.append(svg('circle', { cx: W - 12, cy: 12, r: 8, fill: s.approval ? 'var(--s-ok)' : 'var(--s-await)' }),
        svg('text', { x: W - 12, y: 15.5, 'text-anchor': 'middle', style: 'fill:#fff;font-size:9px;font-weight:700' }, 'H'));
    }
    const open = () => { state.task = t.id; state.panelSig = ''; refreshRun(true); };
    node.addEventListener('click', open);
    node.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
    kids.push(node);
  });
  g.replaceChildren(...kids);
  $('legend').replaceChildren(...['PENDING', 'RUNNING', 'AWAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK'].map((s) =>
    h('span', {}, h('i', { style: `background:${STATUS_COLOR[s]}` }), s.replace('_', ' ').toLowerCase())),
    h('span', {}, 'H = human checkpoint'));
}

/* ---------- task panel ---------- */
async function renderTaskPanel(run, force) {
  const panel = $('taskPanel');
  const id = state.task;
  if (!id || !run.tasks[id]) { panel.hidden = true; return; }
  const s = run.tasks[id], t = run.plan.find((x) => x.id === id);
  const sig = [id, s.status, s.attempts, s.reworks, s.artifactIds.length, s.summary, s.lastError, s.staleReason, run.status].join('|');
  if (!force && sig === state.panelSig) return;
  state.panelSig = sig;
  panel.hidden = false;

  const arts = await api(`/runs/${run.id}/artifacts`);
  state.artifactCache = arts;
  const mine = arts.filter((a) => a.task === id);
  const latest = Object.values(mine.reduce((acc, a) => { const k = a.type + a.name; if (!acc[k] || acc[k].version < a.version) acc[k] = a; return acc; }, {}));

  const left = h('div', {},
    h('h2', {}, t.title, ' ', pill(s.status)),
    h('div', { class: 'kv' },
      h('span', {}, 'task / stage'), h('span', {}, `${t.id} / ${t.stage}`),
      h('span', {}, 'agent'), h('span', {}, `${s.agentUsed || t.agent}${t.fallbackAgent ? ` (fallback: ${t.fallbackAgent})` : ''}`),
      h('span', {}, 'depends on'), h('span', {}, t.dependsOn.join(', ') || '-'),
      h('span', {}, 'risk / approval'), h('span', {}, `${t.risk} / ${t.approval}`),
      h('span', {}, 'attempts (max retries)'), h('span', {}, `${s.attempts} (${t.maxRetries})`),
      h('span', {}, 'agent time'), h('span', {}, fmtMs(s.agentMillis)),
      h('span', {}, 'rollback on failure'), h('span', {}, t.rollbackOnFailure ? 'yes' : 'no')),
    s.staleReason ? h('p', {}, h('span', { class: 'tag' }, 'stale'), ' ', s.staleReason) : null,
    s.summary ? h('p', {}, h('strong', {}, 'Outcome: '), s.summary) : null,
    s.rationale ? h('p', { class: 'sub' }, h('strong', {}, 'Why: '), s.rationale) : null,
    s.lastError ? h('p', { class: 'bad-text' }, s.lastError) : null,
    s.lastViolations.length ? h('div', {}, h('strong', {}, 'Policy findings'), h('ul', {}, ...s.lastViolations.map((v) => h('li', {}, v)))) : null);

  const right = h('div', {},
    h('strong', {}, 'Validation checks'),
    s.checks.length ? h('ul', {}, ...s.checks.map((c) => h('li', { class: `chk ${c.passed ? 'pass' : 'fail'}` }, `${c.passed ? 'PASS' : 'FAIL'} ${c.name} - ${c.detail}`))) : h('p', { class: 'sub' }, 'none yet'),
    h('strong', {}, 'Artifacts (latest versions)'),
    latest.length ? h('ul', {}, ...latest.map((a) => h('li', {}, h('a', { href: '#', onclick: (e) => { e.preventDefault(); viewArtifact(run.id, a); } }, a.name), ` `, h('span', { class: 'tag' }, `${a.type} v${a.version}`)))) : h('p', { class: 'sub' }, 'none yet'),
    h('div', { id: 'artifactView' }));

  const kids = [h('div', { class: 'panel-grid' }, left, right)];
  if (s.status === 'AWAITING_APPROVAL') kids.push(await approvalBox(run, t, s, latest));
  else if (id === 'clarify' && run.status !== 'FAILED') kids.push(await reopenBox(run, t, latest));
  else if (s.status === 'SUCCEEDED' && run.status !== 'FAILED' && t.stage === 'REQUIREMENTS') kids.push(reopenSimple(run, t));
  panel.replaceChildren(...kids);
}
async function viewArtifact(runId, a) {
  const text = await api(`/runs/${runId}/artifacts/${a.id}`);
  $('artifactView').replaceChildren(h('p', { class: 'sub' }, `${a.name} · sha256 ${a.sha256.slice(0, 12)}`), h('pre', { class: 'doc', text }));
}
async function questionForm(run, latest, current) {
  const qa = latest.find((a) => a.name === 'clarification-questions.json');
  if (!qa) return null;
  const doc = JSON.parse(await api(`/runs/${run.id}/artifacts/${qa.id}`));
  const form = h('div', { id: 'qform' }, ...doc.questions.map((q) => h('div', { class: 'question' },
    h('div', { class: 'q' }, q.question), h('div', { class: 'because' }, q.because),
    ...q.options.map((o) => h('label', {}, h('input', { type: 'radio', name: `q-${q.id}`, value: o.value,
      checked: (current?.[q.id] ?? q.recommended) === o.value }), h('strong', {}, o.value), ` - ${o.consequence}`)))));
  return form;
}
const readAnswers = () => {
  const out = {};
  document.querySelectorAll('#qform input:checked').forEach((i) => { out[i.name.slice(2)] = i.value; });
  return out;
};
async function approvalBox(run, t, s, latest) {
  const isClarify = t.id === 'clarify' || t.params.humanInput === 'true';
  const form = isClarify ? await questionForm(run, latest, run.context.answers) : null;
  const comment = h('textarea', { rows: 2, placeholder: t.risk === 'HIGH' ? 'Justification required (high-risk change, min 10 chars)' : 'Comment (required when rejecting)' });
  const err = h('p', { class: 'error', role: 'alert' });
  const decide = async (decision) => {
    try {
      await post(`/runs/${run.id}/tasks/${t.id}/decision`, { decision, approver: reviewer(), comment: comment.value,
        answers: isClarify && decision === 'APPROVE' ? readAnswers() : null });
      state.panelSig = ''; refreshRun(true);
    } catch (e) { err.textContent = e.message; }
  };
  return h('div', { class: 'approval' },
    h('strong', {}, `Human checkpoint - ${t.approval === 'BEFORE' ? 'authorise this action' : 'review the produced artifacts'}`),
    t.risk === 'HIGH' ? h('span', { class: 'tag' }, 'HIGH RISK') : null,
    form, comment,
    h('div', { class: 'row' },
      h('button', { class: 'btn ok', onclick: () => decide('APPROVE') }, `Approve as ${reviewer()}`),
      h('button', { class: 'btn danger', onclick: () => decide('REJECT') }, 'Reject / request rework')),
    err);
}
async function reopenBox(run, t, latest) {
  const form = await questionForm(run, latest, run.context.answers);
  if (!form) return h('div');
  const reason = h('input', { placeholder: 'Why are the answers changing?', value: '' });
  const err = h('p', { class: 'error', role: 'alert' });
  return h('div', { class: 'approval' },
    h('strong', {}, 'Change an answer later (upstream change)'),
    h('p', { class: 'sub' }, 'Dependants whose output really changes are invalidated, their approvals revoked and they re-run.'),
    form, reason,
    h('div', { class: 'row' }, h('button', { class: 'btn', onclick: async () => {
      try { await post(`/runs/${run.id}/tasks/${t.id}/reopen`, { actor: reviewer(), reason: reason.value, answers: readAnswers() }); state.panelSig = ''; refreshRun(true); }
      catch (e) { err.textContent = e.message; } } }, 'Re-open with these answers')), err);
}
function reopenSimple(run, t) {
  const err = h('p', { class: 'error', role: 'alert' });
  return h('div', { class: 'approval' },
    h('strong', {}, 'Re-run this task'),
    h('div', { class: 'row' }, h('button', { class: 'btn', onclick: async () => {
      const reason = prompt('Reason for re-opening ' + t.id + ':'); if (!reason) return;
      try { await post(`/runs/${run.id}/tasks/${t.id}/reopen`, { actor: reviewer(), reason, answers: null }); state.panelSig = ''; refreshRun(true); }
      catch (e) { err.textContent = e.message; } } }, 'Re-open')), err);
}

/* ---------- tabs ---------- */
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
  state.tab = b.dataset.tab; state.tabSig = '';
  document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === b));
  renderTab(true);
}));
async function renderTab(force) {
  if (!state.runId || !state.data) return;
  const run = state.data.run;
  const body = $('tabBody');
  if (state.tab === 'timeline') {
    const events = await api(`/runs/${run.id}/audit`);
    const sig = 't' + events.length;
    if (!force && sig === state.tabSig) return;
    state.tabSig = sig;
    const verify = h('span', {});
    const check = async () => { const v = await api(`/runs/${run.id}/audit/verify`);
      verify.replaceChildren(h('span', { class: v.valid ? 'ok-text' : 'bad-text' }, v.valid ? `hash chain intact (${v.events} events)` : `TAMPERING detected at ${v.firstBrokenAt}`)); };
    body.replaceChildren(h('p', {}, h('button', { class: 'btn tiny', onclick: check }, 'Verify hash chain'), ' ', verify),
      h('table', {}, h('thead', {}, h('tr', {}, ...['#', 'time', 'actor', 'event', 'task', 'detail', 'hash'].map((c) => h('th', {}, c)))),
        h('tbody', {}, ...events.slice().reverse().map((e) => h('tr', {},
          h('td', { class: 'mono' }, e.seq), h('td', { class: 'mono' }, clock(e.ts)), h('td', {}, e.actor),
          h('td', {}, h('strong', { class: /FAIL|VIOLATION|STOP|ROLLBACK/.test(e.type) ? 'bad-text' : '' }, e.type)),
          h('td', {}, e.nodeId || ''), h('td', { class: 'mono' }, e.detail.length > 260 ? e.detail.slice(0, 257) + '…' : e.detail),
          h('td', { class: 'mono' }, e.hash.slice(0, 8)))))));
  } else if (state.tab === 'lineage') {
    const l = await api(`/runs/${run.id}/lineage`);
    const sig = 'l' + l.decisions.length + ':' + l.artifactFlow.length;
    if (!force && sig === state.tabSig) return;
    state.tabSig = sig;
    body.replaceChildren(
      h('h2', {}, 'Decisions that shaped this run'),
      h('table', {}, h('tbody', {}, ...l.decisions.map((d) => h('tr', {}, h('td', { class: 'mono' }, `#${d.seq}`), h('td', {}, d.actor),
        h('td', {}, h('strong', {}, d.type)), h('td', {}, d.task || ''), h('td', { class: 'mono' }, JSON.stringify(d.detail).slice(0, 300)))))),
      h('h2', {}, 'Artifact flow (who consumed what)'),
      h('table', {}, h('thead', {}, h('tr', {}, ...['artifact', 'ver', 'sha', 'produced by', 'consumed by'].map((c) => h('th', {}, c)))),
        h('tbody', {}, ...l.artifactFlow.map((a) => h('tr', {}, h('td', {}, a.artifact), h('td', {}, `v${a.version}`), h('td', { class: 'mono' }, a.sha256), h('td', {}, a.producedBy), h('td', {}, a.consumedBy))))));
  } else if (state.tab === 'artifacts') {
    const arts = await api(`/runs/${run.id}/artifacts`);
    const sig = 'a' + arts.length;
    if (!force && sig === state.tabSig) return;
    state.tabSig = sig;
    const view = h('div', {});
    body.replaceChildren(h('table', {}, h('thead', {}, h('tr', {}, ...['task', 'artifact', 'type', 'ver', 'sha256', 'size', ''].map((c) => h('th', {}, c)))),
      h('tbody', {}, ...arts.map((a) => h('tr', {}, h('td', {}, a.task), h('td', {}, a.name), h('td', {}, a.type), h('td', {}, `v${a.version}`),
        h('td', { class: 'mono' }, a.sha256.slice(0, 12)), h('td', {}, `${a.bytes} B`),
        h('td', {}, h('a', { href: '#', onclick: async (e) => { e.preventDefault(); const text = await api(`/runs/${run.id}/artifacts/${a.id}`); view.replaceChildren(h('pre', { class: 'doc', text })); } }, 'view')))))), view);
  } else if (state.tab === 'summary') {
    const sig = 's' + run.status + run.planVersion + Object.values(run.tasks).map((t) => t.status[0]).join('');
    if (!force && sig === state.tabSig) return;
    state.tabSig = sig;
    body.replaceChildren(renderMd(await api(`/runs/${run.id}/summary`)));
  }
}

/* ---------- tiny, safe markdown renderer (DOM only, no innerHTML) ---------- */
function inline(text) {
  const out = [];
  text.split(/(`[^`]+`|\*\*[^*]+\*\*)/).forEach((p) => {
    if (p.startsWith('`') && p.endsWith('`') && p.length > 1) out.push(h('code', {}, p.slice(1, -1)));
    else if (p.startsWith('**') && p.endsWith('**') && p.length > 4) out.push(h('strong', {}, p.slice(2, -2)));
    else if (p) out.push(p);
  });
  return out;
}
function renderMd(md) {
  const root = h('div', { class: 'md' });
  const lines = md.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (/^#{1,3} /.test(l)) { const n = l.match(/^#+/)[0].length; root.append(h('h' + n, {}, ...inline(l.replace(/^#+ /, '')))); }
    else if (l.startsWith('|')) {
      const rows = [];
      while (i < lines.length && lines[i].startsWith('|')) { rows.push(lines[i]); i++; }
      i--;
      const cells = (r) => r.split('|').slice(1, -1).map((c) => c.trim());
      const [head, , ...body] = rows;
      root.append(h('table', {}, h('thead', {}, h('tr', {}, ...cells(head).map((c) => h('th', {}, ...inline(c))))),
        h('tbody', {}, ...body.map((r) => h('tr', {}, ...cells(r).map((c) => h('td', {}, ...inline(c))))))));
    } else if (l.startsWith('- ')) {
      const ul = h('ul', {});
      while (i < lines.length && lines[i].startsWith('- ')) { ul.append(h('li', {}, ...inline(lines[i].slice(2)))); i++; }
      i--; root.append(ul);
    } else if (l.startsWith('> ')) root.append(h('blockquote', {}, ...inline(l.slice(2))));
    else if (l.trim()) root.append(h('p', {}, ...inline(l)));
  }
  return root;
}

/* ---------- global metrics + kill switch ---------- */
async function loadGlobal() {
  try {
    const m = await api('/metrics');
    const rows = [['Runs (ok / failed / active)', `${m.runs.succeeded} / ${m.runs.failed} / ${m.runs.inProgress}`],
      ['Run success rate', pct(m.runSuccessRate)], ['Task success rate', pct(m.taskSuccessRate)],
      ['Retry rate', pct(m.retryRate)], ['Fallbacks', m.fallbacks], ['Rollbacks (run freq.)', `${m.rollbacks} (${pct(m.rollbackFrequency)})`],
      ['Policy blocks', m.policyBlocks], ['Re-plans', m.replans], ['MTTR', fmtMs(m.mttrMillis)],
      ['E2E latency p50 / p95', `${fmtMs(m.endToEndLatencyMillis.p50)} / ${fmtMs(m.endToEndLatencyMillis.p95)}`],
      ['Active latency p50', fmtMs(m.activeLatencyMillis.p50)]];
    $('globalMetricsBody').replaceChildren(...rows.flatMap(([k, v]) => [h('span', {}, k), h('span', {}, v ?? '-')]));
    const gov = await api('/governance');
    const b = $('killSwitch');
    b.classList.toggle('on', gov.killSwitchEngaged);
    b.textContent = `Kill switch: ${gov.killSwitchEngaged ? 'ENGAGED' : 'off'}`;
    b.dataset.engaged = gov.killSwitchEngaged;
  } catch (e) { /* transient */ }
}
$('killSwitch').addEventListener('click', async (e) => {
  const engage = e.currentTarget.dataset.engaged !== 'true';
  if (engage && !confirm('Safe-stop ALL active runs and block resuming until released?')) return;
  await post('/kill-switch', { engaged: engage, actor: reviewer() });
  loadGlobal(); refreshRun(true);
});

/* ---------- boot ---------- */
$('startBtn').addEventListener('click', startRun);
$('refreshRuns').addEventListener('click', loadRuns);
loadScenarios();
loadRuns();
loadGlobal();
{ // deep link: #run=<id>[&task=<id>][&tab=<name>]
  const m = location.hash.match(/run=(run-[a-f0-9]+)(?:&task=([\w-]+))?(?:&tab=(\w+))?/);
  if (m) {
    if (m[3]) {
      state.tab = m[3];
      document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x.dataset.tab === m[3]));
    }
    selectRun(m[1], m[2] || null);
  }
}
setInterval(() => { if (!document.hidden) refreshRun(false); }, 1200);
setInterval(() => { if (!document.hidden) { loadRuns(); loadGlobal(); } }, 4000);
