// Pure logic shared by the web app and its tests: topics, the host/wake state machine,
// broker profile validation and formatting. Mirrors app/src/main/.../core on Android.

export const DEFAULT_PREFIX = 'sinkhole';
export const MAX_NAME_LENGTH = 64;
export const WAKE_TIMEOUT_MS = 3 * 60 * 1000;

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

  /** Host name from `<prefix>/wol/<name>/state`, or null. */
  hostFromState(prefix, topic) {
    const head = `${prefix}/wol/`;
    const tail = '/state';
    if (!topic.startsWith(head) || !topic.endsWith(tail)) return null;
    if (topic.length <= head.length + tail.length) return null;
    const name = topic.slice(head.length, topic.length - tail.length);
    return name && !name.includes('/') ? name : null;
  },

  /** 'online' | 'offline' | 'cleared' (empty retained payload) | null (unknown, ignore). */
  parsePresence(payload) {
    const v = String(payload ?? '').trim().toLowerCase();
    if (v === '') return 'cleared';
    if (['online', 'on', 'up', '1', 'true'].includes(v)) return 'online';
    if (['offline', 'off', 'down', '0', 'false'].includes(v)) return 'offline';
    return null;
  },
};

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

// --- hosts and wakes ------------------------------------------------------------------
//
// host = { name, presence: 'online'|'offline'|'unknown', presenceSince, reportsState, manual,
//          wake: { kind, startedAt?, onlineAt?, at?, reason? }, lastWake: { at, s } | null }
// wake.kind: idle | sending | waiting | succeeded | sentUnverified | alreadyOnline | timedOut | failed
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
    if (h.manual) b[h.name] = { ...h, presence: 'unknown', presenceSince: null, reportsState: false, wake: IDLE };
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

export function onWakeRequested(book, name, now) {
  const h = book[name] ?? newHost(name, { manual: true });
  return put(book, { ...h, wake: { kind: 'sending', startedAt: now } });
}

/** The broker accepted the wake request. */
export function onWakePublished(book, name, now) {
  return edit(book, name, (h) => {
    if (h.wake.kind !== 'sending') return h; // an `online` already arrived; keep that result
    const started = h.wake.startedAt;
    if (h.presence === 'online') return { ...h, wake: { kind: 'alreadyOnline', at: now }, lastWake: { at: started, s: null } };
    if (!h.reportsState) return { ...h, wake: { kind: 'sentUnverified', at: started }, lastWake: { at: started, s: null } };
    return { ...h, wake: { kind: 'waiting', startedAt: started } };
  });
}

export function onWakeFailed(book, name, reason, now) {
  return edit(book, name, (h) => ({ ...h, wake: { kind: 'failed', at: now, reason } }));
}

/** Times out waits older than WAKE_TIMEOUT_MS. Returns the same object when nothing changed. */
export function onTick(book, now) {
  let out = book;
  for (const h of Object.values(book)) {
    if (h.wake.kind === 'waiting' && now - h.wake.startedAt >= WAKE_TIMEOUT_MS) {
      const started = h.wake.startedAt;
      out = put(out, { ...h, wake: { kind: 'timedOut', startedAt: started }, lastWake: { at: started, s: null } });
    }
  }
  return out;
}

export function dismissWake(book, name) {
  return edit(book, name, (h) => ({ ...h, wake: IDLE }));
}

// --- formatting -----------------------------------------------------------------------

export const Format = {
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
