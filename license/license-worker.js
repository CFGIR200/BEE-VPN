/**
 * BEE-VPN license server — a single Cloudflare Worker backed by D1.
 *
 * Three endpoints the app talks to (all POST, JSON in / JSON out):
 *
 *   /activate  { code, install_id }                -> binds a code to a device
 *   /login     { username, password, install_id }  -> account sign-in
 *   /verify    { token, install_id }               -> re-check (revocation + expiry)
 *
 * and one diagnostic: GET /pubkey (safe to share — it is the key baked into
 * the APK; this endpoint exists so the operator can confirm the deployed
 * secret matches the build without unwrapping an APK).
 *
 * Entitlements are Ed25519-signed tokens: `v1.<payloadB64url>.<sigB64url>`.
 * The payload is JSON {s,k,p,i,e,t} — subject, kind, plan, install id,
 * expiry (epoch sec), issued-at. The app verifies the signature with the
 * public key compiled into BuildConfig, checks that `i` is its own install
 * id and that `e` is in the future. That means:
 *
 *   - the app works fully OFFLINE until the token's own `e` (the grace
 *     period IS the remaining subscription, there is no separate one),
 *   - revocation propagates the next time /verify runs (app does it in
 *     background every ~12 h and on resume),
 *   - nothing secret ships in the APK — the repo is AGPL-public, so all
 *     trust lives here, on the server.
 *
 * Device binding: exactly one device per entitlement (the operator's
 * choice). The subscription clock starts at FIRST activation, not at
 * purchase, so an unsold code never ages on the shelf.
 *
 * Deploy + secrets: see LICENSE-SETUP-FA.txt in this folder.
 */

const PLAN_DAYS = { trial: 1, m1: 30, m3: 90, m6: 182, m12: 365 };
const PBKDF2_ITERATIONS = 100000;  // Workers caps PBKDF2 at 100k iterations
const MAX_FAILED_PER_HOUR = 30;
const MAX_BODY_BYTES = 4096;

const enc = new TextEncoder();

function b64urlEncode(bytes) {
  let bin = '';
  for (const b of new Uint8Array(bytes)) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlDecode(text) {
  const pad = text.length % 4 === 0 ? '' : '='.repeat(4 - (text.length % 4));
  const bin = atob(text.replace(/-/g, '+').replace(/_/g, '/') + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function json(resp, status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function bad(status, error) {
  return json(null, status, { ok: false, error });
}

function normalizeInstallId(value) {
  if (typeof value !== 'string') return null;
  const v = value.trim().toLowerCase();
  return /^[a-f0-9]{16,64}$/.test(v) ? v : null;
}

function normalizeCode(value) {
  if (typeof value !== 'string') return null;
  const v = value.trim().toUpperCase().replace(/\s+/g, '');
  return /^BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(v) ? v : null;
}

function normalizeUsername(value) {
  if (typeof value !== 'string') return null;
  const v = value.trim().toLowerCase();
  return /^[a-z0-9_.-]{3,32}$/.test(v) ? v : null;
}

async function ed25519PrivateKey(env) {
  const seed = b64urlDecode(String(env.LICENSE_ED25519_PRIV_B64 || ''));
  if (seed.length !== 32) throw new Error('LICENSE_ED25519_PRIV_B64 must decode to 32 raw bytes');
  // RFC 8410 PKCS#8 wrapper around the raw 32-byte Ed25519 seed. WebCrypto's
  // raw import is PUBLIC-only for Ed25519 (Workers and Node both reject raw
  // private imports), while pkcs8 works everywhere.
  const pkcs8 = new Uint8Array(48);
  pkcs8.set(new Uint8Array([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]), 0);
  pkcs8.set(seed, 16);
  return crypto.subtle.importKey('pkcs8', pkcs8, { name: 'Ed25519' }, false, ['sign']);
}

/** Signs the payload and returns the full `v1.p.sig` token string. */
async function issueToken(env, payload) {
  const payloadBytes = enc.encode(JSON.stringify(payload));
  const key = await ed25519PrivateKey(env);
  const sig = await crypto.subtle.sign({ name: 'Ed25519' }, key, payloadBytes);
  return `v1.${b64urlEncode(payloadBytes)}.${b64urlEncode(sig)}`;
}

/** Ed25519 verification over a `v1.<payload>.<sig>` token — the same bytes
 * the app's compiled-in public key checks. Raw public import IS allowed for
 * Ed25519 in both Workers and Node (only raw PRIVATE import is rejected). */
async function tokenSignatureValid(env, payloadB64url, sigB64url) {
  try {
    const pub = String(env.LICENSE_ED25519_PUB_B64 || '');
    if (!pub) return false;
    const key = await crypto.subtle.importKey('raw', b64urlDecode(pub), { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify(
      { name: 'Ed25519' },
      key,
      b64urlDecode(sigB64url),
      b64urlDecode(payloadB64url),
    );
  } catch (_) {
    return false;
  }
}

async function pbkdf2Hash(password, saltB64) {
  const salt = b64urlDecode(saltB64);
  const material = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: PBKDF2_ITERATIONS },
    material,
    256,
  );
  return b64urlEncode(bits);
}

/** Timing-safe string compare (both inputs are same-length base64). */
function constantTimeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function randomCode() {
  // Crockford-ish alphabet: no 0/O/1/I/L so a customer cannot misread a code
  // read out over the phone.
  const alphabet = '23456789ABCDEFGHJKMNPQRSTVWXYZ';
  const pick = () => {
    const buf = new Uint8Array(4);
    crypto.getRandomValues(buf);
    let s = '';
    for (const b of buf) s += alphabet[b % alphabet.length];
    return s;
  };
  return `BEE-${pick()}-${pick()}-${pick()}`;
}

async function recordAttempt(env, request, ok) {
  try {
    const ip = request.headers.get('cf-connecting-ip') || 'unknown';
    const now = Math.floor(Date.now() / 1000);
    await env.DB.prepare('INSERT INTO attempts (ip, ts, ok) VALUES (?, ?, ?)').bind(ip, now, ok ? 1 : 0).run();
    // Opportunistic cleanup: rows older than 2 h are dead weight.
    if (Math.random() < 0.1) {
      await env.DB.prepare('DELETE FROM attempts WHERE ts < ?').bind(now - 7200).run();
    }
  } catch (_) { /* audit is best-effort; never block an activation on it */ }
}

async function throttled(env, request) {
  try {
    const ip = request.headers.get('cf-connecting-ip') || 'unknown';
    const since = Math.floor(Date.now() / 1000) - 3600;
    const row = await env.DB
      .prepare('SELECT COUNT(*) AS n FROM attempts WHERE ip = ? AND ts > ? AND ok = 0')
      .bind(ip, since)
      .first();
    return (row ? row.n : 0) >= MAX_FAILED_PER_HOUR;
  } catch (_) {
    return false;
  }
}

/** Shared activation path for codes and accounts. */
async function bindAndIssue(env, row, table, keyColumn, keyValue, installId) {
  const now = Math.floor(Date.now() / 1000);
  if (row.status !== 'active') return bad(403, 'revoked');
  if (row.bound_install_id && row.bound_install_id !== installId) return bad(409, 'bound to another device');
  if (row.expires_at && row.expires_at <= now) return bad(403, 'expired');

  let expiry = row.expires_at;
  if (!expiry) {
    // First activation starts the clock. The `bound_install_id IS NULL OR =
    // installId` guard makes the bind ATOMIC: the 1.7.x check-then-act race
    // (SELECT here, UPDATE there) let two concurrent activations with two
    // different devices both win — one entitlement on two devices. A loser
    // of the atomic UPDATE now gets the honest 409.
    expiry = now + row.duration_days * 86400;
    const upd = await env.DB
      .prepare(`UPDATE ${table} SET bound_install_id = ?, activated_at = ?, expires_at = ? WHERE ${keyColumn} = ? AND (bound_install_id IS NULL OR bound_install_id = ?)`)
      .bind(installId, now, expiry, keyValue, installId)
      .run();
    if (!upd.meta || upd.meta.changes === 0) return bad(409, 'bound to another device');
  }

  const subject = table === 'licenses' ? row.code : row.username;
  const token = await issueToken(env, {
    s: subject, k: table === 'licenses' ? 'code' : 'account', p: row.plan, i: installId, e: expiry, t: now,
  });
  return json(null, 200, {
    ok: true, token, subject, plan: row.plan, kind: table === 'licenses' ? 'code' : 'account', expiry,
  });
}

export default {
  async fetch(request, env) {
    try {
      return await handle(request, env);
    } catch (e) {
      // Never leak a Cloudflare 1101 page to the app — a JSON 500 is
      // diagnosable from the client side and safe to log.
      return bad(500, 'internal error: ' + String((e && e.message) || e));
    }
  },
};

async function handle(request, env) {
  const url = new URL(request.url);

if (request.method === 'GET' && url.pathname === '/healthz') {
  return json(null, 200, { ok: true, service: 'beevpn-license' });
}

if (request.method === 'GET' && url.pathname === '/pubkey') {
  const pub = String(env.LICENSE_ED25519_PUB_B64 || '');
  return pub ? json(null, 200, { ok: true, pubkey: pub }) : bad(500, 'public key not configured');
}

if (request.method !== 'POST') return bad(404, 'not found');

if (!env.DB) return bad(500, 'D1 binding missing');
if (!env.LICENSE_ED25519_PRIV_B64) return bad(500, 'signing key not configured');

let body;
try {
  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) return bad(413, 'body too large');
  body = JSON.parse(raw);
} catch (_) {
  return bad(400, 'invalid json');
}

const installId = normalizeInstallId(body.install_id);

if (url.pathname === '/activate') {
  const code = normalizeCode(body.code);
  if (!code) return bad(400, 'invalid code format');
  if (!installId) return bad(400, 'invalid install_id');
  if (await throttled(env, request)) return bad(429, 'too many attempts, try later');
  const row = await env.DB.prepare('SELECT * FROM licenses WHERE code = ?').bind(code).first();
  if (!row) {
    await recordAttempt(env, request, false);
    return bad(401, 'unknown code');
  }
  const resp = await bindAndIssue(env, row, 'licenses', 'code', code, installId);
  await recordAttempt(env, request, resp.status === 200);
  return resp;
}

if (url.pathname === '/login') {
  const username = normalizeUsername(body.username);
  const password = typeof body.password === 'string' ? body.password : '';
  if (!username || !password || password.length > 128) return bad(400, 'invalid username or password');
  if (!installId) return bad(400, 'invalid install_id');
  if (await throttled(env, request)) return bad(429, 'too many attempts, try later');
  const row = await env.DB.prepare('SELECT * FROM accounts WHERE username = ?').bind(username).first();
  if (!row) {
    await recordAttempt(env, request, false);
    return bad(401, 'wrong username or password');
  }
  const hash = await pbkdf2Hash(password, row.pass_salt);
  if (!constantTimeEqual(hash, row.pass_hash)) {
    await recordAttempt(env, request, false);
    return bad(401, 'wrong username or password');
  }
  const resp = await bindAndIssue(env, row, 'accounts', 'username', username, installId);
  await recordAttempt(env, request, resp.status === 200);
  return resp;
}

if (url.pathname === '/verify') {
  const token = typeof body.token === 'string' ? body.token : '';
  if (!token.startsWith('v1.') || !installId) return bad(400, 'invalid token');
  if (await throttled(env, request)) return bad(429, 'too many attempts, try later');
  const parts = token.split('.');
  if (parts.length !== 3) return bad(400, 'invalid token');
  // The Ed25519 signature is checked HERE, before the payload is trusted.
  // The 1.7.x handler parsed the payload with NO verify call — any client
  // could post `v1.<forged>.<junk>` and reach the DB oracle. A failed
  // signature also counts against the attempts budget, so the forged-
  // payload oracle is rate-limited like every other abuse surface.
  if (!(await tokenSignatureValid(env, parts[1], parts[2]))) {
    await recordAttempt(env, request, false);
    return bad(400, 'invalid token');
  }
  let payload;
  try {
    payload = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1])));
  } catch (_) {
    return bad(400, 'invalid token');
  }
  const now = Math.floor(Date.now() / 1000);
  if (payload.i !== installId) return bad(403, 'device mismatch');
  if (!payload.e || payload.e <= now) return bad(403, 'expired');

  // The signature proved the token is ours; the DB lookup proves the
  // entitlement still is. Revocation and admin extends both land here.
  if (payload.k === 'account') {
    const row = await env.DB.prepare('SELECT * FROM accounts WHERE username = ?').bind(String(payload.s)).first();
    if (!row || row.status !== 'active') return bad(403, 'revoked');
    if (row.bound_install_id && row.bound_install_id !== installId) return bad(403, 'bound to another device');
    if (row.expires_at && row.expires_at <= now) return bad(403, 'expired');
    const fresh = await issueToken(env, {
      s: row.username, k: 'account', p: row.plan, i: installId, e: row.expires_at, t: now,
    });
    return json(null, 200, { ok: true, token: fresh, subject: row.username, plan: row.plan, kind: 'account', expiry: row.expires_at, activated_at: row.activated_at });
  }
  const row = await env.DB.prepare('SELECT * FROM licenses WHERE code = ?').bind(String(payload.s)).first();
  if (!row || row.status !== 'active') return bad(403, 'revoked');
  if (row.bound_install_id && row.bound_install_id !== installId) return bad(403, 'bound to another device');
  if (row.expires_at && row.expires_at <= now) return bad(403, 'expired');
  const fresh = await issueToken(env, {
    s: row.code, k: 'code', p: row.plan, i: installId, e: row.expires_at, t: now,
  });
  return json(null, 200, { ok: true, token: fresh, subject: row.code, plan: row.plan, kind: 'code', expiry: row.expires_at, activated_at: row.activated_at });
}

return bad(404, 'not found');
}
