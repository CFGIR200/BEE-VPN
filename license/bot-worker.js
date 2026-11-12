/**
 * BEE-VPN sales bot — Cloudflare Worker + Telegram webhook, same D1 database
 * as license-worker.js (D1 bindings may be attached to several Workers).
 *
 * What it does for the operator:
 *   - sells: plan catalogue -> payment info (card-to-card + USDT) -> the
 *     buyer sends the receipt, the admin taps ✅ and the code is issued and
 *     delivered automatically. No payment gateway needed on day one.
 *   - registers (ثبت‌نام): the 📝 menu button mints a real ACCOUNT —
 *     username + password delivered straight into the buyer's chat. The
 *     1-day trial is instant and one-per-Telegram across BOTH kinds; paid
 *     plans ride the receipt flow and the ✅ (oka:…) builds the account,
 *     stamped with the buyer's Telegram id/name/@handle.
 *   - trial: hands out exactly one 1-day entitlement per Telegram account,
 *     as a code (🎁) or as a registered account (📝) — never both.
 *   - gate: «🎁 کد تست» و «🛍 خرید اشتراک» only OPEN after ثبت‌نام — the
 *     buttons check the accounts table on tap (admins pass) and unregistered
 *     users are pointed at 📝 instead.
 *   - profile: /profile (rich buyer profile: plan, Jalali activation/expiry
 *              dates, days remaining, device binding) + /link <code> to claim
 *              a code issued by support + the «پروفایل من» button.
 *   - admin: button panel (/panel or 🛠 پنل مدیریت) — dashboard, license
 *            management (gen/adduser/search/list/revoke/extend/reset),
 *            buyers, REGISTRATIONS (ثبت‌نامی‌ها: linked Telegram name,
 *            numeric id, issued username, Jalali register/expiry dates,
 *            device binding — /regs), pending receipts, prices — plus every
 *            command: /gen /adduser /list /who /regs /revoke /unrevoke
 *            /extend /reset /stats /buyers /price /diag
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
      [{ text: '📝 ثبت‌نام (نام کاربری و رمز)', callback_data: 'reg' }],
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

// Runtime-editable payment info: /card and /usdt write the D1 settings table
// (keys pay:card / pay:usdt); getPayInfo() prefers them over the CARD_INFO /
// USDT_INFO secrets — the operator can change the card number / USDT address
// from inside Telegram, no redeploy needed. «/card clear» returns to the
// secret value. Falls back silently when the table is missing.
const PAY_KEYS = { card: 'pay:card', usdt: 'pay:usdt' };

async function getPayInfo(env) {
  const out = {
    card: String(env.CARD_INFO || '(کارت به کارت: شماره کارت در env تنظیم نشده)'),
    usdt: String(env.USDT_INFO || '(USDT TRC20: آدرس کیف پول در env تنظیم نشده)'),
  };
  try {
    const rows = await env.DB.prepare('SELECT key, value FROM settings WHERE key = ? OR key = ?')
      .bind(PAY_KEYS.card, PAY_KEYS.usdt).all();
    for (const row of rows.results || []) {
      if (row.key === PAY_KEYS.card && row.value) out.card = row.value;
      if (row.key === PAY_KEYS.usdt && row.value) out.usdt = row.value;
    }
  } catch (_) { /* no settings table yet — env fallback */ }
  return out;
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
  const pay = await getPayInfo(env);
  const card = pay.card;
  const usdt = pay.usdt;
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
  '   👤 <b>حساب کاربری:</b> در همین ربات «📝 ثبت‌نام» بزن — نام کاربری و رمز همان‌جا به‌ تو می‌رسد.',
  '   🔑 <b>کد فعال‌سازی:</b> کدی که از ربات/پشتیبانی گرفتی را وارد کن.',
  '۳) روی «فعال‌سازی/ورود» بزن — تمام! کندو باز می‌شود 🍯',
  '',
  '⚠️ هر اشتراک فقط روی <b>یک دستگاه</b> فعال می‌شود.',
  '🔁 اگر گوشی را عوض کردی، از پشتیبانی بخواه دستگاهت را ریست کند.',
  '🔒 «🎁 کد تست» و «🛍 خرید» فقط بعد از ثبت‌نام باز می‌شوند.',
].join('\n');

const WELCOME = [
  '🐝 <b>به کندوی BEE-VPN خوش آمدی!</b>',
  '',
  'مسیر تو در کندو:',
  '📝 ثبت‌نام — قدم اول! حساب کاربری (نام کاربری و رمز) می‌گیری',
  '🎁 کد تست ۱ روزه و 🛍 خرید اشتراک — بعد از ثبت‌نام باز می‌شوند 🔒',
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

/** /who for one code/username — the manage card: full info plus one-tap
 *  actions (extend 7/30/90 days, status toggle, device reset). Telegram-linked
 *  accounts show the buyer's registered name as a live link into their chat. */
async function whoView(env, key) {
  const found = await lookup(env, key);
  if (!found) return { text: '❌ پیدا نشد.', kb: adminMenuKb() };
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
  ];
  if (r.telegram_user_id) {
    const label = r.telegram_name
      || (r.telegram_username ? '@' + r.telegram_username : String(r.telegram_user_id));
    lines.push(`تلگرام: <a href="tg://user?id=${r.telegram_user_id}">${esc(label)}</a>`
      + (r.telegram_name && r.telegram_username ? ` (@${esc(r.telegram_username)})` : '')
      + ` — <code>${r.telegram_user_id}</code>`);
  }
  const act = (op, extra) => `adm:${op}:${kind}:${subject}${extra ? ':' + extra : ''}`;
  const kb = { inline_keyboard: [
    [
      { text: '➕ ۷ روز', callback_data: act('ext', '7') },
      { text: '➕ ۳۰ روز', callback_data: act('ext', '30') },
      { text: '➕ ۹۰ روز', callback_data: act('ext', '90') },
    ],
    [
      r.status === 'active'
        ? { text: '⛔ لغو اشتراک', callback_data: act('st', 'revoked') }
        : { text: '✅ فعال‌سازی دوباره', callback_data: act('st', 'active') },
      { text: '🔁 ریست دستگاه', callback_data: act('rst') },
    ],
    [{ text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
  return { text: lines.filter(Boolean).join('\n'), kb };
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
    return { text: '❌ برای این شناسهٔ تلگرام چیزی ثبت نشده.', kb: { inline_keyboard: [[{ text: '🔙 خریداران', callback_data: 'adm:buyers:1' }]] } };
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
    kbRows.push([{ text: `⚙️ ${r.code}`, callback_data: `adm:vw:L:${r.code}` }]);
  }
  kbRows.push([{ text: '🔙 خریداران', callback_data: 'adm:buyers:1' }]);
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
  const regs = await env.DB.prepare(
    "SELECT COUNT(*) AS n, SUM(CASE WHEN status='active' THEN 1 ELSE 0 END) AS act FROM accounts WHERE telegram_user_id IS NOT NULL",
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
    `📝 ثبت‌نامی‌ها: <b>${regs ? regs.n : 0}</b> حساب تلگرامی${regs && regs.act ? ` — ${regs.act} فعال` : ''} (لیست: /regs)`,
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

/** /card and /usdt — runtime-edit the payment lines the buyers see (admin
 *  only). Writes the D1 settings table; «clear» returns to the Secret value. */
async function cmdPayInfo(env, chatId, kind, args) {
  const key = PAY_KEYS[kind];
  const label = kind === 'card' ? 'کارت به کارت' : 'تتر (TRC20)';
  const cmdName = kind === 'card' ? '/card' : '/usdt';
  const rest = args.join(' ').trim();
  const pay = await getPayInfo(env);
  const current = kind === 'card' ? pay.card : pay.usdt;
  if (!rest) {
    return send(env, chatId,
      `💳 <b>${label}</b> — مقدار فعلی:\n<code>${esc(current)}</code>\n\n` +
      `تغییر: <code>${cmdName} متن تازه</code>\n` +
      `بازگشت به مقدار Secret: <code>${cmdName} clear</code>`);
  }
  if (rest.toLowerCase() === 'clear') {
    try {
      await env.DB.prepare('DELETE FROM settings WHERE key = ?').bind(key).run();
    } catch (_) { /* table missing = already the secret value */ }
    const fresh = await getPayInfo(env);
    return send(env, chatId,
      `↩️ ${label} به مقدار Secret برگشت:\n<code>${esc(kind === 'card' ? fresh.card : fresh.usdt)}</code>`);
  }
  await env.DB.prepare(
    'INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) ' +
    'ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
  ).bind(key, rest, nowSec()).run();
  return send(env, chatId, `✅ <b>${label}</b> به‌روز شد:\n<code>${esc(rest)}</code>`);
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

/** 🎁 «کد تست ۱ روزه» — the legacy button now rides the ثبت‌نام flow:
 *  one coherent package (profile account + 1-day license) and the
 *  one-trial-per-Telegram cap enforced across BOTH kinds in one place.
 *  See giveTrialAccount() — the old code-only trial let a registered user
 *  take a SECOND trial through this button (the cap here only counted
 *  licenses, not trial accounts). */

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
    // پروفایل خالی = ثبت‌نام‌نکرده (هر ثبت‌نامی یک حساب می‌سازد) — به‌جای دکمهٔ
    // خریدِ بسته، مسیر 📝 را نشان بده تا گیتِ خرید برایش باز شود.
    const registered = isAdmin(env, from) || (await isRegistered(env, tgId));
    return send(env, chatId, [
      '👤 <b>پروفایل تو در کندو</b>',
      `🆔 شناسه: <code>${tgId}</code>`,
      '',
      registered
        ? 'هنوز اشتراکی نداری — با 🛍 خرید شروع کن یا 🎁 کد تست بگیر.'
        : 'هنوز ثبت‌نام نکردی — با 📝 ثبت‌نام شروع کن؛ بعد از آن 🛍 خرید و 🎁 کد تست برایت باز می‌شود.',
      '',
      'اگر پشتیبانی برایت کد ساخته، با این فرمت به پروفایلت وصلش کن:',
      '<code>/link BEE-XXXX-XXXX-XXXX</code>',
    ].join('\n'), { inline_keyboard: [
    registered
      ? [{ text: '🛍 خرید اشتراک', callback_data: 'buy' }]
      : [{ text: '📝 ثبت‌نام (نام کاربری و رمز)', callback_data: 'reg' }],
    [{ text: '📣 کانال', url: String(env.CHANNEL_URL || 'https://t.me/BEESELL_GP') },
     { text: '💬 پشتیبانی', url: String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN') }],
  ] });
  }
  const lines = [
    '👤 <b>پروفایل تو در کندو</b>',
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
  lines.push(`📌 اشتراک در جریان: <b>${faNum(liveN)}</b>`
    + (nearest ? ` — نزدیک‌ترین انقضا: ${faNum(daysLeftOf({ expires_at: nearest }, now))} روز دیگر` : ''), '');
  lines.push('🔗 اتصال کد از پشتیبانی: <code>/link BEE-XXXX-XXXX-XXXX</code>');
  return send(env, chatId, lines.join('\n'), {
    inline_keyboard: [
      [{ text: '🛍 خرید / تمدید', callback_data: 'buy' }, { text: '🔄 بروزرسانی', callback_data: 'mysub' }],
      [{ text: '📣 کانال', url: String(env.CHANNEL_URL || 'https://t.me/BEESELL_GP') },
       { text: '💬 پشتیبانی', url: String(env.SUPPORT_URL || 'https://t.me/Bee_hive_VPN') }],
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

// kind: 'code' (🛍 خرید → کد فعال‌سازی) or 'account' (📝 ثبت‌نام →
// username/password). The receipt buttons and the approval handler both
// branch on it.
async function setPending(env, tgId, plan, kind = 'code') {
  await env.DB.prepare(
    'INSERT INTO pending_receipts (tg_user_id, plan, ts, kind) VALUES (?, ?, ?, ?) '
    + 'ON CONFLICT(tg_user_id) DO UPDATE SET plan = excluded.plan, ts = excluded.ts, kind = excluded.kind',
  ).bind(tgId, plan, nowSec(), kind).run();
}

async function getPending(env, tgId) {
  const row = await env.DB.prepare(
    'SELECT plan, ts, kind FROM pending_receipts WHERE tg_user_id = ?',
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

function receiptButtons(buyerId, plan, ts, isAccount) {
  return { inline_keyboard: [[
    isAccount
      ? { text: '✅ تأیید و ساخت حساب', callback_data: `oka:${buyerId}:${plan}:${ts}` }
      : { text: '✅ تأیید و ارسال کد', callback_data: `ok:${buyerId}:${plan}:${ts}` },
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
    // گیت ثبت‌نام: عکسِ شخصِ ثبت‌نام‌نکرده خرید نیست — به 📝 هدایت می‌شود.
    // رسیدِ ثبت‌نامِ در جریان (pending دارد) از این شاخه رد نمی‌شود و دست‌نخورده می‌ماند.
    if (!(await regGate(env, msg.chat.id, from, null, 'خرید'))) {
      return new Response('ok');
    }
    return send(env, msg.chat.id,
      '🧾 برای ارسال رسید، اول پلن موردنظرت را انتخاب کن 👇',
      await planKeyboard(env));
  }
  // Milliseconds: the receipt ts doubles as the idempotency key
  // (licenses.note = receipt:<ts>:<buyerId>). Second granularity collided
  // when a buyer sent two receipts within the same second — the second one
  // was silently swallowed as "already approved".
  const ts = Date.now();
  const info = (await getPrices(env))[pending.plan] || { title: pending.plan, price: '?' };
  // A registration receipt (kind='account') mints a username/password, not a
  // code — the caption and the ✅ button both say so, so the admin always
  // knows what they are approving.
  const isAccount = pending.kind === 'account';
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
          isAccount
            ? `🧾 <b>رسید ثبت‌نام</b> — ${esc(info.title)} (${esc(info.price)})`
            : `🧾 <b>رسید پرداخت</b> — ${esc(info.title)} (${esc(info.price)})`,
          isAccount ? '📝 نوع: ساخت <b>حساب کاربری</b> (نام کاربری و رمز)' : '🔑 نوع: کد فعال‌سازی',
          `از: ${buyerLine(from)}`,
          `زمان رسید: <code>${faDateTime(Math.floor(ts / 1000))}</code> (تهران)`,
        ].join('\n'), receiptButtons(from.id, pending.plan, ts, isAccount));
        if (!(cap && cap.ok)) {
          // Photo arrived but the approve buttons did not — bolt them onto
          // the copied photo itself, otherwise the receipt is un-approvable.
          const fixed = await tg(env, 'editMessageCaption', {
            chat_id: id, message_id: copied.result.message_id,
            caption: `${isAccount ? '🧾 رسید ثبت‌نام' : '🧾 رسید'} ${esc(info.title)} از ${esc(from.first_name || 'خریدار')} (id ${from.id})`,
            parse_mode: 'HTML',
            reply_markup: receiptButtons(from.id, pending.plan, ts, isAccount),
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
    isAccount
      ? '✅ رسید دریافت شد — بعد از تأیید ادمین، <b>نام کاربری و رمز عبور</b> برایت ارسال می‌شود. 🍯'
      : '✅ رسید دریافت شد — بعد از تأیید ادمین، کد فعال‌سازی خودکار برایت ارسال می‌شود. 🍯');
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
  try {
    const ovr = await env.DB.prepare('SELECT key FROM settings WHERE key = ? OR key = ?')
      .bind(PAY_KEYS.card, PAY_KEYS.usdt).all();
    const names = (ovr.results || []).map((r) => (r.key === PAY_KEYS.card ? 'کارت' : 'تتر'));
    lines.push('💳 پرداخت: ' + (names.length
      ? `ویرایش در ربات فعال (${names.join('، ')})`
      : 'مقدار Secret'));
  } catch (_) { lines.push('💳 پرداخت: مقدار Secret'); }
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
/** ✅ on ANY receipt (🛍 code-flow «ok:» or 📝 account-flow «oka:») — the
 *  buyer always gets the FULL package:
 *    👤 profile account (reused if they already have one) +
 *    🎫 license code for the app (the thing that expires and locks it).
 *  Idempotency: licenses.note = receipt:<ts>:<buyerId> — two admins racing
 *  on the same receipt (either button flavour) still mint exactly one code.
 *  After expiry the profile survives: /profile keeps the buyer in touch with
 *  the channel/support and one tap away from the next purchase. */
async function fulfillReceipt(env, cb, buyerId, plan, ts) {
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
  // Profile: reuse the buyer's account, or mint one on the spot.
  const ident = await tgIdentity(env, buyerId);
  const prior = await env.DB.prepare(
    'SELECT username FROM accounts WHERE telegram_user_id = ? ORDER BY created_at DESC LIMIT 1',
  ).bind(buyerId).first();
  let username = prior ? prior.username : null;
  let password = null;
  if (username) {
    password = null;  // hashed — never re-shown; the buyer keeps theirs
  } else {
    password = randomPassword();
    username = await insertAccount(env, {
      plan, days: planDays(plan), password, tgId: buyerId,
      tgName: ident.name, tgUser: ident.username, note: null,
    });
  }
  const lines = [
    '🍯 <b>پرداخت شما تأیید شد!</b>',
    `پلن: <b>${esc(((await getPrices(env))[plan] || {}).title || plan)}</b>`,
    '',
  ];
  if (username) {
    lines.push(`👤 <b>پروفایل تو:</b> <code>${esc(username)}</code>`);
    if (password) {
      lines.push(`🔑 <b>رمز عبور:</b> <code>${esc(password)}</code>`);
    } else {
      lines.push('(رمز قبلی‌ات بدون تغییر کار می‌کند)');
    }
    lines.push('');
  }
  lines.push(
    '🎫 <b>لایسنس برنامه (کندو قفله):</b>',
    `<code>${esc(code)}</code>`,
    '',
    'اپ BEE-VPN را باز کن → «کد فعال‌سازی» → این کد را وارد کن.',
    '⏱ اشتراک از لحظهٔ اولین فعال‌سازی در اپ شروع می‌شود.',
    '👤 پروفایل‌ات در ربات: «👤 پروفایل من» — بعد از انقضا هم از همان‌جا می‌توانی بخری و تمدید کنی.',
  );
  const delivered = await send(env, buyerId, lines.join('\n'));
  if (!(delivered && delivered.ok)) {
    // Buyer probably blocked the bot: the entitlements exist but would be
    // lost — hand them to the admin instead of claiming delivery.
    await tg(env, 'editMessageText', {
      chat_id: chatId, message_id: messageId,
      text: '⚠️ تأیید شد ولی <b>ارسال به خریدار ناموفق بود</b> (احتمالاً ربات را بلاک کرده است).\nدستی برایش بفرست:\n'
        + (username ? `👤 <code>${esc(username)}</code>${password ? `\n🔑 <code>${esc(password)}</code>` : ''}\n` : '')
        + `🎫 <code>${esc(code)}</code>`,
    }).catch(() => {});
    return answerCb(env, cb.id, 'صادر شد — ارسال ناموفق!');
  }
  await tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId,
    text: `✅ تأیید شد — لایسنس <code>${esc(code)}</code>${username ? ` و پروفایل <code>${esc(username)}</code>` : ''} برای خریدار (id <code>${buyerId}</code>) ارسال شد.`,
  }).catch(() => {});
  // درخواست اپراتور: کارت کامل ثبت‌نام/خرید تأییدشده برای همهٔ ادمین‌ها.
  await notifyAdmins(env, adminRegistrationText({
    heading: '🍯 <b>خرید تأیید شد</b> — ثبت‌نام/تمدید جدید',
    tgId: buyerId,
    tgName: ident.name,
    tgUser: ident.username,
    username,
    password,
    code,
    planTitle: ((await getPrices(env))[plan] || {}).title || plan,
  }) + `\n✅ تأییدکننده: <code>${cb.from.id}</code>`);
  return answerCb(env, cb.id, 'لایسنس صادر و ارسال شد');
}

async function approveReceipt(env, cb, buyerId, plan, ts) {
  return fulfillReceipt(env, cb, buyerId, plan, ts);
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
// گیت ثبت‌نام — «🎁 کد تست ۱ روزه» و «🛍 خرید اشتراک» فقط برای ثبت‌نامی‌ها باز
// است (درخواست اپراتور: دکمه‌ها برای کسانی که ثبت‌نام نکرده‌اند باز نباشد).
// ثبت‌نام‌شده = صاحب حداقل یک حساب در جدول accounts. ادمین همیشه رد می‌شود
// (تستِ جریان‌ها و خرید برای دیگران) و رسیدِ ثبت‌نامِ وسطِ جریان هم از گیت عبور
// می‌کند چون pending دارد — گیت فقط روی تپِ دکمه و شاخهٔ «عکس بدون pending»
// اعمال شده است، نه روی خودِ رسیدها.
// ---------------------------------------------------------------------------

/** آیا این شناسهٔ تلگرام حداقل یک‌بار 📝 ثبت‌نام را کامل کرده؟ */
async function isRegistered(env, tgId) {
  try {
    const row = await env.DB.prepare(
      'SELECT username FROM accounts WHERE telegram_user_id = ? LIMIT 1',
    ).bind(tgId).first();
    return !!(row && row.username);
  } catch (_) { return false; }  // جدول accounts نیست — محافظه‌کارانه: قفل
}

/** خودِ گیت: true = رد شد (ادمین یا ثبت‌نامی). false = پیامِ «اول ثبت‌نام» رفت
 *  و جریان باید همان‌جا تمام شود. cbId اختیاری است (تپِ دکمه → توست تلگرام). */
async function regGate(env, chatId, from, cbId, what) {
  if (isAdmin(env, from) || (await isRegistered(env, from.id))) return true;
  await send(env, chatId, [
    `🔒 <b>اول ثبت‌نام، بعد ${esc(what || 'ادامه')}!</b>`,
    '',
    'این بخش فقط برای کاربران ثبت‌نام‌شدهٔ کندو باز است.',
    'با 📝 ثبت‌نام کن — نام کاربری و رمزت همان‌جا به‌ تو می‌رسد،',
    'و بعد از آن 🎁 کد تست ۱ روزه و 🛍 خرید اشتراک برایت باز می‌شود.',
  ].join('\n'), { inline_keyboard: [
    [{ text: '📝 ثبت‌نام (نام کاربری و رمز)', callback_data: 'reg' }],
    [{ text: '🔙 منوی اصلی', callback_data: 'menu' }],
  ] });
  if (cbId) await answerCb(env, cbId, 'اول ثبت‌نام کن 📝');
  return false;
}

// ---------------------------------------------------------------------------
// ثبت‌نام (self-registration) — the buyer taps 📝 in the menu and gets a full
// package: profile account (username/password) + a license code for the app.
// The 1-day trial is instant (one per Telegram id, counted across BOTH
// kinds); paid plans ride the same receipt flow as 🛍 خرید and BOTH approval
// buttons (ok:/oka:) land on the same unified fulfillReceipt().
// ---------------------------------------------------------------------------

const USERNAME_RE = /^[a-z0-9_.-]{3,32}$/;

function randomUsername() {
  const alphabet = '23456789abcdefghjkmnpqrstvwxyz';
  const buf = new Uint8Array(7);
  crypto.getRandomValues(buf);
  let s = 'bee';
  for (const b of buf) s += alphabet[b % alphabet.length];
  return s;  // «bee» + 7 chars — collides astronomically rarely, and INSERT retries
}

/** Telegram display name + @handle for a numeric id (best effort — the
 *  receipt approval callback only carries the id). */
async function tgIdentity(env, uid) {
  try {
    const r = await tg(env, 'getChat', { chat_id: uid });
    if (r && r.ok && r.result) {
      const name = [r.result.first_name, r.result.last_name].filter(Boolean).join(' ').trim();
      return { name: name || null, username: r.result.username || null };
    }
  } catch (_) { /* blocked bots and rate limits both land here */ }
  return { name: null, username: null };
}

/** INSERT one account; retries on the (astronomically unlikely) username
 *  collision. Returns the username, or null when every attempt collided. */
async function insertAccount(env, { plan, days, password, tgId, tgName, tgUser, note }) {
  const saltBytes = new Uint8Array(16);
  crypto.getRandomValues(saltBytes);
  const salt = b64urlEncode(saltBytes);
  const hash = await pbkdf2Hash(password, salt);
  for (let i = 0; i < 5; i++) {
    const username = randomUsername();
    try {
      await env.DB.prepare(
        'INSERT INTO accounts (username, pass_hash, pass_salt, plan, status, duration_days, '
        + 'telegram_user_id, telegram_name, telegram_username, note, created_at) '
        + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      ).bind(username, hash, salt, plan, 'active', days, tgId || null, tgName || null,
        tgUser || null, note || null, nowSec()).run();
      return username;
    } catch (e) {
      if (String((e && e.message) || e).toLowerCase().includes('unique')) continue;
      throw e;
    }
  }
  return null;
}

function credentialsText(title, username, password, extra) {
  return [
    `📝 <b>${esc(title)}</b>`,
    '',
    `👤 نام کاربری: <code>${esc(username)}</code>`,
    ...(password ? [`🔑 رمز عبور: <code>${esc(password)}</code>`] : []),
    '',
    ...extra,
    '',
    '👤 این نام کاربری/رمز، پروفایل توست — «👤 پروفایل من» در همین ربات.',
    '⏱ اشتراک از لحظهٔ اولین فعال‌سازی لایسنس در اپ شروع می‌شود، نه از لحظهٔ خرید.',
    '⚠️ هر لایسنس فقط روی یک دستگاه فعال می‌شود.',
  ].join('\n');
}

/** 🔔 کارت کامل ثبت‌نام برای ادمین‌ها (درخواست اپراتور): با هر «تست ۱ روزه» یا
 *  ثبت‌نام/خرید تأییدشده، همهٔ اطلاعات ثبت‌نام‌کننده — نام و آیدی تلگرام، نام
 *  کاربری حساب، رمز (فقط لحظهٔ ساخت؛ بعدش فقط hash می‌ماند) و لایسنس — به همهٔ
 *  ادمین‌ها می‌رود. ارسالش best-effort است (مثل notifyAdmins) و هیچ‌وقت جریان
 *  اصلیِ خریدار را نبند. */
function adminRegistrationText({ heading, tgId, tgName, tgUser, username, password, code, planTitle }) {
  return [
    heading,
    '',
    `👤 <b>ثبت‌نام‌کننده:</b> <a href="tg://user?id=${tgId}">${esc(tgName || 'بدون نام')}</a>`,
    `🆔 شناسهٔ تلگرام: <code>${tgId}</code>`,
    `🔖 یوزرنیم تلگرام: ${tgUser ? '@' + esc(tgUser) : '—'}`,
    '',
    `👤 <b>نام کاربری حساب:</b> <code>${esc(username)}</code>`,
    ...(password ? [`🔑 <b>رمز عبور:</b> <code>${esc(password)}</code> — فقط همین‌جا نشان داده می‌شود؛ بعد از این فقط hash ذخیره است.`] : []),
    ...(code ? [`🎫 <b>لایسنس برنامه:</b> <code>${esc(code)}</code>`] : []),
    `📦 <b>پلن:</b> ${esc(planTitle)}`,
    `🕒 زمان ثبت: ${esc(faJalaliTime(nowSec()))}`,
  ].join('\n');
}

/** 📝 ثبت‌نام — the intro: what they get, then the plan keyboard. */
async function regIntro(env, chatId) {
  return send(env, chatId, [
    '📝 <b>ثبت‌نام در کندو</b>',
    '',
    'به‌جای کد فعال‌سازی، یک <b>حساب کاربری</b> می‌گیری:',
    '👤 نام کاربری + 🔑 رمز عبور — همین‌جا در همین چت.',
    '',
    'پلن را انتخاب کن 👇',
    '🎁 تست ۱ روزه همان لحظه صادر می‌شود؛',
    'پلن‌های خریدنی بعد از تأیید رسیدِ پرداخت ساخته می‌شوند.',
  ].join('\n'), await regPlanKeyboard(env));
}

async function regPlanKeyboard(env) {
  const list = await getPrices(env);
  const rows = [[{ text: '🎁 تست ۱ روزه — رایگان', callback_data: 'regplan:trial' }]];
  for (const [plan, info] of Object.entries(list)) {
    rows.push([{ text: `${info.title} — ${info.price}`, callback_data: `regplan:${plan}` }]);
  }
  rows.push([{ text: '🔙 منوی اصلی', callback_data: 'menu' }]);
  return { inline_keyboard: rows };
}

/** regplan:trial — the free 1-day trial as an ACCOUNT. One per Telegram id,
 *  counted across BOTH kinds: an old trial CODE already taken blocks this. */
async function giveTrialAccount(env, chatId, from) {
  const tgId = from.id;
  const prior = await env.DB.prepare(
    // One prior trial row across BOTH entitlement kinds — a trial CODE taken
    // long ago still closes the free ثبت‌نام. (SQLite forbids parenthesized
    // compound arms, hence the FROM-subquery form.)
    "SELECT x FROM (SELECT code AS x FROM licenses WHERE telegram_user_id = ? AND plan = 'trial' LIMIT 1) "
    + "UNION ALL SELECT x FROM (SELECT username AS x FROM accounts WHERE telegram_user_id = ? AND plan = 'trial' LIMIT 1) "
    + 'LIMIT 1',
  ).bind(tgId, tgId).all();
  const prev = (prior.results || [])[0];
  if (prev) {
    return send(env, chatId, [
      '🎁 قبلاً تست یک‌روزه‌ات را برداشتی:',
      prev.x.startsWith('BEE-') ? `🔑 <code>${esc(prev.x)}</code>` : `👤 <code>${esc(prev.x)}</code>`,
      '',
      'برای ادامهٔ اشتراک برو سراغ 🛍 خرید یا 📝 ثبت‌نام.',
    ].join('\n'));
  }
  const password = randomPassword();
  const username = await insertAccount(env, {
    plan: 'trial', days: 1, password, tgId,
    tgName: [from.first_name, from.last_name].filter(Boolean).join(' ').trim() || null,
    tgUser: from.username || null,
    note: `trial:${tgId}`,
  });
  if (!username) return send(env, chatId, '⚠️ ساخت حساب ناموفق بود — یک‌بار دیگر تلاش کن.');
  // The 1-day license rides along: username/password open the PROFILE, the
  // license code is what unlocks the APP (کندو قفله).
  const licCode = randomCode();
  await env.DB.prepare(
    'INSERT INTO licenses (code, plan, status, duration_days, note, telegram_user_id, created_at) '
    + 'VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(licCode, 'trial', 'active', 1, `trial:${tgId}`, tgId, nowSec()).run();
  // اول خریدار (حساس به تأخیر)، بعد کارت ادمین‌ها.
  const reply = await send(env, chatId, credentialsText('تست ۱ روزهٔ تو ساخته شد!', username, password, [
    '🎁 پلن: تست ۱ روزه (رایگان)',
    '',
    '🎫 <b>لایسنس برنامه (کندو قفله):</b>',
    `<code>${esc(licCode)}</code>`,
    'اپ را باز کن → «کد فعال‌سازی» → این کد را وارد کن.',
    '⏱ ۲۴ ساعت از لحظهٔ فعال‌سازی در اپ شروع می‌شود.',
  ]));
  // درخواست اپراتور: هر تست ۱ روزه با همهٔ اطلاعاتِ گیرنده به ادمین‌ها خبر داده شود.
  await notifyAdmins(env, adminRegistrationText({
    heading: '🎁 <b>تست ۱ روزه گرفته شد</b> — ثبت‌نام رایگان جدید',
    tgId,
    tgName: [from.first_name, from.last_name].filter(Boolean).join(' ').trim() || null,
    tgUser: from.username || null,
    username,
    password,
    code: licCode,
    planTitle: 'تست ۱ روزه (رایگان)',
  }));
  return reply;
}

/** regplan:<paid> — same payment rails as 🛍 خرید; the pending row is stamped
 *  kind='account' so the ✅ approval builds an account. */
async function regPlanPaid(env, chatId, from, plan) {
  if (!planDays(plan)) return send(env, chatId, 'پلن نامعتبر');
  await setPending(env, from.id, plan, 'account');
  await notifyAdmins(env,
    `📝 ثبت‌نام: ${buyerLine(from)} پلن <b>${esc(((await getPrices(env))[plan] || {}).title || plan)}</b>`
    + ' را برای ساخت <b>حساب کاربری</b> انتخاب کرد.\nاسکرین‌شاتش که رسید را با دکمهٔ ✅ تأیید کن —'
    + ' نام کاربری و رمز خودکار برایش ارسال می‌شود.');
  return send(env, chatId, await regPaymentText(env, plan), {
    inline_keyboard: [[{ text: '🔙 بازگشت', callback_data: 'reg' }]],
  });
}

async function regPaymentText(env, plan) {
  const info = (await getPrices(env))[plan] || { title: plan, price: '?' };
  const pay = await getPayInfo(env);
  const card = pay.card;
  const usdt = pay.usdt;
  return [
    `📝 <b>ثبت‌نام ${esc(info.title)}</b> — ${esc(info.price)}`,
    '',
    '💳 <b>پرداخت ریالی (کارت به کارت):</b>',
    `<code>${esc(card)}</code>`,
    '',
    '💎 <b>پرداخت تتر (USDT):</b>',
    `<code>${esc(usdt)}</code>`,
    '',
    '📌 بعد از پرداخت، <b>عکسِ رسید را مستقیم و بدون هیچ کامندی همین‌جا بفرست</b> (اسکرین‌شات).',
    '✅ ادمین تأیید می‌کند و <b>نام کاربری و رمز عبور</b> تو همین‌جا ارسال می‌شود.',
    '⏱ اشتراک از لحظهٔ اولین ورود در اپ شروع می‌شود، نه از لحظهٔ ثبت‌نام.',
  ].join('\n');
}

/** Admin tapped ✅ on a ثبت‌نام receipt — same unified fulfillment as 🛍:
 *  profile account + license code, both delivered (see fulfillReceipt). */
async function approveAccountReceipt(env, cb, buyerId, plan, ts) {
  return fulfillReceipt(env, cb, buyerId, plan, ts);
}

// ---------------------------------------------------------------------------
// Admin panel — a button-driven cockpit over every admin command. Callbacks
// stay short (adm:…) and every one of them is re-checked against ADMIN_IDS in
// the router before this code runs.
//
//   pages      adm:buyers:<p> · adm:list:<p> · adm:regs:<p>
//   guided     adm:gen (→ adm:gen:<plan> → adm:genc:<plan>:<n>)
//              adm:au   (username → password → adm:au:<plan>)
//              adm:search (next plain message is the query)
//   cards      adm:vw:<K>:<id> with one-tap adm:ext/adm:st/adm:rst
// ---------------------------------------------------------------------------

const PAGE_SIZE = 10;
const REG_PAGE_SIZE = 8;

const ADMIN_HELP = [
  '🛠 <b>فرمان‌های ادمین</b> (همه در پنل دکمه‌ای هم هستند)',
  '🛖 رسید خرید: عکسِ مشتری خودکار برای تو می‌آید — فقط دکمهٔ <b>✅ تأیید</b> را بزن؛ کد یا حساب (رسید ثبت‌نام) صادر و خودکار برای خریدار ارسال می‌شود (❌ = رد).',
  '<code>/gen m3 5 تخفیف دوست</code> — ساخت کد دستی (تا ۵۰)',
  '<code>/adduser user pass m1</code> — ساخت حساب دستی',
  '<code>/list 10</code> • <code>/who BEE-…|username|tg-id</code>',
  '<code>/regs [صفحه]</code> — فهرست ثبت‌نامی‌ها با لینک تلگرام',
  '<code>/revoke …</code> • <code>/unrevoke …</code>',
  '<code>/extend … 30</code> • <code>/reset …</code>',
  '<code>/stats</code> — آمار کل + فروش ۷/۳۰ روز + سهم پلن‌ها',
  '<code>/buyers</code> — لیست خریداران با تعداد خرید',
  '<code>/price</code> — پنل دکمه‌ای قیمت‌ها',
  '<code>/card …</code> • <code>/usdt …</code> — تغییر شماره کارت/آدرس تتر بدون استقرار (clear = بازگشت)',
  '<code>/panel</code> — پنل مدیریت کامل',
  '<code>/diag</code> — عیب‌یابی: وب‌هوک، قابل‌ارسال‌بودن ادمین‌ها، رسیدهای در انتظار',
].join('\n');

function adminMenuKb() {
  return { inline_keyboard: [
    [{ text: '📊 داشبورد و آمار', callback_data: 'adm:stats' }, { text: '🧾 رسیدهای در انتظار', callback_data: 'adm:pend' }],
    [{ text: '🎫 ساخت کد فعال‌سازی', callback_data: 'adm:gen' }, { text: '👤 ساخت حساب کاربری', callback_data: 'adm:au' }],
    [{ text: '👥 خریداران', callback_data: 'adm:buyers:1' }, { text: '📝 ثبت‌نامی‌ها', callback_data: 'adm:regs:1' }],
    [{ text: '🗒 آخرین کدها', callback_data: 'adm:list:1' }, { text: '🔍 جستجو و مدیریت', callback_data: 'adm:search' }],
    [{ text: '💰 قیمت‌ها', callback_data: 'price_menu' }, { text: '🩺 ابزار و عیب‌یابی', callback_data: 'adm:tools' }],
    [{ text: '🔙 منوی اصلی', callback_data: 'menu' }],
  ] };
}

function toolsKb() {
  return { inline_keyboard: [
    [{ text: '🩺 اجرای /diag', callback_data: 'adm:diag' }],
    [{ text: '📊 آمار کندو', callback_data: 'adm:stats' }, { text: '🛠 فرمان‌ها', callback_data: 'adm:help' }],
    [{ text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
}

function genPlanKb() {
  return { inline_keyboard: [
    [{ text: '🎁 تست ۱ روزه', callback_data: 'adm:gen:trial' }, { text: '📅 یک‌ماهه m1', callback_data: 'adm:gen:m1' }],
    [{ text: '📅 سه‌ماهه m3', callback_data: 'adm:gen:m3' }, { text: '📅 شش‌ماهه m6', callback_data: 'adm:gen:m6' }],
    [{ text: '📅 سالانه m12', callback_data: 'adm:gen:m12' }],
    [{ text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
}

function genCountKb(plan) {
  return { inline_keyboard: [
    [{ text: '۱ کد', callback_data: `adm:genc:${plan}:1` }, { text: '۵ کد', callback_data: `adm:genc:${plan}:5` }, { text: '۱۰ کد', callback_data: `adm:genc:${plan}:10` }],
    [{ text: '۲۵ کد', callback_data: `adm:genc:${plan}:25` }, { text: '۵۰ کد', callback_data: `adm:genc:${plan}:50` }],
    [{ text: '🔙 انتخاب پلن', callback_data: 'adm:gen' }, { text: '🔙 پنل مدیریت', callback_data: 'adm' }],
  ] };
}

function pagerKb(prefix, page, totalPages) {
  const row = [];
  if (page > 1) row.push({ text: '◀️', callback_data: `${prefix}:${page - 1}` });
  row.push({ text: `${faNum(page)}/${faNum(totalPages)}`, callback_data: 'adm:noop' });
  if (page < totalPages) row.push({ text: '▶️', callback_data: `${prefix}:${page + 1}` });
  row.push({ text: '🔙 پنل', callback_data: 'adm' });
  return { inline_keyboard: [row] };
}

/** Buyers, one page at a time (the /buyers command keeps its own rollup). */
async function buyersPage(env, pageRaw) {
  const rows = await env.DB.prepare(
    `SELECT telegram_user_id AS uid, COUNT(*) AS n,
            SUM(CASE WHEN expires_at > ? THEN 1 ELSE 0 END) AS live,
            SUM(CASE WHEN status = 'revoked' THEN 1 ELSE 0 END) AS banned,
            MAX(created_at) AS last
     FROM licenses WHERE telegram_user_id IS NOT NULL AND plan != 'trial'
     GROUP BY telegram_user_id ORDER BY n DESC, last DESC LIMIT 200`,
  ).bind(nowSec()).all();
  const list = rows.results || [];
  if (!list.length) {
    return {
      text: '👥 هنوز خریدی از طریق ربات ثبت نشده — بعد از اولین تأییدِ رسید، خریداران این‌جا لیست می‌شوند.',
      kb: adminMenuKb(),
    };
  }
  const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
  const p = Math.min(Math.max(1, pageRaw || 1), totalPages);
  const now = nowSec();
  const lines = [`👥 <b>خریداران</b> — ${faNum(list.length)} نفر (صفحهٔ ${faNum(p)} از ${faNum(totalPages)}):`, ''];
  const slice = list.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE);
  for (const r of slice) {
    lines.push(
      `<a href="tg://user?id=${r.uid}">${r.uid}</a> — ${faNum(r.n)} خرید • ${faNum(r.live)} در جریان`
      + (r.banned ? ` • ⛔ ${faNum(r.banned)}` : '') + ` • ${faJalali(r.last)}`,
    );
  }
  lines.push('', '🔎 تاریخچهٔ کامل: 🔍 جستجو ← شناسهٔ عددی را بفرست');
  const kb = pagerKb('adm:buyers', p, totalPages);
  for (const r of slice.slice(0, 6)) {
    kb.inline_keyboard.unshift([{ text: `👤 ${r.uid} — ${faNum(r.n)} خرید`, callback_data: `adm:buyid:${r.uid}` }]);
  }
  return { text: lines.join('\n'), kb };
}

/** Latest codes AND accounts, one page at a time. */
async function listPage(env, pageRaw) {
  const lic = await env.DB.prepare(
    'SELECT code AS subject, plan, status, expires_at, created_at FROM licenses ORDER BY created_at DESC LIMIT 200',
  ).all();
  const acc = await env.DB.prepare(
    'SELECT username AS subject, plan, status, expires_at, created_at FROM accounts ORDER BY created_at DESC LIMIT 200',
  ).all();
  const list = [...(lic.results || []), ...(acc.results || [])].sort((a, b) => b.created_at - a.created_at);
  if (!list.length) return { text: '🗒 هنوز کدی یا حسابی ساخته نشده است.', kb: adminMenuKb() };
  const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
  const p = Math.min(Math.max(1, pageRaw || 1), totalPages);
  const now = nowSec();
  const lines = [`🗒 <b>آخرین کدها و حساب‌ها</b> — ${faNum(list.length)} ردیف (صفحهٔ ${faNum(p)} از ${faNum(totalPages)}):`, ''];
  for (const r of list.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE)) {
    const isAcc = !/^BEE-/.test(r.subject);
    const live = r.expires_at ? (r.expires_at > now ? '🟢' : '🔴') : '⏳';
    lines.push(`${live} ${isAcc ? '👤' : '🔑'} <code>${esc(r.subject)}</code> ${esc(r.plan)} ${r.status === 'active' ? '' : '⛔'} — ${faJalali(r.created_at)}`);
  }
  lines.push('', '🔎 مدیریت یک مورد: 🔍 جستجو ← کد یا نام کاربری را بفرست');
  return { text: lines.join('\n'), kb: pagerKb('adm:list', p, totalPages) };
}

/** 📝 ثبت‌نامی‌ها — every account with its Telegram identity: the registered
 *  name as a live link into the user's chat, the numeric id, the issued
 *  username, plan/status, Jalali registration and expiry dates, device
 *  binding. This is the operator's customer-service cockpit. */
async function regsPage(env, pageRaw) {
  const rows = await env.DB.prepare(
    'SELECT username, plan, status, duration_days, telegram_user_id, telegram_name, telegram_username, '
    + 'bound_install_id, activated_at, expires_at, created_at FROM accounts ORDER BY created_at DESC LIMIT 200',
  ).all();
  const list = rows.results || [];
  if (!list.length) {
    return {
      text: '📝 هنوز ثبت‌نامی ثبت نشده.\n\nوقتی کسی در ربات «📝 ثبت‌نام» را بزند، همین‌جا با نامِ لینک‌شدهٔ تلگرام، حساب صادرشده و زمان‌ها لیست می‌شود.',
      kb: adminMenuKb(),
    };
  }
  const totalPages = Math.max(1, Math.ceil(list.length / REG_PAGE_SIZE));
  const p = Math.min(Math.max(1, pageRaw || 1), totalPages);
  const now = nowSec();
  const live = list.filter((r) => r.status === 'active' && r.expires_at && r.expires_at > now).length;
  const linked = list.filter((r) => r.telegram_user_id).length;
  const lines = [
    `📝 <b>ثبت‌نامی‌ها</b> — ${faNum(list.length)} حساب`,
    `🟢 در جریان: ${faNum(live)} — 📱 فعال‌شده: ${faNum(list.filter((r) => r.bound_install_id).length)} — 🔗 تلگرامی: ${faNum(linked)}`,
    `صفحهٔ ${faNum(p)} از ${faNum(totalPages)}`,
    '',
  ];
  const kbRows = [];
  for (const r of list.slice((p - 1) * REG_PAGE_SIZE, p * REG_PAGE_SIZE)) {
    const st = subStatus(r, now);
    const left = daysLeftOf(r, now);
    const who = r.telegram_user_id
      ? `<a href="tg://user?id=${r.telegram_user_id}">${esc(r.telegram_name || ('@' + (r.telegram_username || r.telegram_user_id)))}</a>`
        + (r.telegram_name && r.telegram_username ? ` (@${esc(r.telegram_username)})` : '')
        + ` — <code>${r.telegram_user_id}</code>`
      : 'بدون تلگرام';
    lines.push(`👤 ${who}`);
    lines.push(`   🔑 <code>${esc(r.username)}</code> — ${esc(r.plan)} — ${st}`
      + (r.expires_at ? ` — پایان: ${faJalali(r.expires_at)} (${faNum(left)} روز)` : ` — ${faNum(left)} روز از اولین ورود`));
    lines.push(`   🕒 ثبت‌نام: ${faJalaliTime(r.created_at)}`
      + (r.activated_at ? ` — فعال‌سازی: ${faJalali(r.activated_at)}` : '')
      + (r.bound_install_id ? ' — 📱 متصل' : ''));
    lines.push('');
    const btnLabel = `⚙️ ${r.username}${r.telegram_name && r.username.length + r.telegram_name.length < 32 ? ' — ' + r.telegram_name : ''}`;
    kbRows.push([{ text: btnLabel, callback_data: `adm:vw:A:${r.username}` }]);
  }
  const kb = pagerKb('adm:regs', p, totalPages);
  kb.inline_keyboard = [...kbRows, ...kb.inline_keyboard];
  return { text: lines.join('\n'), kb };
}

/** Pending plan choices: who is about to send a receipt for which plan. */
async function pendView(env) {
  const back = { inline_keyboard: [[{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]] };
  try {
    const pend = await env.DB.prepare('SELECT tg_user_id, plan, ts, kind FROM pending_receipts ORDER BY ts DESC').all();
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
      const kindTag = r.kind === 'account' ? '📝 حساب' : '🔑 کد';
      lines.push(`• <a href="tg://user?id=${r.tg_user_id}">${r.tg_user_id}</a> → ${esc(title)} (${kindTag}) — ${faNum(mins)} دقیقه پیش${stale}`);
      kbRows.push([{ text: `🗑 پاک کردن انتخاب ${r.tg_user_id}`, callback_data: `pendx:${r.tg_user_id}` }]);
    }
    kbRows.push([{ text: '🔙 پنل مدیریت', callback_data: 'adm' }]);
    return { text: lines.join('\n'), kb: { inline_keyboard: kbRows } };
  } catch (_) {
    return { text: '🧾 جدول pending_receipts در دسترس نیست ❌', kb: back };
  }
}

/** Apply one card action (extend / status / reset), then re-render the card
 *  so the admin immediately sees the new state. */
async function adminItemAction(env, kind, id, op, extra) {
  const key = kind === 'L' ? String(id).toUpperCase() : String(id).toLowerCase();
  const found = await lookup(env, key);
  if (!found) return { text: '❌ پیدا نشد.', kb: adminMenuKb() };
  const r = found.row;
  const table = found.kind === 'license' ? 'licenses' : 'accounts';
  const column = found.kind === 'license' ? 'code' : 'username';
  if (op === 'ext') {
    const days = parseInt(extra, 10) || 0;
    if (days) {
      if (!r.expires_at) {
        await env.DB.prepare(`UPDATE ${table} SET duration_days = duration_days + ? WHERE ${column} = ?`).bind(days, r[column]).run();
      } else {
        const base = Math.max(nowSec(), r.expires_at);
        await env.DB.prepare(`UPDATE ${table} SET expires_at = ? WHERE ${column} = ?`).bind(base + days * 86400, r[column]).run();
      }
    }
  } else if (op === 'st') {
    await env.DB.prepare(`UPDATE ${table} SET status = ? WHERE ${column} = ?`)
      .bind(extra === 'active' ? 'active' : 'revoked', r[column]).run();
  } else if (op === 'rst') {
    await env.DB.prepare(`UPDATE ${table} SET bound_install_id = NULL, activated_at = NULL, expires_at = NULL WHERE ${column} = ?`)
      .bind(r[column]).run();
  }
  return whoView(env, key);
}

/** 👤 ساخت حساب — guided: username → password → plan. No syntax to remember. */
async function adduserIntro(env, cb) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  await setAdminState(env, cb.from.id, { mode: 'adduser', step: 'username' });
  await tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId,
    text: [
      '👤 <b>ساخت حساب کاربری</b> — مرحلهٔ ۱ از ۳',
      'نام کاربری را بفرست (۳ تا ۳۲ کاراکتر از a-z0-9_.-):',
      '(لغو: <code>/cancel</code>)',
    ].join('\n'),
    parse_mode: 'HTML', reply_markup: adminMenuKb(),
  }).catch(() => {});
  return answerCb(env, cb.id);
}

/** The admin's plain message while an adduser flow is pending. */
async function adduserStep(env, chatId, adminId, st, text) {
  if (st.step === 'username') {
    const username = text.trim().toLowerCase();
    if (!USERNAME_RE.test(username)) {
      await send(env, chatId, '❌ نام کاربری نامعتبر — ۳ تا ۳۲ کاراکتر از a-z0-9_.- — دوباره بفرست:');
      return;  // keep the state so the next message retries
    }
    await setAdminState(env, adminId, { mode: 'adduser', step: 'password', username });
    await send(env, chatId, `👤 نام کاربری: <b>${esc(username)}</b>\n🔑 مرحلهٔ ۲ از ۳ — رمز عبور را بفرست (حداقل ۶ کاراکتر):`);
    return;
  }
  if (st.step === 'password') {
    const password = text.trim();
    if (password.length < 6) {
      await send(env, chatId, '❌ رمز کوتاه است — حداقل ۶ کاراکتر، دوباره بفرست:');
      return;
    }
    await setAdminState(env, adminId, { mode: 'adduser', step: 'plan', username: st.username, password });
    await send(env, chatId, '🔑 رمز ثبت شد — مرحلهٔ ۳ از ۳، پلن را انتخاب کن:', {
      inline_keyboard: [
        ['m1', 'm3', 'm6', 'm12'].map((p) => ({ text: p, callback_data: `adm:au:${p}` })),
      ],
    });
    return;
  }
  await clearAdminState(env, adminId);
  await send(env, chatId, '↩️ جریان ساخت حساب لغو شد.');
}

/** The admin's plain message while a search is pending. */
async function searchStep(env, chatId, adminId, text) {
  await clearAdminState(env, adminId);
  const q = text.trim();
  if (/^\d{3,}$/.test(q)) {
    const v = await whoBuyerView(env, parseInt(q, 10));
    return send(env, chatId, v.text, v.kb);
  }
  const card = await whoView(env, q);
  return send(env, chatId, card.text, card.kb);
}

/** The admin's next plain message while a guided flow is armed. */
async function runAdminText(env, chatId, adminId, st, text) {
  if (st.mode === 'search') return searchStep(env, chatId, adminId, text);
  if (st.mode === 'adduser') return adduserStep(env, chatId, adminId, st, text);
}

async function handleAdminCb(env, cb, data) {
  const chatId = cb.message.chat.id;
  const messageId = cb.message.message_id;
  const edit = (text, kb) => tg(env, 'editMessageText', {
    chat_id: chatId, message_id: messageId, text, parse_mode: 'HTML', reply_markup: kb,
    disable_web_page_preview: true,
  }).catch(() => {});

  if (data === 'adm' || data === 'adm:panel') {
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
    await edit(await statsText(env), adminMenuKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:pend') {
    const v = await pendView(env);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data === 'adm:tools') {
    await edit('🩺 <b>ابزار و عیب‌یابی</b>', toolsKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:help') {
    await edit(ADMIN_HELP, toolsKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:diag') {
    await answerCb(env, cb.id, 'در حال بررسی…');
    await cmdDiag(env, chatId);
    return;
  }
  if (data === 'adm:gen') {
    await edit('➕ <b>ساخت کد فعال‌سازی</b>\nپلن را انتخاب کن:', genPlanKb());
    return answerCb(env, cb.id);
  }
  if (data.startsWith('adm:gen:')) {
    const plan = data.slice(8);
    if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
    await edit(`➕ ساخت کد <b>${esc(plan)}</b> — چند کد؟`, genCountKb(plan));
    return answerCb(env, cb.id);
  }
  if (data.startsWith('adm:genc:')) {
    const [, , plan, countRaw] = data.split(':');   // adm:genc:<plan>:<count>
    const count = Math.min(Math.max(parseInt(countRaw || '1', 10) || 1, 1), 50);
    if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
    await answerCb(env, cb.id, 'در حال ساخت…');
    await genCore(env, chatId, plan, count, null, null);
    await edit(`✅ ${faNum(count)} کد <b>${esc(plan)}</b> ساخته و در پیام بالا ارسال شد.`, adminMenuKb());
    return;
  }
  if (data === 'adm:au') return adduserIntro(env, cb);
  if (data.startsWith('adm:au:')) {
    const plan = data.slice(7);
    if (!planDays(plan)) return answerCb(env, cb.id, 'پلن نامعتبر');
    const st = await getAdminState(env, cb.from.id);
    if (!st || st.mode !== 'adduser' || !st.username || !st.password) {
      await edit('⚠️ جریان ساخت حساب منقضی شده — دوباره «👤 ساخت حساب کاربری» را بزن.', adminMenuKb());
      return answerCb(env, cb.id, 'منقضی');
    }
    await clearAdminState(env, cb.from.id);
    const saltBytes = new Uint8Array(16);
    crypto.getRandomValues(saltBytes);
    const salt = b64urlEncode(saltBytes);
    const hash = await pbkdf2Hash(st.password, salt);
    try {
      await env.DB.prepare(
        'INSERT INTO accounts (username, pass_hash, pass_salt, plan, status, duration_days, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      ).bind(st.username, hash, salt, plan, 'active', planDays(plan), nowSec()).run();
    } catch (e) {
      await edit(`⚠️ ساخت حساب ناموفق بود (احتمالاً تکراری است): ${esc(String((e && e.message) || e))}`, adminMenuKb());
      return answerCb(env, cb.id, 'ناموفق');
    }
    await edit(`👤 حساب <b>${esc(st.username)}</b> با پلن <b>${esc(plan)}</b> ساخته شد.\nرمز را همین حالا بردار (فقط hash ذخیره می‌شود):\n🔑 <code>${esc(st.password)}</code>`, adminMenuKb());
    return answerCb(env, cb.id, 'ساخته شد');
  }
  if (data === 'adm:search') {
    await setAdminState(env, cb.from.id, { mode: 'search' });
    await edit('🔍 <b>جستجو و مدیریت</b>\nکد (<code>BEE-…</code>)، نام کاربری یا شناسهٔ عددی تلگرام را بفرست.\nلغو: <code>/cancel</code>', adminMenuKb());
    return answerCb(env, cb.id);
  }
  if (data === 'adm:buyers' || data.startsWith('adm:buyers:')) {
    const p = parseInt(data.split(':')[2] || '1', 10) || 1;
    const v = await buyersPage(env, p);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data.startsWith('adm:buyid:')) {
    const uid = parseInt(data.slice(10), 10);
    if (!uid) return answerCb(env, cb.id, 'نامعتبر');
    const v = await whoBuyerView(env, uid);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data === 'adm:list' || data.startsWith('adm:list:')) {
    const p = parseInt(data.split(':')[2] || '1', 10) || 1;
    const v = await listPage(env, p);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data === 'adm:regs' || data.startsWith('adm:regs:')) {
    const p = parseInt(data.split(':')[2] || '1', 10) || 1;
    const v = await regsPage(env, p);
    await edit(v.text, v.kb);
    return answerCb(env, cb.id);
  }
  if (data.startsWith('adm:vw:')) {
    const [, , kind, subject] = data.split(':');
    const card = await whoView(env, subject || '');
    await edit(card.text, card.kb);
    return answerCb(env, cb.id);
  }
  if (data.startsWith('adm:ext:') || data.startsWith('adm:st:') || data.startsWith('adm:rst:')) {
    const parts = data.split(':');   // adm:<op>:<K>:<id>[:<extra>]
    const op = parts[1];
    const kind = parts[2];
    const id = parts[3] || '';
    if ((kind !== 'L' && kind !== 'A') || !id) return answerCb(env, cb.id, 'نامعتبر');
    const card = await adminItemAction(env, kind, id, op, parts[4]);
    await edit(card.text, card.kb);
    return answerCb(env, cb.id, op === 'ext' ? 'تمدید شد' : op === 'st' ? 'ثبت شد' : 'ریست شد');
  }
  return answerCb(env, cb.id);
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    const secret = String(env.WEBHOOK_SECRET || '');
    // Constant-time compare — the license worker's constantTimeEqual shape.
    // Same-length random secrets make the timing signal negligible, but the
    // comparison cost should not depend on where the first byte differs.
    let diff = secret.length ^ String(request.headers.get('x-telegram-bot-api-secret-token') || '').length;
    if (secret) {
      const given = String(request.headers.get('x-telegram-bot-api-secret-token') || '');
      if (given.length === secret.length) {
        for (let i = 0; i < secret.length; i++) diff |= secret.charCodeAt(i) ^ given.charCodeAt(i);
      } else {
        diff = 1;
      }
    }
    if (!secret || diff !== 0) {
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
          if (await regGate(env, chatId, cb.from, cb.id, 'خرید')) {
            await tg(env, 'editMessageText', {
              chat_id: chatId, message_id: cb.message.message_id,
              text: '🛍 <b>پلن موردنظر را انتخاب کن:</b>',
              parse_mode: 'HTML', reply_markup: await planKeyboard(env),
            }).catch(async () => send(env, chatId, '🛍 پلن‌ها:', await planKeyboard(env)));
          }
        } else if (data.startsWith('plan:')) {
          // تزریقِ مستقیم callback هم از گیت رد نمی‌شود
          if (await regGate(env, chatId, cb.from, cb.id, 'خرید')) {
            const plan = data.slice(5);
            const prev = await getPending(env, tgId);
            if (planDays(plan) && (!prev || prev.plan !== plan || prev.kind !== 'code')) {
              await setPending(env, tgId, plan, 'code');
              await notifyAdmins(env, `🛒 خریدار ${buyerLine(cb.from)} پلن <b>${esc(((await getPrices(env))[plan] || {}).title || plan)}</b> را انتخاب کرد.\nاسکرین‌شاتش که رسید را با دکمهٔ ✅ تأیید کن — کد خودکار برایش ارسال می‌شود.`);
            } else if (planDays(plan)) {
              await setPending(env, tgId, plan, 'code');
            }
            await send(env, chatId, await paymentText(env, plan), {
              inline_keyboard: [[{ text: '🔙 بازگشت', callback_data: 'buy' }]],
            });
          }
        } else if (data === 'reg') {
          await regIntro(env, chatId);
        } else if (data.startsWith('regplan:')) {
          const plan = data.slice(8);
          if (plan === 'trial') await giveTrialAccount(env, chatId, cb.from);
          else await regPlanPaid(env, chatId, cb.from, plan);
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
        } else if (data.startsWith('oka:')) {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            const parts = data.split(':');
            const buyerId = parseInt(parts[1], 10);
            const plan = parts[2] || '';
            const ts = parseInt(parts[3], 10) || 0;
            if (!buyerId || !planDays(plan)) {
              await answerCb(env, cb.id, 'دادهٔ نامعتبر');
            } else {
              await approveAccountReceipt(env, cb, buyerId, plan, ts);
            }
          }
        } else if (data === 'trial') {
          if (await regGate(env, chatId, cb.from, cb.id, 'کد تست')) {
            await giveTrialAccount(env, chatId, cb.from);
          }
        } else if (data === 'mysub') {
          await mySub(env, chatId, tgId, cb.from);
        } else if (data === 'help') {
          await send(env, chatId, HELP_TEXT);
        } else if (data === 'adm' || data.startsWith('adm:')) {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            await handleAdminCb(env, cb, data);
          }
        } else if (data.startsWith('pendx:')) {
          if (!isAdmin(env, cb.from)) {
            await answerCb(env, cb.id, 'فقط ادمین');
          } else {
            const uid = parseInt(data.slice(6), 10);
            if (uid) await clearPending(env, uid);
            const v = await pendView(env);
            await tg(env, 'editMessageText', {
              chat_id: chatId, message_id: cb.message.message_id, text: v.text,
              parse_mode: 'HTML', reply_markup: v.kb, disable_web_page_preview: true,
            }).catch(() => send(env, chatId, v.text, v.kb));
            await answerCb(env, cb.id, 'پاک شد');
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
        // Deep links from the apps: ?start=buy opens the shop, ?start=trial
        // hands out the 1-day code, ?start=register opens ثبت‌نام.
        if (text.includes('buy')) {
          if (await regGate(env, chatId, msg.from, null, 'خرید')) {
            await send(env, chatId, '🛍 <b>پلن موردنظر را انتخاب کن:</b>', await planKeyboard(env));
          }
        } else if (text.includes('trial')) {
          if (await regGate(env, chatId, msg.from, null, 'کد تست')) {
            await giveTrialAccount(env, chatId, msg.from);
          }
        } else if (text.includes('register')) {
          await regIntro(env, chatId);
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
        // (a price, a search query, an adduser step…).
        if (!text.startsWith('/')) {
          const st = await getAdminState(env, msg.from.id);
          if (st && st.mode === 'price' && planDays(st.plan)) {
            const applied = await applyPriceInput(env, chatId, st.plan, text);
            if (applied) await clearAdminState(env, msg.from.id);
            return new Response('ok');
          }
          if (st && (st.mode === 'adduser' || st.mode === 'search')) {
            await runAdminText(env, chatId, msg.from.id, st, text);
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
        else if (cmd === '/regs') {
          const v = await regsPage(env, parseInt(args[0] || '1', 10) || 1);
          await send(env, chatId, v.text, v.kb);
        }
        else if (cmd === '/price') await cmdPrice(env, chatId, args);
        else if (cmd === '/card') await cmdPayInfo(env, chatId, 'card', args);
        else if (cmd === '/usdt') await cmdPayInfo(env, chatId, 'usdt', args);
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
      try { await send(env, update.message?.chat?.id || update.callback_query?.message?.chat?.id, `⚠️ خطا: ${esc(String((e && e.message) || e))}`); } catch (_) {}
      return new Response('ok');
    }
  },
};
