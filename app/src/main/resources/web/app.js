// app.js — jrsctl console: hash router and views (spec §13).
//
// All text that comes from the API goes into the DOM through textContent (the h() helper); this
// file never uses innerHTML. Icons are built with createElementNS from static path data.

import { api, loadToken, setToken, getToken, useMock, isMock } from './api.js';

/* ======================================================================
   DOM helpers
   ====================================================================== */

/** Create an element: h('a', {href, class, onclick}, ...children). Strings become text nodes. */
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

function frag(...children) {
  const f = document.createDocumentFragment();
  append(f, children);
  return f;
}

function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

const SVG_NS = 'http:' + '//www.w3.org/2000/svg'; // namespace identifier, not a network reference

const ICON_PATHS = {
  check: [['path', { d: 'M3.5 8.5l3 3 6-7' }]],
  warn: [['path', { d: 'M8 2.2L14.6 13.5H1.4Z' }], ['path', { d: 'M8 6.5v3.2' }], ['circle', { cx: 8, cy: 11.8, r: 0.5, fill: 'currentColor' }]],
  fail: [['circle', { cx: 8, cy: 8, r: 6 }], ['path', { d: 'M5.5 5.5l5 5M10.5 5.5l-5 5' }]],
  running: [['path', { d: 'M8 2a6 6 0 1 1-5.2 3' }]],
  pending: [['circle', { cx: 8, cy: 8, r: 5.5, 'stroke-dasharray': '2.5 2.5' }]],
  skipped: [['circle', { cx: 8, cy: 8, r: 6 }], ['path', { d: 'M5 8h6' }]],
  undo: [['path', { d: 'M3 8a5 5 0 1 0 1.5-3.6' }], ['path', { d: 'M3 3v3h3' }]],
  download: [['path', { d: 'M8 2v8M4.5 6.5L8 10l3.5-3.5M3 13h10' }]],
  pause: [['path', { d: 'M5 3v10M11 3v10' }]],
  play: [['path', { d: 'M5 3l8 5-8 5z' }]],
  arrowDown: [['path', { d: 'M8 3v10M4 9l4 4 4-4' }]],
  refresh: [['path', { d: 'M13 8a5 5 0 1 1-1.5-3.6' }], ['path', { d: 'M13 3v3h-3' }]],
};

function icon(name, cls) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'icon' + (cls ? ' ' + cls : ''));
  for (const [tag, attrs] of ICON_PATHS[name] || ICON_PATHS.pending) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
    svg.append(el);
  }
  return svg;
}

/** Status chip: icon + word, never colour alone. kind: pass | warn | fail | running | pending | skipped. */
function chip(kind, word, iconName) {
  const names = { pass: 'check', warn: 'warn', fail: 'fail', running: 'running', pending: 'pending', skipped: 'skipped' };
  return h('span', { class: 'status ' + kind }, icon(iconName || names[kind] || 'pending'), h('span', null, word));
}

const OUTCOMES = {
  succeeded: ['pass', 'Succeeded'],
  failed: ['fail', 'Failed'],
  failed_rolled_back: ['fail', 'Failed, rolled back'],
  rolled_back: ['warn', 'Rolled back', 'undo'],
  rollback_incomplete: ['fail', 'Rollback incomplete'],
  cancelled: ['skipped', 'Cancelled'],
  running: ['running', 'Running'],
  pending: ['pending', 'Pending'],
  interrupted: ['warn', 'Interrupted'],
};

function outcomeChip(outcome) {
  const [kind, word, ic] = OUTCOMES[outcome] || ['pending', outcome || 'Unknown'];
  return chip(kind, word, ic);
}

const STEP_STATUS = {
  pending: ['pending', 'Pending'],
  running: ['running', 'Running'],
  retry: ['running', 'Retrying'],
  succeeded: ['pass', 'Succeeded'],
  failed: ['fail', 'Failed'],
  skipped: ['skipped', 'Skipped'],
  rolled_back: ['warn', 'Rolled back', 'undo'],
  rollback_failed: ['fail', 'Rollback failed'],
};

function stepChip(status) {
  const [kind, word, ic] = STEP_STATUS[status] || ['pending', status || 'Pending'];
  return chip(kind, word, ic);
}

function doctorChip(status) {
  const s = String(status || '').toUpperCase();
  const kind = s === 'PASS' ? 'pass' : s === 'WARN' ? 'warn' : 'fail';
  return chip(kind, s || 'FAIL');
}

/* ======================================================================
   Formatting
   ====================================================================== */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const pad2 = (n) => String(n).padStart(2, '0');

function fmtDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const now = new Date();
  const dayStart = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diffDays = Math.round((dayStart(now) - dayStart(d)) / 86400000);
  const hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  if (diffDays === 0) return 'Today ' + hm;
  if (diffDays === 1) return 'Yesterday ' + hm;
  const day = d.getDate() + ' ' + MONTHS[d.getMonth()];
  return (d.getFullYear() === now.getFullYear() ? day : day + ' ' + d.getFullYear()) + ' ' + hm;
}

function fmtTime(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}

function fmtDuration(ms) {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  const m = Math.floor(ms / 60000);
  const s = Math.round((ms % 60000) / 1000);
  if (m < 60) return m + 'm ' + pad2(s) + 's';
  return Math.floor(m / 60) + 'h ' + pad2(m % 60) + 'm';
}

function fmtElapsed(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hh = Math.floor(total / 3600);
  const mm = Math.floor((total % 3600) / 60);
  const ss = total % 60;
  return (hh > 0 ? pad2(hh) + ':' : '') + pad2(mm) + ':' + pad2(ss);
}

function fmtBytes(n) {
  if (typeof n !== 'number') return '';
  if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
  if (n >= 1e6) return (n / 1e6).toFixed(0) + ' MB';
  return (n / 1e3).toFixed(0) + ' kB';
}

function plural(n, one, many) {
  return n + ' ' + (n === 1 ? one : many || one + 's');
}

function opLabel(op) {
  const labels = { 'hotfix.apply': 'hotfix apply', 'hotfix.rollback': 'hotfix rollback', 'hotfix.verify': 'hotfix verify',
    export: 'export', import: 'import', upgrade: 'upgrade' };
  return labels[op] || op || '';
}

/* ======================================================================
   Toasts and in-page confirm dialog
   ====================================================================== */

function toast(message, kind) {
  const root = document.getElementById('toasts');
  const el = h('div', { class: 'toast ' + (kind || 'info'), role: kind === 'fail' ? 'alert' : 'status' },
    h('span', null, message),
    h('button', { class: 'close', type: 'button', 'aria-label': 'Dismiss', onclick: () => el.remove() }, 'Dismiss'));
  root.append(el);
  setTimeout(() => el.remove(), 8000);
}

function errorMessage(e) {
  if (e && typeof e.message === 'string' && e.message) return e.message;
  return 'Unexpected error.';
}

/** Report a failed call: toast, and switch to the token panel on 401. */
function reportError(e) {
  if (e && e.status === 401) {
    renderTokenPanel();
    return;
  }
  toast(errorMessage(e), 'fail');
}

/**
 * In-page confirmation (never window.confirm). Resolves true when confirmed. Escape and Tab are
 * handled on the dialog element itself, not on the document.
 */
function confirmDialog({ title, body, confirmLabel, danger }) {
  return new Promise((resolve) => {
    const root = document.getElementById('modal-root');
    const previous = document.activeElement;
    const finish = (result) => {
      clear(root);
      if (previous && previous.focus) previous.focus();
      resolve(result);
    };
    const cancelBtn = h('button', { class: 'btn', type: 'button', onclick: () => finish(false) }, 'Keep as is');
    const okBtn = h('button', { class: 'btn ' + (danger ? 'danger solid' : 'primary'), type: 'button', onclick: () => finish(true) }, confirmLabel);
    const dialog = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'modal-title', tabindex: '-1' },
      h('h2', { class: 'h3', id: 'modal-title' }, title),
      h('div', { class: 'secondary' }, body),
      h('div', { class: 'btn-row' }, cancelBtn, okBtn));
    dialog.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') { ev.preventDefault(); finish(false); }
      if (ev.key === 'Tab') {
        const order = [cancelBtn, okBtn];
        const i = order.indexOf(document.activeElement);
        const next = ev.shiftKey ? (i <= 0 ? order.length - 1 : i - 1) : (i >= order.length - 1 ? 0 : i + 1);
        ev.preventDefault();
        order[next].focus();
      }
    });
    root.append(h('div', { class: 'modal-backdrop', onclick: () => finish(false) }), dialog);
    (danger ? cancelBtn : okBtn).focus();
  });
}

/* ======================================================================
   Chrome (rail, top bar) and boot
   ====================================================================== */

const state = { health: null, server: null, doctorReport: null };

function byId(id) {
  return document.getElementById(id);
}

function setText(id, text) {
  const el = byId(id);
  if (el) el.textContent = text;
}

function applyChrome() {
  const health = state.health;
  const server = state.server;
  if (health && health.tool && health.tool.version) setText('tool-version', 'v' + health.tool.version);
  if (health && health.bind) setText('bind-address', 'Bound to ' + health.bind);
  setText('token-state', isMock() ? 'Sample data, no token' : getToken() ? 'Session token active' : 'Session token missing');
  const url = server && server.baseUrl ? server.baseUrl : '';
  setText('server-url', url);
  const pill = byId('network-pill');
  const mode = (health && health.networkMode) || (server && server.networkMode);
  if (pill && mode) {
    clear(pill);
    const words = { isolated: 'Isolated network', proxy: 'Proxy network', direct: 'Direct network' };
    pill.append(words[mode] || String(mode) + ' network');
    pill.hidden = false;
  }
}

/* The sample backend lives only in the source tree: the build excludes mock.js from the jar, so a
   console served by jrsctl cannot load it. Developers open index.html from disk (or serve the
   web/ directory themselves) and add ?mock=1. */
function mockAllowed() {
  return window.location.protocol === 'file:';
}

async function enableMock() {
  let mod;
  try {
    mod = await import('./mock.js');
  } catch (e) {
    throw new Error('Sample data is not part of this build. Open web/index.html from the source tree to use it.');
  }
  useMock(mod.createMockBackend());
  byId('sample-banner').hidden = false;
  setText('token-state', 'Sample data, no token');
}

function renderMockUnavailable(e) {
  const view = byId('view');
  clear(view);
  view.append(h('section', { class: 'panel token-panel' },
    h('div', { class: 'panel-body' },
      h('h2', { class: 'h2' }, 'Sample data unavailable'),
      h('p', { class: 'secondary' }, errorMessage(e)),
      h('div', { class: 'btn-row' },
        h('button', { class: 'btn primary', type: 'button', onclick: () => { window.location.search = ''; } }, 'Open the real console')))));
}

async function loadHealth() {
  state.health = await api.health();
  try {
    state.server = await api.server();
  } catch (e) {
    if (e && e.status === 401) throw e;
    state.server = null;
  }
  applyChrome();
}

function renderTokenPanel() {
  setText('token-state', 'Session token missing');
  const view = byId('view');
  clear(view);
  const input = h('input', { type: 'password', id: 'token-input', class: 'mono', autocomplete: 'off', spellcheck: 'false', placeholder: 'paste the token' });
  const form = h('form', { class: 'form', onsubmit: async (ev) => {
    ev.preventDefault();
    const value = input.value.trim();
    if (!value) return;
    setToken(value);
    try {
      await loadHealth();
      startRouter();
    } catch (e) {
      reportError(e);
    }
  } },
  h('div', { class: 'field' },
    h('label', { for: 'token-input' }, 'Console session token'),
    input,
    h('span', { class: 'hint' }, 'Stored in this browser tab only (sessionStorage). Closing the tab forgets it.')),
  h('div', { class: 'btn-row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Use token')));
  view.append(h('section', { class: 'panel token-panel' },
    h('div', { class: 'panel-body' },
      h('h2', { class: 'h2' }, 'Token required'),
      h('p', { class: 'secondary' }, 'Every console request must carry the per-launch bearer token. jrsctl prints it once when the console starts and writes it to ',
        h('code', null, '$JRSCTL_HOME/console.token'), ' with owner-only permissions.'),
      h('p', { class: 'secondary' }, 'Open the link printed at console start (it carries the token in the URL fragment), or paste the token below.'),
      form)));
  view.focus();
}

function renderOfflinePanel(e) {
  const view = byId('view');
  clear(view);
  view.append(h('section', { class: 'panel token-panel' },
    h('div', { class: 'panel-body' },
      h('h2', { class: 'h2' }, 'Console API unreachable'),
      h('p', { class: 'secondary' }, errorMessage(e)),
      h('p', { class: 'secondary' }, 'The console expects its API at ', h('code', null, '/api'), ' on the same origin. If jrsctl is not running, start it with ',
        h('code', null, 'jrsctl console'), ' and open the address it prints.'),
      h('div', { class: 'btn-row' },
        h('button', { class: 'btn primary', type: 'button', onclick: () => boot() }, 'Retry'),
        ...(mockAllowed()
          ? [h('button', { class: 'btn', type: 'button', onclick: async () => {
              try { await enableMock(); } catch (err) { renderMockUnavailable(err); return; }
              boot();
            } }, 'Use sample data')]
          : [])))));
}

async function boot() {
  await loadToken();
  const params = new URLSearchParams(window.location.search);
  if (params.get('mock') === '1' && !isMock()) {
    try {
      await enableMock();
    } catch (e) {
      renderMockUnavailable(e);
      return;
    }
  }
  try {
    await loadHealth();
  } catch (e) {
    if (e && e.status === 401) renderTokenPanel();
    else renderOfflinePanel(e);
    return;
  }
  startRouter();
}

/* ======================================================================
   Router
   ====================================================================== */

const ROUTES = [
  { pattern: /^\/dashboard$/, title: 'Dashboard', nav: 'dashboard', view: dashboardView },
  { pattern: /^\/new$/, title: 'New operation', nav: 'new', view: newOperationView },
  { pattern: /^\/runs$/, title: 'Runs', nav: 'runs', view: runsView },
  { pattern: /^\/runs\/([^/]+)$/, title: 'Runs', nav: 'runs', view: runView },
  { pattern: /^\/doctor$/, title: 'Doctor', nav: 'doctor', view: doctorView },
  { pattern: /^\/hotfixes$/, title: 'Hotfixes', nav: 'hotfixes', view: hotfixesView },
];

let routerStarted = false;
let activeCleanups = [];

function parseHash() {
  const raw = window.location.hash.replace(/^#/, '') || '/dashboard';
  const q = raw.indexOf('?');
  const path = q < 0 ? raw : raw.slice(0, q);
  const query = new URLSearchParams(q < 0 ? '' : raw.slice(q + 1));
  return { path, query };
}

function navigate(hash) {
  if (window.location.hash === hash) onRoute();
  else window.location.hash = hash;
}

function setNav(name) {
  for (const a of byId('rail-nav').querySelectorAll('a')) {
    if (a.dataset.route === name) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
}

function setCrumb(parts) {
  const crumb = byId('topbar-crumb');
  clear(crumb);
  parts.forEach((p, i) => {
    if (i > 0) crumb.append(h('span', { class: 'sep', 'aria-hidden': 'true' }, '/'));
    crumb.append(p.href ? h('a', { href: p.href }, p.label) : h('span', { class: 'mono' }, p.label));
  });
}

async function onRoute() {
  const { path, query } = parseHash();
  const route = ROUTES.find((r) => r.pattern.test(path));
  if (!route) { navigate('#/dashboard'); return; }
  for (const fn of activeCleanups) { try { fn(); } catch { /* ignore */ } }
  activeCleanups = [];
  const params = route.pattern.exec(path).slice(1).map(decodeURIComponent);
  setNav(route.nav);
  setText('page-title', route.title);
  setCrumb([]);
  const view = byId('view');
  clear(view);
  view.append(h('p', { class: 'centered-note' }, 'Loading'));
  const ctx = { params, query, onCleanup: (fn) => activeCleanups.push(fn) };
  try {
    const node = await route.view(ctx);
    if (parseHash().path !== path) return; // navigated away while loading
    clear(view);
    view.append(node);
  } catch (e) {
    if (e && e.status === 401) { renderTokenPanel(); return; }
    clear(view);
    view.append(h('div', { class: 'callout fail' }, chip('fail', 'Could not load this view'), h('span', null, errorMessage(e))));
    toast(errorMessage(e), 'fail');
  }
}

function startRouter() {
  if (!routerStarted) {
    routerStarted = true;
    window.addEventListener('hashchange', onRoute);
  }
  if (!/^#\/./.test(window.location.hash)) window.location.hash = '#/dashboard';
  onRoute();
}

/* ======================================================================
   Shared components
   ====================================================================== */

function panel(head, body, foot) {
  return h('section', { class: 'panel' }, head, body, foot);
}

function panelHead(title, ...right) {
  return h('div', { class: 'panel-head' }, h('h2', { class: 'h3' }, title), ...right);
}

function table(columns, rows, emptyText) {
  const thead = h('thead', null, h('tr', null, columns.map((c) => h('th', { scope: 'col', class: c.class }, c.label))));
  const tbody = h('tbody', null, rows.length === 0
    ? h('tr', null, h('td', { colspan: columns.length, class: 'empty' }, emptyText))
    : rows.map((r) => h('tr', null, columns.map((c) => h('td', { class: c.class }, c.cell(r))))));
  return h('div', { class: 'table-wrap' }, h('table', { class: 'table' }, thead, tbody));
}

function runLink(id) {
  return h('a', { class: 'mono', href: '#/runs/' + encodeURIComponent(id) }, id);
}

function runOpCell(r) {
  return frag(opLabel(r.op), r.target ? [' ', h('span', { class: 'mono' }, r.target)] : null);
}

function supportBundleButton(id) {
  return h('button', { class: 'btn link small', type: 'button', onclick: async (ev) => {
    const btn = ev.currentTarget;
    btn.disabled = true;
    try { await api.supportBundle(id); toast('Support bundle for ' + id + ' downloaded.', 'pass'); }
    catch (e) { reportError(e); }
    finally { btn.disabled = false; }
  } }, icon('download'), 'Support bundle');
}

async function rollbackRun(id) {
  const ok = await confirmDialog({
    title: 'Roll back ' + id + '?',
    body: 'jrsctl builds a compensating plan from the recorded snapshot and runs it as a new run. The service is stopped while files are restored.',
    confirmLabel: 'Roll back', danger: true });
  if (!ok) return;
  try {
    const res = await api.rollbackRun(id);
    if (res && res.runId) navigate('#/runs/' + encodeURIComponent(res.runId));
    else toast('Rollback started.', 'pass');
  } catch (e) {
    reportError(e);
  }
}

function pendingRecoveryBanner(health) {
  const pending = (health && health.pendingRuns) || [];
  if (pending.length === 0) return null;
  const run = pending[0];
  return h('div', { class: 'callout warn' },
    chip('warn', plural(pending.length, 'interrupted run needs recovery', 'interrupted runs need recovery')),
    h('span', null, 'Run ', h('span', { class: 'mono' }, run.id), ' (', opLabel(run.op), ') stopped', run.stepId ? [' at step ', h('span', { class: 'mono' }, run.stepId)] : null,
      '. Resume re-runs the precheck of the interrupted step and continues; roll back compensates every succeeded step in reverse. Mutating commands refuse to run until this is resolved.'),
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn primary', type: 'button', onclick: async () => {
        const ok = await confirmDialog({ title: 'Resume ' + run.id + '?', body: 'The interrupted step is prechecked and re-executed. If the precheck fails only rollback is offered.', confirmLabel: 'Resume' });
        if (!ok) return;
        try { const res = await api.resumeRun(run.id); navigate('#/runs/' + encodeURIComponent((res && res.runId) || run.id)); } catch (e) { reportError(e); }
      } }, 'Resume'),
      h('button', { class: 'btn danger', type: 'button', onclick: () => rollbackRun(run.id) }, 'Roll back'),
      h('a', { class: 'btn link', href: '#/runs/' + encodeURIComponent(run.id) }, 'Open run')));
}

/* ======================================================================
   Dashboard
   ====================================================================== */

async function dashboardView() {
  const [health, server, runs, hotfixes] = await Promise.all([
    api.health(), api.server().catch(() => null), api.runs().catch(() => []), api.hotfixes().catch(() => []),
  ]);
  state.health = health;
  state.server = server;
  applyChrome();
  return frag(
    pendingRecoveryBanner(health),
    h('div', { class: 'grid-3' }, serverPanel(server), healthPanel(health), runStatePanel(health)),
    recentRunsPanel(runs.slice(0, 5)),
    hotfixesPanel(hotfixes, false));
}

function serverPanel(server) {
  if (!server) {
    return panel(null, h('div', { class: 'panel-body' }, h('span', { class: 'eyebrow' }, 'Server'),
      chip('warn', 'Server identity unavailable'), h('p', { class: 'secondary' }, 'GET /api/server failed. Run doctor to see why.')));
  }
  const svc = server.service || {};
  const ks = server.keystore || {};
  const db = server.database || {};
  const tenancyWord = server.tenancy === 'multi-tenant' ? 'Multi-tenant' : server.tenancy === 'single-tenant' ? 'Single-tenant' : server.tenancy;
  return panel(null, h('div', { class: 'panel-body' },
    h('span', { class: 'eyebrow' }, 'Server'),
    h('div', { class: 'big' }, (server.product || 'JasperReports Server') + ' ' + (server.version || '')),
    h('div', { class: 'chips' },
      server.edition ? h('span', { class: 'chip accent' }, server.edition) : null,
      tenancyWord ? h('span', { class: 'chip' }, tenancyWord) : null,
      db.vendor ? h('span', { class: 'chip' }, db.vendor + (db.version ? ' ' + db.version : '')) : null),
    h('div', { class: 'lines' },
      h('div', { class: 'line' }, (svc.kind || 'Service') + ' service ', h('span', { class: 'mono' }, svc.name || ''), ' ',
        svc.state === 'running' ? chip('pass', 'running') : svc.state ? chip('warn', svc.state) : null),
      h('div', { class: 'line' }, ks.present ? 'Keystore present for ' : 'No keystore found for ', h('span', { class: 'mono' }, ks.user || 'the current user')),
      server.installDir ? h('div', { class: 'line mono' }, server.installDir) : null)));
}

function healthPanel(health) {
  const d = (health && health.doctor) || {};
  const fail = d.fail || 0;
  const warn = d.warn || 0;
  const headline = fail > 0 ? 'Doctor: ' + plural(fail, 'failure') : warn > 0 ? 'Doctor: ' + plural(warn, 'warning') : d.pass ? 'Doctor: all checks pass' : 'Doctor has not run yet';
  const attention = (d.attention || []).slice(0, 2);
  return panel(null, h('div', { class: 'panel-body' },
    h('span', { class: 'eyebrow' }, 'Health'),
    h('div', { class: 'big' }, headline),
    h('div', { class: 'lines' },
      attention.map((it) => h('div', { class: 'line' }, doctorChip(it.status), h('span', null, it.detail || it.title))),
      d.ranAt ? h('div', { class: 'line muted' }, 'Last run ' + fmtDate(d.ranAt)) : null),
    h('button', { class: 'btn link cta', type: 'button', onclick: async (ev) => {
      ev.currentTarget.disabled = true;
      try { state.doctorReport = await api.doctor(); navigate('#/doctor'); } catch (e) { reportError(e); ev.currentTarget.disabled = false; }
    } }, icon('refresh'), 'Run doctor again')));
}

function runStatePanel(health) {
  const pending = (health && health.pendingRuns) || [];
  const lock = (health && health.lock) || {};
  const snaps = (health && health.snapshots) || null;
  const last = health && health.lastRun;
  return panel(null, h('div', { class: 'panel-body' },
    h('span', { class: 'eyebrow' }, 'Run state'),
    h('div', { class: 'big' }, pending.length === 0 ? 'No pending runs' : plural(pending.length, 'pending run')),
    pending.length > 0 ? h('div', { class: 'callout warn' },
      chip('warn', 'Recovery required'),
      h('span', null, 'Run ', h('span', { class: 'mono' }, pending[0].id), ' has no terminal state.'),
      h('div', { class: 'btn-row' },
        h('button', { class: 'btn primary small', type: 'button', onclick: async () => {
          try { const res = await api.resumeRun(pending[0].id); navigate('#/runs/' + encodeURIComponent((res && res.runId) || pending[0].id)); } catch (e) { reportError(e); }
        } }, 'Resume'),
        h('button', { class: 'btn danger small', type: 'button', onclick: () => rollbackRun(pending[0].id) }, 'Roll back'))) : null,
    h('div', { class: 'lines' },
      h('div', { class: 'line' }, lock.held
        ? [chip('warn', 'Run lock held'), h('span', null, ' by ', h('span', { class: 'mono' }, lock.runId || ''), lock.pid ? ' (pid ' + lock.pid + ')' : '')]
        : [chip('pass', 'Run lock is free')]),
      snaps ? h('div', { class: 'line' }, plural(snaps.count || 0, 'snapshot') + (snaps.bytes ? ', ' + fmtBytes(snaps.bytes) : '') + (snaps.retentionDays ? ', retention ' + snaps.retentionDays + ' days' : '')) : null,
      last && last.id ? h('div', { class: 'line' }, 'Last run ', runLink(last.id), ' ', outcomeChip(last.outcome)) : null),
    h('a', { class: 'cta', href: '#/new' }, 'Start a new operation')));
}

function recentRunsPanel(runs) {
  return panel(
    panelHead('Recent runs', h('a', { href: '#/runs' }, 'All runs')),
    table([
      { label: 'Run', cell: (r) => runLink(r.id) },
      { label: 'Operation', cell: runOpCell },
      { label: 'Started', cell: (r) => fmtDate(r.startedAt), class: 'nowrap' },
      { label: 'Duration', cell: (r) => h('span', { class: 'tabular' }, fmtDuration(r.durationMs)), class: 'nowrap' },
      { label: 'Outcome', cell: (r) => outcomeChip(r.outcome) },
    ], runs, 'No runs yet.'));
}

function hotfixesPanel(hotfixes, full) {
  const rows = hotfixes.filter((x) => !x.state || x.state === 'installed' || full);
  return panel(
    panelHead('Installed hotfixes', h('span', { class: 'muted' }, plural(rows.length, 'installed', 'installed')), full ? null : h('a', { href: '#/hotfixes' }, 'Hotfixes')),
    table([
      { label: 'Id', cell: (x) => h('span', { class: 'mono' }, x.id) },
      { label: 'Title', cell: (x) => x.title || '' },
      { label: 'Installed', cell: (x) => fmtDate(x.installedAt), class: 'nowrap' },
      { label: 'Files', cell: (x) => (typeof x.files === 'number' ? String(x.files) : Array.isArray(x.files) ? String(x.files.length) : '') },
      full ? { label: 'State', cell: (x) => (x.state === 'installed' || !x.state ? chip('pass', 'Installed') : x.state === 'rolled_back' ? chip('warn', 'Rolled back', 'undo') : chip('pending', x.state)) } : null,
      { label: '', class: 'actions', cell: (x) => hotfixAction(x) },
    ].filter(Boolean), rows, 'No hotfixes installed.'));
}

function hotfixAction(x) {
  if (x.state && x.state !== 'installed') return h('span', { class: 'muted' }, '');
  if (Array.isArray(x.blockedBy) && x.blockedBy.length > 0) {
    return h('span', { class: 'muted', title: 'Rollback is LIFO per file. Roll back the blocking hotfixes first or use cascade.' },
      'Blocked by ' + x.blockedBy.map((b) => b.replace(/^JRS-[\d.]+-/, '')).join(', '));
  }
  return h('a', { href: '#/new?op=hotfix.rollback&id=' + encodeURIComponent(x.id) }, 'Roll back');
}

/* ======================================================================
   New operation: form -> plan -> confirm -> run
   ====================================================================== */

function field(label, input, hint) {
  const id = input.id || ('f-' + Math.random().toString(36).slice(2, 8));
  input.id = id;
  return h('div', { class: 'field' }, h('label', { for: id }, label), input, hint ? h('span', { class: 'hint' }, hint) : null);
}

function textInput(name, opts) {
  const o = opts || {};
  return h('input', { type: o.type || 'text', name, class: o.mono ? 'mono' : null, value: o.value || '', placeholder: o.placeholder || null, required: o.required || null, autocomplete: 'off', spellcheck: 'false' });
}

function checkField(name, label, hint, checked) {
  const id = 'c-' + name;
  return h('label', { class: 'check', for: id }, h('input', { type: 'checkbox', name, id, checked: !!checked }),
    h('span', null, label, hint ? h('span', { class: 'hint' }, hint) : null));
}

function selectInput(name, options, value) {
  return h('select', { name }, options.map(([v, label]) => h('option', { value: v, selected: v === value }, label)));
}

function radioRow(name, legend, options, value) {
  return h('fieldset', null, h('legend', null, legend), h('div', { class: 'radio-row' },
    options.map(([v, label]) => h('label', null, h('input', { type: 'radio', name, value: v, checked: v === value }), label))));
}

function formValues(form) {
  const out = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    if (el.type === 'checkbox') out[el.name] = el.checked;
    else if (el.type === 'radio') { if (el.checked) out[el.name] = el.value; }
    else out[el.name] = el.value.trim();
  }
  return out;
}

const OPS = {
  'hotfix.apply': {
    label: 'Hotfix apply',
    fields: (q) => [
      field('Bundle path', textInput('bundle', { mono: true, required: true, value: q.get('bundle') || '', placeholder: 'C:\\hotfixes\\JRS-8.2.0-HF-0004.zip' }), 'Signed ZIP produced by jrsctl hotfix build. Read on the jrsctl host.'),
      checkField('allowUnsigned', 'Allow unsigned bundle (audited)', 'Only for bundles you built yourself. The override is written to the audit log.'),
      h('div', { class: 'callout info' }, h('span', null, 'A snapshot is taken before every mutation and the run rolls back to the phase boundary on failure. These are not optional.')),
    ],
    args: (v) => ({ bundle: v.bundle, allowUnsigned: !!v.allowUnsigned }),
    validate: (v) => (v.bundle ? null : 'Enter the bundle path.'),
  },
  'hotfix.rollback': {
    label: 'Hotfix rollback',
    fields: (q) => [
      field('Hotfix id', textInput('id', { mono: true, required: true, value: q.get('id') || '', placeholder: 'JRS-8.2.0-HF-0004' }), 'As listed under Installed hotfixes.'),
      checkField('cascade', 'Cascade', 'Also roll back later hotfixes that own the same files, newest to oldest, in one plan.'),
    ],
    args: (v) => ({ id: v.id, cascade: !!v.cascade }),
    validate: (v) => (v.id ? null : 'Enter the hotfix id.'),
  },
  export: {
    label: 'Export',
    fields: () => [
      field('Repository URIs', h('textarea', { name: 'uris', class: 'mono', placeholder: '/organizations/acme\n/public/Samples' }), 'One URI per line. Leave empty with "Full server" for everything.'),
      checkField('usersRoles', 'Include users and roles'),
      checkField('accessEvents', 'Include access events'),
      checkField('fullServer', 'Full server', 'Forces the vendor strategy, which stops the service.'),
      field('Strategy', selectInput('strategy', [['auto', 'Auto (REST when the server supports it)'], ['rest', 'REST'], ['vendor', 'Vendor CLI (js-export)']], 'auto')),
      field('Output file', textInput('out', { mono: true, required: true, placeholder: 'C:\\exports\\acme.zip' })),
    ],
    args: (v) => ({ uris: v.uris.split(/\r?\n/).map((s) => s.trim()).filter(Boolean), usersRoles: !!v.usersRoles, accessEvents: !!v.accessEvents,
      fullServer: !!v.fullServer, strategy: v.strategy === 'auto' ? null : v.strategy, out: v.out }),
    validate: (v) => (!v.out ? 'Enter the output file.' : !v.fullServer && !v.uris.trim() ? 'Enter at least one URI or choose Full server.' : null),
  },
  import: {
    label: 'Import',
    fields: () => [
      field('Archive', textInput('archive', { mono: true, required: true, placeholder: 'C:\\imports\\acme.zip' })),
      checkField('update', 'Update existing resources'),
      checkField('skipUserUpdate', 'Skip user update', 'Keep current user accounts; archive users are not applied.'),
      field('Source keystore', textInput('sourceKeystore', { mono: true, placeholder: 'C:\\imports\\keystore' }), 'Required when the archive was exported from another server.'),
      field('Source keystore password', textInput('sourceKeystorePassword', { type: 'password' }), 'Sent once with this request and never stored by the console.'),
      field('Strategy', selectInput('strategy', [['auto', 'Auto (REST when the server supports it)'], ['rest', 'REST'], ['vendor', 'Vendor CLI (js-import, stops the service)']], 'auto')),
    ],
    args: (v) => ({ archive: v.archive, update: !!v.update, skipUserUpdate: !!v.skipUserUpdate, sourceKeystore: v.sourceKeystore || null,
      sourceKeystorePassword: v.sourceKeystorePassword || null, strategy: v.strategy === 'auto' ? null : v.strategy }),
    validate: (v) => (v.archive ? null : 'Enter the archive path.'),
  },
  upgrade: {
    label: 'Upgrade',
    fields: () => [
      field('Target version', textInput('to', { mono: true, required: true, placeholder: '8.2.1' })),
      field('Upgrade package', textInput('package', { mono: true, required: true, placeholder: 'C:\\packages\\TIB_js-jrs_8.2.1_bin.zip' })),
      radioRow('mode', 'Mode', [['samedb', 'samedb (upgrade the schema in place)'], ['newdb', 'newdb (new database, old one untouched)']], 'samedb'),
      checkField('dbBackupConfirmed', 'I have a database backup', 'jrsctl does not back up the database. Required for samedb.'),
      checkField('reapplyHotfixes', 'Re-apply installed hotfixes where still applicable', null, true),
    ],
    args: (v) => ({ to: v.to, package: v.package, mode: v.mode, dbBackupConfirmed: !!v.dbBackupConfirmed, reapplyHotfixes: !!v.reapplyHotfixes }),
    validate: (v) => (!v.to || !v.package ? 'Enter the target version and the package path.' : v.mode === 'samedb' && !v.dbBackupConfirmed ? 'Confirm the database backup before a samedb upgrade.' : null),
  },
};

function stepper(current) {
  const labels = ['choose & fill', 'review plan', 'run'];
  return h('div', { class: 'stepper', 'aria-label': 'Progress' }, labels.map((l, i) => {
    const n = i + 1;
    const cls = n === current ? 'current' : n < current ? 'done' : '';
    return h('span', { class: 's ' + cls, 'aria-current': n === current ? 'step' : null }, h('span', { class: 'num' }, n < current ? icon('check') : String(n)), l);
  }));
}

async function newOperationView(ctx) {
  const q = ctx.query;
  let planResp = null;
  const stepperHost = h('div', null, stepper(1));
  const right = h('section', { class: 'panel' }, h('div', { class: 'panel-body' },
    h('span', { class: 'eyebrow' }, 'Plan'),
    h('p', { class: 'secondary' }, 'Build a plan to see what will change, whether the server goes down, where the backups are, and how to undo it. Nothing runs until you confirm.')));

  const opSelect = selectInput('op', Object.entries(OPS).map(([k, v]) => [k, v.label]), OPS[q.get('op')] ? q.get('op') : 'hotfix.apply');
  const fieldsHost = h('div', { class: 'form' });
  const buildBtn = h('button', { class: 'btn primary', type: 'submit' }, 'Build plan');
  const renderFields = () => { clear(fieldsHost); append(fieldsHost, OPS[opSelect.value].fields(q)); };
  opSelect.addEventListener('change', renderFields);
  renderFields();

  const form = h('form', { class: 'form', onsubmit: async (ev) => {
    ev.preventDefault();
    const op = opSelect.value;
    const def = OPS[op];
    const values = formValues(form);
    const problem = def.validate(values);
    if (problem) { toast(problem, 'warn'); return; }
    buildBtn.disabled = true;
    buildBtn.textContent = 'Building plan';
    try {
      planResp = await api.plan(op, def.args(values));
      clear(stepperHost); stepperHost.append(stepper(2));
      clear(right);
      right.append(planPane(planResp, ctx, {
        onBack: () => { clear(right); right.append(h('div', { class: 'panel-body' }, h('span', { class: 'eyebrow' }, 'Plan'), h('p', { class: 'secondary' }, 'Adjust the inputs and build the plan again.'))); clear(stepperHost); stepperHost.append(stepper(1)); },
        onRun: async () => {
          const plan = planResp.plan || {};
          const downtime = plan.summary && plan.summary.downtime ? ' ' + plan.summary.downtime : '';
          const ok = await confirmDialog({ title: 'Run this plan?', body: 'Every step is journaled and compensated on failure.' + downtime, confirmLabel: 'Run this plan', danger: /stops the server/.test(downtime) });
          if (!ok) return;
          clear(stepperHost); stepperHost.append(stepper(3));
          try {
            const res = await api.startRun(planResp.planId);
            navigate('#/runs/' + encodeURIComponent(res.runId));
          } catch (e) {
            clear(stepperHost); stepperHost.append(stepper(2));
            reportError(e);
          }
        },
      }));
      right.querySelector('.plan-title').focus();
    } catch (e) {
      reportError(e);
    } finally {
      buildBtn.disabled = false;
      buildBtn.textContent = 'Build plan';
    }
  } },
  field('Operation', opSelect),
  fieldsHost,
  h('div', { class: 'btn-row' }, buildBtn));

  const left = h('section', { class: 'panel' }, h('div', { class: 'panel-body' }, h('span', { class: 'eyebrow' }, 'Inputs'), form));
  return frag(stepperHost, h('div', { class: 'grid-plan' }, left, right));
}

const SUMMARY_LABELS = [
  ['filesTouched', 'Files touched'], ['service', 'Service'], ['database', 'Database'], ['backups', 'Backups'],
  ['rollbackPoints', 'Rollback points'], ['strategy', 'Strategy'], ['requires', 'Requires'], ['signature', 'Signature'],
];

function summaryText(v) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string' || typeof v === 'number') return String(v);
  if (Array.isArray(v)) return v.map(summaryText).join(', ');
  if (typeof v === 'object') return Object.entries(v).map(([k, x]) => k + ': ' + summaryText(x)).join('; ');
  return String(v);
}

function summaryGrid(summary) {
  const s = summary || {};
  const known = new Set(SUMMARY_LABELS.map(([k]) => k).concat(['warnings', 'downtime']));
  const entries = SUMMARY_LABELS.filter(([k]) => s[k] !== undefined && s[k] !== null && s[k] !== '').map(([k, l]) => [l, s[k]]);
  for (const [k, v] of Object.entries(s)) if (!known.has(k) && (typeof v === 'string' || typeof v === 'number')) entries.push([k, v]);
  return h('dl', { class: 'kv' }, entries.map(([l, v]) => [h('dt', null, l), h('dd', null, summaryText(v))]));
}

function groupByPhase(steps) {
  const groups = [];
  for (const s of steps || []) {
    const phase = s.phase || 'steps';
    let g = groups[groups.length - 1];
    if (!g || g.phase !== phase) { g = { phase, steps: [] }; groups.push(g); }
    g.steps.push(s);
  }
  return groups;
}

function planSteps(steps) {
  let n = 0;
  return h('div', { class: 'steps' }, groupByPhase(steps).map((g) => [
    h('div', { class: 'phase' }, g.phase),
    g.steps.map((s) => { n += 1; return h('div', { class: 'step' }, h('span', { class: 'n' }, String(n)),
      h('span', { class: 't' }, s.title || s.id, s.why ? h('span', { class: 'why' }, s.why) : null), h('span', null)); }),
  ]));
}

function planPane(resp, ctx, handlers) {
  const plan = resp.plan || {};
  const summary = plan.summary || {};
  const warnings = Array.isArray(summary.warnings) ? summary.warnings : [];
  const runBtn = h('button', { class: 'btn primary', type: 'button', onclick: handlers.onRun }, 'Run this plan');
  const validity = h('span', { class: 'muted' });
  const fingerprint = plan.fingerprint ? h('div', { class: 'kv' }, h('dt', null, 'Plan fingerprint'), h('dd', { class: 'mono' }, plan.fingerprint, ' ', validity)) : null;
  if (plan.validUntil) {
    const until = new Date(plan.validUntil).getTime();
    const tick = () => {
      const left = until - Date.now();
      if (left <= 0) { validity.textContent = 'expired, build the plan again'; runBtn.disabled = true; return; }
      validity.textContent = 'valid for ' + Math.max(1, Math.ceil(left / 60000)) + ' min';
    };
    tick();
    const timer = setInterval(tick, 15000);
    ctx.onCleanup(() => clearInterval(timer));
  }
  return frag(
    h('div', { class: 'panel-body' },
      h('h2', { class: 'plan-title', tabindex: '-1' }, plan.title || ('Plan: ' + opLabel(plan.op))),
      summaryGrid(summary),
      fingerprint,
      warnings.map((w) => h('div', { class: 'callout warn' }, chip('warn', 'Warning'), h('span', null, summaryText(w))))),
    planSteps(plan.steps),
    h('div', { class: 'panel-foot plan-foot' },
      h('span', null, h('strong', null, 'Nothing has changed yet. '), summary.downtime || ''),
      h('div', { class: 'btn-row' },
        h('button', { class: 'btn', type: 'button', onclick: handlers.onBack }, 'Back'),
        runBtn)));
}

/* ======================================================================
   Run view: step tree + live log
   ====================================================================== */

async function runView(ctx) {
  const id = ctx.params[0];
  const run = await api.run(id);
  setCrumb([{ label: 'Runs', href: '#/runs' }, { label: id }]);

  const model = {
    run,
    steps: new Map(),
    order: [],
    outcome: run.outcome || 'running',
    failure: run.failure || null,
    logCount: 0,
    paused: false,
  };
  const seed = run.steps || (run.plan && run.plan.steps) || [];
  for (const s of seed) { model.steps.set(s.id, { ...s, status: s.status || 'pending', durationMs: s.durationMs === undefined ? null : s.durationMs }); model.order.push(s.id); }

  /* header */
  const statusHost = h('span', null, outcomeChip(model.outcome));
  const elapsed = h('span', { class: 'elapsed', 'aria-label': 'Elapsed' }, '00:00');
  const cancelBtn = h('button', { class: 'btn danger', type: 'button', onclick: async () => {
    const ok = await confirmDialog({ title: 'Cancel run ' + id + '?', body: 'The in-flight step is completed or compensated first; a partial write is never abandoned. Steps that already succeeded in this phase are rolled back.', confirmLabel: 'Cancel run', danger: true });
    if (!ok) return;
    cancelBtn.disabled = true;
    try { await api.cancelRun(id); toast('Cancellation requested.', 'warn'); } catch (e) { cancelBtn.disabled = false; reportError(e); }
  } }, 'Cancel run');
  const rollbackBtn = h('button', { class: 'btn danger', type: 'button', onclick: () => rollbackRun(id) }, icon('undo'), 'Roll back');
  const actions = h('div', { class: 'btn-row' });
  const head = h('div', { class: 'run-head' }, h('span', { class: 'id' }, id), statusHost, elapsed, actions);
  const sub = h('div', { class: 'run-sub' }, h('div', { class: 'title' }, run.title || (opLabel(run.op) + (run.target ? ' ' + run.target : ''))),
    run.subtitle ? h('div', { class: 'secondary' }, run.subtitle) : null);
  const failHost = h('div', null);

  /* step tree */
  const stepsHost = h('div', { class: 'steps' });
  const backupsFoot = h('div', { class: 'panel-foot' }, h('span', { class: 'eyebrow' }, 'Backups'), h('span', { class: 'mono' }, run.backups || 'None recorded yet'));
  const stepsPanel = h('section', { class: 'panel' }, stepsHost, backupsFoot);

  /* log */
  const logBody = h('div', { class: 'log-body', role: 'log', 'aria-live': 'off', tabindex: '0' });
  const logCountEl = h('span', null, '0 lines');
  const connState = h('span', null, 'connecting');
  const pauseBtn = h('button', { class: 'btn small', type: 'button', 'aria-pressed': 'false', onclick: () => {
    model.paused = !model.paused;
    pauseBtn.setAttribute('aria-pressed', String(model.paused));
    clear(pauseBtn);
    append(pauseBtn, model.paused ? [icon('play'), 'Resume auto-scroll'] : [icon('pause'), 'Pause auto-scroll']);
    if (!model.paused) logBody.scrollTop = logBody.scrollHeight;
  } }, icon('pause'), 'Pause auto-scroll');
  const logPane = h('section', { class: 'log-pane', 'aria-label': 'Live log' },
    h('div', { class: 'log-head' }, h('span', null, 'Live log'), h('span', null, 'Secrets redacted'), connState, h('span', { class: 'spacer' }), logCountEl, pauseBtn),
    logBody);

  function renderActions() {
    clear(actions);
    const terminal = model.outcome !== 'running' && model.outcome !== 'pending';
    if (!terminal) actions.append(cancelBtn);
    if (terminal && model.run.rollbackAvailable) actions.append(rollbackBtn);
    if (model.run.supportBundleAvailable !== false) actions.append(supportBundleButton(id));
  }

  function renderSteps() {
    clear(stepsHost);
    const list = model.order.map((k) => model.steps.get(k));
    for (const g of groupByPhase(list)) {
      stepsHost.append(h('div', { class: 'phase' }, g.phase));
      for (const s of g.steps) {
        stepsHost.append(h('div', { class: 'step' + (s.status === 'running' || s.status === 'retry' ? ' active' : ''), 'data-step': s.id },
          h('span', { class: 'n' }, ''),
          h('span', { class: 't' }, s.title || s.id),
          h('span', { class: 'side' }, s.durationMs !== null && s.durationMs !== undefined ? h('span', { class: 'd' }, fmtDuration(s.durationMs)) : null, stepChip(s.status))));
      }
    }
    if (list.length === 0) stepsHost.append(h('p', { class: 'centered-note' }, 'Waiting for the plan steps.'));
  }

  function renderFailure() {
    clear(failHost);
    const f = model.failure;
    if (!f || (model.outcome !== 'failed' && model.outcome !== 'failed_rolled_back' && model.outcome !== 'rollback_incomplete')) return;
    failHost.append(h('div', { class: 'callout fail' },
      chip('fail', model.outcome === 'rollback_incomplete' ? 'Rollback incomplete, manual action required' : 'Run failed'),
      h('dl', { class: 'kv' },
        f.stepId ? [h('dt', null, 'Step'), h('dd', { class: 'mono' }, f.stepId)] : null,
        h('dt', null, 'Cause'), h('dd', null, f.cause || 'No cause recorded'),
        h('dt', null, 'Backups'), h('dd', { class: 'mono' }, Array.isArray(f.backups) ? f.backups.join('\n') : f.backups || model.run.backups || ''),
        h('dt', null, 'Next action'), h('dd', null, f.nextAction || 'Inspect the log, then download the support bundle.'))));
  }

  function appendLog(ts, stepId, message, level) {
    model.logCount += 1;
    const line = h('span', { class: 'log-line ' + (level || '') },
      h('span', { class: 'ts' }, ts ? fmtTime(ts) : '        '), ' ',
      stepId ? [h('span', { class: 'step-id' }, stepId), ' '] : '',
      message, '\n');
    logBody.append(line);
    logCountEl.textContent = plural(model.logCount, 'line');
    if (!model.paused) logBody.scrollTop = logBody.scrollHeight;
  }

  function resetForReplay() {
    for (const s of model.steps.values()) { s.status = 'pending'; s.durationMs = null; }
    clear(logBody);
    model.logCount = 0;
    logCountEl.textContent = '0 lines';
  }

  function ensureStep(d) {
    if (!d.stepId) return null;
    if (!model.steps.has(d.stepId)) { model.steps.set(d.stepId, { id: d.stepId, phase: d.phase || 'steps', title: d.title || d.stepId, status: 'pending', durationMs: null }); model.order.push(d.stepId); }
    return model.steps.get(d.stepId);
  }

  let handle = null;
  async function refreshRun() {
    try {
      model.run = await api.run(id);
      if (model.run.failure) model.failure = model.run.failure;
      if (model.run.outcome) model.outcome = model.run.outcome;
      backupsFoot.lastChild.textContent = model.run.backups || 'None recorded yet';
    } catch { /* keep what we have */ }
    renderAll();
  }

  function onEvent({ type, data }) {
    const d = (data && typeof data === 'object') ? data : { message: String(data) };
    const s = ensureStep(d);
    switch (type) {
      case 'PlanCreated': break;
      case 'StepPending': if (s) s.status = 'pending'; break;
      case 'StepRunning': if (s) { s.status = 'running'; s.startedAt = d.ts; } break;
      case 'StepRetry': if (s) s.status = 'retry'; appendLog(d.ts, d.stepId, 'retry ' + (d.attempt || '') + (d.of ? '/' + d.of : '') + (d.delayMs ? ' in ' + fmtDuration(d.delayMs) : ''), 'warn'); break;
      case 'StepSucceeded': if (s) { s.status = 'succeeded'; s.durationMs = typeof d.durationMs === 'number' ? d.durationMs : (s.startedAt ? new Date(d.ts) - new Date(s.startedAt) : null); } break;
      case 'StepFailed': if (s) s.status = 'failed'; model.failure = Object.assign({ stepId: d.stepId }, model.failure || {}, { cause: (d.failure && d.failure.cause) || d.cause || (model.failure && model.failure.cause) }); break;
      case 'StepSkipped': if (s) s.status = 'skipped'; break;
      case 'StepRolledBack': if (s) s.status = 'rolled_back'; break;
      case 'StepRollbackFailed': if (s) s.status = 'rollback_failed'; appendLog(d.ts, d.stepId, 'rollback failed: ' + (d.cause || ''), 'error'); break;
      case 'Log': appendLog(d.ts, d.stepId, d.message || '', d.level === 'error' ? 'error' : d.level === 'warn' ? 'warn' : ''); return;
      case 'RunSucceeded': model.outcome = 'succeeded'; terminal(d.ts); break;
      case 'RunFailed': model.outcome = d.rollbackIncomplete ? 'rollback_incomplete' : 'failed'; model.failure = Object.assign({}, model.failure || {}, { cause: d.cause || (model.failure && model.failure.cause), backups: d.backups, nextAction: d.nextAction }); terminal(d.ts); break;
      case 'RunRolledBack': model.outcome = 'failed_rolled_back'; model.failure = Object.assign({}, model.failure || {}, { cause: d.cause || (model.failure && model.failure.cause), backups: d.backups, nextAction: d.nextAction }); terminal(d.ts); break;
      case 'RunCancelled': model.outcome = 'cancelled'; terminal(d.ts); break;
      default: appendLog(d.ts, d.stepId, type + (d.message ? ' ' + d.message : ''), 'system'); return;
    }
    renderAll();
  }

  function terminal(ts) {
    if (handle) handle.done();
    appendLog(ts || new Date().toISOString(), null, 'run finished: ' + (OUTCOMES[model.outcome] || [null, model.outcome])[1].toLowerCase(), 'system');
    refreshRun();
  }

  function renderAll() {
    clear(statusHost);
    statusHost.append(outcomeChip(model.outcome));
    renderActions();
    renderSteps();
    renderFailure();
  }

  /* elapsed */
  const started = run.startedAt ? new Date(run.startedAt).getTime() : Date.now();
  const tick = () => {
    const end = model.outcome === 'running' || model.outcome === 'pending' ? Date.now() : (model.run.finishedAt ? new Date(model.run.finishedAt).getTime() : Date.now());
    elapsed.textContent = 'Elapsed ' + fmtElapsed(end - started);
  };
  tick();
  const timer = setInterval(tick, 1000);
  ctx.onCleanup(() => clearInterval(timer));

  /* stream */
  let opened = false;
  handle = api.events(id, onEvent, (kind, detail) => {
    if (kind === 'open') { connState.textContent = 'live'; if (opened) resetForReplay(); opened = true; renderAll(); }
    else if (kind === 'reconnecting') connState.textContent = 'reconnecting in ' + Math.round(detail / 1000) + 's';
    else if (kind === 'ended') connState.textContent = 'journal complete';
    else if (kind === 'error') { connState.textContent = 'stream unavailable'; reportError(detail); }
  });
  ctx.onCleanup(() => handle.close());

  renderAll();
  return frag(head, sub, failHost, h('div', { class: 'grid-run' }, stepsPanel, logPane));
}

/* ======================================================================
   Runs (history)
   ====================================================================== */

const PAGE_SIZE = 10;

async function runsView(ctx) {
  const [runs, health] = await Promise.all([api.runs(), api.health().catch(() => state.health)]);
  const filters = { op: '', outcome: '', q: '', page: 0 };
  const ops = Array.from(new Set(runs.map((r) => r.op).filter(Boolean)));
  const opSel = selectInput('op', [['', 'all operations']].concat(ops.map((o) => [o, opLabel(o)])), '');
  const outSel = selectInput('outcome', [['', 'any outcome']].concat(Object.entries(OUTCOMES).map(([k, v]) => [k, v[1].toLowerCase()])), '');
  const search = h('input', { type: 'search', name: 'q', placeholder: 'search run id', 'aria-label': 'Search run id', class: 'mono' });
  const host = h('div', null);
  const pager = h('div', { class: 'pager' });

  function filtered() {
    return runs.filter((r) => (!filters.op || r.op === filters.op) && (!filters.outcome || r.outcome === filters.outcome) && (!filters.q || String(r.id).toLowerCase().includes(filters.q.toLowerCase())));
  }

  function render() {
    const rows = filtered();
    const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    filters.page = Math.min(filters.page, pages - 1);
    const slice = rows.slice(filters.page * PAGE_SIZE, (filters.page + 1) * PAGE_SIZE);
    clear(host);
    host.append(table([
      { label: 'Run', cell: (r) => runLink(r.id) },
      { label: 'Operation', cell: runOpCell },
      { label: 'Started', cell: (r) => fmtDate(r.startedAt), class: 'nowrap' },
      { label: 'Duration', cell: (r) => h('span', { class: 'tabular' }, fmtDuration(r.durationMs)), class: 'nowrap' },
      { label: 'Outcome', cell: (r) => outcomeChip(r.outcome) },
      { label: 'Actions', class: 'actions', cell: (r) => frag(
        h('a', { href: '#/runs/' + encodeURIComponent(r.id) }, 'open'),
        r.rollbackAvailable ? h('button', { class: 'btn link small', type: 'button', onclick: () => rollbackRun(r.id) }, 'roll back') : null,
        r.supportBundleAvailable !== false ? supportBundleButton(r.id) : null) },
    ], slice, runs.length === 0 ? 'No runs recorded.' : 'No runs match these filters.'));
    clear(pager);
    // Pagination is client-side for now; the history endpoint returns the whole list.
    pager.append(
      h('button', { class: 'btn small', type: 'button', disabled: filters.page === 0, onclick: () => { filters.page -= 1; render(); } }, 'newer'),
      h('span', null, (filters.page + 1) + ' / ' + pages),
      h('button', { class: 'btn small', type: 'button', disabled: filters.page >= pages - 1, onclick: () => { filters.page += 1; render(); } }, 'older'));
  }

  opSel.addEventListener('change', () => { filters.op = opSel.value; filters.page = 0; render(); });
  outSel.addEventListener('change', () => { filters.outcome = outSel.value; filters.page = 0; render(); });
  search.addEventListener('input', () => { filters.q = search.value.trim(); filters.page = 0; render(); });
  render();

  return frag(
    pendingRecoveryBanner(health),
    h('div', { class: 'filters' }, opSel, outSel, search, h('span', { class: 'spacer' }), h('span', { class: 'muted' }, plural(runs.length, 'run'))),
    h('section', { class: 'panel' }, host, h('div', { class: 'panel-foot' }, pager)));
}

/* ======================================================================
   Doctor
   ====================================================================== */

async function doctorView(ctx) {
  const host = h('div', null);
  let report = state.doctorReport;

  async function runDoctor() {
    clear(host);
    host.append(h('p', { class: 'centered-note' }, 'Running doctor. Checks that reach the server may take a few seconds.'));
    try {
      report = await api.doctor();
      state.doctorReport = report;
    } catch (e) {
      clear(host);
      host.append(h('div', { class: 'callout fail' }, chip('fail', 'Doctor could not run'), h('span', null, errorMessage(e))));
      if (e && e.status === 401) reportError(e);
      return;
    }
    render();
  }

  function render() {
    clear(host);
    const items = Array.isArray(report.items) ? report.items.slice() : [];
    const rank = { FAIL: 0, WARN: 1, PASS: 2 };
    items.sort((a, b) => (rank[String(a.status).toUpperCase()] ?? 3) - (rank[String(b.status).toUpperCase()] ?? 3));
    const counts = report.counts || items.reduce((acc, it) => { acc[String(it.status).toLowerCase()] = (acc[String(it.status).toLowerCase()] || 0) + 1; return acc; }, { pass: 0, warn: 0, fail: 0 });
    const attention = items.filter((it) => String(it.status).toUpperCase() !== 'PASS');
    const passes = items.filter((it) => String(it.status).toUpperCase() === 'PASS');
    const shown = passes.slice(0, 8);
    const rest = passes.slice(8);
    const json = JSON.stringify(report, null, 2);
    const lastRunId = state.health && state.health.lastRun && state.health.lastRun.id;

    host.append(
      h('div', { class: 'btn-row' },
        h('span', { class: 'muted' }, report.ranAt ? 'Last run ' + fmtDate(report.ranAt) : ''),
        h('span', { class: 'spacer' }),
        h('button', { class: 'btn primary', type: 'button', onclick: runDoctor }, icon('refresh'), 'Run doctor')),
      h('div', { class: 'counts' },
        h('span', { class: 'count pass' }, icon('check'), plural(counts.pass || 0, 'pass', 'pass')),
        h('span', { class: 'count warn' }, icon('warn'), plural(counts.warn || 0, 'warn', 'warn')),
        h('span', { class: 'count fail' }, icon('fail'), plural(counts.fail || 0, 'fail', 'fail'))),
      h('section', { class: 'panel' },
        h('div', { class: 'doctor-list' },
          attention.map((it) => doctorItem(it, false)),
          shown.map((it) => doctorItem(it, true)),
          rest.length > 0 ? h('details', null, h('summary', null, rest.length + ' more passes'), rest.map((it) => doctorItem(it, true))) : null,
          items.length === 0 ? h('p', { class: 'centered-note' }, 'The report has no items.') : null),
        h('div', { class: 'panel-foot' }, h('div', { class: 'btn-row' },
          h('a', { class: 'btn', href: 'data:application/json;charset=utf-8,' + encodeURIComponent(json), download: 'jrsctl-doctor.json' }, icon('download'), 'Download report (JSON)'),
          lastRunId ? supportBundleButton(lastRunId) : h('span', { class: 'muted' }, 'Support bundles are per run; open a run to download one.'),
          h('span', { class: 'muted' }, 'Same content as jrsctl doctor --json')))));
  }

  function doctorItem(it, compact) {
    const status = String(it.status || '').toUpperCase();
    return h('div', { class: 'doctor-item' + (compact ? ' compact' : '') },
      h('div', { class: 'row' }, doctorChip(status), h('span', { class: 'title' }, it.title || it.id || ''), it.detail && !compact ? h('span', { class: 'detail' }, it.detail) : null),
      compact && it.detail ? h('div', { class: 'detail', style: null }, it.detail) : null,
      status !== 'PASS' && it.remediation ? h('div', { class: 'remedy' }, it.remediation) : null);
  }

  if (report) render();
  else await runDoctor();
  ctx.onCleanup(() => {});
  return host;
}

/* ======================================================================
   Hotfixes
   ====================================================================== */

async function hotfixesView(ctx) {
  const hotfixes = await api.hotfixes();
  const resultHost = h('div', null);
  const bundle = textInput('bundle', { mono: true, required: true, placeholder: 'C:\\hotfixes\\JRS-8.2.0-HF-0005.zip' });
  const verifyBtn = h('button', { class: 'btn primary', type: 'submit' }, 'Verify bundle');
  const form = h('form', { class: 'form', onsubmit: async (ev) => {
    ev.preventDefault();
    if (!bundle.value.trim()) { toast('Enter the bundle path.', 'warn'); return; }
    verifyBtn.disabled = true;
    try {
      const resp = await api.plan('hotfix.verify', { bundle: bundle.value.trim() });
      clear(resultHost);
      resultHost.append(h('section', { class: 'panel' }, planPane(resp, ctx, {
        onBack: () => clear(resultHost),
        onRun: async () => {
          try { const res = await api.startRun(resp.planId); navigate('#/runs/' + encodeURIComponent(res.runId)); } catch (e) { reportError(e); }
        },
      })));
    } catch (e) {
      reportError(e);
    } finally {
      verifyBtn.disabled = false;
    }
  } },
  field('Bundle path', bundle, 'Checks signature, hashes and applicability only. Nothing is installed.'),
  h('div', { class: 'btn-row' }, verifyBtn));

  return frag(
    hotfixesPanel(hotfixes, true),
    h('div', { class: 'grid-2' },
      h('section', { class: 'panel' }, h('div', { class: 'panel-body' }, h('span', { class: 'eyebrow' }, 'Verify bundle'), form)),
      h('section', { class: 'panel' }, h('div', { class: 'panel-body' }, h('span', { class: 'eyebrow' }, 'Apply'),
        h('p', { class: 'secondary' }, 'To install a hotfix, start a new operation with "Hotfix apply". Rollback is last-in-first-out per file: a hotfix whose files were later replaced by another hotfix shows "Blocked by" until that one is rolled back, or use cascade.'),
        h('a', { class: 'btn', href: '#/new?op=hotfix.apply' }, 'New hotfix apply')))),
    resultHost);
}

/* ======================================================================
   Start
   ====================================================================== */

boot();
