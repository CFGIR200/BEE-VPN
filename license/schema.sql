-- BEE-VPN license database (Cloudflare D1)
-- Apply with:  wrangler d1 execute beevpn-license --remote --file=schema.sql
--
-- Two entitlement tables, one audit helper:
--   licenses  — activation codes (paid plans and the 1-day trial alike).
--   accounts  — username/password subscriptions issued by the admin.
-- Both share the same lifecycle: issued by the Telegram bot -> bound to one
-- device on first activation (the clock starts there, not at purchase) ->
-- renewed with /extend -> killed with /revoke.

CREATE TABLE IF NOT EXISTS licenses (
  code             TEXT PRIMARY KEY,          -- BEE-XXXX-XXXX-XXXX
  plan             TEXT NOT NULL,             -- trial | m1 | m3 | m6 | m12
  status           TEXT NOT NULL DEFAULT 'active',  -- active | revoked
  duration_days    INTEGER NOT NULL,
  note             TEXT,
  telegram_user_id INTEGER,                   -- who bought it (via bot)
  bound_install_id TEXT,                      -- device binding: exactly one
  activated_at     INTEGER,                   -- epoch sec, first activation
  expires_at       INTEGER,                   -- epoch sec; NULL until bound
  created_at       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
  username         TEXT PRIMARY KEY,
  pass_hash        TEXT NOT NULL,             -- base64 PBKDF2-SHA256, 100k iters (Workers cap)
  pass_salt        TEXT NOT NULL,             -- base64, 16 bytes
  plan             TEXT NOT NULL DEFAULT 'm1',
  status           TEXT NOT NULL DEFAULT 'active',
  duration_days    INTEGER NOT NULL DEFAULT 30,
  telegram_user_id INTEGER,
  bound_install_id TEXT,
  activated_at     INTEGER,
  expires_at       INTEGER,
  created_at       INTEGER NOT NULL
);

-- Failed-attempt ring for a crude per-IP throttle (see license-worker.js).
CREATE TABLE IF NOT EXISTS attempts (
  ip  TEXT NOT NULL,
  ts  INTEGER NOT NULL,
  ok  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_attempts_ip_ts ON attempts (ip, ts);

CREATE INDEX IF NOT EXISTS idx_licenses_tg ON licenses (telegram_user_id);
CREATE INDEX IF NOT EXISTS idx_accounts_tg ON accounts (telegram_user_id);
