// Pocket Wake web app: state, rendering and events. No framework; the views are small.

import * as C from './core.js';
import * as Store from './store.js';
import { openSession, testConnection } from './broker.js';

const VERSION = '0.2.0';
const { Topics, Format } = C;

const state = {
  profile: C.defaultProfile(),
  remember: true,
  passwordLost: false,
  conn: { kind: 'idle' },
  board: { presence: 'unknown', since: null },
  blocking: C.newBlocking(),
  stats: null,
  book: {},
  lastMessageAt: null,
  now: Date.now(),
  view: 'home',
  menuFor: null,
  testing: false,
  testReport: null,
  triedSave: false,
  showPassword: false,
  installHintDismissed: false,
  draft: null, // settings filled in from a setup link, not saved yet
  setupNote: null,
};

let session = null;
let stopTimer = null;
let tickTimer = null;
let installPrompt = null;
const app = document.getElementById('app');

// --- helpers ----------------------------------------------------------------------------

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const icon = (id, cls = '') => `<svg class="i ${cls}" aria-hidden="true"><use href="#i-${id}"/></svg>`;
const configured = () => C.isConfigured(state.profile);
const connected = () => state.conn.kind === 'connected';

function toast(text) {
  const t = document.getElementById('toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 2600);
}

// Last wakes saved in earlier visits, including PCs not discovered yet in this one.
let savedLastWakes = {};

function setBook(book) {
  state.book = C.adoptLastWakes(book, savedLastWakes);
}

// --- connection lifecycle (only while the page is visible) -----------------------------

function connect() {
  disconnect();
  if (!configured()) return;
  const p = state.profile;
  session = openSession(
    p,
    Store.clientId(),
    Topics.filters(p.prefix),
    (conn) => { state.conn = conn; render(); },
    (m) => onMessage(p.prefix, m),
  );
}

function disconnect() {
  if (session) {
    const s = session;
    session = null;
    s.stop();
  }
  state.conn = { kind: 'idle' };
}

function onMessage(prefix, m) {
  const now = Date.now();
  if (m.topic === Topics.blocking(prefix) || m.topic === Topics.stats(prefix)) {
    if (m.topic === Topics.blocking(prefix)) {
      const on = C.parseBlocking(m.payload);
      if (on === null) return;
      state.blocking = C.blockingReported(state.blocking, on);
    } else {
      const stats = C.parseStats(m.payload, now);
      if (!stats) return;
      state.stats = stats;
    }
    state.lastMessageAt = now;
    render();
    return;
  }
  const resultFor = Topics.hostFromResult(prefix, m.topic);
  if (resultFor) {
    const result = C.parseResult(m.payload);
    setBook(result ? C.onResult(state.book, resultFor, result, now, !m.retained) : C.onResultCleared(state.book, resultFor));
    if (result && result.kind !== 'waking') persistLastWakes();
    state.lastMessageAt = now;
    scheduleTick();
    render();
    return;
  }
  const presence = Topics.parsePresence(m.payload);
  if (!presence) return;
  if (m.topic === Topics.status(prefix)) {
    const p = presence === 'cleared' ? 'unknown' : presence;
    const since = state.board.presence === p ? state.board.since : m.retained ? null : now;
    state.board = { presence: p, since };
  } else {
    const name = Topics.hostFromState(prefix, m.topic);
    if (!name) return;
    setBook(C.onState(state.book, name, presence, now, !m.retained));
    if (!m.retained) persistLastWakes();
  }
  state.lastMessageAt = now;
  render();
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') {
    clearTimeout(stopTimer);
    stopTimer = setTimeout(() => { disconnect(); render(); }, 5000);
  } else {
    clearTimeout(stopTimer);
    if (!session) connect();
    state.now = Date.now();
    render();
  }
});
window.addEventListener('online', () => { if (document.visibilityState === 'visible') connect(); });

function scheduleTick() {
  clearTimeout(tickTimer);
  tickTimer = setTimeout(() => {
    state.now = Date.now();
    const next = C.onTick(state.book, state.now);
    if (next !== state.book) { setBook(next); persistLastWakes(); }
    state.blocking = C.blockingTick(state.blocking, state.now, Topics.blockingSet(state.profile.prefix));
    if (state.view === 'home') render();
    scheduleTick();
  }, C.anyBusy(state.book) || state.blocking.requested !== null ? 1000 : 15000);
}

function persistLastWakes() {
  savedLastWakes = C.mergeLastWakes(savedLastWakes, state.book);
  Store.saveLastWakes(savedLastWakes);
}

// --- actions ----------------------------------------------------------------------------

async function wake(name) {
  if (!session) return;
  const prefix = state.profile.prefix;
  setBook(C.onWakeRequested(state.book, name, Date.now()));
  render();
  // The board only watches this PC: publishing would do nothing.
  if (state.book[name]?.wake.kind === 'watchOnly') return;
  scheduleTick();
  try {
    await session.publish(Topics.set(prefix, name), '1');
    setBook(C.onWakePublished(state.book, name, Date.now()));
  } catch (e) {
    setBook(C.onWakeFailed(state.book, name, e.message || 'Not connected', Date.now()));
  }
  persistLastWakes();
  render();
}

// Stops the board waiting for a PC. Only offered once the board said "waking": older firmware
// treats any payload on the set topic, "cancel" included, as another wake.
async function cancelWake(name) {
  const w = state.book[name]?.wake;
  if (!session || !w || !C.canCancel(w)) return;
  setBook(C.onCancelRequested(state.book, name, Date.now()));
  render();
  try {
    await session.publish(Topics.set(state.profile.prefix, name), 'cancel');
  } catch {
    setBook(C.onCancelFailed(state.book, name));
  }
  render();
}

async function setBlocking(on) {
  if (!session) return;
  if (!on && !confirm("Turn off ad blocking?\n\nEvery device that uses the board will see ads and trackers until you turn it back on, here or in the board's web UI.")) return;
  state.blocking = C.blockingRequested(state.blocking, on, Date.now());
  render();
  scheduleTick();
  try {
    await session.publish(Topics.blockingSet(state.profile.prefix), on ? 'ON' : 'OFF');
  } catch (e) {
    state.blocking = C.blockingFailed(state.blocking, e.message || 'Not connected');
  }
  render();
}

function addHost(name) {
  setBook(C.addManual(state.book, name));
  Store.saveManual(C.manualNames(state.book));
  render();
}

function removeHost(name) {
  setBook(C.removeManual(state.book, name));
  Store.saveManual(C.manualNames(state.book));
  render();
}

function readForm() {
  const f = document.getElementById('settings-form');
  const v = (n) => f.elements[n].value;
  return {
    profile: { host: v('host'), port: Number(v('port')), path: v('path'), username: v('username'), password: v('password'), prefix: v('prefix') },
    remember: f.elements.remember.checked,
  };
}

async function saveSettings() {
  const { profile, remember } = readForm();
  state.triedSave = true;
  if (!C.isConfigured(profile)) { renderSettingsErrors(); return; }
  const n = C.normalizeProfile(profile);
  const old = state.profile;
  const ok = await Store.saveProfile(n, remember);
  if (!ok) toast("This browser couldn't store the password; you'll need to enter it again next time");
  const brokerChanged = old.host !== n.host || old.port !== n.port || old.path !== n.path || old.prefix !== n.prefix;
  state.profile = n;
  state.remember = remember;
  state.passwordLost = false;
  if (brokerChanged) {
    setBook(C.forgetDiscovered(state.book));
    state.board = { presence: 'unknown', since: null };
    state.blocking = C.newBlocking();
    state.stats = null;
  }
  state.testReport = null;
  go('home');
  connect();
}

async function runTest() {
  const { profile } = readForm();
  state.triedSave = true;
  if (!C.isConfigured(profile)) { renderSettingsErrors(); return; }
  state.testing = true;
  state.testReport = null;
  renderSettingsDynamic();
  state.testReport = { ...(await testConnection(profile, Store.clientId())), prefix: C.normalizeProfile(profile).prefix };
  state.testing = false;
  renderSettingsDynamic();
}

function go(view) {
  if (view !== 'settings') { state.draft = null; state.setupNote = null; }
  state.view = view;
  state.menuFor = null;
  state.triedSave = false;
  if (view === 'settings') { if (location.hash !== '#settings') history.pushState(null, '', '#settings'); }
  else if (location.hash) history.pushState(null, '', location.pathname);
  render(true);
  window.scrollTo(0, 0);
}

/** Opens the settings filled in from a board's setup link (#setup?…), if the address has one. */
function applySetupFromUrl() {
  if (!location.hash.startsWith('#setup?')) return false;
  const r = C.parseSetupLink(location.hash);
  history.replaceState(null, '', configured() ? '#settings' : location.pathname);
  if (!r.ok) { toast(r.message); return false; }
  const p = C.applySetupLink(state.profile, r.link);
  state.draft = p;
  state.setupNote = !p.username ? "Enter this device's username and password, then Test connection."
    : !p.password ? `Enter the password for ${p.username}, then Test connection.`
    : 'Test the connection, then Save.';
  state.view = 'settings';
  state.triedSave = false;
  state.testReport = null;
  return true;
}

window.addEventListener('popstate', () => {
  if (applySetupFromUrl()) { render(true); return; }
  const v = location.hash === '#settings' || !configured() ? 'settings' : 'home';
  if (v !== state.view) { state.view = v; render(true); }
});

window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; render(); });

// --- rendering: home ----------------------------------------------------------------------

function render(force = false) {
  if (state.view === 'settings') { if (force || !document.getElementById('settings-form')) renderSettings(); return; }
  renderHome();
}

function renderHome() {
  const hosts = C.sortedHosts(state.book);
  const isOn = connected();
  const online = hosts.filter((h) => h.presence === 'online').length;
  const reporting = hosts.filter((h) => h.reportsState).length;
  const count = !hosts.length ? '' : !isOn ? `${hosts.length} PCs` : reporting ? `${online} of ${reporting} online` : `${hosts.length} added`;

  app.innerHTML = `
  <header class="topbar">
    <div class="titles">
      <h1>Pocket Wake</h1>
      ${configured() ? `<div class="sub">${esc(state.profile.host)}</div>` : ''}
    </div>
    <button class="icon-btn" data-action="settings" aria-label="Broker settings">${icon('settings')}</button>
  </header>
  <main class="wrap">
    ${!configured() ? setupCard() : `
      ${installHint()}
      ${connectionCard()}
      ${state.blocking.on !== null || state.stats ? boardCard() : ''}
      <div class="section"><h3>Computers</h3><span class="count">${esc(count)}</span></div>
      ${hosts.length ? hosts.map(hostCard).join('') : emptyCard()}
      ${hosts.length ? '<p class="muted small m4">PCs with an IP on the board appear automatically. Names are case-sensitive.</p>' : ''}
    `}
  </main>
  ${configured() ? `<button class="fab" data-action="add">${icon('add')}Add PC</button>` : ''}`;
  for (const bar of app.querySelectorAll('.progress > i[data-pct]')) bar.style.width = `${bar.dataset.pct}%`;
}

function connectionCard() {
  const c = state.conn;
  const { profile: p, board, now } = state;
  let dot = 'offline', title = 'Not connected', detail = '', pulse = false;
  if (c.kind === 'connected') { dot = 'online'; title = 'Connected'; detail = `since ${Format.clock(now, c.since)}`; }
  else if (c.kind === 'connecting') { dot = 'busy'; title = 'Connecting…'; pulse = true; }
  else if (c.kind === 'retrying') { dot = 'warn'; title = 'Reconnecting…'; detail = `${c.reason} · retry in ${c.next} s`; pulse = true; }
  else if (c.kind === 'failed') { dot = 'error'; title = "Can't connect"; detail = c.reason; }
  const failed = c.kind === 'failed';

  let bDot = 'offline', bText = 'Unknown until connected';
  if (c.kind === 'connected') {
    if (board.presence === 'online') { bDot = 'online'; bText = 'Online' + (board.since ? ` · since ${Format.clock(now, board.since)}` : ''); }
    else if (board.presence === 'offline') { bDot = 'error'; bText = 'Offline' + (board.since ? ` · since ${Format.clock(now, board.since)}` : ' (last will)'); }
    else { bDot = 'warn'; bText = `No status under ${Topics.status(p.prefix)}`; }
  }

  return `
  <section class="card ${failed ? 'error' : 'high'}">
    <div class="row">
      <span class="dot lg ${dot} ${pulse ? 'pulse' : ''}"></span>
      <div class="grow">
        <div class="status-title">${title}</div>
        ${detail ? `<div class="small ${failed ? '' : 'muted'}">${esc(detail)}</div>` : ''}
      </div>
      ${c.kind !== 'connected' && c.kind !== 'connecting' ? `<button class="icon-btn" data-action="retry" aria-label="Retry now">${icon('refresh')}</button>` : ''}
    </div>
    ${failed ? `<div class="row mt12"><button class="btn" data-action="retry">Retry</button><button class="btn outline" data-action="settings">Settings</button></div>` : ''}
    <div class="divider"></div>
    <div class="info">
      <span class="k">Board</span><span class="v"><span class="dot sm ${bDot}"></span>${esc(bText)}</span>
      <span class="k">Broker</span><span class="v">MQTT over WebSocket TLS · port ${p.port}</span>
      <span class="k">Prefix</span><span class="v">${esc(p.prefix)}</span>
      ${state.lastMessageAt ? `<span class="k">Last update</span><span class="v">${Format.ago(now, state.lastMessageAt)}</span>` : ''}
    </div>
    ${c.kind === 'connected' && board.presence === 'offline' ? '<p class="small err-c mt8">The board is offline, so wake requests wait until it reconnects.</p>' : ''}
  </section>`;
}

function boardCard() {
  const b = state.blocking;
  const on = connected();
  const shown = C.blockingShown(b);
  const pending = b.requested !== null;
  let sub = 'State unknown';
  if (shown !== null) {
    if (!on) sub = shown ? 'On when last seen' : 'Off when last seen';
    else if (pending) sub = 'Waiting for the board…';
    else sub = shown ? 'On · ads and trackers are blocked' : 'Off · devices using the board see ads';
  }
  const st = state.stats;
  const stats = st ? `
    <div class="divider"></div>
    <div class="info">
      <span class="k">Blocked</span><span class="v">${Format.count(st.blocked)} of ${Format.count(st.total)} · ${Format.percent(st.percent)}</span>
      <span class="k">Blocklist</span><span class="v">${Format.count(st.domains)} domains</span>
      <span class="k">Devices</span><span class="v">${Format.count(st.clients)}</span>
      ${st.uptime !== null ? `<span class="k">Up for</span><span class="v">${Format.uptime(C.statsUptime(st, state.now))}</span>` : ''}
      ${st.restart ? `<span class="k">Last restart</span><span class="v${C.unexpectedRestart(st) ? ' warn-c' : ''}">${esc(st.restart.charAt(0).toUpperCase() + st.restart.slice(1))}</span>` : ''}
    </div>
    <p class="muted small mt8">Since the board restarted · updated ${Format.ago(state.now, st.at)}</p>`
    : on ? '<p class="muted small mt8">Blocking stats arrive within a minute.</p>' : '';
  const sw = b.on === null ? '' : `<button class="switch" role="switch" aria-checked="${shown === true}" aria-label="Ad blocking"
      data-action="blocking" data-on="${shown === true ? 'off' : 'on'}" ${on && !pending ? '' : 'disabled'}><i></i></button>`;
  return `
  <section class="card">
    <div class="row">
      <div class="grow">
        <div class="status-title">Ad blocking</div>
        <div class="small ${shown === false && on ? 'warn-c' : 'muted'}">${esc(sub)}</div>
      </div>
      ${pending ? '<span class="spinner"></span>' : ''}
      ${sw}
    </div>
    ${b.problem ? `<p class="small err-c mt8">${esc(b.problem)}</p>` : ''}
    ${stats}
  </section>`;
}

function presenceLine(h) {
  const on = connected();
  if (!on && h.presence === 'online') return 'Online when last seen';
  if (!on && h.presence === 'offline') return 'Offline when last seen';
  if (h.presence === 'online') return 'Online' + (h.presenceSince ? ` · since ${Format.clock(state.now, h.presenceSince)}` : '');
  if (h.presence === 'offline') return 'Offline' + (h.presenceSince ? ` · since ${Format.clock(state.now, h.presenceSince)}` : '');
  if (!on) return 'Status unknown';
  if (h.manual && !h.reportsState) return 'No status from the board · wake only';
  return 'Waiting for status…';
}

function hostCard(h) {
  const busy = C.isBusy(h.wake);
  const online = h.presence === 'online';
  const n = esc(h.name);
  const dot = busy ? 'busy' : online ? 'online' : 'offline';
  const label = h.wake.kind === 'timedOut' || h.wake.kind === 'failed' ? 'Retry' : 'Wake';
  const disabled = connected() ? '' : 'disabled';
  const menuOpen = state.menuFor === h.name;
  let origin = 'Added by you · no IP on the board';
  if (h.watchOnly) origin = 'Watched by the board · no MAC to wake it';
  else if (h.reportsState) origin = h.manual ? 'Reported by the board · added by you' : 'Reported by the board';
  let action = '';
  if (h.watchOnly && !busy) action = '<span class="watch-only">Watch only</span>';
  else if (!busy) action = `<button class="btn ${online ? 'tonal' : ''}" data-action="wake" data-name="${n}" ${disabled}>${label}</button>`;

  return `
  <article class="card host">
    <div class="host-head">
      <div class="avatar ${online ? 'online' : busy ? 'busy' : ''}">${icon('power')}<span class="badge"><span class="dot ${dot} ${busy ? 'pulse' : ''}"></span></span></div>
      <div class="grow">
        <div class="host-name">${n}</div>
        <div class="host-line ${online && connected() ? 'online' : ''}">${esc(presenceLine(h))}</div>
      </div>
      ${action}
      <div class="menu">
        <button class="icon-btn" data-action="menu" data-name="${n}" aria-label="More for ${n}" aria-expanded="${menuOpen}">${icon('more')}</button>
        ${menuOpen ? `<div class="menu-list" role="menu">
          ${h.watchOnly ? '' : `<button role="menuitem" data-action="copy" data-name="${n}">Copy wake topic</button>`}
          ${h.manual ? `<button role="menuitem" data-action="remove" data-name="${n}">${h.reportsState ? 'Unpin (keep while the board reports it)' : 'Remove'}</button>` : ''}
          <div class="note">${origin}</div>
        </div>` : ''}
      </div>
    </div>
    ${wakeSection(h)}
    ${h.lastWake && h.wake.kind === 'idle' ? `<div class="host-foot">Last woken ${Format.ago(state.now, h.lastWake.at)}${h.lastWake.s != null ? ` · online after ${Format.seconds(h.lastWake.s)}` : ''}</div>` : ''}
  </article>`;
}

function banner(kind, ic, title, body, name) {
  return `<div class="host-body"><div class="banner ${kind}">${icon(ic, 'lead')}<div class="grow"><div class="t">${esc(title)}</div><div class="b">${esc(body)}</div></div>
    <button class="icon-btn" data-action="dismiss" data-name="${esc(name)}" aria-label="Dismiss">${icon('close', 'sm')}</button></div></div>`;
}

function wakeSection(h) {
  const w = h.wake;
  const now = state.now;
  switch (w.kind) {
    case 'sending':
    case 'waiting': {
      const elapsed = Math.max(0, Math.floor((now - w.startedAt) / 1000));
      const pct = Math.min(100, (elapsed * 1000 * 100) / C.WAKE_TIMEOUT_MS);
      // Once the board says "waking" it keeps the PC's own wait (up to 60 min): no fixed maximum.
      const board = w.kind === 'waiting' && w.board;
      const cancel = board
        ? `<div class="row end"><button class="btn text" data-action="cancel-wake" data-name="${esc(h.name)}" ${C.canCancel(w) ? '' : 'disabled'}>${w.cancelAt ? 'Cancelling…' : 'Cancel'}</button></div>`
        : '';
      return `<div class="host-body">
        <div class="row"><span class="spinner"></span><span class="grow">${w.kind === 'sending' ? 'Sending wake request…' : `Waking… ${Format.seconds(elapsed)}`}</span>${board ? '' : '<span class="muted small">max 3 min</span>'}</div>
        ${board ? '<div class="progress indeterminate"><i></i></div>' : `<div class="progress"><i data-pct="${pct.toFixed(1)}"></i></div>`}
        ${w.kind === 'waiting' ? '<div class="muted small">The board sent the magic packet and is waiting for the PC to answer.</div>' : ''}
        ${cancel}
      </div>`;
    }
    case 'succeeded': return banner('ok', 'check', `Online after ${Format.seconds(C.wakeSeconds(w))}`, `Woken at ${Format.clock(now, w.startedAt)}`, h.name);
    case 'alreadyOnline': return banner('note', 'info', 'Already online', w.sent === false ? 'The board checked it and sent nothing.' : 'The wake request was sent anyway.', h.name);
    case 'sentUnverified': return banner('note', 'info', 'Magic packet sent', "This PC has no IP on the board, so it can't confirm when it's up.", h.name);
    case 'cancelled': return banner('note', 'info', 'Cancelled', 'The board stopped waiting. If the PC still starts, its dot turns green.', h.name);
    case 'timedOut': return banner('warn', 'warn', w.text ? w.text.charAt(0).toUpperCase() + w.text.slice(1) : 'No answer after 3 min', "Check that Wake-on-LAN is enabled in the PC's BIOS and network adapter, and that it's on wired power.", h.name);
    case 'failed': return banner('err', 'warn', "Couldn't send", w.reason, h.name);
    case 'watchOnly': return banner('note', 'info', 'Only watched', "The board has no MAC for this PC, so it can't wake it. Add the MAC on the board to wake it from here.", h.name);
    default: return '';
  }
}

function emptyCard() {
  return `<section class="card">
    <h2>${connected() ? 'No PCs reported yet' : 'No PCs yet'}</h2>
    <p class="muted">PCs that have an IP on the board show up here by themselves, with a live online dot. For PCs without an IP, add them by name, exactly as in the board's Wake-on-LAN list.</p>
    <button class="btn text" data-action="add">${icon('add', 'sm')}Add a PC by name</button>
  </section>`;
}

function setupCard() {
  return `<section class="card hero">
    <div class="avatar hero-avatar">${icon('power')}</div>
    <h2 class="hero-title">Wake your PCs from anywhere</h2>
    <p>Pocket Wake talks to your Pocket AdBlock board through its MQTT broker. Enter the broker's WebSocket address and the phone's login to get started.</p>
    <ol class="steps">
      <li><b>1</b>Turn on the MQTT bridge in the board's web UI</li>
      <li><b>2</b>Create a separate login for this device on your broker</li>
      <li><b>3</b>Enter it here and tap Test connection</li>
    </ol>
    <button class="btn" data-action="settings">Set up broker</button>
  </section>
  <p class="muted small m12-4">Step-by-step guide: <a href="https://github.com/joanrra2/pocket-wake/blob/main/docs/SETUP.md" rel="noopener" class="link">docs/SETUP.md</a></p>
  ${installHint()}`;
}

function installHint() {
  const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  if (standalone || state.installHintDismissed) return '';
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (ios) {
    return `<section class="card"><div class="row">${icon('share', 'pri-c')}<div class="grow small"><b>Add to Home Screen</b><br>In Safari, tap Share, then <i>Add to Home Screen</i>, to open Pocket Wake like an app.</div>
      <button class="icon-btn" data-action="hide-install" aria-label="Dismiss">${icon('close', 'sm')}</button></div></section>`;
  }
  if (installPrompt) {
    return `<section class="card"><div class="row">${icon('add', 'pri-c')}<div class="grow small"><b>Install Pocket Wake</b><br>Add it to your home screen or desktop.</div>
      <button class="btn tonal" data-action="install">Install</button></div></section>`;
  }
  return '';
}

// --- rendering: settings -----------------------------------------------------------------

function field(name, label, value, { type = 'text', placeholder = '', help = '', mode = '', extra = '' } = {}) {
  return `<label class="field" data-field="${name}">
    <span class="field-label">${label}</span>
    <input name="${name}" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${mode ? `inputmode="${mode}"` : ''} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" ${extra}>
    <span class="field-help" data-help="${name}" data-default="${esc(help)}">${esc(help)}</span>
  </label>`;
}

function renderSettings() {
  const p = state.draft ?? state.profile;
  const back = configured();
  app.innerHTML = `
  <header class="topbar">
    ${back ? `<button class="icon-btn" data-action="home" aria-label="Back">${icon('back')}</button>` : ''}
    <div class="titles"><h1>Broker settings</h1></div>
  </header>
  <main class="wrap">
    <form id="settings-form" novalidate>
      ${state.setupNote ? `<div class="banner ok mt8">${icon('check', 'lead')}<div class="grow"><div class="t">Filled in from your board</div><div class="b">${esc(state.setupNote)}</div></div></div>` : ''}
      <div class="h-section">Broker</div>
      ${field('host', 'Address', p.host, { placeholder: 'abc123.ala.eu-central-1.emqxsl.com', mode: 'url' })}
      <div class="row top">
        <div class="w120">${field('port', 'Port', p.port, { mode: 'numeric', extra: 'maxlength="5"' })}</div>
        <div class="grow">${field('path', 'Path', p.path, { placeholder: '/mqtt' })}</div>
      </div>
      <p class="hint">${icon('lock', 'sm')}<span>Always encrypted (WebSocket over TLS), checked by your browser. EMQX Cloud: port 8084, path /mqtt.</span></p>

      <div class="h-section">Login</div>
      ${field('username', 'Username', p.username)}
      <label class="field" data-field="password">
        <span class="field-label">Password</span>
        <span class="pw"><input name="password" type="${state.showPassword ? 'text' : 'password'}" value="${esc(p.password)}" autocomplete="off" autocapitalize="off" spellcheck="false">
        <button type="button" class="btn text" data-action="toggle-pw">${state.showPassword ? 'Hide' : 'Show'}</button></span>
        ${state.passwordLost ? '<span class="field-help err-c">The saved password couldn\'t be read in this browser. Enter it again.</span>' : ''}
      </label>
      <label class="check"><input type="checkbox" name="remember" ${state.remember ? 'checked' : ''}>
        <span>Remember the password on this device<br><span class="muted small">Encrypted with a key this browser keeps and won't export. Untick on shared devices; you'll then type it each time.</span></span></label>
      <p class="hint">Use this device's own broker login, not the board's.</p>

      <div class="h-section">Topics</div>
      ${field('prefix', 'Topic prefix', p.prefix, { placeholder: 'sinkhole', help: "Same as the MQTT prefix in the board's web UI" })}
      <div class="topics mono" id="topics"></div>

      <div class="actions">
        <button type="button" class="btn outline" data-action="test" id="test-btn">Test connection</button>
        <button type="submit" class="btn">Save</button>
      </div>
      <div id="report"></div>
    </form>

    <div class="h-section">About</div>
    <p class="muted small m0-4">Pocket Wake ${VERSION} (web) · for the Pocket AdBlock board's Wake-on-LAN MQTT bridge.<br>
      Connects only while this page is open. No analytics, no ads, no other servers: this page talks only to your broker.<br>
      Setup guide and bug reports: <a href="https://github.com/joanrra2/pocket-wake" rel="noopener" class="link">github.com/joanrra2/pocket-wake</a></p>
    <button class="btn text mt8" data-action="forget">Forget this device (erase saved settings)</button>
  </main>`;
  renderSettingsDynamic();
}

function renderSettingsErrors() {
  const f = document.getElementById('settings-form');
  if (!f) return;
  const { profile } = readForm();
  const errors = state.triedSave ? C.profileErrors(profile) : {};
  for (const el of f.querySelectorAll('[data-field]')) {
    const name = el.dataset.field;
    el.classList.toggle('invalid', !!errors[name]);
    const help = el.querySelector('[data-help]');
    if (help) help.textContent = errors[name] || help.dataset.default;
  }
}

function renderSettingsDynamic() {
  const f = document.getElementById('settings-form');
  if (!f) return;
  const prefix = Topics.normalizePrefix(f.elements.prefix.value) || '<prefix>';
  document.getElementById('topics').innerHTML =
    `<div>wake    ${esc(prefix)}/wol/&lt;PC&gt;/set</div><div>state   ${esc(prefix)}/wol/+/state</div><div>board   ${esc(prefix)}/status</div>` +
    `<div class="muted">optional, for the ad-blocking card:</div><div>read    ${esc(prefix)}/blocking, ${esc(prefix)}/stats</div><div>switch  ${esc(prefix)}/blocking/set</div>` +
    `<div class="muted">optional, board firmware 0.6+ (wake results, Cancel):</div><div>result  ${esc(prefix)}/wol/+/result</div>`;
  const btn = document.getElementById('test-btn');
  btn.disabled = state.testing;
  btn.innerHTML = state.testing ? '<span class="spinner"></span>Testing…' : 'Test connection';
  document.getElementById('report').innerHTML = state.testReport ? reportCard(state.testReport) : '';
  renderSettingsErrors();
}

function line(ic, cls, title, body) {
  return `<div class="line">${icon(ic, cls)}<div><div class="t">${esc(title)}</div><div class="b">${esc(body)}</div></div></div>`;
}

function reportCard(r) {
  if (r.error) return `<section class="card high report mt16">${line('warn', 'err-c', "Couldn't connect", r.error)}</section>`;
  const parts = [line('check', 'ok-c', `Connected in ${r.connectMillis} ms`, 'TLS verified by the browser, login accepted')];
  if (r.board === 'online') parts.push(line('check', 'ok-c', 'Board online', `${r.prefix}/status = online`));
  else if (r.board === 'offline') parts.push(line('warn', 'err-c', 'Board offline', 'Its last will says offline. Is it powered and on Wi-Fi?'));
  else parts.push(line('warn', 'warn-c', 'No board status', `Nothing on ${r.prefix}/status. Check the prefix and that the board's MQTT bridge is on.`));
  const names = Object.keys(r.hosts).sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
  if (!names.length) parts.push(line('info', 'pri-c', 'No PCs reported', 'PCs without an IP on the board can be added by name.'));
  else parts.push(line('check', 'ok-c', `Found ${names.length} PC${names.length === 1 ? '' : 's'}`, names.map((n) => `${n} (${r.hosts[n]})`).join(', ')));
  const denied = r.denied || [];
  const required = denied.filter((f) => f === Topics.status(r.prefix) || f === Topics.stateFilter(r.prefix));
  const optional = denied.filter((f) => f === Topics.blocking(r.prefix) || f === Topics.stats(r.prefix));
  if (required.length) parts.push(line('warn', 'err-c', `The broker won't let this login read ${required.join(' and ')}`, 'Add a Client Authorization rule for this login that allows Subscribe on it.'));
  if (r.blocking === true || r.blocking === false) parts.push(line('check', 'ok-c', `Ad blocking ${r.blocking ? 'on' : 'off'}`, "The home screen shows a switch and the board's stats."));
  else if (optional.length) parts.push(line('info', 'pri-c', 'Ad-blocking switch not allowed (optional)', `To see and switch ad blocking here, allow this login Subscribe on ${r.prefix}/blocking and ${r.prefix}/stats, and Publish on ${r.prefix}/blocking/set.`));
  if (denied.includes(Topics.resultFilter(r.prefix))) parts.push(line('info', 'pri-c', 'Wake results not allowed (optional)', `With board firmware 0.6 or later, allow this login Subscribe on ${Topics.resultFilter(r.prefix)} to get the board's own wake results and a Cancel button.`));
  return `<section class="card high report mt16">${parts.join('')}</section>`;
}

// --- events -------------------------------------------------------------------------------

app.addEventListener('click', async (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) {
    if (state.menuFor) { state.menuFor = null; render(); }
    return;
  }
  const name = el.dataset.name;
  switch (el.dataset.action) {
    case 'settings': go('settings'); break;
    case 'home': state.testReport = null; go('home'); break;
    case 'retry': connect(); render(); break;
    case 'wake': wake(name); break;
    case 'blocking': setBlocking(el.dataset.on === 'on'); break;
    case 'dismiss': setBook(C.dismissWake(state.book, name)); render(); break;
    case 'cancel-wake': cancelWake(name); break;
    case 'menu': state.menuFor = state.menuFor === name ? null : name; render(); break;
    case 'copy': {
      state.menuFor = null;
      const topic = Topics.set(state.profile.prefix, name);
      try { await navigator.clipboard.writeText(topic); toast(`Copied ${topic}`); } catch { toast(topic); }
      render();
      break;
    }
    case 'remove': state.menuFor = null; removeHost(name); break;
    case 'add': openAddDialog(); break;
    case 'install': if (installPrompt) { installPrompt.prompt(); installPrompt = null; render(); } break;
    case 'hide-install': state.installHintDismissed = true; render(); break;
    case 'toggle-pw': {
      state.showPassword = !state.showPassword;
      const input = document.querySelector('input[name=password]');
      input.type = state.showPassword ? 'text' : 'password';
      el.textContent = state.showPassword ? 'Hide' : 'Show';
      break;
    }
    case 'test': runTest(); break;
    case 'forget':
      if (confirm('Erase the broker settings, password and PC list saved in this browser?')) {
        disconnect();
        await Store.forgetAll();
        savedLastWakes = {};
        Object.assign(state, { profile: C.defaultProfile(), book: {}, board: { presence: 'unknown', since: null }, blocking: C.newBlocking(), stats: null, testReport: null, passwordLost: false, lastMessageAt: null });
        go('settings');
        toast('Saved settings erased');
      }
      break;
  }
});

app.addEventListener('submit', (e) => {
  if (e.target.id === 'settings-form') { e.preventDefault(); saveSettings(); }
});

app.addEventListener('input', (e) => {
  if (!e.target.closest('#settings-form')) return;
  if (e.target.name === 'port') e.target.value = e.target.value.replace(/\D/g, '').slice(0, 5);
  renderSettingsDynamic();
});

// --- add dialog ---------------------------------------------------------------------------

const dialog = document.getElementById('add-dialog');
const addName = document.getElementById('add-name');
const addHelp = document.getElementById('add-help');
const addOk = document.getElementById('add-ok');
const addTopic = document.getElementById('add-topic');

function addError() {
  const n = addName.value;
  return Topics.nameError(n) || (state.book[n] ? 'Already in the list' : null);
}

function refreshAdd(touched) {
  const err = addError();
  addOk.disabled = !!err;
  addHelp.textContent = touched && err ? err : "Case-sensitive, as in the board's Wake-on-LAN list";
  addHelp.parentElement.classList.toggle('invalid', !!(touched && err));
  addTopic.textContent = !err ? `Wake topic: ${Topics.set(state.profile.prefix, addName.value)}` : '';
}

function openAddDialog() {
  addName.value = '';
  refreshAdd(false);
  dialog.showModal();
  addName.focus();
}

addName.addEventListener('input', () => refreshAdd(true));
document.getElementById('add-form').addEventListener('submit', (e) => {
  e.preventDefault();
  if (addError()) { refreshAdd(true); return; }
  addHost(addName.value);
  dialog.close();
});
dialog.querySelector('[data-action=add-cancel]').addEventListener('click', () => dialog.close());

// --- start --------------------------------------------------------------------------------

async function start() {
  const loaded = await Store.loadProfile();
  state.profile = loaded.profile;
  state.remember = loaded.remember;
  state.passwordLost = loaded.passwordLost;
  savedLastWakes = Store.loadLastWakes();
  setBook(C.withManual({}, Store.loadManual()));
  state.view = location.hash === '#settings' ? 'settings' : 'home';
  applySetupFromUrl();
  render(true);
  if (document.visibilityState === 'visible') connect();
  scheduleTick();
  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
}

start();
