/**
 * BEE-VPN sales bot — Cloudflare Worker + Telegram webhook, same D1 database
 * as license-worker.js (D1 bindings may be attached to several Workers).
 *
 * What it does for the operator:
 *   - sells: plan catalogue -> payment info (card-to-card + USDT) -> the
 *     buyer sends the receipt, the admin issues a code with /gen and pastes
 *     it into the chat. No payment gateway needed on day one.
 *   - trial: hands out exactly one 1-day code per Telegram account.
 *   - profile: /profile (rich buyer profile: plan, Jalali activation/expiry
 *              dates, days remaining, device binding) + /link <code> to claim
 *              a code issued by support + the «پروفایل من» button.
 *   - admin: button panel (/panel or 🛠 پنل مدیریت) — dashboard, license
 *            management (gen/adduser/search/list/revoke/extend/reset),
 *            buyers, pending receipts, prices — plus every command:
 *            /gen /adduser /list /who /revoke /unrevoke /extend /reset
 *            /stats /buyers /price /diag
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
  const when = faDate(row.expires_at);
  if (row.expires_at <= now) return `❌ منقضی شده (${when})`;
  return `✅ ${left + 1} روز باقی‌مانده — تا ${when}`;
}

// Iran has been UTC+3:30 year-round since 2022 (no DST) — all buyer-facing
// dates are shown in Tehran time, not the Worker's UTC.
const TEHRAN_OFFSET = 12600;

function faDate(epochSec) {
  return new Date((epochSec + TEHRAN_OFFSET) * 1000).toISOString().slice(0, 10);
}

function faDateTime(epochSec) {
  return new Date((epochSec + TEHRAN_OFFSET) * 1000).toISOString().slice(0, 16).replace('T', ' ');
}

// Persian (Jalali) calendar rendering for the profile and admin panel. The
// Workers runtime ships a full ICU, so Intl does the conversion; any failure
// falls back to the Gregorian faDate rather than breaking a reply.
const FA_JALALI_DATE = 'fa-IR-u-ca-persian-nu-arabext';

function faJalali(epochSec) {
  try {
    const parts = new Intl.DateTimeFormat(FA_JALALI_DATE, {
      timeZone: 'Asia/Tehran', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date(epochSec * 1000));
    const pick = (t) => (parts.find((p) => p.type === t) || {}).value || '';
    return `${pick('year')}/${pick('month')}/${pick('day')}`;
  } catch (_) {
    return faDate(epochSec);
  }
}

function faJalaliTime(epochSec) {
  try {
    const parts = new Intl.DateTimeFormat(FA_JALALI_DATE, {
      timeZone: 'Asia/Tehran', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hour12: false,
    }).formatToParts(new Date(epochSec * 1000));
    const pick = (t) => (parts.find((p) => p.type === t) || {}).value || '';
    return `${pick('year')}/${pick('month')}/${pick('day')} — ${pick('hour')}:${pick('minute')}`;
  } catch (_) {
    return faDateTime(epochSec);
  }
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

function mainMenu(env, from) {
  const support = String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN');
  const kb = {
    inline_keyboard: [
      [{ text: '🛍 خرید اشتراک', callback_data: 'buy' }, { text: '🎁 کد تست ۱ روزه', callback_data: 'trial' }],
      [{ text: '👤 پروفایل من', callback_data: 'mysub' }, { text: '🧾 راهنمای فعال‌سازی', callback_data: 'help' }],
      [{ text: '🆘 پشتیبانی', url: support }],
    ],
  };
  if (from && isAdmin(env, from)) {
    kb.inline_keyboard.push([{ text: '🛠 پنل مدیریت', callback_data: 'adm' }]);
  }
  return kb;
}

function prices(env) {
  try {
    const parsed = JSON.parse(String(env.PRICES_JSON || ''));
    if (parsed && typeof parsed === 'object' && Object.keys(parsed).length) return parsed;
  } catch (_) { /* fall through */ }
  return DEFAULT_PRICES;
}

// Runtime-editable prices: /price writes the whole catalogue to the D1
// settings table; getPrices() prefers it over PRICES_JSON / DEFAULT_PRICES.
// Falls back silently when the table is missing (pre-migration databases).
const PRICES_KEY = 'prices';

async function getPrices(env) {
  try {
    const row = await env.DB.prepare('SELECT value FROM settings WHERE key = ?').bind(PRICES_KEY).first();
    if (row) {
      const parsed = JSON.parse(row.value);
      if (parsed && typeof parsed === 'object' && Object.keys(parsed).length) return parsed;
    }
  } catch (_) { /* no settings table yet — fall through */ }
  return prices(env);
}

async function savePrices(env, obj) {
  await env.DB.prepare(
    'INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) ' +
    'ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
  ).bind(PRICES_KEY, JSON.stringify(obj), nowSec()).run();
}

// ---------------------------------------------------------------------------
// Button-driven /price panel (admin only)
// ---------------------------------------------------------------------------

const FA_DIGITS = '۰۱۲۳۴۵۶۷۸۹';

/** 250000 -> «۲۵۰٬۰۰۰» (thousands separator first, then Persian digits). */
function faNum(n) {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, '٬').replace(/\d/g, (d) => FA_DIGITS[+d]);
}

const PRICE_PRESETS = [
  ['۵۰ هزار', 50000], ['۱۰۰ هزار', 100000], ['۱۵۰ هزار', 150000], ['۲۰۰ هزار', 200000],
  ['۲۵۰ هزار', 250000], ['۳۰۰ هزار', 300000], ['۴۰۰ هزار', 400000], ['۵۰۰ هزار', 500000],
  ['۷۵۰ هزار', 750000], ['۱ میلیون', 1000000],
];

// One pending admin interaction at a time (currently: typing a new price).
// Lives in the settings table (key = edit:<tg-id>) — no extra migration.
async function getAdminState(env, uid) {
  try {
    const row = await env.DB.prepare('SELECT value, updated_at FROM settings WHERE key = ?')
      .bind(`edit:${uid}`).first();
    if (!row || nowSec() - row.updated_at > 7200) return null; // stale after 2h
    return JSON.parse(row.value);
  } catch (_) { return null; }
}

async function setAdminState(env, uid, obj) {
  await env.DB.prepare(
    'INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) ' +
    'ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
  ).bind(`edit:${uid}`, JSON.stringify(obj), nowSec()).run();
}

async function clearAdminState(env, uid) {
  try {
    await env.DB.prepare('DELETE FROM settings WHERE key = ?').bind(`edit:${uid}`).run();
  } catch (_) { /* fine */ }
}

async function priceMenuText(env) {
  const cur = await getPrices(env);
  const lines = ['💰 <b>قیمت‌های فعلی فروشگاه</b>', ''];
  for (const [plan, info] of Object.entries(cur)) {
    lines.push(`🔹 <b>${esc(plan)}</b> — ${esc(info.title)}: <b>${esc(info.price)}</b>`);
  }
  lines.push('', 'برای تغییر، دکمهٔ پلن را بزن 👇');
  return lines.join('\n');
}

function priceMenuKb(cur) {
  const rows = Object.entries(cur).map(([plan, info]) => ([
    { text: `✏️ ${plan} — ${info.price}`, callback_data: `price:${plan}` },
  ]));
  rows.push([{ text: '↩️ ریست به پیش‌فرض', callback_data: 'reset_ask' }]);
  return { inline_keyboard: rows };
}

function priceEditKb(plan) {
  const rows = [];
  for (let i = 0; i < PRICE_PRESETS.length; i += 5) {
    rows.push(PRICE_PRESETS.slice(i, i + 5).map(([label, n]) => ({
      text: label, callback_data: `pset:${plan}:${n}`,
    })));
  }
  rows.push([{ text: '🔙 فهرست قیمت‌ها', callback_data: 'price_menu' }]);
  return { inline_keyboard: rows };
}

/** Shared: apply «عنوان | قیمت» or a bare price to one plan. Returns true on success. */
async function applyPriceInput(env, chatId, plan, text) {
  const cur = await getPrices(env);
  const fallback = prices(env);
  let title = (cur[plan] && cur[plan].title) || (fallback[plan] && fallback[plan].title) || plan;
  let priceTxt = (cur[plan] && cur[plan].price) || '?';
  const raw = text.trim().split('|').map((s) => s.trim());
  if (raw.length > 1) {
    if (!raw[1]) {
      await send(env, chatId, 'قیمت بعد از | خالی است — مثال: <code>عنوان | ۲۵۰٬۰۰۰ تومان</code>');
      return false;
    }
    if (raw[0]) title = raw[0].slice(0, 60);
    priceTxt = raw[1].slice(0, 80);
  } else {
    priceTxt = raw[0].slice(0, 80);
  }
  try {
    await savePrices(env, { ...cur, [plan]: { title, price: priceTxt } });
  } catch (e) {
    await send(env, chatId, `⚠️ ذخیرهٔ قیمت ناموفق بود: ${esc(String(e.message || e))}`);
    return false;
  }
  await send(env, chatId, [
    '✅ <b>قیمت ثبت شد</b> — از این لحظه در «🛍 خرید اشتراک» همین شکل دیده می‌شود:',
    `🛍 <b>${esc(title)}</b> — ${esc(priceTxt)}`,
  ].join('\n'), { inline_keyboard: [[{ text: '🔙 فهرست قیمت‌ها', callback_data: 'price_menu' }]] });
  return true;
}

/** Callbacks of the /price panel (admin only). */
async function handlePriceCb(env, cb, data) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  const adminId = cb.from.id;
  const edit = (text, kb) => tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId, text, parse_mode: 'HTML', reply_markup: kb,
  }).catch(() => {});

  if (data === 'price_menu') {
    await clearAdminState(env, adminId);
    await edit(await priceMenuText(env), priceMenuKb(await getPrices(env)));
    return answerCb(env, cb.id);
  }
  if (data === 'reset_ask') {
    await edit('↩️ همهٔ قیمت‌ها به پیش‌فرض برمی‌گردند. مطمئنی؟', {
      inline_keyboard: [[
        { text: '✅ بله، ریست کن', callback_data: 'reset_go' },
        { text: '❌ لغو', callback_data: 'price_menu' },
      ]],
    });
    return answerCb(env, cb.id);
  }
  if (data === 'reset_go') {
    try {
      await env.DB.prepare('DELETE FROM settings WHERE key = ?').bind(PRICES_KEY).run();
    } catch (_) { /* already default */ }
    await clearAdminState(env, adminId);
    await edit('↩️ قیمت‌های پیش‌فرض بازیابی شد.', {
      inline_keyboard: [[{ text: '🔙 فهرست قیمت‌ها', callback_data: 'price_menu' }]],
    });
    return answerCb(env, cb.id, 'ریست شد');
  }
  const parts = data.split(':');
  const plan = parts[1] || '';
  if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
  if (parts[0] === 'price') {
    // Entering edit mode: the admin's next plain message becomes the price.
    await setAdminState(env, adminId, { mode: 'price', plan });
    const cur = await getPrices(env);
    const info = cur[plan] || { title: plan, price: '?' };
    await edit([
      `✏️ <b>ویرایش ${esc(plan)}</b>`,
      `عنوان: ${esc(info.title)}`,
      `قیمت فعلی: <b>${esc(info.price)}</b>`,
      '',
      'یک قیمت آماده انتخاب کن 👇',
      'یا قیمت دلخواه را تایپ کن و بفرست.',
      'با عنوان جدید: <code>عنوان | قیمت</code> — لغو: <code>/cancel</code>',
    ].join('\n'), priceEditKb(plan));
    return answerCb(env, cb.id);
  }
  // pset:<plan>:<amount> — one-tap preset.
  const n = parseInt(parts[2] || '0', 10);
  if (!n) return answerCb(env, cb.id, 'قیمت نامعتبر');
  const cur = await getPrices(env);
  const title = (cur[plan] && cur[plan].title) || plan;
  await savePrices(env, { ...cur, [plan]: { title, price: `${faNum(n)} تومان` } });
  await clearAdminState(env, adminId);
  await edit(`✅ <b>${esc(plan)}</b> → <b>${faNum(n)} تومان</b> ثبت شد.`, {
    inline_keyboard: [[
      { text: '🔙 فهرست قیمت‌ها', callback_data: 'price_menu' },
      { text: '✏️ ویرایش دوباره', callback_data: `price:${plan}` },
    ]],
  });
  return answerCb(env, cb.id, 'ثبت شد');
}

async function planKeyboard(env) {
  const list = await getPrices(env);
  const rows = Object.entries(list).map(([plan, info]) => ([{
    text: `${info.title} — ${info.price}`,
    callback_data: `plan:${plan}`,
  }]));
  rows.push([{ text: '🔙 بازگشت', callback_data: 'menu' }]);
  return { inline_keyboard: rows };
}

async function paymentText(env, plan) {
  const info = (await getPrices(env))[plan] || { title: plan, price: '?' };
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
  '👤 پروفایل من — تاریخ انقضا و روزهای باقی‌مانده',
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

/** Core of /gen — shared by the command and the admin panel's gen flow. */
async function genCore(env, chatId, plan, count, note, kb) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const code = randomCode();
    await env.DB.prepare(
      'INSERT INTO licenses (code, plan, status, duration_days, note, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(code, plan, 'active', planDays(plan), note, nowSec()).run();
    rows.push(`<code>${code}</code>`);
  }
  return send(env, chatId, `🍯 ${faNum(count)} کد <b>${esc(plan)}</b> ساخته شد:\n\n${rows.join('\n')}\n\n⏱ انقضا از اولین فعال‌سازی شروع می‌شود.`, kb);
}

async function cmdGen(env, chatId, args) {
  const plan = (args[0] || '').toLowerCase();
  if (!planDays(plan)) {
    return send(env, chatId, 'فرمت: <code>/gen m1|m3|m6|m12|trial [تعداد] [یادداشت]</code>');
  }
  const count = Math.min(Math.max(parseInt(args[1] || '1', 10) || 1, 1), 50);
  let note = args.slice(2).join(' ') || null;
  // «receipt:…» is reserved for the approval flow's idempotency key.
  if (note && note.startsWith('receipt:')) note = 'manual:' + note;
  return genCore(env, chatId, plan, count, note, null);
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

/** /who for one code/username — same text as before, now with an action
 *  keyboard so the panel can revoke/extend/reset without typing anything. */
async function whoView(env, key) {
  const found = await lookup(env, key);
  if (!found) return { text: '❌ پیدا نشد.', kb: licMenuKb() };
  const r = found.row;
  const kind = found.kind === 'license' ? 'L' : 'A';
  const subject = found.kind === 'license' ? r.code : r.username;
  const now = nowSec();
  const lines = [
    `🔎 <b>${esc(subject)}</b> (${found.kind === 'license' ? 'کد' : 'حساب'})`,
    `پلن: <b>${esc(r.plan)}</b> — وضعیت: <b>${r.status === 'active' ? '✅ فعال' : '⛔ ' + esc(r.status)}</b>`,
    fmtExpiry(r, now),
    `دستگاه: ${r.bound_install_id ? `<code>${esc(r.bound_install_id.slice(0, 12))}…</code>` : '—'}`,
    r.activated_at ? `فعال‌سازی: ${faJalali(r.activated_at)}` : '',
    r.telegram_user_id ? `تلگرام: <a href="tg://user?id=${r.telegram_user_id}">${r.telegram_user_id}</a>` : '',
  ].filter(Boolean);
  const act = (op) => `act:${kind}:${subject}:${op}`;
  const kb = { inline_keyboard: [
    [
      { text: r.status === 'active' ? '⛔ لغو اشتراک' : '✅ رفع لغو', callback_data: act(r.status === 'active' ? 'rv' : 'un') },
      { text: '🔁 ریست دستگاه', callback_data: act('rst') },
    ],
    [
      { text: '➕ ۳۰ روز', callback_data: act('e30') },
      { text: '➕ ۹۰ روز', callback_data: act('e90') },
      { text: '🔄', callback_data: act('vw') },
    ],
    [{ text: '🔙 مدیریت اشتراک‌ها', callback_data: 'adm:lic' }],
  ] };
  return { text: lines.join('\n'), kb };
}

async function cmdWho(env, chatId, args) {
  if (!args[0]) return send(env, chatId, 'فرمت: <code>/who BEE-XXXX-XXXX-XXXX|username|تلگرام-id</code>');
  // A bare number is a Telegram user id: show that buyer's whole purchase
  // history instead of a single row.
  if (/^\d{3,}$/.test(args[0].trim())) {
    const v = await whoBuyerView(env, parseInt(args[0].trim(), 10));
    return send(env, chatId, v.text, v.kb);
  }
  const v = await whoView(env, args[0]);
  return send(env, chatId, v.text, v.kb);
}

/** /who <tg-id>: every code and account one Telegram buyer owns — text plus
 *  one-tap buttons to open each paid code in the action view. */
async function whoBuyerView(env, uid) {
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
    return { text: '❌ برای این شناسهٔ تلگرام چیزی ثبت نشده.', kb: { inline_keyboard: [[{ text: '🔙 خریداران', callback_data: 'adm:buyers' }]] } };
  }
  for (const r of rows) {
    const when = faDate(r.created_at);
    const live = r.expires_at ? (r.expires_at > now ? '🟢' : '🔴') : '⏳';
    if (r.plan === 'trial') {
      lines.push(`🎁 <code>${esc(r.code)}</code> تست — ${when}`);
    } else {
      lines.push(`${live} <code>${esc(r.code)}</code> ${esc(r.plan)} ${r.status === 'active' ? '' : '⛔'} — ${when}`);
    }
  }
  for (const r of acc.results || []) {
    const live = r.expires_at ? (r.expires_at > now ? '🟢' : '🔴') : '⏳';
    lines.push(`${live} 👤 <code>${esc(r.username)}</code> ${esc(r.plan)} ${r.status === 'active' ? '' : '⛔'}`);
  }
  const paid = rows.filter((r) => r.plan !== 'trial');
  const trialN = rows.length - paid.length;
  lines.push('', `مجموع: ${paid.length} خرید — ${liveCount(paid, now)} در جریان` + (trialN ? ` — ${trialN} تست 🎁` : ''));
  const kbRows = [];
  for (const r of paid.slice(0, 5)) {
    kbRows.push([{ text: `⚙️ ${r.code}`, callback_data: `act:L:${r.code}:vw` }]);
  }
  kbRows.push([{ text: '🔙 خریداران', callback_data: 'adm:buyers' }]);
  return { text: lines.join('\n'), kb: { inline_keyboard: kbRows } };
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
  return send(env, chatId, `➕ ${days} روز اضافه شد — انقضای جدید: <b>${faDate(base + days * 86400)}</b>`);
}

async function cmdReset(env, chatId, args) {
  if (!args[0]) return send(env, chatId, 'فرمت: <code>/reset BEE-…|username</code>');
  const found = await lookup(env, args[0]);
  if (!found) return send(env, chatId, '❌ پیدا نشد.');
  const table = found.kind === 'license' ? 'licenses' : 'accounts';
  const column = found.kind === 'license' ? 'code' : 'username';
  await env.DB.prepare(`UPDATE ${table} SET bound_install_id = NULL, activated_at = NULL, expires_at = NULL WHERE ${column} = ?`)
    .bind(found.row[column]).run();
  return send(env, chatId, '🔁 بایند دستگاه پاک شد — دستگاه بعدی که فعال کند، ساعت از نو شروع می‌شود. (دورهٔ قبلی باطل است)');
}

async function statsText(env) {
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
  // Trial codes are gifts, not sales — they are counted separately so the
  // sales numbers stay honest.
  const buyers = await env.DB.prepare(
    "SELECT COUNT(DISTINCT telegram_user_id) AS n FROM licenses WHERE telegram_user_id IS NOT NULL AND plan != 'trial'",
  ).first();
  const d7 = await env.DB.prepare("SELECT COUNT(*) AS n FROM licenses WHERE created_at > ? AND plan != 'trial'").bind(now - 7 * 86400).first();
  const d30 = await env.DB.prepare("SELECT COUNT(*) AS n FROM licenses WHERE created_at > ? AND plan != 'trial'").bind(now - 30 * 86400).first();
  const trials = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM licenses WHERE plan = 'trial' AND created_at > ?",
  ).bind(now - 7 * 86400).first();
  const mix = await env.DB.prepare("SELECT plan, COUNT(*) AS n FROM licenses WHERE plan != 'trial' GROUP BY plan ORDER BY n DESC").all();
  const mixLine = (mix.results || []).map((r) => `${esc(r.plan)}×${r.n}`).join(' — ');
  return [
    '📊 <b>آمار کندو</b>',
    `کدها: ${lic.total || 0} کل — ${lic.active || 0} فعال — ${lic.unused || 0} بلااستفاده`,
    `حساب‌ها: ${acc.total || 0} کل — ${acc.active || 0} فعال`,
    `اشتراک‌های در جریان: <b>${liveN}</b>`,
    '',
    `🧑‍🤝‍🧑 خریداران: <b>${buyers ? buyers.n : 0}</b> نفر`,
    `🛒 فروش ۷ روز: <b>${d7 ? d7.n : 0}</b> — ۳۰ روز: <b>${d30 ? d30.n : 0}</b>`,
    `🏷 پلن‌ها: ${mixLine || '—'}`,
    `🎁 تست ۱ روزه — ۷ روز اخیر: ${trials ? trials.n : 0}`,
    '',
    '👥 لیست خریداران: <code>/buyers</code>',
  ].join('\n');
}

async function cmdStats(env, chatId) {
  return send(env, chatId, await statsText(env));
}

/** /buyers — per-buyer rollup: who bought, how many codes, what is still live. */
async function buyersView(env, limit) {
  const n = Math.min(Math.max(parseInt(limit || '15', 10) || 15, 1), 30);
  const rows = await env.DB.prepare(
    `SELECT telegram_user_id AS uid, COUNT(*) AS n,
            SUM(CASE WHEN expires_at > ? THEN 1 ELSE 0 END) AS live,
            SUM(CASE WHEN status = 'revoked' THEN 1 ELSE 0 END) AS banned,
            MAX(created_at) AS last
     FROM licenses WHERE telegram_user_id IS NOT NULL AND plan != 'trial'
     GROUP BY telegram_user_id ORDER BY n DESC, last DESC LIMIT ?`,
  ).bind(nowSec(), n).all();
  const total = await env.DB.prepare(
    "SELECT COUNT(DISTINCT telegram_user_id) AS n FROM licenses WHERE telegram_user_id IS NOT NULL AND plan != 'trial'",
  ).first();
  const list = rows.results || [];
  if (!list.length) {
    return {
      text: 'هنوز خریدی از طریق ربات ثبت نشده — بعد از اولین تأییدِ رسید، خریداران این‌جا لیست می‌شوند.',
      kb: { inline_keyboard: [[{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]] },
    };
  }
  const now = nowSec();
  const lines = [`👥 <b>خریداران — ${total ? total.n : 0} نفر</b> (${list.length} نفرِ برتر):`];
  for (const r of list) {
    const when = faDate(r.last);
    lines.push(
      `<a href="tg://user?id=${r.uid}">${r.uid}</a> — ${r.n} خرید • ${r.live} در جریان` +
      (r.banned ? ` • ⛔ ${r.banned} لغو` : '') + ` • آخرین: ${when}`,
    );
  }
  lines.push('', '🔎 تاریخچهٔ یک نفر: <code>/who 123456789</code>');
  const kbRows = [];
  for (const r of list.slice(0, 8)) {
    kbRows.push([{ text: `👤 ${r.uid} — ${r.n} خرید`, callback_data: `buy:${r.uid}` }]);
  }
  kbRows.push([{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]);
  return { text: lines.join('\n'), kb: { inline_keyboard: kbRows } };
}

async function cmdBuyers(env, chatId, args) {
  const v = await buyersView(env, args[0] || '15');
  return send(env, chatId, v.text, v.kb);
}

/** /price — button panel; text form (…/price m1 …) kept for quick use. */
async function cmdPrice(env, chatId, args) {
  if (args.length) {
    if (args[0].toLowerCase() === 'reset') {
      try {
        await env.DB.prepare('DELETE FROM settings WHERE key = ?').bind(PRICES_KEY).run();
      } catch (_) { /* table missing = already default */ }
      return send(env, chatId, '↩️ قیمت‌های پیش‌فرض بازیابی شد.');
    }
    const plan = (args[0] || '').toLowerCase();
    if (!planDays(plan)) {
      return send(env, chatId, 'فرمت: <code>/price m1 عنوان | قیمت</code>\nپلن‌ها: <code>m1 m3 m6 m12</code>');
    }
    const rest = args.slice(1).join(' ').trim();
    if (!rest) return send(env, chatId, 'فرمت: <code>/price m1 عنوان | قیمت</code> یا <code>/price m1 قیمت</code>');
    return applyPriceInput(env, chatId, plan, rest);
  }
  return send(env, chatId, await priceMenuText(env), priceMenuKb(await getPrices(env)));
}

async function listText(env, limitRaw) {
  const n = Math.min(Math.max(parseInt(limitRaw || '10', 10) || 10, 1), 30);
  const lic = await env.DB.prepare('SELECT * FROM licenses ORDER BY created_at DESC LIMIT ?').bind(n).all();
  const acc = await env.DB.prepare('SELECT * FROM accounts ORDER BY created_at DESC LIMIT ?').bind(n).all();
  const lines = [];
  for (const r of lic.results || []) {
    lines.push(`<code>${esc(r.code)}</code> ${esc(r.plan)} ${r.status === 'active' ? '✅' : '⛔'}${r.expires_at ? ' 🕒' : ' ⏳'} — ${faDate(r.created_at)}`);
  }
  for (const r of acc.results || []) {
    lines.push(`👤 <code>${esc(r.username)}</code> ${esc(r.plan)} ${r.status === 'active' ? '✅' : '⛔'}${r.expires_at ? ' 🕒' : ' ⏳'} — ${faDate(r.created_at)}`);
  }
  return lines.length ? ['🗒 آخرین‌ها:', '', ...lines].join('\n') : 'خالی است.';
}

async function cmdList(env, chatId, args) {
  return send(env, chatId, await listText(env, args[0] || '10'));
}

async function giveTrial(env, chatId, tgId) {
  // Atomic one-per-account: the INSERT only lands if no trial row exists yet,
  // so a double-tap on 🎁 can never mint two codes.
  const code = randomCode();
  const ins = await env.DB.prepare(
    'INSERT INTO licenses (code, plan, status, duration_days, note, telegram_user_id, created_at) '
    + 'SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS '
    + "(SELECT 1 FROM licenses WHERE telegram_user_id = ? AND plan = 'trial')",
  ).bind(code, 'trial', 'active', 1, `trial:${tgId}`, tgId, nowSec(), tgId).run();
  if (!ins.meta || !ins.meta.changes) {
    const prior = await env.DB.prepare(
      "SELECT code FROM licenses WHERE telegram_user_id = ? AND plan = 'trial' LIMIT 1",
    ).bind(tgId).first();
    return send(env, chatId, `🎁 قبلاً کد تست گرفتی: <code>${esc(prior ? prior.code : '')}</code>\nاگر منقضی شده، برای ادامه اشتراک برو سراغ 🛍 خرید.`);
  }
  return send(env, chatId, [
    '🎁 <b>کد تست ۱ روزهٔ تو:</b>',
    `<code>${esc(code)}</code>`,
    '',
    'اپ BEE-VPN را باز کن → «کد فعال‌سازی» → این کد را وارد کن.',
    '⏱ ۲۴ ساعت از لحظهٔ فعال‌سازی در اپ شروع می‌شود.',
  ].join('\n'));
}

/** Buyer profile — the «👤 پروفایل من» view: every code/account with plan
 *  title, status, Jalali activation/expiry dates, days remaining and device
 *  binding, plus a summary line and the /link hint for unlinked codes. */
function subStatus(r, now) {
  if (r.status !== 'active') return '⛔ باطل شده';
  if (!r.expires_at) return '⏳ هنوز فعال نشده';
  if (r.expires_at <= now) return '🔴 منقضی شده';
  return '🟢 فعال';
}

function daysLeftOf(r, now) {
  if (!r.expires_at) return r.duration_days || planDays(r.plan) || 0;
  return Math.max(0, Math.floor((r.expires_at - now) / 86400) + 1);
}

async function mySub(env, chatId, tgId, from) {
  const lic = await env.DB.prepare('SELECT * FROM licenses WHERE telegram_user_id = ? ORDER BY created_at DESC').bind(tgId).all();
  const acc = await env.DB.prepare('SELECT * FROM accounts WHERE telegram_user_id = ? ORDER BY created_at DESC').bind(tgId).all();
  const rows = lic.results || [];
  const accRows = acc.results || [];
  const now = nowSec();
  const priceMap = await getPrices(env);
  const planTitle = (p) => esc((priceMap[p] && priceMap[p].title) || p);
  if (!rows.length && !accRows.length) {
    return send(env, chatId, [
      '👤 <b>پروفایل من</b>',
      `🆔 شناسه: <code>${tgId}</code>`,
      '',
      'هنوز اشتراکی روی این حساب ثبت نشده.',
      'با 🛍 خرید شروع کن یا از 🎁 کد تست ۱ روزه استفاده کن.',
      '',
      'اگر پشتیبانی برایت کد ساخته، با این فرمت به پروفایلت وصلش کن:',
      '<code>/link BEE-XXXX-XXXX-XXXX</code>',
    ].join('\n'), { inline_keyboard: [[{ text: '🛍 خرید اشتراک', callback_data: 'buy' }]] });
  }
  const name = esc((from && (from.first_name || '')) || '')
    + (from && from.last_name ? ' ' + esc(from.last_name) : '');
  const lines = [
    `👤 <b>پروفایل ${name || 'من'}</b>`,
    `🆔 شناسه: <code>${tgId}</code>`,
    '',
  ];
  let nearest = 0;
  let liveN = 0;
  const pushEnt = (r, icon) => {
    const st = subStatus(r, now);
    const left = daysLeftOf(r, now);
    lines.push(`${icon} <code>${esc(r.code || r.username)}</code> — ${planTitle(r.plan)}`);
    lines.push(`   وضعیت: ${st}`);
    if (r.expires_at) {
      lines.push(`   ⏱ <b>${faNum(left)} روز</b> باقی‌مانده — انقضا: ${faJalali(r.expires_at)}`);
      if (r.expires_at > now) {
        liveN++;
        if (!nearest || r.expires_at < nearest) nearest = r.expires_at;
      }
    } else {
      lines.push(`   ⏱ ${faNum(left)} روز از لحظهٔ اولین فعال‌سازی در اپ`);
    }
    if (r.activated_at) lines.push(`   📅 فعال‌سازی: ${faJalali(r.activated_at)}`);
    lines.push(`   📱 دستگاه: ${r.bound_install_id ? 'متصل ✅' : 'بدون دستگاه'}`);
    lines.push('');
  };
  for (const r of rows) pushEnt(r, '🔑');
  for (const r of accRows) pushEnt(r, '👤');
  if (nearest) lines.push(`📌 نزدیک‌ترین انقضا: <b>${faNum(daysLeftOf({ expires_at: nearest }, now))} روز</b> دیگر`, '');
  lines.push('🔗 اتصال کد از پشتیبانی: <code>/link BEE-XXXX-XXXX-XXXX</code>');
  return send(env, chatId, lines.join('\n'), {
    inline_keyboard: [
      [{ text: '🛍 خرید / تمدید', callback_data: 'buy' }, { text: '🔄 بروزرسانی', callback_data: 'mysub' }],
      [{ text: '🔙 منوی اصلی', callback_data: 'menu' }],
    ],
  });
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

function receiptButtons(buyerId, plan, ts) {
  return { inline_keyboard: [[
    { text: '✅ تأیید و ارسال کد', callback_data: `ok:${buyerId}:${plan}:${ts}` },
    { text: '❌ رد', callback_data: `no:${buyerId}:${plan}:${ts}` },
  ]] };
}

/** A buyer photo/document: deliver it untouched to every admin + buttons.
 *  Works for admins too (they test the flow / buy for someone). Never claims
 *  success silently: if no admin actually received it, the buyer is told so
 *  and the reason lands in the Worker logs (wrangler tail). */
async function handleReceipt(env, msg) {
  const from = msg.from;
  const pending = await getPending(env, from.id);
  if (!pending || !planDays(pending.plan)) {
    await clearPending(env, from.id);  // sweep missing/expired row (getPending already rejects stale ones)
    return send(env, msg.chat.id,
      '🧾 برای ارسال رسید، اول پلن موردنظرت را انتخاب کن 👇',
      await planKeyboard(env));
  }
  const ts = nowSec();
  const info = (await getPrices(env))[pending.plan] || { title: pending.plan, price: '?' };
  const ids = String(env.ADMIN_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (!ids.length) {
    console.error('receipt: ADMIN_IDS is empty — buyer receipt has nowhere to go');
    return send(env, msg.chat.id, [
      '⚠️ <b>ارسال رسید ناموفق بود.</b>',
      `لطفاً مستقیم به پشتیبانی پیام بده: ${esc(String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN'))}`,
    ].join('\n'));
  }
  let delivered = 0;
  const errors = [];
  for (const id of ids) {
    try {
      const copied = await tg(env, 'copyMessage', {
        chat_id: id, from_chat_id: msg.chat.id, message_id: msg.message_id,
      });
      if (copied && copied.ok) {
        delivered++;
        const cap = await send(env, id, [
          `🧾 <b>رسید پرداخت</b> — ${esc(info.title)} (${esc(info.price)})`,
          `از: ${buyerLine(from)}`,
          `زمان رسید: <code>${faDateTime(ts)}</code> (تهران)`,
        ].join('\n'), receiptButtons(from.id, pending.plan, ts));
        if (!(cap && cap.ok)) {
          // Photo arrived but the approve buttons did not — bolt them onto
          // the copied photo itself, otherwise the receipt is un-approvable.
          const fixed = await tg(env, 'editMessageCaption', {
            chat_id: id, message_id: copied.result.message_id,
            caption: `🧾 رسید ${esc(info.title)} از ${esc(from.first_name || 'خریدار')} (id ${from.id})`,
            parse_mode: 'HTML',
            reply_markup: receiptButtons(from.id, pending.plan, ts),
          });
          if (!(fixed && fixed.ok)) {
            errors.push(`buttons:${id}:${(fixed && fixed.description) || '؟'}`);
            console.error(`receipt: caption fallback for ${id} failed:`, fixed);
          }
        }
      } else {
        const why = (copied && copied.description) || 'نامشخص';
        errors.push(`copy:${id}:${why}`);
        console.error(`receipt: copyMessage to admin ${id} failed: ${why}`);
      }
    } catch (e) {
      errors.push(`copy:${id}:${String(e.message || e)}`);
      console.error('receipt: copyMessage threw:', e);
    }
  }
  if (!delivered) {
    console.error('receipt: NO admin received the receipt —', errors.join(' | '));
    return send(env, msg.chat.id, [
      '⚠️ <b>ارسال رسید ناموفق بود.</b>',
      'احتمالاً ادمین ربات را استارت نکرده یا ربات بلاک شده است.',
      `لطفاً مستقیم به پشتیبانی پیام بده: ${esc(String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN'))}`,
    ].join('\n'));
  }
  return send(env, msg.chat.id,
    '✅ رسید دریافت شد — بعد از تأیید ادمین، کد فعال‌سازی خودکار برایت ارسال می‌شود. 🍯');
}

/** /diag — «چرا چیزی نمی‌آید؟» یک فرمان، کل زنجیره را لاپوشانی می‌کند:
 *  پیکربندی، خودِ ربات، سلامت وب‌هوک از زبان تلگرام، قابل‌ارسال‌بودن هر ادمین،
 *  و صف رسیدهای در انتظار. مقادیر محرمانه هرگز نشان داده نمی‌شوند. */
async function cmdDiag(env, chatId) {
  const lines = ['🩺 <b>وضعیت ربات BEE-VPN</b>', ''];
  lines.push('🔐 پیکربندی: ' + [
    ['BOT_TOKEN', env.BOT_TOKEN], ['WEBHOOK_SECRET', env.WEBHOOK_SECRET],
    ['ADMIN_IDS', env.ADMIN_IDS], ['CARD_INFO', env.CARD_INFO], ['USDT_INFO', env.USDT_INFO],
  ].map(([k, v]) => `${k} ${v ? '✅' : '❌'}`).join(' — '));
  const me = await tg(env, 'getMe', {});
  lines.push(me && me.ok
    ? `🤖 ربات: @${esc(me.result.username)}`
    : '🤖 ربات: ❌ BOT_TOKEN پاسخ نمی‌دهد');
  const wh = await tg(env, 'getWebhookInfo', {});
  if (wh && wh.ok) {
    const r = wh.result;
    lines.push(`🔗 وب‌هوک: ${r.url ? '✅ تنظیم است' : '❌ تنظیم نشده — setWebhook را بزن'} — در صف: ${r.pending_update_count || 0}`);
    if (r.last_error_message) {
      lines.push(`⚠️ آخرین خطای وب‌هوک: ${esc(r.last_error_message)}${r.last_error_date ? ` (${faDateTime(r.last_error_date)})` : ''}`);
    }
  } else {
    lines.push('🔗 وب‌هوک: ❌ بررسی ناموفق');
  }
  const ids = String(env.ADMIN_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
  lines.push('');
  if (!ids.length) {
    lines.push('📣 ادمین: ❌ ADMIN_IDS خالی است — رسید کسی به کسی نمی‌رسد!');
  }
  for (const id of ids) {
    const r = await tg(env, 'sendMessage', {
      chat_id: id, text: '🩺 تست ارسال از /diag', disable_notification: true,
    });
    lines.push(r && r.ok
      ? `📣 ادمین <code>${id}</code>: دریافت پیام ✅`
      : `📣 ادمین <code>${id}</code>: ❌ ${(r && r.description) || 'نامشخص'} — ربات باید یک‌بار <b>استارت</b> شود (یا آن‌بلاک شود)`);
  }
  try {
    const pend = await env.DB.prepare('SELECT tg_user_id, plan, ts FROM pending_receipts').all();
    const now = nowSec();
    const rows = (pend.results || []).map((r) =>
      `• <code>${r.tg_user_id}</code> → ${esc(r.plan)} — ${Math.max(0, Math.round((now - r.ts) / 60))} دقیقه پیش${now - r.ts > 86400 ? ' (منقضی)' : ''}`);
    lines.push('', rows.length
      ? `🧾 رسیدهای در انتظار: ${rows.length}\n${rows.join('\n')}`
      : '🧾 رسید در انتظاری نیست');
  } catch (_) { lines.push('', '🧾 جدول pending_receipts در دسترس نیست ❌'); }
  try {
    const n = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first();
    lines.push(`🗄 دیتابیس: ✅ (${n ? n.n : 0} کد)`);
  } catch (_) { lines.push('🗄 دیتابیس: ❌'); }
  return send(env, chatId, lines.join('\n'));
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
  // Atomic insert keyed on the receipt note: two admins tapping ✅ at the
  // same instant still mint exactly one code (the second insert is a no-op).
  const ins = await env.DB.prepare(
    'INSERT INTO licenses (code, plan, status, duration_days, note, telegram_user_id, created_at) '
    + 'SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM licenses WHERE note = ?)',
  ).bind(code, plan, 'active', planDays(plan), note, buyerId, nowSec(), note).run();
  if (!ins.meta || !ins.meta.changes) {
    // Another admin already approved this exact receipt.
    const row = await env.DB.prepare('SELECT code FROM licenses WHERE note = ?').bind(note).first();
    await tg(env, 'editMessageText', {
      chat_id: chatId, message_id: messageId,
      text: `✅ این رسید قبلاً تأیید شده — کد <code>${esc(row ? row.code : '')}</code> برای خریدار ارسال شد.`,
    }).catch(() => {});
    return answerCb(env, cb.id, 'این رسید قبلاً تأیید شده است');
  }
  await clearPending(env, buyerId);
  const delivered = await send(env, buyerId, [
    '🍯 <b>پرداخت شما تأیید شد!</b>',
    `پلن: <b>${esc(((await getPrices(env))[plan] || {}).title || plan)}</b>`,
    '',
    '🔑 <b>کد فعال‌سازی تو:</b>',
    `<code>${esc(code)}</code>`,
    '',
    'اپ BEE-VPN را باز کن → «کد فعال‌سازی» → کد را وارد کن.',
    '⏱ اشتراک از لحظهٔ اولین فعال‌سازی در اپ شروع می‌شود.',
  ].join('\n'));
  if (!(delivered && delivered.ok)) {
    // Buyer probably blocked the bot: the code exists but would be lost —
    // hand it to the admin instead of claiming it was delivered.
    await tg(env, 'editMessageText', {
      chat_id: chatId, message_id: messageId,
      text: '⚠️ تأیید شد ولی <b>ارسال به خریدار ناموفق بود</b> (احتمالاً ربات را بلاک کرده است).\nکد را خودت دستی برایش بفرست:\n<code>' + esc(code) + '</code>',
    }).catch(() => {});
    return answerCb(env, cb.id, 'کد صادر شد — ارسال ناموفق!');
  }
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
// Admin panel — a button-driven cockpit over every admin command. Callbacks
// stay short (adm:/gen/genc:/st:/act:/buy:/pendx) and every one of them is
// re-checked against ADMIN_IDS in the router before this code runs.
// ---------------------------------------------------------------------------

const ADMIN_HELP = [
  '🛠 <b>فرمان‌های ادمین</b> (همه در پنل دکمه‌ای هم هستند)',
  '🛖 رسید خرید: عکسِ مشتری خودکار برای تو می‌آید — فقط دکمهٔ <b>✅ تأیید و ارسال کد</b> را بزن؛ کد صادر و خودکار برای خریدار ارسال می‌شود (❌ = رد).',
  '<code>/gen m3 5 تخفیف دوست</code> — ساخت کد دستی (تا ۵۰)',
  '<code>/adduser user pass m1</code> — ساخت حساب',
  '<code>/list 10</code> • <code>/who BEE-…</code>',
  '<code>/revoke …</code> • <code>/unrevoke …</code>',
  '<code>/extend … 30</code> • <code>/reset …</code>',
  '<code>/stats</code> — آمار کل + فروش ۷/۳۰ روز + سهم پلن‌ها',
  '<code>/buyers</code> — لیست خریداران با تعداد خرید',
  '<code>/who 123456789</code> — تاریخچهٔ کامل یک خریدار',
  '<code>/price</code> — پنل دکمه‌ای قیمت‌ها',
  '<code>/panel</code> — پنل مدیریت کامل',
  '<code>/diag</code> — عیب‌یابی: وب‌هوک، قابل‌ارسال‌بودن ادمین‌ها، رسیدهای در انتظار',
].join('\n');

function adminMenuKb() {
  return { inline_keyboard: [
    [{ text: '📊 داشبورد و آمار', callback_data: 'adm:stats' }, { text: '🧾 رسیدهای در انتظار', callback_data: 'adm:pend' }],
    [{ text: '🎫 مدیریت اشتراک‌ها', callback_data: 'adm:lic' }],
    [{ text: '👥 خریداران', callback_data: 'adm:buyers' }, { text: '💰 قیمت‌ها', callback_data: 'price_menu' }],
    [{ text: '🩺 ابزار و عیب‌یابی', callback_data: 'adm:tools' }],
    [{ text: '🔙 منوی اصلی', callback_data: 'menu' }],
  ] };
}

function licMenuKb() {
  return { inline_keyboard: [
    [{ text: '➕ ساخت کد فعال‌سازی', callback_data: 'gen' }, { text: '👤 ساخت حساب کاربری', callback_data: 'st:adduser' }],
    [{ text: '🔎 جستجوی اشتراک', callback_data: 'st:who' }, { text: '🗒 آخرین کدها', callback_data: 'adm:list' }],
    [{ text: '⛔ لغو اشتراک', callback_data: 'st:revoke' }, { text: '✅ رفع لغو', callback_data: 'st:unrevoke' }],
    [{ text: '➕ تمدید (روز)', callback_data: 'st:extend' }, { text: '🔁 ریست دستگاه', callback_data: 'st:reset' }],
    [{ text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
}

function genPlanKb() {
  return { inline_keyboard: [
    [{ text: '🎁 تست ۱ روزه', callback_data: 'gen:trial' }, { text: '📅 یک‌ماهه m1', callback_data: 'gen:m1' }],
    [{ text: '📅 سه‌ماهه m3', callback_data: 'gen:m3' }, { text: '📅 شش‌ماهه m6', callback_data: 'gen:m6' }],
    [{ text: '📅 سالانه m12', callback_data: 'gen:m12' }],
    [{ text: '🔙 مدیریت اشتراک‌ها', callback_data: 'adm:lic' }],
  ] };
}

function genCountKb(plan) {
  return { inline_keyboard: [
    [{ text: '۱ کد', callback_data: `genc:${plan}:1` }, { text: '۵ کد', callback_data: `genc:${plan}:5` }, { text: '۱۰ کد', callback_data: `genc:${plan}:10` }],
    [{ text: '۲۵ کد', callback_data: `genc:${plan}:25` }, { text: '۵۰ کد', callback_data: `genc:${plan}:50` }],
    [{ text: '🔙 انتخاب پلن', callback_data: 'gen' }],
  ] };
}

function toolsKb() {
  return { inline_keyboard: [
    [{ text: '🩺 اجرای /diag', callback_data: 'adm:diag' }],
    [{ text: '📊 آمار کندو', callback_data: 'adm:stats' }, { text: '🛠 فرمان‌ها', callback_data: 'adm:help' }],
    [{ text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
}

/** Pending plan choices: who is about to send a receipt for which plan. */
async function pendView(env) {
  const back = { inline_keyboard: [[{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]] };
  try {
    const pend = await env.DB.prepare('SELECT tg_user_id, plan, ts FROM pending_receipts ORDER BY ts DESC').all();
    const now = nowSec();
    const rows = pend.results || [];
    if (!rows.length) {
      return {
        text: '🧾 رسید در انتظاری نیست.\n\nوقتی خریدار پلن را انتخاب کند و عکسِ رسید را بفرستد، عکس با دکمهٔ ✅ مستقیم به چت تو می‌آید.',
        kb: back,
      };
    }
    const priceMap = await getPrices(env);
    const lines = [`🧾 <b>انتخاب‌های در انتظار رسید</b> (${faNum(rows.length)}):`, ''];
    const kbRows = [];
    for (const r of rows.slice(0, 15)) {
      const mins = Math.max(0, Math.round((now - r.ts) / 60));
      const stale = now - r.ts > PENDING_TTL ? ' (منقضی)' : '';
      const title = (priceMap[r.plan] && priceMap[r.plan].title) || r.plan;
      lines.push(`• <a href="tg://user?id=${r.tg_user_id}">${r.tg_user_id}</a> → ${esc(title)} — ${faNum(mins)} دقیقه پیش${stale}`);
      kbRows.push([{ text: `🗑 پاک کردن انتخاب ${r.tg_user_id}`, callback_data: `pendx:${r.tg_user_id}` }]);
    }
    kbRows.push([{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]);
    return { text: lines.join('\n'), kb: { inline_keyboard: kbRows } };
  } catch (_) {
    return { text: '🧾 جدول pending_receipts در دسترس نیست ❌', kb: back };
  }
}

/** Apply one panel action (revoke/unrevoke/reset/extend) then re-render the
 *  who view so the admin immediately sees the new state. */
async function actApply(env, kind, subject, op) {
  const table = kind === 'L' ? 'licenses' : 'accounts';
  const column = kind === 'L' ? 'code' : 'username';
  const key = kind === 'L' ? String(subject).toUpperCase() : String(subject).toLowerCase();
  if (op === 'rv' || op === 'un') {
    await env.DB.prepare(`UPDATE ${table} SET status = ? WHERE ${column} = ?`)
      .bind(op === 'rv' ? 'revoked' : 'active', key).run();
  } else if (op === 'rst') {
    await env.DB.prepare(`UPDATE ${table} SET bound_install_id = NULL, activated_at = NULL, expires_at = NULL WHERE ${column} = ?`)
      .bind(key).run();
  } else if (op === 'e30' || op === 'e90') {
    const days = op === 'e30' ? 30 : 90;
    const row = await env.DB.prepare(`SELECT * FROM ${table} WHERE ${column} = ?`).bind(key).first();
    if (row && row.expires_at) {
      const base = Math.max(nowSec(), row.expires_at);
      await env.DB.prepare(`UPDATE ${table} SET expires_at = ? WHERE ${column} = ?`).bind(base + days * 86400, key).run();
    } else if (row) {
      await env.DB.prepare(`UPDATE ${table} SET duration_days = duration_days + ? WHERE ${column} = ?`).bind(days, key).run();
    }
  }
  return whoView(env, key);
}

/** The next plain admin message while a st:<mode> state is armed. */
async function runAdminText(env, chatId, mode, text) {
  const parts = text.trim().split(/\s+/);
  if (mode === 'who') return cmdWho(env, chatId, parts);
  if (mode === 'revoke') return setStatus(env, chatId, parts, 'revoked');
  if (mode === 'unrevoke') return setStatus(env, chatId, parts, 'active');
  if (mode === 'extend') return cmdExtend(env, chatId, parts);
  if (mode === 'reset') return cmdReset(env, chatId, parts);
  if (mode === 'adduser') return cmdAddUser(env, chatId, parts);
}

const ADMIN_STATES = {
  who: '🔎 <b>جستجوی اشتراک</b>\nکد فعال‌سازی، نام کاربری یا شناسهٔ عددی تلگرام را بفرست:\n(لغو: <code>/cancel</code>)',
  revoke: '⛔ <b>لغو اشتراک</b>\nکد یا نام کاربری را بفرست:\n(لغو: <code>/cancel</code>)',
  unrevoke: '✅ <b>رفع لغو</b>\nکد یا نام کاربری را بفرست:\n(لغو: <code>/cancel</code>)',
  extend: '➕ <b>تمدید اشتراک</b>\nفرمت: <code>BEE-… 30</code> یا <code>username 30</code>:\n(لغو: <code>/cancel</code>)',
  reset: '🔁 <b>ریست دستگاه</b>\nکد یا نام کاربری را بفرست — بایند پاک می‌شود و دستگاه بعدی از نو شروع می‌کند:\n(لغو: <code>/cancel</code>)',
  adduser: '👤 <b>ساخت حساب کاربری</b>\nفرمت: <code>username password m1|m3|m6|m12</code>:\n(لغو: <code>/cancel</code>)',
};

async function handleAdminCb(env, cb, data) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  const edit = (text, kb) => tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId, text, parse_mode: 'HTML', reply_markup: kb,
    disable_web_page_preview: true,
  }).catch(() => {});

  if (data === 'adm') {
    let live = 0;
    try {
      const now = nowSec();
      const lic = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE expires_at > ?').bind(now).first();
      const acc = await env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE expires_at > ?').bind(now).first();
      live = (lic ? lic.n : 0) + (acc ? acc.n : 0);
    } catch (_) { /* panel still renders */ }
    await edit([
      '🛠 <b>پنل مدیریت کندو</b>',
      `🟢 اشتراک در جریان: <b>${faNum(live)}</b>`,
      '',
      'همه‌چیز دکمه‌ای است؛ فرمان‌های متنی هم مثل قبل کار می‌کنند.',
    ].join('\n'), adminMenuKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:stats') {
    await edit(await statsText(env), { inline_keyboard: [[{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]] });
    return answerCb(env, cb.id);
  }
  if (data === 'adm:lic') {
    await edit('🎫 <b>مدیریت اشتراک‌ها</b>\nساخت، جستجو، لغو، تمدید و ریست — همه از همین‌جا:', licMenuKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:buyers') {
    const v = await buyersView(env, '15');
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data === 'adm:pend') {
    const v = await pendView(env);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data === 'adm:list') {
    await edit(await listText(env, '10'), licMenuKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:tools') {
    await edit('🩺 <b>ابزار و عیب‌یابی</b>', toolsKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:diag') {
    await answerCb(env, cb.id, 'در حال بررسی…');
    await cmdDiag(env, chatId);
    return;
  }
  if (data === 'adm:help') {
    await edit(ADMIN_HELP, toolsKb());
    return answerCb(env, cb.id);
  }
  if (data === 'gen') {
    await edit('➕ <b>ساخت کد فعال‌سازی</b>\nپلن را انتخاب کن:', genPlanKb());
    return answerCb(env, cb.id);
  }
  if (data.startsWith('gen:')) {
    const plan = data.slice(4);
    if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
    await edit(`➕ ساخت کد <b>${esc(plan)}</b> — چند کد؟`, genCountKb(plan));
    return answerCb(env, cb.id);
  }
  if (data.startsWith('genc:')) {
    const [, plan, countRaw] = data.split(':');
    const count = Math.min(Math.max(parseInt(countRaw || '1', 10) || 1, 1), 50);
    if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
    await answerCb(env, cb.id, 'در حال ساخت…');
    await genCore(env, chatId, plan, count, null, null);
    await edit(`✅ ${faNum(count)} کد <b>${esc(plan)}</b> ساخته و در پیام بالا ارسال شد.`, licMenuKb());
    return;
  }
  if (data.startsWith('st:')) {
    const mode = data.slice(3);
    if (!ADMIN_STATES[mode]) return answerCb(env, cb.id, 'نامعتبر');
    await setAdminState(env, cb.from.id, { mode });
    await edit(ADMIN_STATES[mode], licMenuKb());
    return answerCb(env, cb.id);
  }
  if (data.startsWith('act:')) {
    const parts = data.split(':');
    const kind = parts[1];
    const subject = parts[2] || '';
    const op = parts[3] || 'vw';
    if (kind !== 'L' && kind !== 'A') return answerCb(env, cb.id, 'نامعتبر');
    if (!['rv', 'un', 'rst', 'e30', 'e90', 'vw'].includes(op)) return answerCb(env, cb.id, 'نامعتبر');
    if (op !== 'vw') await actApply(env, kind, subject, op);
    const v = await whoView(env, subject);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id,
      op === 'rv' ? '⛔ باطل شد' : op === 'un' ? '✅ فعال شد' : op === 'rst' ? '🔁 ریست شد'
        : op === 'e30' ? '➕ ۳۰ روز اضافه شد' : op === 'e90' ? '➕ ۹۰ روز اضافه شد' : 'بروزرسانی شد');
  }
  if (data.startsWith('buy:')) {
    const uid = parseInt(data.slice(4), 10);
    if (!uid) return answerCb(env, cb.id, 'نامعتبر');
    const v = await whoBuyerView(env, uid);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data.startsWith('pendx:')) {
    const uid = parseInt(data.slice(6), 10);
    if (uid) await clearPending(env, uid);
    const v = await pendView(env);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id, 'پاک شد');
  }
  return answerCb(env, cb.id);
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
        // The bot only lives in private chats — ignore group chatter.
        if (cb.message.chat.type && cb.message.chat.type !== 'private') {
          return new Response('ok');
        }
        if (data === 'menu' || data === 'start') {
          await tg(env, 'editMessageText', {
            chat_id: chatId, message_id: cb.message.message_id, text: WELCOME,
            parse_mode: 'HTML', reply_markup: mainMenu(env, cb.from), disable_web_page_preview: true,
          }).catch(() => send(env, chatId, WELCOME, mainMenu(env, cb.from)));
        } else if (data === 'buy') {
          await tg(env, 'editMessageText', {
            chat_id: chatId, message_id: cb.message.message_id,
            text: '🛍 <b>پلن موردنظر را انتخاب کن:</b>',
            parse_mode: 'HTML', reply_markup: await planKeyboard(env),
          }).catch(async () => send(env, chatId, '🛍 پلن‌ها:', await planKeyboard(env)));
        } else if (data.startsWith('plan:')) {
          const plan = data.slice(5);
          const prev = await getPending(env, tgId);
          if (planDays(plan) && (!prev || prev.plan !== plan)) {
            await setPending(env, tgId, plan);
            await notifyAdmins(env, `🛒 خریدار ${buyerLine(cb.from)} پلن <b>${esc(((await getPrices(env))[plan] || {}).title || plan)}</b> را انتخاب کرد.\nاسکرین‌شاتش که رسید را با دکمهٔ ✅ تأیید کن — کد خودکار برایش ارسال می‌شود.`);
          } else if (planDays(plan)) {
            await setPending(env, tgId, plan);
          }
          await send(env, chatId, await paymentText(env, plan), {
            inline_keyboard: [[{ text: '🔙 بازگشت', callback_data: 'buy' }]],
          });
        } else if (data.startsWith('price:') || data.startsWith('pset:') ||
                   data === 'price_menu' || data === 'reset_ask' || data === 'reset_go') {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            await handlePriceCb(env, cb, data);
          }
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
          await mySub(env, chatId, tgId, cb.from);
        } else if (data === 'help') {
          await send(env, chatId, HELP_TEXT);
        } else if (data === 'adm' || data.startsWith('adm:') || data.startsWith('gen')
                   || data.startsWith('st:') || data.startsWith('act:') || data.startsWith('buy:')
                   || data.startsWith('pendx:')) {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            await handleAdminCb(env, cb, data);
          }
        }
        await answerCb(env, cb.id);
        return new Response('ok');
      }

      const msg = update.message;
      if (!msg || !msg.from) return new Response('ok');
      // Groups/channels are out of scope: every flow assumes a private chat.
      if (msg.chat.type && msg.chat.type !== 'private') return new Response('ok');
      const chatId = msg.chat.id;
      const tgId = msg.from.id;
      const text = (msg.text || '').trim();

      if (text.startsWith('/start')) {
        await send(env, chatId, WELCOME, mainMenu(env, msg.from));
        // Deep links from the app: ?start=buy opens the shop, ?start=trial
        // hands out the 1-day code directly.
        if (text.includes('buy')) {
          await send(env, chatId, '🛍 <b>پلن موردنظر را انتخاب کن:</b>', await planKeyboard(env));
        } else if (text.includes('trial')) {
          await giveTrial(env, chatId, tgId);
        }
        return new Response('ok');
      }

      if (text.startsWith('/help')) {
        await send(env, chatId, HELP_TEXT);
        return new Response('ok');
      }

      // Buyer profile — works for admins too (they buy for others).
      // /mysub is a text alias (same name as the profile button callback).
      if (text.startsWith('/profile') || text.startsWith('/mysub')) {
        await mySub(env, chatId, tgId, msg.from);
        return new Response('ok');
      }

      // /link BEE-XXXX-XXXX-XXXX — claim a code support issued without a
      // Telegram owner, so it shows up in the buyer's profile. A code already
      // linked to someone else can never be stolen this way.
      if (text.startsWith('/link')) {
        const m = text.match(/BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}/i);
        if (!m) {
          await send(env, chatId, 'فرمت: <code>/link BEE-XXXX-XXXX-XXXX</code>\nکدی که از پشتیبانی گرفتی را بفرست تا به پروفایلت وصل شود.');
        } else {
          const code = m[0].toUpperCase();
          const row = await env.DB.prepare('SELECT * FROM licenses WHERE code = ?').bind(code).first();
          if (!row) {
            await send(env, chatId, '❌ این کد پیدا نشد — دوباره چک کن یا با پشتیبانی تماس بگیر.');
          } else if (row.telegram_user_id && row.telegram_user_id !== tgId) {
            await send(env, chatId, '⚠️ این کد قبلاً به حساب دیگری وصل شده است. با پشتیبانی تماس بگیر.');
          } else if (row.telegram_user_id === tgId) {
            await send(env, chatId, '✅ این کد از قبل به پروفایلت وصل است.', { inline_keyboard: [[{ text: '👤 پروفایل من', callback_data: 'mysub' }]] });
          } else {
            await env.DB.prepare('UPDATE licenses SET telegram_user_id = ? WHERE code = ? AND telegram_user_id IS NULL').bind(tgId, code).run();
            await send(env, chatId, [
              '✅ <b>کد به پروفایلت وصل شد!</b>',
              `🔑 <code>${esc(code)}</code>`,
              'در «👤 پروفایل من» می‌بینیش — تاریخ انقضا و روزهای باقی‌مانده هم آنجاست.',
            ].join('\n'), { inline_keyboard: [[{ text: '👤 پروفایل من', callback_data: 'mysub' }]] });
          }
        }
        return new Response('ok');
      }

      // A photo (or an image sent as a file) IS the payment receipt — no
      // command needed. This runs BEFORE the admin branch on purpose: when the
      // admin tests the flow (or buys for someone), their photo used to fall
      // into the command dispatcher and vanish silently — nothing to anyone.
      if ((msg.photo && msg.photo.length)
          || (msg.document && String(msg.document.mime_type || '').startsWith('image/'))) {
        await handleReceipt(env, msg);
        return new Response('ok');
      }

      if (isAdmin(env, msg.from)) {
        // A pending panel prompt: the admin's next plain message IS the input
        // (a price, a search key, a revoke target, a gen spec…).
        if (!text.startsWith('/')) {
          const st = await getAdminState(env, msg.from.id);
          if (st && st.mode === 'price' && planDays(st.plan)) {
            const applied = await applyPriceInput(env, chatId, st.plan, text);
            if (applied) await clearAdminState(env, msg.from.id);
            return new Response('ok');
          }
          if (st && ADMIN_STATES[st.mode]) {
            await runAdminText(env, chatId, st.mode, text);
            await clearAdminState(env, msg.from.id);
            return new Response('ok');
          }
        }
        const parts = text.split(/\s+/);
        const cmd = (parts[0] || '').toLowerCase();
        const args = parts.slice(1);
        if (cmd === '/gen') await cmdGen(env, chatId, args);
        else if (cmd === '/adduser') await cmdAddUser(env, chatId, args);
        else if (cmd === '/list') await cmdList(env, chatId, args);
        else if (cmd === '/who') await cmdWho(env, chatId, args);
        else if (cmd === '/buyers') await cmdBuyers(env, chatId, args);
        else if (cmd === '/price') await cmdPrice(env, chatId, args);
        else if (cmd === '/revoke') await setStatus(env, chatId, args, 'revoked');
        else if (cmd === '/unrevoke') await setStatus(env, chatId, args, 'active');
        else if (cmd === '/extend') await cmdExtend(env, chatId, args);
        else if (cmd === '/reset') await cmdReset(env, chatId, args);
        else if (cmd === '/stats') await cmdStats(env, chatId);
        else if (cmd === '/diag') await cmdDiag(env, chatId);
        else if (cmd === '/panel' || cmd === '/admin') {
          let live = 0;
          try {
            const now = nowSec();
            const l1 = await env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE expires_at > ?').bind(now).first();
            const l2 = await env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE expires_at > ?').bind(now).first();
            live = (l1 ? l1.n : 0) + (l2 ? l2.n : 0);
          } catch (_) { /* still render the panel */ }
          await send(env, chatId, [
            '🛠 <b>پنل مدیریت کندو</b>',
            `🟢 اشتراک در جریان: <b>${faNum(live)}</b>`,
          ].join('\n'), adminMenuKb());
        }
        else if (cmd === '/cancel') {
          await clearAdminState(env, msg.from.id);
          await send(env, chatId, '↩️ لغو شد.');
        }
        else if (cmd === '/helpadmin') {
          await send(env, chatId, ADMIN_HELP);
        }
        return new Response('ok');
      }

      // Buyer sent something that is not a photo: if they are mid-purchase,
      // tell them exactly what is expected instead of the generic menu.
      const pend = await getPending(env, tgId);
      if (pend && planDays(pend.plan) && !text.startsWith('/')) {
        await send(env, chatId, [
          '📸 رسید باید به شکل <b>عکس (اسکرین‌شات)</b> باشد — همین‌جا بفرستش، بدون کامند.',
          'پلن انتخاب‌شدهٔ تو:', `<b>${esc(((await getPrices(env))[pend.plan] || {}).title || pend.plan)}</b>`,
        ].join('\n'));
        return new Response('ok');
      }
      // Any other text from a regular user: nudge them to the menu.
      await send(env, chatId, 'برای شروع از منوی زیر استفاده کن 👇', mainMenu(env, msg.from));
      return new Response('ok');
    } catch (e) {
      console.error('webhook handler error:', e);
      try { await send(env, update.message?.chat?.id || update.callback_query?.message?.chat?.id, `⚠️ خطا: ${esc(String(e.message || e))}`); } catch (_) {}
      return new Response('ok');
    }
  },
};
