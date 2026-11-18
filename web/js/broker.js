// MQTT 3.1.1 over secure WebSocket with MQTT.js (MIT, vendored in ../vendor).
// The browser verifies the broker's TLS certificate itself; this page can only use wss://.

import mqttModule from '../vendor/mqtt.esm.js';
import { Topics, backoffSeconds, brokerUrl, normalizeProfile, parseBlocking } from './core.js';

const mqtt = typeof mqttModule.connect === 'function' ? mqttModule : mqttModule.default;

/** Turns an MQTT.js error (or none) into words a user can act on. fatal = retrying won't help. */
export function describeError(err, profile, everConnected) {
  const p = normalizeProfile(profile);
  const code = err && typeof err.code === 'number' ? err.code : null;
  const msg = String((err && err.message) || '').toLowerCase();
  if (code === 4 || msg.includes('bad username or password')) return { message: 'Wrong username or password', fatal: true };
  if (code === 5 || msg.includes('not authorized')) return { message: "Login refused: check the username, password and the broker's access rules", fatal: true };
  if (code === 2 || msg.includes('identifier rejected')) return { message: "The broker rejected this browser's client id", fatal: true };
  if (code === 1 || msg.includes('unacceptable protocol')) return { message: "The broker doesn't accept MQTT 3.1.1", fatal: true };
  if (code === 3 || msg.includes('server unavailable')) return { message: 'The broker is temporarily unavailable', fatal: false };
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return { message: 'No internet connection', fatal: false };
  if (msg.includes('connack timeout') || msg.includes('timeout')) return { message: "The broker didn't answer in time", fatal: false };
  if (everConnected) return { message: 'Connection to the broker was lost', fatal: false };
  // Browsers hide WebSocket failure details (DNS, TLS, wrong port or path all look the same).
  return {
    message: `Can't reach ${brokerUrl(p)}. Check the address, the WebSocket TLS port (EMQX Cloud: 8084) and path (/mqtt)`,
    fatal: false,
  };
}

function connectOptions(p, clientId) {
  return {
    clientId,
    username: p.username || undefined,
    password: p.password || undefined,
    protocolVersion: 4,
    clean: true,
    keepalive: 30,
    connectTimeout: 10_000,
    reconnectPeriod: 0, // reconnects are scheduled below, with backoff
    // Plain timers: background throttling doesn't matter, the page disconnects when hidden.
    timerVariant: 'native',
  };
}

/**
 * Long-lived connection while the page is visible.
 * onConnection(state): { kind: 'idle'|'connecting'|'connected'|'retrying'|'failed', since?, reason?, attempt?, next? }
 * onMessage({ topic, payload, retained })
 */
export function openSession(profile, clientId, filters, onConnection, onMessage) {
  const p = normalizeProfile(profile);
  let stopped = false;
  let attempt = 0;
  let everConnected = false;
  let lastError = null;
  let fatal = false;
  let timer = null;
  let connected = false;

  onConnection({ kind: 'connecting' });
  const client = mqtt.connect(brokerUrl(p), connectOptions(p, clientId));

  client.on('connect', () => {
    connected = true;
    everConnected = true;
    attempt = 0;
    lastError = null;
    onConnection({ kind: 'connected', since: Date.now() });
    // A refused optional topic (blocking, stats) just stays silent; the test reports it.
    client.subscribe(filters, { qos: 1 }, () => {});
  });

  client.on('message', (topic, payload, packet) => {
    onMessage({ topic, payload: new TextDecoder().decode(payload), retained: !!packet.retain });
  });

  client.on('error', (err) => {
    lastError = err;
    const d = describeError(err, p, everConnected);
    if (d.fatal) {
      fatal = true;
      onConnection({ kind: 'failed', reason: d.message });
      client.end(true);
    }
  });

  client.on('close', () => {
    const wasConnected = connected;
    connected = false;
    if (stopped || fatal) return;
    const d = describeError(lastError, p, wasConnected || everConnected);
    attempt += 1;
    const next = backoffSeconds(attempt);
    onConnection({ kind: 'retrying', reason: d.message, attempt, next });
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (stopped) return;
      lastError = null;
      onConnection({ kind: 'connecting' });
      client.reconnect();
    }, next * 1000);
  });

  return {
    isConnected: () => connected,

    /** Non-retained QoS 1 publish; resolves when the broker acknowledges it. */
    publish(topic, payload) {
      return new Promise((resolve, reject) => {
        if (!connected) return reject(new Error('Not connected to the broker'));
        const t = setTimeout(() => reject(new Error("The broker didn't accept the request")), 10_000);
        client.publish(topic, payload, { qos: 1, retain: false }, (err) => {
          clearTimeout(t);
          if (err) reject(new Error("The broker didn't accept the request"));
          else resolve();
        });
      });
    },

    stop() {
      stopped = true;
      clearTimeout(timer);
      client.end(true);
      onConnection({ kind: 'idle' });
    },
  };
}

/**
 * Connects once, listens briefly for the retained board status and PC states, disconnects.
 * Resolves { error } or { connectMillis, board: 'online'|'offline'|null, hosts: { name: presence },
 * denied: [filters the broker refused], blocking: true|false|null }.
 */
export function testConnection(profile, clientId, listenMillis = 2500) {
  const p = normalizeProfile(profile);
  return new Promise((resolve) => {
    const started = performance.now();
    const client = mqtt.connect(brokerUrl(p), { ...connectOptions(p, `${clientId}-test`), connectTimeout: 15_000 });
    let board = null;
    let blocking = null;
    const hosts = {};
    let done = false;
    let lastError = null;
    const finish = (result) => {
      if (done) return;
      done = true;
      clearTimeout(guard);
      client.end(true);
      resolve(result);
    };
    const guard = setTimeout(() => finish({ error: describeError(lastError ?? new Error('timeout'), p, false).message }), 20_000);

    client.on('message', (topic, payload) => {
      if (topic === Topics.blocking(p.prefix)) {
        blocking = parseBlocking(new TextDecoder().decode(payload)) ?? blocking;
        return;
      }
      const presence = Topics.parsePresence(new TextDecoder().decode(payload));
      if (!presence) return;
      if (topic === Topics.status(p.prefix)) board = presence === 'cleared' ? null : presence;
      const name = Topics.hostFromState(p.prefix, topic);
      if (name) {
        if (presence === 'cleared') delete hosts[name];
        else hosts[name] = presence;
      }
    });
    client.on('error', (err) => {
      lastError = err;
      if (describeError(err, p, false).fatal) finish({ error: describeError(err, p, false).message });
    });
    client.on('close', () => {
      if (!done && !client.connected) finish({ error: describeError(lastError, p, false).message });
    });
    client.on('connect', () => {
      const connectMillis = Math.round(performance.now() - started);
      const filters = Topics.filters(p.prefix);
      client.subscribe(filters, { qos: 1 }, (err, granted) => {
        // MQTT 3.1.1 SUBACK return code 0x80 = refused by the broker's access rules. MQTT.js
        // then reports an error that carries the SUBACK (codes in the order of the filters).
        const codes = err?.packet?.granted ?? (granted || []).map((g) => g.qos);
        const denied = filters.filter((f, i) => typeof codes[i] === 'number' && (codes[i] & 0x80) !== 0);
        setTimeout(() => finish({ connectMillis, board, hosts: { ...hosts }, denied, blocking }), listenMillis);
      });
    });
  });
}
