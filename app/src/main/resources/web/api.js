// api.js — fetch wrapper for the jrsctl console API (spec §13.1, §11.2).
//
// Every request carries "Authorization: Bearer <token>". The token is generated per console
// launch, printed once to the terminal, and handed to the browser either through the
// "#token=" URL fragment (stripped on first load) or by pasting it into the token panel.
// It lives in sessionStorage only; no cookies are used (spec §11.2).
//
// The SSE run stream cannot use EventSource because EventSource cannot send a custom header,
// so the stream is read with fetch + ReadableStream and parsed by hand.

const TOKEN_KEY = 'jrsctl.token';
const API_BASE = '/api';

/** Error thrown for any non-2xx response or network failure. status 0 = unreachable. */
export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

/* ---------- backend switch (real HTTP or mock.js) ---------- */

let backend = null;

/** Route every call through a mock backend (see mock.js). */
export function useMock(impl) {
  backend = impl;
}

export function isMock() {
  return backend !== null;
}

/* ---------- token handling ---------- */

function storage() {
  try {
    return window.sessionStorage;
  } catch {
    return null; // storage can be blocked on file:// or by policy
  }
}

let memoryToken = null;

export function getToken() {
  const s = storage();
  return (s && s.getItem(TOKEN_KEY)) || memoryToken;
}

export function setToken(token) {
  memoryToken = token;
  const s = storage();
  if (s) {
    if (token) s.setItem(TOKEN_KEY, token);
    else s.removeItem(TOKEN_KEY);
  }
}

/**
 * On first load, accept "#token=<value>" or "#launch=<value>" from the URL, store it, and replace
 * the fragment so credentials never stay in the address bar or browser history.
 */
export async function loadToken() {
  const m = /^#token=([^&]+)/.exec(window.location.hash);
  if (m) {
    setToken(decodeURIComponent(m[1]));
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/dashboard');
  }
  const l = /^#launch=([^&]+)/.exec(window.location.hash);
  if (l) {
    const code = decodeURIComponent(l[1]);
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/dashboard');
    try {
      const res = await fetch(API_BASE + '/auth/launch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code })
      });
      if (res.ok) {
        const body = await res.json();
        if (body && body.token) {
          setToken(body.token);
        }
      }
    } catch {
      // ignore, fall back to getToken()
    }
  }
  return getToken();
}

function authHeaders(extra) {
  const headers = Object.assign({}, extra);
  const token = getToken();
  if (token) headers.Authorization = 'Bearer ' + token;
  return headers;
}

/* ---------- JSON requests ---------- */

function messageFor(status, parsed) {
  if (parsed && typeof parsed.message === 'string') return parsed.message;
  if (parsed && typeof parsed.error === 'string') return parsed.error;
  switch (status) {
    case 401: return 'The console token is missing or invalid.';
    case 403: return 'This action is not permitted.';
    case 404: return 'Not found.';
    case 409: return 'The request conflicts with the current state (lock held or plan changed).';
    case 421: return 'Host header rejected. Open the console using the address it was bound to.';
    case 410: return 'The plan has expired. Build it again.';
    default: return 'The console API returned HTTP ' + status + '.';
  }
}

async function parseBody(res) {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

async function request(method, path, body) {
  if (backend) return backend.request(method, path, body);
  const headers = authHeaders({ Accept: 'application/json' });
  const init = { method, headers, credentials: 'omit' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(API_BASE + path, init);
  } catch {
    throw new ApiError(0, 'The console API is unreachable.', null);
  }
  if (!res.ok) {
    const parsed = await parseBody(res);
    throw new ApiError(res.status, messageFor(res.status, parsed), parsed);
  }
  if (res.status === 204) return null;
  return parseBody(res);
}

/** Fetch a binary endpoint with the bearer header and hand it to the browser as a download. */
async function download(path, filename) {
  let blob;
  if (backend) {
    blob = await backend.download(path);
  } else {
    let res;
    try {
      res = await fetch(API_BASE + path, { headers: authHeaders({}), credentials: 'omit' });
    } catch {
      throw new ApiError(0, 'The console API is unreachable.', null);
    }
    if (!res.ok) {
      const parsed = await parseBody(res);
      throw new ApiError(res.status, messageFor(res.status, parsed), parsed);
    }
    blob = await res.blob();
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

const enc = encodeURIComponent;

/* ---------- SSE over fetch ---------- */

/** Parse one complete SSE event block into {type, data, id}. */
function finishEvent(ev, onEvent) {
  if (ev.data.length === 0 && ev.type === 'message') return;
  const raw = ev.data.join('\n');
  let data = raw;
  try {
    data = JSON.parse(raw);
  } catch {
    // Non-JSON payloads are passed through as strings.
  }
  // The event name comes from the "event:" line; fall back to a "type" field in the payload.
  const type = ev.type !== 'message' ? ev.type : (data && data.type) || 'message';
  onEvent({ type, data, id: ev.id });
}

async function readSseStream(body, onEvent, isClosed) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let ev = { type: 'message', data: [], id: null };
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      let line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);
      if (line === '') {
        finishEvent(ev, onEvent);
        ev = { type: 'message', data: [], id: null };
        continue;
      }
      if (line.startsWith(':')) continue; // comment / keep-alive
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value2 = colon < 0 ? '' : line.slice(colon + 1);
      if (value2.startsWith(' ')) value2 = value2.slice(1);
      if (field === 'event') ev.type = value2;
      else if (field === 'data') ev.data.push(value2);
      else if (field === 'id') ev.id = value2;
    }
    if (isClosed()) {
      await reader.cancel();
      break;
    }
  }
}

/**
 * Subscribe to GET /api/runs/{id}/events. The server replays the journal first, then streams
 * live events. On a dropped connection we reconnect with exponential backoff; because the
 * server replays on every connect, the caller resets its state when onStatus('open') fires.
 *
 * onStatus(kind, detail): 'open' | 'reconnecting' (delay ms) | 'ended' | 'error' (ApiError).
 * Returns { close(), done() } — call done() after a terminal event so a server-side close is
 * not treated as a drop.
 */
export function events(runId, onEvent, onStatus) {
  if (backend) return backend.events(runId, onEvent, onStatus);
  const state = { closed: false, terminal: false, abort: null, attempt: 0 };
  const status = (kind, detail) => { if (onStatus && !state.closed) onStatus(kind, detail); };

  async function connect() {
    if (state.closed) return;
    const ac = new AbortController();
    state.abort = ac;
    try {
      const res = await fetch(API_BASE + '/runs/' + enc(runId) + '/events', {
        headers: authHeaders({ Accept: 'text/event-stream' }),
        credentials: 'omit',
        signal: ac.signal,
      });
      if (!res.ok) {
        const parsed = await parseBody(res);
        throw new ApiError(res.status, messageFor(res.status, parsed), parsed);
      }
      state.attempt = 0;
      status('open');
      await readSseStream(res.body, onEvent, () => state.closed);
      if (state.closed || state.terminal) {
        status('ended');
        return;
      }
      scheduleReconnect();
    } catch (e) {
      if (state.closed) return;
      if (e instanceof ApiError && (e.status === 401 || e.status === 404 || e.status === 421)) {
        status('error', e);
        return;
      }
      scheduleReconnect();
    }
  }

  function scheduleReconnect() {
    state.attempt += 1;
    const delay = Math.min(30000, 1000 * 2 ** (state.attempt - 1));
    status('reconnecting', delay);
    setTimeout(connect, delay);
  }

  connect();
  return {
    close() {
      state.closed = true;
      if (state.abort) state.abort.abort();
    },
    done() {
      state.terminal = true;
    },
  };
}

/* ---------- endpoint helpers (spec §13.1) ---------- */

/** Accept either a bare array or an envelope such as {runs: [...]}. */
function unwrapList(value, key) {
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value[key])) return value[key];
  if (value && Array.isArray(value.items)) return value.items;
  return [];
}

export const api = {
  health: () => request('GET', '/health'),
  server: () => request('GET', '/server'),
  /** {op, args} -> {planId, plan}. op is one of hotfix.apply, hotfix.rollback, hotfix.verify, export, import, upgrade. */
  plan: (op, args) => request('POST', '/plan', { op, args }),
  /** {planId, confirm: true} -> {runId}. Refused (409/410) if the fingerprint changed or the TTL expired. */
  startRun: (planId) => request('POST', '/run', { planId, confirm: true }),
  runs: async () => unwrapList(await request('GET', '/runs'), 'runs'),
  run: (id) => request('GET', '/runs/' + enc(id)),
  cancelRun: (id) => request('POST', '/runs/' + enc(id) + '/cancel'),
  rollbackRun: (id) => request('POST', '/runs/' + enc(id) + '/rollback'),
  // Assumed: resume for an interrupted run (spec §6.6 defines it for the CLI as `runs recover --resume`).
  resumeRun: (id) => request('POST', '/runs/' + enc(id) + '/resume'),
  supportBundle: (id) => download('/runs/' + enc(id) + '/support-bundle', id + '-support-bundle.zip'),
  doctor: () => request('GET', '/doctor'),
  // Assumed shape: {hotfixes: [{id, title, installedAt, files, state, blockedBy: []}]}.
  hotfixes: async () => unwrapList(await request('GET', '/hotfixes'), 'hotfixes'),
  events,
};
