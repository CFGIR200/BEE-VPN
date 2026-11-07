-- Migration 0002 — the ثبت‌نام (self-registration) feature.
-- Apply to an EXISTING database with:
--   wrangler d1 execute beevpn-license --remote --file=migrations/0002-registrations.sql
--
-- Fresh installs don't need this file: schema.sql already has the final shape.
--
-- What it adds:
--   accounts.telegram_name     — Telegram display name at registration time
--   accounts.telegram_username — @handle at registration time (may be NULL)
--   accounts.note              — receipt:<ts>:<buyerId> approval idempotency
--                                (same pattern licenses.note already uses) and
--                                trial:<tgId> for trial registrations
--   pending_receipts.kind      — 'code' (🛍 خرید → کد) or 'account'
--                                (📝 ثبت‌نام → username/password)
--   idx_accounts_note          — the idempotency lookup
--
-- Note: SQLite has no ADD COLUMN IF NOT EXISTS. If a statement fails with
-- «duplicate column name» that column is already there — move on to the rest.

ALTER TABLE accounts ADD COLUMN telegram_name TEXT;
ALTER TABLE accounts ADD COLUMN telegram_username TEXT;
ALTER TABLE accounts ADD COLUMN note TEXT;
ALTER TABLE pending_receipts ADD COLUMN kind TEXT NOT NULL DEFAULT 'code';
CREATE INDEX IF NOT EXISTS idx_accounts_note ON accounts (note);
