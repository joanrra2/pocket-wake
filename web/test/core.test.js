// Unit tests for web/js/core.js:  cd web && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../js/core.js';

const { Topics, Format } = C;
const t0 = 1_700_000_000_000;

test('builds the bridge topics', () => {
  assert.equal(Topics.status('sinkhole'), 'sinkhole/status');
  assert.equal(Topics.stateFilter('sinkhole'), 'sinkhole/wol/+/state');
  assert.equal(Topics.set('sinkhole', 'OFFICE-PC'), 'sinkhole/wol/OFFICE-PC/set');
});

test('extracts host names case-sensitively, with multi-level prefixes', () => {
  assert.equal(Topics.hostFromState('sinkhole', 'sinkhole/wol/OFFICE-PC/state'), 'OFFICE-PC');
  assert.equal(Topics.hostFromState('sinkhole', 'sinkhole/wol/office-pc/state'), 'office-pc');
  assert.equal(Topics.hostFromState('home/sinkhole', 'home/sinkhole/wol/NAS/state'), 'NAS');
});

test('ignores topics that are not host states', () => {
  for (const t of ['sinkhole/status', 'sinkhole/wol/PC/set', 'sinkhole/wol//state', 'sinkhole/wol/a/b/state', 'other/wol/PC/state']) {
    assert.equal(Topics.hostFromState('sinkhole', t), null, t);
  }
  assert.equal(Topics.hostFromState('sink', 'sinkhole/wol/PC/state'), null);
});

test('parses presence payloads', () => {
  assert.equal(Topics.parsePresence('online'), 'online');
  assert.equal(Topics.parsePresence(' Online\n'), 'online');
  assert.equal(Topics.parsePresence('offline'), 'offline');
  assert.equal(Topics.parsePresence(''), 'cleared');
  assert.equal(Topics.parsePresence('rebooting'), null);
});

test('validates prefixes and names', () => {
  assert.equal(Topics.normalizePrefix(' /sinkhole/ '), 'sinkhole');
  assert.equal(Topics.prefixError('home/sinkhole'), null);
  for (const p of ['', 'a+b', 'a#', 'a//b']) assert.ok(Topics.prefixError(p), p);
  assert.equal(Topics.nameError('OFFICE-PC'), null);
  assert.equal(Topics.nameError('Living room PC'), null);
  for (const n of ['', ' PC', 'a/b', 'a+', '#', 'x'.repeat(65)]) assert.ok(Topics.nameError(n), n);
});

test('profile validation and broker URL', () => {
  assert.ok(!C.isConfigured(C.defaultProfile()));
  const p = { ...C.defaultProfile(), host: ' abc.emqxsl.com ', username: 'phone', password: 'x' };
  assert.ok(C.isConfigured(p));
  assert.equal(C.brokerUrl(p), 'wss://abc.emqxsl.com:8084/mqtt');
  assert.equal(C.brokerUrl({ ...p, path: 'ws' }), 'wss://abc.emqxsl.com:8084/ws');
  assert.ok(C.profileErrors({ ...p, host: 'wss://abc.com' }).host);
  assert.ok(C.profileErrors({ ...p, port: 0 }).port);
  assert.ok(C.profileErrors({ ...p, username: '' }).username);
  assert.ok(C.profileErrors({ ...p, prefix: 'a#' }).prefix);
});

test('retained states discover hosts without a since time; live changes get one', () => {
  let b = C.onState({}, 'OFFICE-PC', 'offline', t0, false);
  b = C.onState(b, 'NAS', 'online', t0, false);
  assert.deepEqual(C.sortedHosts(b).map((h) => h.name), ['NAS', 'OFFICE-PC']);
  assert.equal(b['OFFICE-PC'].presenceSince, null);
  assert.ok(b['OFFICE-PC'].reportsState);
  b = C.onState(b, 'OFFICE-PC', 'online', t0 + 5000);
  b = C.onState(b, 'OFFICE-PC', 'online', t0 + 9000);
  assert.equal(b['OFFICE-PC'].presenceSince, t0 + 5000);
});

test('wake then online reports seconds to online', () => {
  let b = C.onState({}, 'PC', 'offline', t0, false);
  b = C.onWakeRequested(b, 'PC', t0 + 1000);
  assert.equal(b.PC.wake.kind, 'sending');
  b = C.onWakePublished(b, 'PC', t0 + 1200);
  assert.deepEqual(b.PC.wake, { kind: 'waiting', startedAt: t0 + 1000, board: false, cancelAt: 0 });
  assert.ok(C.anyBusy(b));
  b = C.onState(b, 'PC', 'online', t0 + 24500);
  assert.equal(b.PC.wake.kind, 'succeeded');
  assert.equal(C.wakeSeconds(b.PC.wake), 23);
  assert.deepEqual(b.PC.lastWake, { at: t0 + 1000, s: 23 });
  assert.ok(!C.anyBusy(b));
});

test('online arriving before the publish ack still counts', () => {
  let b = C.onState({}, 'PC', 'offline', t0, false);
  b = C.onWakePublished(C.onState(C.onWakeRequested(b, 'PC', t0), 'PC', 'online', t0 + 3000), 'PC', t0 + 3100);
  assert.equal(b.PC.wake.kind, 'succeeded');
});

test('times out after three minutes; a late boot still succeeds', () => {
  let b = C.onState({}, 'PC', 'offline', t0, false);
  b = C.onWakePublished(C.onWakeRequested(b, 'PC', t0), 'PC', t0);
  assert.equal(C.onTick(b, t0 + C.WAKE_TIMEOUT_MS - 1), b);
  b = C.onTick(b, t0 + C.WAKE_TIMEOUT_MS);
  assert.deepEqual(b.PC.wake, { kind: 'timedOut', startedAt: t0 });
  assert.deepEqual(b.PC.lastWake, { at: t0, s: null });
  b = C.onState(b, 'PC', 'online', t0 + C.WAKE_TIMEOUT_MS + 30000);
  assert.equal(b.PC.wake.kind, 'succeeded');
});

test('already online, sent-unverified and failures', () => {
  let b = C.onState({}, 'ON', 'online', t0, false);
  b = C.onWakePublished(C.onWakeRequested(b, 'ON', t0), 'ON', t0 + 1);
  assert.equal(b.ON.wake.kind, 'alreadyOnline');
  b = C.addManual(b, 'OLD');
  b = C.onWakePublished(C.onWakeRequested(b, 'OLD', t0), 'OLD', t0 + 100);
  assert.deepEqual(b.OLD.wake, { kind: 'sentUnverified', at: t0 });
  b = C.onWakeFailed(C.onWakeRequested(b, 'OLD', t0), 'OLD', 'Not connected', t0 + 1);
  assert.deepEqual(b.OLD.wake, { kind: 'failed', at: t0 + 1, reason: 'Not connected' });
});

test('cleared states, manual hosts, forgetting', () => {
  let b = C.onState({}, 'A', 'online', t0, false);
  b = C.onState(C.addManual(b, 'B'), 'B', 'online', t0);
  b = C.onState(C.onState(b, 'A', 'cleared', t0), 'B', 'cleared', t0);
  assert.equal(b.A, undefined);
  assert.equal(b.B.presence, 'unknown');
  let m = C.onState(C.addManual(C.addManual({}, 'A'), 'B'), 'B', 'offline', t0);
  m = C.removeManual(C.removeManual(m, 'A'), 'B');
  assert.equal(m.A, undefined);
  assert.equal(m.B.manual, false);
  const f = C.forgetDiscovered(C.onState(C.onState(C.addManual({}, 'M'), 'M', 'online', t0), 'D', 'online', t0));
  assert.deepEqual(Object.keys(f), ['M']);
  assert.equal(f.M.presence, 'unknown');
  assert.deepEqual(C.manualNames(C.addManual(C.addManual({}, 'b'), 'a')), ['a', 'b']);
});

test('last wakes round trip and sorting', () => {
  const last = { PC: { at: t0, s: 20 } };
  assert.deepEqual(C.lastWakes(C.withLastWakes(C.addManual({}, 'PC'), last)), last);
  const b = C.withManual({}, ['beta', 'Alpha', 'alpha']);
  assert.deepEqual(C.sortedHosts(b).map((h) => h.name), ['Alpha', 'alpha', 'beta']);
});

test('formats durations, relative and clock times', () => {
  assert.equal(Format.seconds(23), '23 s');
  assert.equal(Format.seconds(65), '1 min 5 s');
  assert.equal(Format.seconds(180), '3 min');
  assert.equal(Format.ago(t0, t0 - 10000), 'just now');
  assert.equal(Format.ago(t0, t0 - 5 * 60000), '5 min ago');
  assert.equal(Format.ago(t0, t0 - 3 * 3600000), '3 h ago');
  assert.equal(Format.ago(t0, t0 - 30 * 3600000), 'yesterday');
  assert.equal(Format.ago(t0, t0 - 4 * 86400000), '4 days ago');
  // t0 = 2023-11-14 22:13:20 UTC
  assert.equal(Format.clock(t0, t0, 'UTC'), '22:13');
  assert.equal(Format.clock(t0, t0 - 86400000, 'UTC'), 'yesterday 22:13');
  assert.equal(Format.clock(t0, t0 - 2 * 86400000, 'UTC'), '12 Nov 22:13');
});

test('reconnect backoff caps at 30 s', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7].map(C.backoffSeconds), [1, 2, 4, 8, 16, 30, 30]);
});

test('saved last wakes reach PCs discovered later and are never dropped', () => {
  const saved = { NAS: { at: t0, s: 41 }, GONE: { at: t0, s: 9 } };
  let b = C.adoptLastWakes({}, saved);
  assert.deepEqual(b, {});
  b = C.adoptLastWakes(C.onState(b, 'NAS', 'online', t0 + 1, false), saved);
  assert.deepEqual(b.NAS.lastWake, { at: t0, s: 41 });
  // a newer wake wins over the saved one
  b = C.onState(C.onWakePublished(C.onWakeRequested(C.onState(b, 'NAS', 'offline', t0 + 2), 'NAS', t0 + 10), 'NAS', t0 + 11), 'NAS', 'online', t0 + 20_010);
  assert.deepEqual(C.adoptLastWakes(b, saved).NAS.lastWake, { at: t0 + 10, s: 20 });
  assert.deepEqual(C.mergeLastWakes(saved, b), { NAS: { at: t0 + 10, s: 20 }, GONE: { at: t0, s: 9 } });
});

test('reads the setup link a board shows', () => {
  const r = C.parseSetupLink('https://joanrra2.github.io/pocket-wake/#setup?v=1&host=x1.ala.eu-central-1.emqxsl.com&port=8883&tls=1&wsport=8084&wspath=%2Fmqtt&prefix=sinkhole&user=phone');
  assert.ok(r.ok);
  assert.deepEqual(r.link, { host: 'x1.ala.eu-central-1.emqxsl.com', port: 8883, tls: true, prefix: 'sinkhole', username: 'phone', wsPort: 8084, wsPath: '/mqtt' });
  const d = C.parseSetupLink('#setup?host=b.lan&tls=0&prefix=home%2Fwake%2F&user=');
  assert.deepEqual(d.link, { host: 'b.lan', port: 1883, tls: false, prefix: 'home/wake', username: null, wsPort: null, wsPath: null });
});

test('rejects other links and bad setup values', () => {
  assert.equal(C.parseSetupLink('https://example.com/').message, "This isn't a Pocket Wake setup link");
  assert.equal(C.parseSetupLink('#setup?port=1').message, 'The setup link has no valid broker address');
  assert.equal(C.parseSetupLink('#setup?host=b&port=70000').message, 'The setup link has an invalid port');
  assert.match(C.parseSetupLink('#setup?host=b&prefix=a%23').message, /invalid prefix/);
  assert.match(C.parseSetupLink('#setup?v=2&host=b').message, /newer/);
});

test('a setup link fills the web profile but never a password', () => {
  const link = C.parseSetupLink('#setup?host=new.example&wsport=8443&wspath=%2Fws&user=phone&password=leak&pass=leak').link;
  const p = C.applySetupLink({ ...C.defaultProfile(), host: 'old.example', username: 'phone', password: 'secret' }, link);
  assert.deepEqual([p.host, p.port, p.path, p.username, p.password], ['new.example', 8443, '/ws', 'phone', '']);
  const same = C.parseSetupLink('#setup?host=OLD.example&prefix=other').link;
  const kept = C.applySetupLink({ ...C.defaultProfile(), host: 'old.example', username: 'phone', password: 'secret' }, same);
  assert.deepEqual([kept.port, kept.path, kept.username, kept.password, kept.prefix], [8084, '/mqtt', 'phone', 'secret', 'other']);
});

// --- ad blocking and stats ----------------------------------------------------------------

test('blocking and stats topics', () => {
  assert.equal(Topics.blocking('sinkhole'), 'sinkhole/blocking');
  assert.equal(Topics.blockingSet('sinkhole'), 'sinkhole/blocking/set');
  assert.equal(Topics.stats('home/sinkhole'), 'home/sinkhole/stats');
  assert.deepEqual(Topics.filters('sinkhole'), ['sinkhole/status', 'sinkhole/wol/+/state', 'sinkhole/blocking', 'sinkhole/stats', 'sinkhole/wol/+/result']);
});

test('parses blocking payloads', () => {
  assert.equal(C.parseBlocking('ON'), true);
  assert.equal(C.parseBlocking(' on\n'), true);
  assert.equal(C.parseBlocking('OFF'), false);
  assert.equal(C.parseBlocking('0'), false);
  assert.equal(C.parseBlocking(''), null);
  assert.equal(C.parseBlocking('paused'), null);
});

test("parses the board's stats", () => {
  const s = C.parseStats('{"blocked":1834,"allowed":4127,"percent":30.7,"domains":139651,"clients":6}', 5);
  assert.deepEqual(s, { blocked: 1834, allowed: 4127, total: 5961, percent: 30.7, domains: 139651, clients: 6, at: 5, uptime: null, restart: null });
  assert.equal(C.parseStats('{"blocked":1,"allowed":3}', 0).percent, 25);
  assert.equal(C.parseStats('{"blocked":0,"allowed":0}', 0).percent, 0);
  assert.equal(C.parseStats('{"percent":3}', 0), null);
  assert.equal(C.parseStats('garbage', 0), null);
  assert.equal(C.parseStats('null', 0), null);
});

test('switch shows the request until the board confirms it', () => {
  let b = C.blockingReported(C.newBlocking(), true);
  b = C.blockingRequested(b, false, 1000);
  assert.equal(C.blockingShown(b), false);
  b = C.blockingReported(b, true); // periodic refresh with the old value
  assert.equal(b.requested, false);
  b = C.blockingReported(b, false);
  assert.equal(b.requested, null);
  assert.equal(b.on, false);
  assert.equal(b.problem, null);
});

test('unconfirmed blocking change times out with a hint', () => {
  const b = C.blockingRequested({ ...C.newBlocking(), on: true }, false, 0);
  assert.equal(C.blockingTick(b, C.BLOCKING_CONFIRM_MS - 1, 'sinkhole/blocking/set'), b);
  const t = C.blockingTick(b, C.BLOCKING_CONFIRM_MS, 'sinkhole/blocking/set');
  assert.equal(t.requested, null);
  assert.equal(C.blockingShown(t), true);
  assert.match(t.problem, /sinkhole\/blocking\/set/);
  assert.equal(C.blockingRequested(t, true, 0).problem, null);
});

test('formats counts and percentages', () => {
  assert.equal(Format.count(139651, 'en-US'), '139,651');
  assert.equal(Format.percent(30.66), '30.7 %');
});

test('stats from firmware 0.6 carry uptime and the restart reason', () => {
  const s = C.parseStats('{"blocked":1,"allowed":3,"uptime":3600,"restart":"power dip (brownout)"}', 1000);
  assert.equal(s.uptime, 3600);
  assert.equal(s.restart, 'power dip (brownout)');
  assert.equal(C.statsUptime(s, 1000 + 90_000), 3690);
  assert.equal(C.unexpectedRestart(s), true);
  assert.equal(C.unexpectedRestart(C.parseStats('{"blocked":1,"allowed":3,"restart":"firmware update"}', 0)), false);
  const old = C.parseStats('{"blocked":1,"allowed":3}', 0);
  assert.equal(old.uptime, null);
  assert.equal(old.restart, null);
  assert.equal(C.statsUptime(old, 5000), null);
  assert.equal(C.parseStats('{"blocked":1,"allowed":3,"uptime":-4,"restart":"  "}', 0).uptime, null);
  assert.equal(C.parseStats('{"blocked":1,"allowed":3,"restart":"  "}', 0).restart, null);
});

test('formats board uptime', () => {
  assert.equal(Format.uptime(45), '45 s');
  assert.equal(Format.uptime(754), '12 min');
  assert.equal(Format.uptime(7200), '2 h');
  assert.equal(Format.uptime(11_520), '3 h 12 min');
  assert.equal(Format.uptime(86_400), '1 d');
  assert.equal(Format.uptime(367_200), '4 d 6 h');
});

// --- wake results and Cancel (board firmware 0.6+) ------------------------------------------

const PC = 'OFFICE-PC';
const waitingBook = () => C.onWakePublished(C.onWakeRequested(C.onState({}, PC, 'offline', t0, false), PC, t0), PC, t0 + 200);
const boardWaiting = () => C.onResult(waitingBook(), PC, { kind: 'waking' }, t0 + 1000);

test("parses the board's wake results", () => {
  assert.deepEqual(C.parseResult('waking'), { kind: 'waking' });
  assert.deepEqual(C.parseResult('online after 41 s'), { kind: 'online', seconds: 41 });
  assert.deepEqual(C.parseResult('no answer after 10 min'), { kind: 'noAnswer', text: 'no answer after 10 min' });
  assert.deepEqual(C.parseResult('cancelled'), { kind: 'cancelled' });
  assert.deepEqual(C.parseResult('Already online\n'), { kind: 'alreadyOnline' });
  assert.deepEqual(C.parseResult('magic packet sent'), { kind: 'other', text: 'magic packet sent' });
  assert.equal(C.parseResult(''), null);
  assert.equal(Topics.hostFromResult('sinkhole', `sinkhole/wol/${PC}/result`), PC);
  assert.equal(Topics.hostFromResult('sinkhole', `sinkhole/wol/${PC}/state`), null);
  assert.equal(Topics.hostFromState('sinkhole', `sinkhole/wol/${PC}/result`), null);
  assert.ok(Topics.optionalFilters('sinkhole').includes('sinkhole/wol/+/result'));
});

test('waking lets the board track the wait and allows cancel', () => {
  assert.equal(C.canCancel(waitingBook()[PC].wake), false);
  const w = boardWaiting()[PC].wake;
  assert.equal(w.board, true);
  assert.equal(C.canCancel(w), true);
  assert.equal(w.startedAt, t0);
  // A "waking" that beats the publish ack is kept.
  let b = C.onWakeRequested(C.onState({}, PC, 'offline', t0, false), PC, t0);
  b = C.onWakePublished(C.onResult(b, PC, { kind: 'waking' }, t0 + 100), PC, t0 + 300);
  assert.equal(b[PC].wake.board, true);
});

test('board-tracked waits outlast the 3-minute default', () => {
  const b = boardWaiting();
  assert.equal(C.onTick(b, t0 + C.WAKE_TIMEOUT_MS + 1), b);
  assert.equal(C.onTick(b, t0 + C.BOARD_WAKE_LIMIT_MS)[PC].wake.text, 'No result from the board');
  assert.equal(C.onTick(waitingBook(), t0 + C.WAKE_TIMEOUT_MS)[PC].wake.kind, 'timedOut');
});

test('board results end the wait', () => {
  const ok = C.onResult(boardWaiting(), PC, { kind: 'online', seconds: 41 }, t0 + 42000)[PC];
  assert.equal(C.wakeSeconds(ok.wake), 41);
  assert.deepEqual(ok.lastWake, { at: t0, s: 41 });
  const none = C.onResult(boardWaiting(), PC, { kind: 'noAnswer', text: 'no answer after 10 min' }, t0 + 600000)[PC];
  assert.deepEqual(none.wake, { kind: 'timedOut', startedAt: t0, text: 'no answer after 10 min' });
  const up = C.onResult(boardWaiting(), PC, { kind: 'alreadyOnline' }, t0 + 1500)[PC];
  assert.deepEqual(up.wake, { kind: 'alreadyOnline', at: t0 + 1500, sent: false });
  assert.equal(up.presence, 'online');
  assert.equal(up.presenceSince, t0 + 1500);
});

test('cancel waits for the board, or gives up after a while', () => {
  let b = C.onCancelRequested(boardWaiting(), PC, t0 + 5000);
  assert.equal(C.canCancel(b[PC].wake), false);
  assert.equal(C.onResult(b, PC, { kind: 'cancelled' }, t0 + 6000)[PC].wake.kind, 'cancelled');
  assert.equal(C.onTick(b, t0 + 5000 + C.CANCEL_CONFIRM_MS - 1), b);
  assert.equal(C.onTick(b, t0 + 5000 + C.CANCEL_CONFIRM_MS)[PC].wake.kind, 'cancelled');
  b = C.onCancelFailed(b, PC);
  assert.equal(C.canCancel(b[PC].wake), true);
});

test("stale retained results don't touch a wait or start one", () => {
  const b = waitingBook();
  assert.equal(C.onResult(b, PC, { kind: 'online', seconds: 41 }, t0 + 500, false), b);
  assert.equal(C.onResult(b, PC, { kind: 'cancelled' }, t0 + 500, false), b);
  assert.equal(C.onResult(boardWaiting(), PC, { kind: 'online', seconds: 30 }, t0 + 60000, false)[PC].wake.kind, 'succeeded');
  const idle = C.onState({}, PC, 'offline', t0, false);
  assert.equal(C.onResult(idle, PC, { kind: 'waking' }, t0, false), idle);
  assert.equal(C.onResult(idle, PC, { kind: 'online', seconds: 12 }, t0), idle);
  assert.equal(C.onResult(idle, 'NOPE', { kind: 'cancelled' }, t0), idle);
});

test('a live wake from elsewhere shows as waiting', () => {
  const b = C.onResult(C.onState({}, PC, 'offline', t0, false), PC, { kind: 'waking' }, t0 + 1000);
  assert.equal(b[PC].wake.board, true);
  assert.equal(b[PC].wake.startedAt, t0 + 1000);
});
