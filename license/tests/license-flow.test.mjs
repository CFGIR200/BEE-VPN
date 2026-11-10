/**
 * BEE-VPN license worker — end-to-end harness (run: node license/tests/license-flow.test.mjs)
 * Drives the REAL license-worker.js with an in-memory SQLite (node:sqlite) D1 shim,
 * the same shape as bot-flow.test.mjs. Covers the audit fixes:
 *   T1  unknown code -> 401 (attempts recorded)
 *   T2  first activation binds + starts the clock (Ed25519 token issued)
 *   T3  second device on the same entitlement -> 409 (atomic binding guard)
 *   T4  re-activation from the SAME device stays 200 (idempotent re-bind)
 *   T5  /verify with a real token -> 200 fresh token
 *   T6  /verify with a FORGED payload (junk signature) -> 400 — the audit's
 *       3.1 fix: the old handler processed forged payloads as its own
 *   T7  /verify with a tampered payload (real signature on other bytes) -> 400
 *   T8  /verify signed but device-mismatched -> 403; expired -> 403
 *   T9  forgery flood hits the attempts budget -> 429 (the 3.2 fix: /verify
 *       used to be an unthrottled oracle)
 *   T10 /login wrong password -> 401; right password binds -> 200; other
 *       device -> 409
 *   T11 /pubkey serves the public key; bot webhook rejects a wrong secret
 *       and accepts the right one (constant-time compare path)
 */
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import nodeCrypto from 'node:crypto';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..', 'license-worker.js');
const BOT = path.join(HERE, '..', 'bot-worker.js');
const TMP = '/tmp/lw-under-test.mjs';
const TMP_BOT = '/tmp/bw-under-test-2.mjs';
const SCHEMA = fs.readFileSync(path.join(HERE, '..', 'schema.sql'), 'utf8');
fs.copyFileSync(SRC, TMP);
fs.copyFileSync(BOT, TMP_BOT);
const worker = (await import(TMP)).default;
const botWorker = (await import(TMP_BOT)).default;

// --- D1 shim over node:sqlite (same shape as bot-flow.test.mjs) -------------
function makeD1() {
  const db = new DatabaseSync(':memory:');
  db.exec(SCHEMA);
  return {
    prepare(sql) {
      const mk = (args) => {
        const stmt = db.prepare(sql);
        return {
          run: () => { const r = stmt.run(...args); return { meta: { changes: r.changes, last_row_id: r.lastInsertRowid }, success: true }; },
          all: () => ({ results: stmt.all(...args), success: true }),
          first: () => stmt.get(...args) ?? null,
        };
      };
      return { bind: (...args) => mk(args), ...mk([]) };
    },
  };
}

// --- Ed25519 keypair from a raw seed (the worker's PKCS#8 wrapper) -----------
const seed = nodeCrypto.randomBytes(32);
const pkcs8 = Buffer.concat([
  Buffer.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]),
  seed,
]);
const pubRaw = nodeCrypto.createPublicKey(
  nodeCrypto.createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' }),
).export({ format: 'der', type: 'spki' }).subarray(-32);
const b64u = (buf) => Buffer.from(buf).toString('base64url');

const env = {
  DB: makeD1(),
  LICENSE_ED25519_PRIV_B64: b64u(seed),
  LICENSE_ED25519_PUB_B64: b64u(pubRaw),
};

const subtleKey = await crypto.subtle.importKey('pkcs8', pkcs8, { name: 'Ed25519' }, false, ['sign']);
const signPayload = async (payload) => {
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  const sig = await crypto.subtle.sign({ name: 'Ed25519' }, subtleKey, bytes);
  return `v1.${b64u(bytes)}.${b64u(sig)}`;
};

const call = (pathname, body, ip = '1.2.3.4') =>
  worker.fetch(new Request(`https://lw${pathname}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'cf-connecting-ip': ip },
    body: JSON.stringify(body),
  }), env);

let pass = 0, failN = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log(`  ✅ ${name}${extra ? ' — ' + extra : ''}`); }
  else { failN++; console.log(`  ❌ ${name}${extra ? ' — ' + extra : ''}`); }
};

// --- seed -------------------------------------------------------------------
await env.DB.prepare(
  "INSERT INTO licenses (code, plan, status, duration_days, created_at) VALUES ('BEE-TEST-TEST-TEST', 'm1', 'active', 30, strftime('%s','now'))",
).run();
const salt = nodeCrypto.randomBytes(16);
const pbkdf2 = async (pw, s) => {
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: s, iterations: 100000 },
    await crypto.subtle.importKey('raw', new TextEncoder().encode(pw), 'PBKDF2', false, ['deriveBits']),
    256,
  );
  return b64u(bits);
};
await env.DB.prepare(
  "INSERT INTO accounts (username, pass_hash, pass_salt, plan, status, duration_days, created_at) VALUES ('buyer', ?, ?, 'm1', 'active', 30, strftime('%s','now'))",
).bind(await pbkdf2('correct horse', salt), b64u(salt)).run();

// --- T1 unknown code ---------------------------------------------------------
let r = await call('/activate', { code: 'BEE-NOPE-NOPE-NOPE', install_id: 'aabbccdd11223344' });
check('T1 unknown code -> 401', r.status === 401, `status=${r.status}`);

// --- T2 first activation -----------------------------------------------------
r = await call('/activate', { code: 'BEE-TEST-TEST-TEST', install_id: 'aabbccdd11223344' });
const act = await r.json();
check('T2 first activation -> 200 + token', r.status === 200 && act.token?.startsWith('v1.'), `status=${r.status}`);
const nowSec = Math.floor(Date.now() / 1000);
check('T2 clock started ≈ now + 30d', Math.abs(act.expiry - (nowSec + 30 * 86400)) < 60, `expiry=${act.expiry}`);
const row = await env.DB.prepare("SELECT * FROM licenses WHERE code = 'BEE-TEST-TEST-TEST'").first();
check('T2 row bound to device A', row.bound_install_id === 'aabbccdd11223344' && row.expires_at === act.expiry);

// --- T3 second device -> 409 -------------------------------------------------
r = await call('/activate', { code: 'BEE-TEST-TEST-TEST', install_id: 'ffee001122334455' });
check('T3 second device -> 409 bound', r.status === 409, `status=${r.status}`);

// --- T4 same device re-activation -> 200 -------------------------------------
r = await call('/activate', { code: 'BEE-TEST-TEST-TEST', install_id: 'aabbccdd11223344' });
check('T4 same device re-activation -> 200', r.status === 200, `status=${r.status}`);

// --- T5 /verify with the real token ------------------------------------------
r = await call('/verify', { token: act.token, install_id: 'aabbccdd11223344' });
const ver = await r.json();
check('T5 valid /verify -> 200 ok + fresh token', r.status === 200 && ver.ok === true && ver.token?.startsWith('v1.'), `status=${r.status}`);

// --- T6 forged payload -------------------------------------------------------
const forged = `v1.${b64u(new TextEncoder().encode(JSON.stringify({ s: 'BEE-TEST-TEST-TEST', k: 'code', p: 'm12', i: 'aabbccdd11223344', e: nowSec + 365 * 86400, t: nowSec })))}.AAAA`;
r = await call('/verify', { token: forged, install_id: 'aabbccdd11223344' });
check('T6 forged payload -> 400 (was: processed as ours)', r.status === 400, `status=${r.status}`);

// --- T7 tampered payload (real signature, other bytes) -----------------------
const parts = act.token.split('.');
const tampered = `v1.${b64u(new TextEncoder().encode(JSON.stringify({ s: 'BEE-TEST-TEST-TEST', k: 'code', p: 'm12', i: 'aabbccdd11223344', e: nowSec + 365 * 86400, t: nowSec })))}.${parts[2]}`;
r = await call('/verify', { token: tampered, install_id: 'aabbccdd11223344' });
check('T7 tampered payload -> 400', r.status === 400, `status=${r.status}`);

// --- T8 signed but wrong device / expired ------------------------------------
const wrongDev = await signPayload({ s: 'BEE-TEST-TEST-TEST', k: 'code', p: 'm1', i: 'ffee001122334455', e: nowSec + 86400, t: nowSec });
r = await call('/verify', { token: wrongDev, install_id: 'aabbccdd11223344' });
check('T8 signed token, device mismatch -> 403', r.status === 403, `status=${r.status}`);
const expired = await signPayload({ s: 'BEE-TEST-TEST-TEST', k: 'code', p: 'm1', i: 'aabbccdd11223344', e: nowSec - 100, t: nowSec - 200 });
r = await call('/verify', { token: expired, install_id: 'aabbccdd11223344' });
check('T8 signed token, expired -> 403', r.status === 403, `status=${r.status}`);

// --- T9 forgery flood -> 429 --------------------------------------------------
let lastStatus = 0;
for (let i = 0; i < 31; i++) {
  const rr = await call('/verify', { token: forged, install_id: 'aabbccdd11223344' }, '9.9.9.9');
  lastStatus = rr.status;
}
check('T9 forgery flood hits the attempts budget -> 429 on #31', lastStatus === 429, `last=${lastStatus}`);

// --- T10 login flow -----------------------------------------------------------
r = await call('/login', { username: 'buyer', password: 'wrong', install_id: 'ffee001122334455' });
check('T10 wrong password -> 401', r.status === 401, `status=${r.status}`);
r = await call('/login', { username: 'buyer', password: 'correct horse', install_id: 'ffee001122334455' });
const log = await r.json();
check('T10 right password -> 200 + account token', r.status === 200 && log.kind === 'account', `status=${r.status}`);
r = await call('/login', { username: 'buyer', password: 'correct horse', install_id: 'aabbccdd11223344' });
check('T10 second device on the account -> 409', r.status === 409, `status=${r.status}`);

// --- T11 /pubkey + bot webhook secret -----------------------------------------
r = await worker.fetch(new Request('https://lw/pubkey'), env);
const pk = await r.json();
check('T11 /pubkey serves the compiled-in key', r.status === 200 && pk.pubkey === b64u(pubRaw));

const botCall = async (secret) => botWorker.fetch(new Request('https://bot/w', {
  method: 'POST',
  headers: { 'x-telegram-bot-api-secret-token': secret },
  body: '{"update_id":1}',
}), { WEBHOOK_SECRET: 's3cret', DB: null, BOT_TOKEN: null });
check('T11 bot webhook wrong secret -> 403', (await botCall('nope')).status === 403);
check('T11 bot webhook right secret passes auth (500 not-configured next)', (await botCall('s3cret')).status === 500);

console.log(`\n═══ نتیجه: ${pass} پاس ✅ — ${failN} شکست ❌ ═══`);
process.exit(failN === 0 ? 0 : 1);
