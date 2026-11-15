// Settings in localStorage. The password is saved only when the user asks, and then only as
// AES-GCM ciphertext made with a non-extractable WebCrypto key kept in IndexedDB.
// Every storage access is guarded: private browsing can block or wipe storage.

import { defaultProfile } from './core.js';

const KEY = 'pocketwake.v1';
const DB = 'pocketwake';
const STORE = 'keys';
const KEY_ID = 'secret.v1';

function readAll() {
  try { return JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { return {}; }
}

function writeAll(obj) {
  try { localStorage.setItem(KEY, JSON.stringify(obj)); return true; } catch { return false; }
}

function patch(fields) {
  return writeAll({ ...readAll(), ...fields });
}

// --- WebCrypto key in IndexedDB ------------------------------------------------------

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function idb(db, mode, fn) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode);
    const req = fn(tx.objectStore(STORE));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function secretKey(create) {
  const db = await openDb();
  try {
    let key = await idb(db, 'readonly', (s) => s.get(KEY_ID));
    if (!key && create) {
      key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      await idb(db, 'readwrite', (s) => s.put(key, KEY_ID));
    }
    return key || null;
  } finally {
    db.close();
  }
}

const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function encrypt(plain) {
  const key = await secretKey(true);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(plain));
  return `v1:${b64(iv)}:${b64(ct)}`;
}

async function decrypt(stored) {
  const [v, iv, ct] = String(stored).split(':');
  if (v !== 'v1' || !iv || !ct) return null;
  const key = await secretKey(false);
  if (!key) return null;
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(iv) }, key, unb64(ct));
  return new TextDecoder().decode(pt);
}

// --- public API -----------------------------------------------------------------------

/** { profile, remember, passwordLost } */
export async function loadProfile() {
  const all = readAll();
  const profile = { ...defaultProfile(), ...(all.profile || {}), password: '' };
  let passwordLost = false;
  if (all.passEnc) {
    try { profile.password = (await decrypt(all.passEnc)) ?? ''; } catch { profile.password = ''; }
    passwordLost = !profile.password;
  }
  return { profile, remember: all.remember !== false, passwordLost };
}

export async function saveProfile(p, remember) {
  const { password, ...rest } = p;
  let passEnc = null;
  if (remember && password) {
    try { passEnc = await encrypt(password); } catch { passEnc = null; }
  }
  patch({ profile: rest, remember, passEnc });
  return remember && password ? passEnc !== null : true;
}

export const loadManual = () => (Array.isArray(readAll().manual) ? readAll().manual : []);
export const saveManual = (names) => patch({ manual: names });
export const loadLastWakes = () => readAll().lastWakes || {};
export const saveLastWakes = (map) => patch({ lastWakes: map });

/** Stable random MQTT client id for this browser, e.g. "pocketwake-web-3f9a1c2e". */
export function clientId() {
  const all = readAll();
  if (all.clientId) return all.clientId;
  const id = 'pocketwake-web-' + crypto.getRandomValues(new Uint32Array(1))[0].toString(16).padStart(8, '0');
  patch({ clientId: id });
  return id;
}

/** Removes everything this app stored in this browser. */
export async function forgetAll() {
  try { localStorage.removeItem(KEY); } catch { /* ignore */ }
  try { await new Promise((res) => { const r = indexedDB.deleteDatabase(DB); r.onsuccess = r.onerror = r.onblocked = () => res(); }); } catch { /* ignore */ }
}
