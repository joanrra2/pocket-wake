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
  assert.deepEqual(b.PC.wake, { kind: 'waiting', startedAt: t0 + 1000 });
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
