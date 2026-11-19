// Pure logic shared by the web app and its tests: topics, the host/wake state machine,
// broker profile validation and formatting. Mirrors app/src/main/.../core on Android.

export const DEFAULT_PREFIX = 'sinkhole';
export const MAX_NAME_LENGTH = 64;
export const WAKE_TIMEOUT_MS = 3 * 60 * 1000;
// While the board reports the wake itself (firmware 0.6+): its own wait is per PC, up to
// 60 minutes, and it publishes the result when it ends.
export const BOARD_WAKE_LIMIT_MS = 61 * 60 * 1000;
// How long Cancel waits for the board's "cancelled" before stopping the wait here anyway.
export const CANCEL_CONFIRM_MS = 10_000;
/** How long the blocking switch waits for the board to confirm a change. */
export const BLOCKING_CONFIRM_MS = 10_000;

// --- topics -------------------------------------------------------------------------

export const Topics = {
  normalizePrefix: (raw) => String(raw ?? '').trim().replace(/^\/+|\/+$/g, ''),

  prefixError(prefix) {
    if (!prefix) return 'Enter the topic prefix set on the board';
    if (/[+#]/.test(prefix)) return "The prefix can't contain + or #";
    if (prefix.includes('//')) return "The prefix can't contain empty levels (//)";
    if (/[\u0000-\u001f]/.test(prefix)) return 'Invalid character';
    return null;
  },

  nameError(name) {
    if (!name) return 'Enter the PC name exactly as on the board';
    if (name.trim() === '' || name !== name.trim()) return 'Remove leading or trailing spaces';
    if (/[/+#]/.test(name)) return "Names can't contain /, + or #";
    if (/[\u0000-\u001f]/.test(name)) return 'Invalid character';
    if (name.length > MAX_NAME_LENGTH) return `At most ${MAX_NAME_LENGTH} characters`;
    return null;
  },

  status: (p) => `${p}/status`,
  stateFilter: (p) => `${p}/wol/+/state`,
  state: (p, name) => `${p}/wol/${name}/state`,
  set: (p, name) => `${p}/wol/${name}/set`,
  resultFilter: (p) => `${p}/wol/+/result`,
  blocking: (p) => `${p}/blocking`,
  blockingSet: (p) => `${p}/blocking/set`,
  stats: (p) => `${p}/stats`,

  /** Everything the app reads. Wake and blocking topics are only written. */
  filters: (p) => [`${p}/status`, `${p}/wol/+/state`, `${p}/blocking`, `${p}/stats`, `${p}/wol/+/result`],

  /** Filters the app works without (older boards, or a login not allowed to read them). */
  optionalFilters: (p) => [`${p}/blocking`, `${p}/stats`, `${p}/wol/+/result`],

  /** Host name from `<prefix>/wol/<name>/state`, or null. */
  hostFromState: (prefix, topic) => hostFrom(prefix, topic, '/state'),

  /** Host name from `<prefix>/wol/<name>/result` (firmware 0.6+), or null. */
  hostFromResult: (prefix, topic) => hostFrom(prefix, topic, '/result'),

  /** 'online' | 'offline' | 'cleared' (empty retained payload) | null (unknown, ignore). */
  parsePresence(payload) {
    const v = String(payload ?? '').trim().toLowerCase();
    if (v === '') return 'cleared';
    if (['online', 'on', 'up', '1', 'true'].includes(v)) return 'online';
    if (['offline', 'off', 'down', '0', 'false'].includes(v)) return 'offline';
    return null;
  },
};

function hostFrom(prefix, topic, tail) {
  const head = `${prefix}/wol/`;
  if (!topic.startsWith(head) || !topic.endsWith(tail)) return null;
  if (topic.length <= head.length + tail.length) return null;
  const name = topic.slice(head.length, topic.length - tail.length);
  return name && !name.includes('/') ? name : null;
}

/**
 * `<prefix>/wol/<name>/result` (firmware 0.6+, retained) -> { kind: 'waking' | 'online'
 * (seconds) | 'noAnswer' (text) | 'cancelled' | 'alreadyOnline' | 'watchOnly' | 'other' (text) }, or null
 * for an empty (cleared) payload.
 */
export function parseResult(payload) {
  const text = String(payload ?? '').trim().slice(0, 64);
  const l = text.toLowerCase();
  if (!text) return null;
  if (l === 'waking') return { kind: 'waking' };
  if (l === 'cancelled' || l === 'canceled') return { kind: 'cancelled' };
  if (l === 'already online') return { kind: 'alreadyOnline' };
  if (l === 'watch only') return { kind: 'watchOnly' };
  if (l.startsWith('no answer')) return { kind: 'noAnswer', text };
  const m = /^online after (\d+) s$/.exec(l);
  if (m) return { kind: 'online', seconds: Number(m[1]) };
  return { kind: 'other', text };
}

// --- ad blocking and stats (<prefix>/blocking, <prefix>/stats) -------------------------

/** "ON"/"OFF" as the board publishes them -> true/false; anything else -> null (ignored). */
export function parseBlocking(payload) {
  const v = String(payload ?? '').trim().toUpperCase();
  if (['ON', '1', 'TRUE'].includes(v)) return true;
  if (['OFF', '0', 'FALSE'].includes(v)) return false;
  return null;
}

/**
 * `{"blocked":1,"allowed":2,"percent":33.3,"domains":4,"clients":5,"uptime":60,"restart":"power on"}` (every 60 s, counters
 * since the board restarted) -> stats object, or null when blocked/allowed are missing.
 */
export function parseStats(payload, at) {
  let o;
  try { o = JSON.parse(payload); } catch { return null; }
  const n = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
  if (!o || n(o.blocked) === null || n(o.allowed) === null) return null;
  const total = o.blocked + o.allowed;
  return {
    blocked: o.blocked,
    allowed: o.allowed,
    total,
    percent: n(o.percent) ?? (total ? (o.blocked * 100) / total : 0),
    domains: n(o.domains) ?? 0,
    clients: n(o.clients) ?? 0,
    at,
    // Firmware 0.6+: seconds since boot (when received) and why the board last restarted.
    uptime: n(o.uptime) !== null && o.uptime >= 0 ? Math.floor(o.uptime) : null,
    restart: typeof o.restart === 'string' && o.restart.trim() ? o.restart.trim().slice(0, 60) : null,
  };
}

/** Uptime now, counting the time since the stats arrived; null on older firmware. */
export const statsUptime = (st, now) => (st.uptime === null ? null : st.uptime + Math.max(0, Math.floor((now - st.at) / 1000)));

/** The last restart wasn't asked for: a crash, the watchdog or a power dip. */
export const unexpectedRestart = (st) => !!st.restart && /crash|watchdog|brownout|panic/i.test(st.restart);

// The switch: { on: true|false|null (never seen), requested: value asked for until the
// board confirms, requestedAt, problem: why the last change didn't go through }.
export const newBlocking = () => ({ on: null, requested: null, requestedAt: 0, problem: null });
export const blockingShown = (b) => b.requested ?? b.on;

export function blockingReported(b, value) {
  return b.requested === value ? { ...b, on: value, requested: null, problem: null } : { ...b, on: value };
}
export const blockingRequested = (b, value, now) => ({ ...b, requested: value, requestedAt: now, problem: null });
export const blockingFailed = (b, reason) => ({ ...b, requested: null, problem: reason });

/** Gives up waiting after BLOCKING_CONFIRM_MS. Returns the same object when nothing changed. */
export function blockingTick(b, now, setTopic) {
  if (b.requested === null || now - b.requestedAt < BLOCKING_CONFIRM_MS) return b;
  return blockingFailed(b, `The board didn't confirm. The broker login may not be allowed to publish ${setTopic}.`);
}

// --- broker profile -------------------------------------------------------------------

export const DEFAULT_WSS_PORT = 8084;
export const DEFAULT_WSS_PATH = '/mqtt';

export function defaultProfile() {
  return { host: '', port: DEFAULT_WSS_PORT, path: DEFAULT_WSS_PATH, username: '', password: '', prefix: DEFAULT_PREFIX };
}

export function normalizeProfile(p) {
  let path = String(p.path ?? '').trim() || DEFAULT_WSS_PATH;
  if (!path.startsWith('/')) path = '/' + path;
  return {
    ...p,
    host: String(p.host ?? '').trim(),
    port: Number(p.port),
    path,
    username: String(p.username ?? '').trim(),
    password: String(p.password ?? ''),
    prefix: Topics.normalizePrefix(p.prefix),
  };
}

/** Field -> message for every invalid field ({} when usable). */
export function profileErrors(raw) {
  const p = normalizeProfile(raw);
  const e = {};
  if (!p.host) e.host = 'Enter the broker address';
  else if (p.host.includes('://')) e.host = 'Address only, without wss:// or mqtts://';
  else if (/[\s/]/.test(p.host)) e.host = 'Address only, no spaces or paths';
  if (!Number.isInteger(p.port) || p.port < 1 || p.port > 65535) e.port = 'Port 1 to 65535';
  if (/\s/.test(p.path)) e.path = 'No spaces in the path';
  const pe = Topics.prefixError(p.prefix);
  if (pe) e.prefix = pe;
  if (p.password && !p.username) e.username = 'Enter the username for this password';
  return e;
}

export const isConfigured = (p) => Object.keys(profileErrors(p)).length === 0;

/** wss://host:port/path */
export function brokerUrl(raw) {
  const p = normalizeProfile(raw);
  return `wss://${p.host}:${p.port}${p.path}`;
}

// --- setup link (the QR code a board shows under Wake-on-LAN → Phone app) ----------------
//
// https://joanrra2.github.io/pocket-wake/#setup?v=1&host=…&port=8883&tls=1&wsport=8084&wspath=%2Fmqtt&prefix=sinkhole&user=…
// port/tls are for the Android app (MQTT over TLS); wsport/wspath for this web app. Never a password.

/** { ok: true, link } or { ok: false, message }. */
export function parseSetupLink(text) {
  const t = String(text ?? '').trim();
  const at = t.indexOf('#setup?');
  if (at < 0) return { ok: false, message: "This isn't a Pocket Wake setup link" };
  const q = new URLSearchParams(t.slice(at + '#setup?'.length));
  if (Number(q.get('v') || 1) > 1) return { ok: false, message: 'This setup link is newer than the app. Reload the page' };
  const host = (q.get('host') || '').trim();
  if (!host || /[\s/]/.test(host)) return { ok: false, message: 'The setup link has no valid broker address' };
  const tls = q.get('tls') !== '0';
  const port = q.has('port') ? Number(q.get('port')) : tls ? 8883 : 1883;
  if (!Number.isInteger(port) || port < 1 || port > 65535) return { ok: false, message: 'The setup link has an invalid port' };
  const prefix = Topics.normalizePrefix(q.get('prefix') ?? DEFAULT_PREFIX);
  const pe = Topics.prefixError(prefix);
  if (pe) return { ok: false, message: `The setup link has an invalid prefix: ${pe}` };
  const ws = Number(q.get('wsport'));
  const wsPath = q.get('wspath') || '';
  return {
    ok: true,
    link: {
      host, port, tls, prefix,
      username: (q.get('user') || '').trim() || null,
      wsPort: Number.isInteger(ws) && ws >= 1 && ws <= 65535 ? ws : null,
      wsPath: wsPath.startsWith('/') ? wsPath : null,
    },
  };
}

/** The web profile with the link's settings; the password stays only if the broker and user are unchanged. */
export function applySetupLink(profile, link) {
  const p = normalizeProfile(profile);
  const username = link.username ?? p.username;
  const port = link.wsPort ?? DEFAULT_WSS_PORT;
  const same = p.host.toLowerCase() === link.host.toLowerCase() && p.port === port && p.username === username;
  return {
    ...p,
    host: link.host,
    port,
    path: link.wsPath ?? DEFAULT_WSS_PATH,
    prefix: link.prefix,
    username,
    password: same ? p.password : '',
  };
}

// --- hosts and wakes ------------------------------------------------------------------
//
// host = { name, presence: 'online'|'offline'|'unknown', presenceSince, reportsState, manual,
//          wake: { kind, startedAt?, onlineAt?, at?, reason? }, lastWake: { at, s } | null }
// wake.kind: idle | sending | waiting | succeeded | sentUnverified | alreadyOnline | timedOut |
//            cancelled | failed | watchOnly
// watchOnly: true on a host the board only watches (no MAC saved, firmware 0.6+): no Wake button.
// A waiting wake has board: true once the board said "waking" (firmware 0.6+: it publishes
// the result, keeps its own per-PC wait and understands "cancel"), and cancelAt (ms, 0 = no).
// timedOut may carry text: the board's own result ("no answer after 10 min").
// A book is a plain object { [name]: host }. Every function returns a new book.

const IDLE = Object.freeze({ kind: 'idle' });

export function newHost(name, extra = {}) {
  return { name, presence: 'unknown', presenceSince: null, reportsState: false, manual: false, wake: IDLE, lastWake: null, ...extra };
}

export const isBusy = (w) => w.kind === 'sending' || w.kind === 'waiting';
export const wakeSeconds = (w) => Math.max(0, Math.floor((w.onlineAt - w.startedAt) / 1000));
export const anyBusy = (book) => Object.values(book).some((h) => isBusy(h.wake));

const put = (book, h) => ({ ...book, [h.name]: h });
const without = (book, name) => { const b = { ...book }; delete b[name]; return b; };
const edit = (book, name, f) => (book[name] ? put(book, f(book[name])) : book);

/** Hosts sorted by name (case-insensitive, then exact) so cards don't jump around. */
export function sortedHosts(book) {
  return Object.values(book).sort((a, b) => {
    const x = a.name.toLowerCase(), y = b.name.toLowerCase();
    if (x !== y) return x < y ? -1 : 1;
    return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
  });
}

export function addManual(book, name) {
  return put(book, book[name] ? { ...book[name], manual: true } : newHost(name, { manual: true }));
}

export function withManual(book, names) {
  return names.reduce(addManual, book);
}

export function removeManual(book, name) {
  const h = book[name];
  if (!h) return book;
  return h.reportsState ? put(book, { ...h, manual: false }) : without(book, name);
}

export function withLastWakes(book, last) {
  const b = { ...book };
  for (const [n, lw] of Object.entries(last)) if (b[n]) b[n] = { ...b[n], lastWake: lw };
  return b;
}

export function lastWakes(book) {
  const out = {};
  for (const h of Object.values(book)) if (h.lastWake) out[h.name] = h.lastWake;
  return out;
}

/**
 * Gives hosts that have no last wake yet the one saved earlier. Hosts discovered from the
 * board only appear after connecting, so this runs on every book change, not just at start.
 */
export function adoptLastWakes(book, saved) {
  let out = book;
  for (const [n, lw] of Object.entries(saved)) {
    if (out[n] && !out[n].lastWake) out = put(out, { ...out[n], lastWake: lw });
  }
  return out;
}

/** Saved map plus the book's current values (never drops PCs that aren't shown right now). */
export const mergeLastWakes = (saved, book) => ({ ...saved, ...lastWakes(book) });

export function manualNames(book) {
  return Object.values(book).filter((h) => h.manual).map((h) => h.name).sort();
}

/** Drops everything learned from the broker (after switching broker or prefix). */
export function forgetDiscovered(book) {
  const b = {};
  for (const h of Object.values(book)) {
    if (h.manual) b[h.name] = { ...h, presence: 'unknown', presenceSince: null, reportsState: false, wake: IDLE, watchOnly: false };
  }
  return b;
}

/**
 * Applies a `<prefix>/wol/<name>/state` message. live = false for retained messages
 * delivered on subscribe (they carry no timestamp, so they get no "since").
 */
export function onState(book, name, presence, now, live = true) {
  const old = book[name];
  if (presence === 'cleared' || presence === 'unknown') {
    if (!old) return book;
    return old.manual
      ? put(book, { ...old, presence: 'unknown', presenceSince: null, reportsState: false })
      : without(book, name);
  }
  const base = old ?? newHost(name);
  const since = base.presence === presence ? base.presenceSince : live ? now : null;
  let wake = base.wake;
  let lastWake = base.lastWake;
  if (presence === 'online') {
    let started = null;
    if (wake.kind === 'waiting' || wake.kind === 'sending') started = wake.startedAt;
    else if (wake.kind === 'sentUnverified') started = wake.at;
    else if (wake.kind === 'timedOut' && now - wake.startedAt < 2 * WAKE_TIMEOUT_MS) started = wake.startedAt;
    if (started !== null) {
      wake = { kind: 'succeeded', startedAt: started, onlineAt: now };
      lastWake = { at: started, s: wakeSeconds(wake) };
    }
  }
  return put(book, { ...base, presence, presenceSince: since, reportsState: true, wake, lastWake });
}

/** A watch-only PC gets wake.kind 'watchOnly' instead, and nothing should be published. */
export function onWakeRequested(book, name, now) {
  const h = book[name] ?? newHost(name, { manual: true });
  return put(book, { ...h, wake: h.watchOnly ? { kind: 'watchOnly', at: now } : { kind: 'sending', startedAt: now } });
}

/** The broker accepted the wake request. */
export function onWakePublished(book, name, now) {
  return edit(book, name, (h) => {
    if (h.wake.kind !== 'sending') return h; // an `online` already arrived; keep that result
    const started = h.wake.startedAt;
    if (h.presence === 'online') return { ...h, wake: { kind: 'alreadyOnline', at: now }, lastWake: { at: started, s: null } };
    if (!h.reportsState) return { ...h, wake: { kind: 'sentUnverified', at: started }, lastWake: { at: started, s: null } };
    return { ...h, wake: { kind: 'waiting', startedAt: started, board: false, cancelAt: 0 } };
  });
}

export function onWakeFailed(book, name, reason, now) {
  return edit(book, name, (h) => ({ ...h, wake: { kind: 'failed', at: now, reason } }));
}

/** Only boards that said "waking" understand "cancel"; older ones would wake the PC again. */
export const canCancel = (w) => w.kind === 'waiting' && !!w.board && !w.cancelAt;

/**
 * Applies `<prefix>/wol/<name>/result`. A live "waking" turns a wait into one the board
 * tracks, or shows a wake started elsewhere. A retained "waking" only confirms a wait already
 * running here (a board that restarted mid-wake leaves "waking" behind for good). Final
 * results end a wait; a retained one only ends a wait the board already confirmed, since an
 * older retained result may be left over from a previous wake.
 *
 * "watch only" (retained or live) marks the PC as one the board can't wake and ends any wait
 * for it. Any other result means the board has a MAC for it again.
 */
export function onResult(book, name, result, now, live = true) {
  const h = book[name];
  if (result.kind === 'watchOnly') {
    const base = h ?? newHost(name);
    return put(book, { ...base, watchOnly: true, wake: isBusy(base.wake) ? { kind: 'watchOnly', at: now } : base.wake });
  }
  if (h?.watchOnly) return onResult(put(book, { ...h, watchOnly: false }), name, result, now, live);
  const w = h?.wake;
  const busy = w && isBusy(w);
  if (result.kind === 'waking') {
    if (w?.kind === 'waiting') return put(book, { ...h, wake: { ...w, board: true } });
    // A "waking" that beats the publish acknowledgement; onWakePublished only acts on sending.
    if (w?.kind === 'sending') return put(book, { ...h, wake: { kind: 'waiting', startedAt: w.startedAt, board: true, cancelAt: 0 } });
    if (!live) return book;
    return put(book, { ...(h ?? newHost(name)), wake: { kind: 'waiting', startedAt: now, board: true, cancelAt: 0 } });
  }
  if (!busy || !(live || w.board)) return book;
  const started = w.startedAt;
  switch (result.kind) {
    case 'online':
      return put(book, { ...h, wake: { kind: 'succeeded', startedAt: started, onlineAt: started + result.seconds * 1000 }, lastWake: { at: started, s: result.seconds } });
    case 'noAnswer':
      return put(book, { ...h, wake: { kind: 'timedOut', startedAt: started, text: result.text }, lastWake: { at: started, s: null } });
    case 'cancelled':
      return put(book, { ...h, wake: { kind: 'cancelled', at: now } });
    case 'alreadyOnline':
      // The board marks the PC online without publishing its state topic, so do it here.
      return put(book, {
        ...h,
        presence: 'online',
        presenceSince: h.presence === 'online' ? h.presenceSince : now,
        wake: { kind: 'alreadyOnline', at: now, sent: false },
        lastWake: { at: started, s: null },
      });
    default:
      return book;
  }
}

/**
 * The retained result was deleted (an empty payload): the board got a MAC for a PC it only
 * watched. Nothing else changes: an empty result means "no result yet".
 */
export function onResultCleared(book, name) {
  return book[name]?.watchOnly ? put(book, { ...book[name], watchOnly: false }) : book;
}

/** Cancel was sent; the board answers with "cancelled". */
export function onCancelRequested(book, name, now) {
  return edit(book, name, (h) => (h.wake.kind === 'waiting' ? { ...h, wake: { ...h.wake, cancelAt: now } } : h));
}

/** The cancel didn't reach the broker; the wait goes on. */
export function onCancelFailed(book, name) {
  return edit(book, name, (h) => (h.wake.kind === 'waiting' ? { ...h, wake: { ...h.wake, cancelAt: 0 } } : h));
}

/**
 * Times out waits older than WAKE_TIMEOUT_MS, or BOARD_WAKE_LIMIT_MS when the board reports
 * the result itself, and ends a Cancel the board didn't confirm within CANCEL_CONFIRM_MS.
 * Returns the same object when nothing changed.
 */
export function onTick(book, now) {
  let out = book;
  for (const h of Object.values(book)) {
    const w = h.wake;
    if (w.kind !== 'waiting') continue;
    if (w.cancelAt && now - w.cancelAt >= CANCEL_CONFIRM_MS) {
      out = put(out, { ...h, wake: { kind: 'cancelled', at: now } });
    } else if (now - w.startedAt >= (w.board ? BOARD_WAKE_LIMIT_MS : WAKE_TIMEOUT_MS)) {
      const wake = { kind: 'timedOut', startedAt: w.startedAt };
      if (w.board) wake.text = 'No result from the board';
      out = put(out, { ...h, wake, lastWake: { at: w.startedAt, s: null } });
    }
  }
  return out;
}

export function dismissWake(book, name) {
  return edit(book, name, (h) => ({ ...h, wake: IDLE }));
}

// --- formatting -----------------------------------------------------------------------

export const Format = {
  /** 139651 -> "139,651" (grouped for the browser's language, or [locale]). */
  count: (n, locale) => new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(n),

  /** 30.66 -> "30.7 %". */
  percent: (p) => `${Number(p).toFixed(1)} %`,

  /** Board uptime: 45 -> "45 s", 754 -> "12 min", 11520 -> "3 h 12 min", 367200 -> "4 d 6 h". */
  uptime(total) {
    const s = Math.max(0, Math.floor(total));
    const m = Math.floor(s / 60), h = Math.floor(s / 3600), d = Math.floor(s / 86400);
    if (s < 60) return `${s} s`;
    if (s < 3600) return `${m} min`;
    if (s < 86400) return m % 60 ? `${h} h ${m % 60} min` : `${h} h`;
    return h % 24 ? `${d} d ${h % 24} h` : `${d} d`;
  },

  /** 7 -> "7 s", 65 -> "1 min 5 s", 120 -> "2 min". */
  seconds(total) {
    const s = Math.max(0, Math.floor(total));
    if (s < 60) return `${s} s`;
    return s % 60 === 0 ? `${s / 60} min` : `${Math.floor(s / 60)} min ${s % 60} s`;
  },

  /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
  ago(now, then) {
    const d = Math.max(0, Math.floor((now - then) / 1000));
    if (d < 45) return 'just now';
    if (d < 3600) return `${Math.max(1, Math.floor((d + 30) / 60))} min ago`;
    if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
    if (d < 2 * 86400) return 'yesterday';
    return `${Math.floor(d / 86400)} days ago`;
  },

  /** "14:32" today, "yesterday 14:32", otherwise "3 Nov 14:32". */
  clock(now, then, timeZone) {
    const opt = timeZone ? { timeZone } : {};
    const day = (t) => new Intl.DateTimeFormat('en-CA', { ...opt, year: 'numeric', month: '2-digit', day: '2-digit' }).format(t);
    const time = new Intl.DateTimeFormat('en-GB', { ...opt, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(then);
    const today = day(now);
    if (day(then) === today) return time;
    if (day(then) === day(now - 86400000)) return `yesterday ${time}`;
    const date = new Intl.DateTimeFormat('en-GB', { ...opt, day: 'numeric', month: 'short' }).format(then);
    return `${date} ${time}`;
  },
};

/** 1, 2, 4, 8, 16, 30, 30... seconds. */
export const backoffSeconds = (attempt) => Math.min(30, 2 ** Math.min(Math.max(attempt - 1, 0), 5));
