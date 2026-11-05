/**
 * BEE-VPN sales bot — Cloudflare Worker + Telegram webhook, same D1 database
 * as license-worker.js (D1 bindings may be attached to several Workers).
 *
 * What it does for the operator:
 *   - sells: plan catalogue -> payment info (card-to-card + USDT) -> the
 *     buyer sends the receipt, the admin issues a code with /gen and pastes
 *     it into the chat. No payment gateway needed on day one.
 *   - trial: hands out exactly one 1-day code per Telegram account.
 *   - admin: /gen /adduser /list /who /revoke /unrevoke /extend /reset
 *            /stats /buyers
 *
 * Webhook setup (once, after deploy):
 *   curl "https://api.telegram.org/bot$BOT_TOKEN/setWebhook" \
 *        -d "url=https://<this-worker>.workers.dev/w" \
 *        -d "secret_token=$WEBHOOK_SECRET"
 *
 * Every request is checked against the X-Telegram-Bot-Api-Secret-Token
 * header, so only Telegram can drive this Worker. Admin commands only run
 * for ids listed in ADMIN_IDS.
 *
 * Deploy + secrets: see LICENSE-SETUP-FA.txt in this folder.
 */

const PLAN_DAYS = { trial: 1, m1: 30, m3: 90, m6: 182, m12: 365 };
const PBKDF2_ITERATIONS = 100000;  // Workers caps PBKDF2 at 100k iterations

const DEFAULT_PRICES = {
  m1:  { title: 'اشتراک یک‌ماهه',            price: '۱۵۰٬۰۰۰ تومان' },
  m3:  { title: 'اشتراک سه‌ماهه (۱۰٪ تخفیف)', price: '۴۰۵٬۰۰۰ تومان' },
  m6:  { title: 'اشتراک شش‌ماهه (۲۰٪ تخفیف)', price: '۷۲۰٬۰۰۰ تومان' },
  m12: { title: 'اشتراک دوازده‌ماهه (۳۰٪ تخفیف)', price: '۱٬۲۶۰٬۰۰۰ تومان' },
};

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

function randomCode() {
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

function esc(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function planDays(plan) {
  return PLAN_DAYS[plan] || 0;
}

function fmtExpiry(row, now) {
  if (!row.expires_at) return '⏳ فعال نشده (از اولین فعال‌سازی شروع می‌شود)';
  const left = Math.floor((row.expires_at - now) / 86400);
  const when = new Date(row.expires_at * 1000).toISOString().slice(0, 10);
  if (row.expires_at <= now) return `❌ منقضی شده (${when})`;
  return `✅ ${left + 1} روز باقی‌مانده — تا ${when}`;
}

async function tg(env, method, body) {
  const res = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json().catch(() => ({}));
}

async function send(env, chatId, text, keyboard) {
  const body = { chat_id: chatId, text, parse_mode: 'HTML', disable_web_page_preview: true };
  if (keyboard) body.reply_markup = keyboard;
  return tg(env, 'sendMessage', body);
}

async function answerCb(env, id, text) {
  return tg(env, 'answerCallbackQuery', { callback_query_id: id, text: text || undefined });
}

function mainMenu(env) {
  const support = String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN');
  return {
    inline_keyboard: [
      [{ text: '🛍 خرید اشتراک', callback_data: 'buy' }, { text: '🎁 کد تست ۱ روزه', callback_data: 'trial' }],
      [{ text: '👤 اشتراک من', callback_data: 'mysub' }, { text: '🧾 راهنمای فعال‌سازی', callback_data: 'help' }],
      [{ text: '🆘 پشتیبانی', url: support }],
    ],
  };
}

function prices(env) {
  try {
    const parsed = JSON.parse(String(env.PRICES_JSON || ''));
    if (parsed && typeof parsed === 'object' && Object.keys(parsed).length) return parsed;
  } catch (_) { /* fall through */ }
  return DEFAULT_PRICES;
}

function planKeyboard(env) {
  const list = prices(env);
  const rows = Object.entries(list).map(([plan, info]) => ([{
    text: `${info.title} — ${info.price}`,
    callback_data: `plan:${plan}`,
  }]));
  rows.push([{ text: '🔙 بازگشت', callback_data: 'menu' }]);
  return { inline_keyboard: rows };
}

function paymentText(env, plan) {
  const info = prices(env)[plan] || { title: plan, price: '?' };
  const card = String(env.CARD_INFO || '(کارت به کارت: شماره کارت در env تنظیم نشده)');
  const usdt = String(env.USDT_INFO || '(USDT TRC20: آدرس کیف پول در env تنظیم نشده)');
  return [
    `🐝 <b>${esc(info.title)}</b> — ${esc(info.price)}`,
    '',
    '💳 <b>پرداخت ریالی (کارت به کارت):</b>',
    `<code>${esc(card)}</code>`,
    '',
    '💎 <b>پرداخت تتر (USDT):</b>',
    `<code>${esc(usdt)}</code>`,
    '',
    '📌 بعد از پرداخت، <b>عکسِ رسید را مستقیم و بدون هیچ کامندی همین‌جا بفرست</b> (اسکرین‌شات).',
    '✅ ادمین تأیید می‌کند و کد فعال‌سازی خودکار برایت ارسال می‌شود.',
    '⏱ اشتراک از لحظهٔ اولین فعال‌سازی در اپ شروع می‌شود، نه از لحظهٔ خرید.',
  ].join('\n');
}

const HELP_TEXT = [
  '🧾 <b>راهنمای فعال‌سازی BEE-VPN</b>',
  '',
  '۱) اپ BEE-VPN را باز کن — صفحهٔ «کندو قفله 🐝» می‌آید.',
  '۲) یکی از دو راه:',
  '   🔑 <b>کد فعال‌سازی:</b> کدی که از ربات/پشتیبانی گرفتی را وارد کن.',
  '   👤 <b>حساب کاربری:</b> نام کاربری و رمزی که ادمین داده را بزن.',
  '۳) روی «فعال‌سازی/ورود» بزن — تمام! کندو باز می‌شود 🍯',
  '',
  '⚠️ هر اشتراک فقط روی <b>یک دستگاه</b> فعال می‌شود.',
  '🔁 اگر گوشی را عوض کردی، از پشتیبانی بخواه دستگاهت را ریست کند.',
].join('\n');

const WELCOME = [
  '🐝 <b>به کندوی BEE-VPN خوش آمدی!</b>',
  '',
  'اینجا می‌توانی اشتراک بفروشی یا فعال‌سازی کنی:',
  '🛍 خرید اشتراک — پلن‌ها و روش پرداخت',
  '🎁 کد تست ۱ روزه — یک‌بار برای هر حساب تلگرام',
  '👤 اشتراک من — وضعیت و تاریخ انقضا',
].join('\n');

function nowSec() {
  return Math.floor(Date.now() / 1000);
}

function isAdmin(env, from) {
  const ids = String(env.ADMIN_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  return from && ids.includes(String(from.id));
}

function randomPassword() {
  const alphabet = '23456789abcdefghjkmnpqrstvwxyz';
  const buf = new Uint8Array(10);
  crypto.getRandomValues(buf);
  let s = '';
  for (const b of buf) s += alphabet[b % alphabet.length];
  return s;
}

// ---------------------------------------------------------------------------
// Bot command implementations
// ---------------------------------------------------------------------------

async function cmdGen(env, chatId, args) {
  const plan = (args[0] || '').toLowerCase();
  if (!planDays(plan)) {
    return send(env, chatId, 'فرمت: <code>/gen m1|m3|m6|m12|trial [تعداد] [یادداشت]</code>');
  }
  const count = Math.min(Math.max(parseInt(args[1] || '1', 10) || 1, 1), 50);
  const note = args.slice(2).join(' ') || null;
  const rows = [];
  for (let i = 0; i < count; i++) {
    const code = randomCode();
    await env.DB.prepare(
      'INSERT INTO licenses (code, plan, status, duration_days, note, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(code, plan, 'active', planDays(plan), note, nowSec()).run();
    rows.push(`<code>${code}</code>`);
  }
  await send(env, chatId, `🍯 ${count} کد <b>${esc(plan)}</b> ساخته شد:\n\n${rows.join('\n')}\n\n⏱ انقضا از اولین فعال‌سازی شروع می‌شود.`);
}

async function cmdAddUser(env, chatId, args) {
  const username = (args[0] || '').trim().toLowerCase();
  const password = args[1] || '';
  const plan = (args[2] || 'm1').toLowerCase();
  if (!/^[a-z0-9_.-]{3,32}$/.test(username) || password.length < 6 || !planDays(plan)) {
    return send(env, chatId, 'فرمت: <code>/adduser username password [m1|m3|m6|m12]</code>\n(نام کاربری ۳–۳۲ کاراکتر، رمز حداقل ۶ کاراکتر)');
  }
  const saltBytes = new Uint8Array(16);
  crypto.getRandomValues(saltBytes);
  const salt = b64urlEncode(saltBytes);
  const hash = await pbkdf2Hash(password, salt);
  try {
    await env.DB.prepare(
      'INSERT INTO accounts (username, pass_hash, pass_salt, plan, status, duration_days, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).bind(username, hash, salt, plan, 'active', planDays(plan), nowSec()).run();
  } catch (e) {
    return send(env, chatId, `⚠️ ساخت حساب ناموفق (احتمالاً تکراری است): ${esc(String(e.message || e))}`);
  }
  return send(env, chatId, `👤 حساب <b>${esc(username)}</b> با پلن <b>${esc(plan)}</b> ساخته شد.\nرمز را در امن نگه دار؛ فقط hash ذخیره می‌شود.`);
}

async function lookup(env, key) {
  const k = key.trim();
  if (/^BEE-/i.test(k)) {
    const row = await env.DB.prepare('SELECT * FROM licenses WHERE code = ?').bind(k.toUpperCase()).first();
    if (row) return { kind: 'license', row };
  }
  const row = await env.DB.prepare('SELECT * FROM accounts WHERE username = ?').bind(k.toLowerCase()).first();
  if (row) return { kind: 'account', row };
  return null;
}

async function cmdWho(env, chatId, args) {
  if (!args[0]) return send(env, chatId, 'فرمت: <code>/who BEE-XXXX-XXXX-XXXX|username|تلگرام-id</code>');
  // A bare number is a Telegram user id: show that buyer's whole purchase
  // history instead of a single row.
  if (/^\d{3,}$/.test(args[0].trim())) return whoBuyer(env, chatId, parseInt(args[0].trim(), 10));
  const found = await lookup(env, args[0]);
  if (!found) return send(env, chatId, '❌ پیدا نشد.');
  const r = found.row;
  const subject = found.kind === 'license' ? r.code : r.username;
  await send(env, chatId, [
    `🔎 <b>${esc(subject)}</b> (${found.kind === 'license' ? 'کد' : 'حساب'})`,
    `پلن: <b>${esc(r.plan)}</b> — وضعیت: <b>${r.status === 'active' ? '✅ فعال' : '⛔ ' + esc(r.status)}</b>`,
    fmtExpiry(r, nowSec()),
    `دستگاه: ${r.bound_install_id ? `<code>${esc(r.bound_install_id.slice(0, 12))}…</code>` : '—'}`,
    r.telegram_user_id ? `تلگرام: <a href="tg://user?id=${r.telegram_user_id}">${r.telegram_user_id}</a>` : '',
  ].filter(Boolean).join('\n'));
}

/** /who <tg-id>: every code and account one Telegram buyer owns. */
async function whoBuyer(env, chatId, uid) {
  const lic = await env.DB.prepare(
    'SELECT code, plan, status, activated_at, expires_at, created_at FROM licenses WHERE telegram_user_id = ? ORDER BY created_at DESC',
  ).bind(uid).all();
  const acc = await env.DB.prepare(
    'SELECT username, plan, status, activated_at, expires_at FROM accounts WHERE telegram_user_id = ? ORDER BY created_at DESC',
  ).bind(uid).all();
  const now = nowSec();
  const lines = [`👤 <b>خریدار</b> <a href="tg://user?id=${uid}">${uid}</a>`];
  const rows = lic.results || [];
  if (!rows.length && !(acc.results || []).length) {
    return send(env, chatId, '❌ برای این شناسهٔ تلگرام چیزی ثبت نشده.');
  }
  for (const r of rows) {
    const when = new Date(r.created_at * 1000).toISOString().slice(0, 10);
    const live = r.expires_at ? (r.expires_at > now ? '🟢' : '🔴') : '⏳';
    lines.push(`${live} <code>${esc(r.code)}</code> ${esc(r.plan)} ${r.status === 'active' ? '' : '⛔'} — ${when}`);
  }
  for (const r of acc.results || []) {
    const live = r.expires_at ? (r.expires_at > now ? '🟢' : '🔴') : '⏳';
    lines.push(`${live} 👤 <code>${esc(r.username)}</code> ${esc(r.plan)} ${r.status === 'active' ? '' : '⛔'}`);
  }
  lines.push('', `مجموع: ${rows.length} کد — ${liveCount(rows, now)} در جریان`);
  return send(env, chatId, lines.join('\n'));
}

function liveCount(rows, now) {
  return rows.filter((r) => r.expires_at && r.expires_at > now).length;
}

async function setStatus(env, chatId, args, status) {
  if (!args[0]) return send(env, chatId, `فرمت: <code>/${status === 'active' ? 'unrevoke' : 'revoke'} BEE-…|username</code>`);
  const found = await lookup(env, args[0]);
  if (!found) return send(env, chatId, '❌ پیدا نشد.');
  const table = found.kind === 'license' ? 'licenses' : 'accounts';
  const column = found.kind === 'license' ? 'code' : 'username';
  await env.DB.prepare(`UPDATE ${table} SET status = ? WHERE ${column} = ?`).bind(status, found.row[column]).run();
  return send(env, chatId, status === 'active' ? '✅ فعال شد.' : '⛔ باطل شد.');
}

async function cmdExtend(env, chatId, args) {
  const days = parseInt(args[1] || '0', 10);
  if (!args[0] || !days) return send(env, chatId, 'فرمت: <code>/extend BEE-…|username 30</code>');
  const found = await lookup(env, args[0]);
  if (!found) return send(env, chatId, '❌ پیدا نشد.');
  const r = found.row;
  const table = found.kind === 'license' ? 'licenses' : 'accounts';
  const column = found.kind === 'license' ? 'code' : 'username';
  if (!r.expires_at) {
    await env.DB.prepare(`UPDATE ${table} SET duration_days = duration_days + ? WHERE ${column} = ?`).bind(days, r[column]).run();
    return send(env, chatId, `➕ ${days} روز به دورهٔ فعال‌نشده اضافه شد (از اولین فعال‌سازی محاسبه می‌شود).`);
  }
  const base = Math.max(nowSec(), r.expires_at);
  await env.DB.prepare(`UPDATE ${table} SET expires_at = ? WHERE ${column} = ?`)
    .bind(base + days * 86400, r[column]).run();
  return send(env, chatId, `➕ ${days} روز اضافه شد — انقضای جدید: <b>${new Date((base + days * 86400) * 1000).toISOString().slice(0, 10)}</b>`);
}

async function cmdReset(env, chatId, args) {
  if (!args[0]) return send(env, chatId, 'فرمت: <code>/reset BEE-…|username</code>');
  const found = await lookup(env, args[0]);
  if (!found) return send(env, chatId, '❌ پیدا نشد.');
  const table = found.kind === 'license' ? 'licenses' : 'accounts';
  const column = found.kind === 'license' ? 'code' : 'username';
  await env.DB.prepare(`UPDATE ${table} SET bound_install_id = NULL, activated_at = NULL, expires_at = NULL WHERE ${column} = ?`)
    .bind(found.row[column]).run();
  return send(env, chatId, '🔁 بایند دستگاه پاک شد — دوربین بعدی که فعال کند، ساعت از نو شروع می‌شود. (دورهٔ قبلی باطل است)');
}

async function cmdStats(env, chatId) {
  const now = nowSec();
  const lic = await env.DB.prepare(
    "SELECT COUNT(*) AS total, SUM(CASE WHEN status='active' THEN 1 ELSE 0 END) AS active, SUM(CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END) AS unused FROM licenses",
  ).first();
  const acc = await env.DB.prepare(
    "SELECT COUNT(*) AS total, SUM(CASE WHEN status='active' THEN 1 ELSE 0 END) AS active FROM accounts",
  ).first();
  const liveLic = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE expires_at > ?').bind(now).first();
  const liveAcc = await env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE expires_at > ?').bind(now).first();
  const liveN = (liveLic ? liveLic.n : 0) + (liveAcc ? liveAcc.n : 0);
  // The «who is buying» picture: distinct buyers, recent sales, plan mix.
  const buyers = await env.DB.prepare(
    'SELECT COUNT(DISTINCT telegram_user_id) AS n FROM licenses WHERE telegram_user_id IS NOT NULL',
  ).first();
  const d7 = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE created_at > ?').bind(now - 7 * 86400).first();
  const d30 = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE created_at > ?').bind(now - 30 * 86400).first();
  const mix = await env.DB.prepare('SELECT plan, COUNT(*) AS n FROM licenses GROUP BY plan ORDER BY n DESC').all();
  const mixLine = (mix.results || []).map((r) => `${esc(r.plan)}×${r.n}`).join(' — ');
  await send(env, chatId, [
    '📊 <b>آمار کندو</b>',
    `کدها: ${lic.total || 0} کل — ${lic.active || 0} فعال — ${lic.unused || 0} بلااستفاده`,
    `حساب‌ها: ${acc.total || 0} کل — ${acc.active || 0} فعال`,
    `اشتراک‌های در جریان: <b>${liveN}</b>`,
    '',
    `🧑‍🤝‍🧑 خریداران: <b>${buyers ? buyers.n : 0}</b> نفر`,
    `🛒 فروش ۷ روز: <b>${d7 ? d7.n : 0}</b> — ۳۰ روز: <b>${d30 ? d30.n : 0}</b>`,
    `🏷 پلن‌ها: ${mixLine || '—'}`,
    '',
    '👥 لیست خریداران: <code>/buyers</code>',
  ].join('\n'));
}

/** /buyers — per-buyer rollup: who bought, how many codes, what is still live. */
async function cmdBuyers(env, chatId, args) {
  const limit = Math.min(Math.max(parseInt(args[0] || '15', 10) || 15, 1), 30);
  const rows = await env.DB.prepare(
    `SELECT telegram_user_id AS uid, COUNT(*) AS n,
            SUM(CASE WHEN expires_at > ? THEN 1 ELSE 0 END) AS live,
            SUM(CASE WHEN status = 'revoked' THEN 1 ELSE 0 END) AS banned,
            MAX(created_at) AS last
     FROM licenses WHERE telegram_user_id IS NOT NULL
     GROUP BY telegram_user_id ORDER BY n DESC, last DESC LIMIT ?`,
  ).bind(nowSec(), limit).all();
  const total = await env.DB.prepare(
    'SELECT COUNT(DISTINCT telegram_user_id) AS n FROM licenses WHERE telegram_user_id IS NOT NULL',
  ).first();
  const list = rows.results || [];
  if (!list.length) {
    return send(env, chatId, 'هنوز خریدی از طریق ربات ثبت نشده — بعد از اولین تأییدِ رسید، خریداران این‌جا لیست می‌شوند.');
  }
  const now = nowSec();
  const lines = [`👥 <b>خریداران — ${total ? total.n : 0} نفر</b> (${list.length} نفرِ برتر):`];
  for (const r of list) {
    const when = new Date(r.last * 1000).toISOString().slice(0, 10);
    lines.push(
      `<a href="tg://user?id=${r.uid}">${r.uid}</a> — ${r.n} خرید • ${r.live} در جریان` +
      (r.banned ? ` • ⛔ ${r.banned} لغو` : '') + ` • آخرین: ${when}`,
    );
  }
  lines.push('', '🔎 تاریخچهٔ یک نفر: <code>/who 123456789</code>');
  return send(env, chatId, lines.join('\n'));
}

async function cmdList(env, chatId, args) {
  const n = Math.min(Math.max(parseInt(args[0] || '10', 10) || 10, 1), 30);
  const lic = await env.DB.prepare('SELECT * FROM licenses ORDER BY created_at DESC LIMIT ?').bind(n).all();
  const acc = await env.DB.prepare('SELECT * FROM accounts ORDER BY created_at DESC LIMIT ?').bind(n).all();
  const lines = [];
  for (const r of lic.results || []) {
    lines.push(`<code>${esc(r.code)}</code> ${esc(r.plan)} ${r.status === 'active' ? '✅' : '⛔'}${r.expires_at ? ' 🕒' : ' ⏳'}`);
  }
  for (const r of acc.results || []) {
    lines.push(`👤 <code>${esc(r.username)}</code> ${esc(r.plan)} ${r.status === 'active' ? '✅' : '⛔'}${r.expires_at ? ' 🕒' : ' ⏳'}`);
  }
  return send(env, chatId, lines.length ? ['🗒 آخرین‌ها:', '', ...lines].join('\n') : 'خالی است.');
}

async function giveTrial(env, chatId, tgId) {
  const prior = await env.DB.prepare(
    "SELECT code FROM licenses WHERE telegram_user_id = ? AND plan = 'trial' LIMIT 1",
  ).bind(tgId).first();
  if (prior) {
    return send(env, chatId, `🎁 قبلاً کد تست گرفتی: <code>${esc(prior.code)}</code>\nاگر منقضی شده، برای ادامه اشتراک برو سراغ 🛍 خرید.`);
  }
  const code = randomCode();
  await env.DB.prepare(
    'INSERT INTO licenses (code, plan, status, duration_days, note, telegram_user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(code, 'trial', 'active', 1, 'trial', tgId, nowSec()).run();
  return send(env, chatId, [
    '🎁 <b>کد تست ۱ روزهٔ تو:</b>',
    `<code>${esc(code)}</code>`,
    '',
    'اپ BEE-VPN را باز کن → «کد فعال‌سازی» → این کد را وارد کن.',
    '⏱ ۲۴ ساعت از لحظهٔ فعال‌سازی در اپ شروع می‌شود.',
  ].join('\n'));
}

async function mySub(env, chatId, tgId) {
  const lic = await env.DB.prepare('SELECT * FROM licenses WHERE telegram_user_id = ? ORDER BY created_at DESC').bind(tgId).all();
  const acc = await env.DB.prepare('SELECT * FROM accounts WHERE telegram_user_id = ? ORDER BY created_at DESC').bind(tgId).all();
  const lines = [];
  for (const r of lic.results || []) lines.push(`🔑 <code>${esc(r.code)}</code> ${esc(r.plan)} — ${fmtExpiry(r, nowSec())}`);
  for (const r of acc.results || []) lines.push(`👤 <code>${esc(r.username)}</code> ${esc(r.plan)} — ${fmtExpiry(r, nowSec())}`);
  return send(env, chatId, lines.length ? ['👤 <b>اشتراک‌های تو:</b>', '', ...lines].join('\n') : 'هنوز اشتراکی نداری — با 🛍 خرید شروع کن.');
}

async function notifyAdmins(env, text) {
  const ids = String(env.ADMIN_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  for (const id of ids) {
    try { await send(env, id, text); } catch (_) { /* best-effort */ }
  }
}

// ---------------------------------------------------------------------------
// Purchase-by-receipt flow — no commands for the buyer.
//
// plan tap -> a pending_receipts row remembers (buyer -> plan) -> the buyer's
// next photo becomes the receipt for that plan: it rides to every admin
// untouched (copyMessage) carrying approve/reject buttons; ✅ issues the code
// and hands it to the buyer directly. The admin never types anything.
//
// Idempotency: every receipt carries a timestamp; the issued code is stored
// with note = "receipt:<ts>:<buyerId>". A second admin tapping ✅ on the same
// receipt finds that note already in licenses and does nothing — one receipt,
// exactly one code, no matter how many admins race on the buttons.
// ---------------------------------------------------------------------------

const PENDING_TTL = 24 * 3600;  // a plan choice expires after a day

async function setPending(env, tgId, plan) {
  await env.DB.prepare(
    'INSERT INTO pending_receipts (tg_user_id, plan, ts) VALUES (?, ?, ?) '
    + 'ON CONFLICT(tg_user_id) DO UPDATE SET plan = excluded.plan, ts = excluded.ts',
  ).bind(tgId, plan, nowSec()).run();
}

async function getPending(env, tgId) {
  const row = await env.DB.prepare(
    'SELECT plan, ts FROM pending_receipts WHERE tg_user_id = ?',
  ).bind(tgId).first();
  if (!row || nowSec() - (row.ts || 0) > PENDING_TTL) return null;
  return row;
}

async function clearPending(env, tgId) {
  await env.DB.prepare('DELETE FROM pending_receipts WHERE tg_user_id = ?').bind(tgId).run();
}

function buyerLine(from) {
  const name = esc(from.first_name || '')
    + (from.last_name ? ' ' + esc(from.last_name) : '');
  return `<a href="tg://user?id=${from.id}">${name || 'خریدار'}</a> (id <code>${from.id}</code>)`;
}

/** A buyer photo/document: deliver it untouched to every admin + buttons. */
async function handleReceipt(env, msg) {
  const from = msg.from;
  const pending = await getPending(env, from.id);
  if (!pending || !planDays(pending.plan)) {
    return send(env, msg.chat.id,
      '🧾 برای ارسال رسید، اول پلن موردنظرت را انتخاب کن 👇',
      planKeyboard(env));
  }
  const ts = nowSec();
  const info = prices(env)[pending.plan] || { title: pending.plan, price: '?' };
  const ids = String(env.ADMIN_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  for (const id of ids) {
    const copied = await tg(env, 'copyMessage', {
      chat_id: id, from_chat_id: msg.chat.id, message_id: msg.message_id,
    });
    if (copied && copied.ok) {
      await send(env, id, [
        `🧾 <b>رسید پرداخت</b> — ${esc(info.title)} (${esc(info.price)})`,
        `از: ${buyerLine(from)}`,
        `زمان رسید: <code>${new Date(ts * 1000).toISOString().slice(0, 16).replace('T', ' ')} UTC</code>`,
      ].join('\n'), {
        inline_keyboard: [[
          { text: '✅ تأیید و ارسال کد', callback_data: `ok:${from.id}:${pending.plan}:${ts}` },
          { text: '❌ رد', callback_data: `no:${from.id}:${pending.plan}:${ts}` },
        ]],
      });
    }
  }
  await send(env, msg.chat.id,
    '✅ رسید دریافت شد — بعد از تأیید ادمین، کد فعال‌سازی خودکار برایت ارسال می‌شود. 🍯');
}

/** Admin tapped ✅: issue the code, hand it to the buyer, close the ticket. */
async function approveReceipt(env, cb, buyerId, plan, ts) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  const note = `receipt:${ts}:${buyerId}`;
  const existing = await env.DB.prepare('SELECT code FROM licenses WHERE note = ?')
    .bind(note).first();
  if (existing) {
    // Another admin already approved this exact receipt.
    await tg(env, 'editMessageText', {
      chat_id: chatId, message_id: messageId,
      text: `✅ این رسید قبلاً تأیید شده — کد <code>${esc(existing.code)}</code> برای خریدار ارسال شد.`,
    }).catch(() => {});
    return answerCb(env, cb.id, 'این رسید قبلاً تأیید شده است');
  }
  const code = randomCode();
  await env.DB.prepare(
    'INSERT INTO licenses (code, plan, status, duration_days, note, telegram_user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(code, plan, 'active', planDays(plan), note, buyerId, nowSec()).run();
  await clearPending(env, buyerId);
  await send(env, buyerId, [
    '🍯 <b>پرداخت شما تأیید شد!</b>',
    `پلن: <b>${esc((prices(env)[plan] || {}).title || plan)}</b>`,
    '',
    '🔑 <b>کد فعال‌سازی تو:</b>',
    `<code>${esc(code)}</code>`,
    '',
    'اپ BEE-VPN را باز کن → «کد فعال‌سازی» → کد را وارد کن.',
    '⏱ اشتراک از لحظهٔ اولین فعال‌سازی در اپ شروع می‌شود.',
  ].join('\n'));
  await tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId,
    text: `✅ تأیید شد — کد <code>${esc(code)}</code> برای خریدار (id <code>${buyerId}</code>) ارسال شد.`,
  }).catch(() => {});
  return answerCb(env, cb.id, 'کد صادر و ارسال شد');
}

/** Admin tapped ❌: tell the buyer kindly; the plan choice stays active so a
 *  corrected receipt can be sent right away. */
async function rejectReceipt(env, cb, buyerId, plan, ts) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  const support = String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN');
  await tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId,
    text: '❌ این رسید رد شد.',
  }).catch(() => {});
  await send(env, buyerId, [
    '❌ <b>رسید شما تأیید نشد.</b>',
    'ممکن است عکس ناخوانا باشد یا پرداخت کامل نشده باشد.',
    `برای بررسی با پشتیبانی در تماس باش: ${esc(support)}`,
  ].join('\n'));
  return answerCb(env, cb.id, 'رسید رد شد');
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    const secret = String(env.WEBHOOK_SECRET || '');
    if (!secret || request.headers.get('x-telegram-bot-api-secret-token') !== secret) {
      return new Response('forbidden', { status: 403 });
    }
    if (!env.DB || !env.BOT_TOKEN) return new Response('not configured', { status: 500 });

    let update;
    try { update = await request.json(); } catch (_) { return new Response('ok'); }

    try {
      if (update.callback_query) {
        const cb = update.callback_query;
        const chatId = cb.message.chat.id;
        const tgId = cb.from.id;
        const data = cb.data || '';
        if (data === 'menu' || data === 'start') {
          await tg(env, 'editMessageText', {
            chat_id: chatId, message_id: cb.message.message_id, text: WELCOME,
            parse_mode: 'HTML', reply_markup: mainMenu(env), disable_web_page_preview: true,
          }).catch(() => send(env, chatId, WELCOME, mainMenu(env)));
        } else if (data === 'buy') {
          await tg(env, 'editMessageText', {
            chat_id: chatId, message_id: cb.message.message_id,
            text: '🛍 <b>پلن موردنظر را انتخاب کن:</b>',
            parse_mode: 'HTML', reply_markup: planKeyboard(env),
          }).catch(() => send(env, chatId, '🛍 پلن‌ها:', planKeyboard(env)));
        } else if (data.startsWith('plan:')) {
          const plan = data.slice(5);
          if (planDays(plan)) await setPending(env, tgId, plan);
          await send(env, chatId, paymentText(env, plan), {
            inline_keyboard: [[{ text: '🔙 بازگشت', callback_data: 'buy' }]],
          });
          await notifyAdmins(env, `🛒 خریدار ${buyerLine(cb.from)} پلن <b>${esc(plan)}</b> را انتخاب کرد.\nاسکرین‌شاتش که رسید را با دکمهٔ ✅ تأیید کن — کد خودکار برایش ارسال می‌شود.`);
        } else if (data.startsWith('ok:') || data.startsWith('no:')) {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            const parts = data.split(':');
            const buyerId = parseInt(parts[1], 10);
            const plan = parts[2] || '';
            const ts = parseInt(parts[3], 10) || 0;
            if (!buyerId || !planDays(plan)) {
              await answerCb(env, cb.id, 'دادهٔ نامعتبر');
            } else if (parts[0] === 'ok') {
              await approveReceipt(env, cb, buyerId, plan, ts);
            } else {
              await rejectReceipt(env, cb, buyerId, plan, ts);
            }
          }
        } else if (data === 'trial') {
          await giveTrial(env, chatId, tgId);
        } else if (data === 'mysub') {
          await mySub(env, chatId, tgId);
        } else if (data === 'help') {
          await send(env, chatId, HELP_TEXT);
        }
        await answerCb(env, cb.id);
        return new Response('ok');
      }

      const msg = update.message;
      if (!msg || !msg.from) return new Response('ok');
      const chatId = msg.chat.id;
      const tgId = msg.from.id;
      const text = (msg.text || '').trim();

      if (text.startsWith('/start')) {
        await send(env, chatId, WELCOME, mainMenu(env));
        // A buyer landing from the app's «خرید اشتراک» button: open the shop
        // for them instead of making them tap again.
        if (text.includes('buy')) {
          await send(env, chatId, '🛍 <b>پلن موردنظر را انتخاب کن:</b>', planKeyboard(env));
        }
        return new Response('ok');
      }

      if (text.startsWith('/help')) {
        await send(env, chatId, HELP_TEXT);
        return new Response('ok');
      }

      if (isAdmin(env, msg.from)) {
        const parts = text.split(/\s+/);
        const cmd = (parts[0] || '').toLowerCase();
        const args = parts.slice(1);
        if (cmd === '/gen') await cmdGen(env, chatId, args);
        else if (cmd === '/adduser') await cmdAddUser(env, chatId, args);
        else if (cmd === '/list') await cmdList(env, chatId, args);
        else if (cmd === '/who') await cmdWho(env, chatId, args);
        else if (cmd === '/buyers') await cmdBuyers(env, chatId, args);
        else if (cmd === '/revoke') await setStatus(env, chatId, args, 'revoked');
        else if (cmd === '/unrevoke') await setStatus(env, chatId, args, 'active');
        else if (cmd === '/extend') await cmdExtend(env, chatId, args);
        else if (cmd === '/reset') await cmdReset(env, chatId, args);
        else if (cmd === '/stats') await cmdStats(env, chatId);
        else if (cmd === '/helpadmin') {
          await send(env, chatId, [
            '🛠 <b>فرمان‌های ادمین:</b>',
            '🧾 رسید خرید: عکسِ مشتری خودکار برای تو می‌آید — فقط دکمهٔ <b>✅ تأیید و ارسال کد</b> را بزن؛ کد صادر و خودکار برای خریدار ارسال می‌شود (❌ = رد).',
            '<code>/gen m3 5 تخفیف دوست</code> — ساخت کد دستی (تا ۵۰)',
            '<code>/adduser user pass m1</code> — ساخت حساب',
            '<code>/list 10</code> • <code>/who BEE-…</code>',
            '<code>/revoke …</code> • <code>/unrevoke …</code>',
            '<code>/extend … 30</code> • <code>/reset …</code>',
            '<code>/stats</code> — آمار کل + فروش ۷/۳۰ روز + سهم پلن‌ها',
            '<code>/buyers</code> — لیست خریداران با تعداد خرید',
            '<code>/who 123456789</code> — تاریخچهٔ کامل یک خریدار',
          ].join('\n'));
        }
        return new Response('ok');
      }

      // A photo (or an image sent as a file) from a regular user IS their
      // payment receipt — no command needed. It rides to the admins untouched,
      // tagged with the plan they picked, carrying approve/reject buttons.
      if ((msg.photo && msg.photo.length)
          || (msg.document && String(msg.document.mime_type || '').startsWith('image/'))) {
        await handleReceipt(env, msg);
        return new Response('ok');
      }

      // Any other text from a regular user: nudge them to the menu.
      await send(env, chatId, 'برای شروع از منوی زیر استفاده کن 👇', mainMenu(env));
      return new Response('ok');
    } catch (e) {
      try { await send(env, update.message?.chat?.id || update.callback_query?.message?.chat?.id, `⚠️ خطا: ${esc(String(e.message || e))}`); } catch (_) {}
      return new Response('ok');
    }
  },
};
