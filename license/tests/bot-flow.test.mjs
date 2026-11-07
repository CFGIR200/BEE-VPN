/**
 * BEE-VPN bot — end-to-end harness (run: node license/tests/bot-flow.test.mjs)
 * Drives the REAL bot-worker.js with an in-memory SQLite (node:sqlite) D1 shim
 * and a scripted Telegram API. Covers the reported bug: admin-as-buyer photo
 * vanishing, plus delivery-failure visibility, fallbacks and idempotency.
 */
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Paths are relative to this file, so the harness runs from any checkout.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..', 'bot-worker.js');
const TMP = '/tmp/bw-under-test.mjs';
const SCHEMA = fs.readFileSync(path.join(HERE, '..', 'schema.sql'), 'utf8');
fs.copyFileSync(SRC, TMP);
const worker = (await import(TMP)).default;

// --- D1 shim over node:sqlite -----------------------------------------------
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

// --- Telegram API stub -------------------------------------------------------
const tg = [];
let mid = 100;
let failRules = []; // {method, chat_id, times, description}
globalThis.fetch = async (url, opts) => {
  const method = String(url).match(/\/bot[^/]+\/(\w+)/)[1];
  const body = JSON.parse(opts.body);
  tg.push({ method, body });
  for (const r of failRules) {
    if (r.method === method
      && (r.chat_id === undefined || String(body.chat_id) === String(r.chat_id))
      && r.times > 0) { r.times--; return resp({ ok: false, error_code: 403, description: r.description }); }
  }
  let result = { message_id: ++mid };
  if (method === 'getMe') result = { id: 1, is_bot: true, username: 'BEEHIVE_GUARD_BOT' };
  if (method === 'getWebhookInfo') result = { url: 'https://beevpn-bot.workers.dev/w', pending_update_count: 0 };
  return resp({ ok: true, result });
};
const resp = (obj) => new Response(JSON.stringify(obj), { status: 200, headers: { 'content-type': 'application/json' } });

const env = {
  DB: makeD1(), BOT_TOKEN: 'TEST:TOKEN', WEBHOOK_SECRET: 't',
  ADMIN_IDS: '111,222', CARD_INFO: 'CARD-INFO', USDT_INFO: 'USDT-INFO',
  SUPPORT_URL: 'https://t.me/Bee_hive_VPN',
};

const drive = async (update) => {
  const req = new Request('https://bot/w', {
    method: 'POST',
    headers: { 'x-telegram-bot-api-secret-token': 't' },
    body: JSON.stringify(update),
  });
  return worker.fetch(req, env).then((r) => r.status);
};
const textMsg = (id, text) => ({ update_id: Math.random(), message: { message_id: Math.random(), from: { id, first_name: 'U' + id }, chat: { id, type: 'private' }, text } });
const photoMsg = (id) => ({ update_id: Math.random(), message: { message_id: Math.random(), from: { id, first_name: 'U' + id }, chat: { id, type: 'private' }, photo: [{ file_id: 'F' + id, width: 800, height: 600 }] } });
const docMsg = (id) => ({ update_id: Math.random(), message: { message_id: Math.random(), from: { id, first_name: 'U' + id }, chat: { id, type: 'private' }, document: { file_id: 'D' + id, mime_type: 'image/png' } } });
const cb = (id, data) => ({ update_id: Math.random(), callback_query: { id: 'cb' + Math.random(), from: { id, first_name: 'U' + id }, message: { message_id: 500, chat: { id, type: 'private' } }, data } });
const sentTo = (chatId) => tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === String(chatId));
const lastSentTo = (chatId) => sentTo(chatId).at(-1)?.body?.text || '';
const lastEditTo = (chatId) => tg.filter((c) => c.method === 'editMessageText' && String(c.body.chat_id) === String(chatId)).at(-1)?.body?.text || '';
const count = (method) => tg.filter((c) => c.method === method).length;
// حسابِ ثبت‌نام‌شدهٔ فرضی — برای کاربرانی که باید از گیتِ ثبت‌نام رد شوند بدون
// اینکه سهمیهٔ تست ۱ روزه‌شان مصرف شود (آزمون‌های خرید).
const seedReg = (tgId, plan = 'm1') => env.DB.prepare(
  'INSERT INTO accounts (username, pass_hash, pass_salt, plan, status, duration_days, telegram_user_id, created_at) '
  + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
).bind('beeseed' + tgId, 'seeded', 'seeded', plan, 'active', 30, tgId, Math.floor(Date.now() / 1000)).run();

let pass = 0, fail = 0;
const ok = (cond, label) => { if (cond) { pass++; console.log('  ✅', label); } else { fail++; console.log('  ❌', label); } };
const section = (s) => console.log('\n' + s);

// ============================================================================
section('T1 — باگ گزارش‌شده: ادمین به‌عنوان خریدار (tg 111 ∈ ADMIN_IDS)');
ok((await drive(textMsg(111, '/start'))) === 200, '/start → 200');
ok(await drive(cb(111, 'plan:m1')) === 200, 'تپ plan:m1 → 200');
const pend111 = env.DB.prepare('SELECT plan FROM pending_receipts WHERE tg_user_id=111').first();
ok(pend111 && pend111.plan === 'm1', 'pending برای ادمین/خریدار ثبت شد');
const nCopyBefore = count('copyMessage');
ok(await drive(photoMsg(111)) === 200, 'عکس رسید از طرف ادمین → 200');
ok(count('copyMessage') > nCopyBefore, 'عکس ادمین به copyMessage رسید (قبلاً بی‌صدا نابود می‌شد!)');
const capTo111 = sentTo(111).find((c) => (c.body.text || '').includes('رسید پرداخت'));
ok(!!capTo111, 'کپشن رسید + دکمه‌ها برای ادمین آمد');
ok(JSON.stringify(capTo111?.body?.reply_markup || {}).includes('ok:111:m1:'), 'دکمهٔ ✅ با callback_data درست');
ok(lastSentTo(111).includes('رسید دریافت شد'), 'خریدار «رسید دریافت شد» گرفت');
ok(sentTo(222).some((c) => (c.body.text || '').includes('رسید پرداخت')), 'ادمین دوم هم رسید را گرفت');

section('T2 — خریدار عادی (905556034) با پلن m3');
// گیت ثبت‌نام: خرید فقط بعد از 📝 — 905556034 اول ثبت‌نام می‌کند (مسیر واقعی)
ok(await drive(cb(905556034, 'reg')) === 200, 'تپ 📝 ثبت‌نام → 200');
ok(await drive(cb(905556034, 'regplan:trial')) === 200, 'ثبت‌نامِ تست → حساب ساخته شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 905556034').first().n === 1, 'پروفایل 905556034 ساخته شد (گیت برایش باز شد)');
ok(await drive(cb(905556034, 'plan:m3')) === 200, 'تپ plan:m3 → 200 (بعد از ثبت‌نام از گیت رد شد)');
const nCopy = count('copyMessage');
ok(await drive(photoMsg(905556034)) === 200, 'عکس رسید → 200');
ok(count('copyMessage') === nCopy + 2, 'copyMessage به هر دو ادمین');
const buttons = sentTo(111).map((c) => JSON.stringify(c.body.reply_markup || {})).find((s) => s.includes('ok:905556034:m3:'));
ok(!!buttons, 'دکمهٔ تأیید با id خریدار و پلن m3');

section('T2b — عکس به شکل فایل (document image/png)');
ok(await drive(cb(905556034, 'plan:m6')) === 200, 'تپ plan:m6 → 200');
const nCopy2 = count('copyMessage');
ok(await drive(docMsg(905556034)) === 200, 'ارسال عکس به‌صورت document → 200');
ok(count('copyMessage') === nCopy2 + 2, 'document-عکس هم به ادمین‌ها رسید');

section('T3 — عکس بدون انتخاب پلن (کاربر تازه و ثبت‌نام‌نکردهٔ 777)');
const nCopy3 = count('copyMessage');
ok(await drive(photoMsg(777)) === 200, 'عکس بدون pending از ثبت‌نام‌نکرده → 200');
ok(count('copyMessage') === nCopy3, 'هیچ copyMessage اتفاق نیفتاد');
ok(lastSentTo(777).includes('اول ثبت‌نام'), 'کاربر ثبت‌نام‌نکرده به گیت خورد — به 📝 هدایت شد');

section('T4 — شکست ارسال به ادمین (شبیه‌سازی 403 هر دو ادمین)');
seedReg(888);  // ثبت‌نام‌شدهٔ فرضی — از گیت رد شود؛ سهمیهٔ تستش برای T20b دست‌نخورده می‌ماند
ok(await drive(cb(888, 'plan:m1')) === 200, 'تپ plan:m1 برای 888 → 200');
failRules = [{ method: 'copyMessage', times: 99, description: "Forbidden: bot can't initiate conversation with a user" }];
ok(await drive(photoMsg(888)) === 200, 'عکس با شکست ارسال → 200');
ok(lastSentTo(888).includes('ارسال رسید ناموفق'), 'خریدار دروغ «رسید دریافت شد» نشنید — پیام شکست آمد');
ok(lastSentTo(888).includes('t.me/Bee_hive_VPN'), 'لینک پشتیبانی در پیام شکست');
failRules = [];

section('T5 — کپشن/دکمه ناموفق → fallback به editMessageCaption');
seedReg(999);  // همان منطق T4
ok(await drive(cb(999, 'plan:m12')) === 200, 'تپ plan:m12 برای 999 → 200');
const nEditCap = count('editMessageCaption');
failRules = [{ method: 'sendMessage', chat_id: '222', times: 1, description: 'Bad Request: message caption too long' }];
ok(await drive(photoMsg(999)) === 200, 'عکس با شکست کپشن برای ادمین 222 → 200');
ok(count('editMessageCaption') === nEditCap + 1, 'دکمه‌ها با editMessageCaption روی خود عکس چسبید');
failRules = [];

section('T6 — تأیید ✅: صدور و ارسال کد');
const cbData = buttons?.match(/ok:(\d+:\w+:\d+)/)?.[0]; // ok:905556034:m3:ts
ok(!!cbData, 'callback_data تأیید قابل استخراج');
const nLicBefore = env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n;
ok(await drive(cb(222, cbData)) === 200, 'تپ ✅ توسط ادمین 222 → 200');
const lic = env.DB.prepare('SELECT code, plan, telegram_user_id, note FROM licenses WHERE note = ?').bind('receipt:' + cbData.split(':')[3] + ':905556034').first();
ok(!!lic && lic.plan === 'm3', 'لایسنس با note رسید صادر شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n === nLicBefore + 1, 'دقیقاً یک کد اضافه شد');
ok(lastSentTo(905556034).includes('کد فعال‌سازی'), 'کد برای خریدار ارسال شد');
ok(!env.DB.prepare('SELECT plan FROM pending_receipts WHERE tg_user_id=905556034').first(), 'pending خریدار پس از تأیید پاک شد');

section('T7 — تأیید تکراری ✅ توسط ادمین دیگر');
ok(await drive(cb(111, cbData)) === 200, 'تپ ✅ دوباره → 200');
ok(tg.filter((c) => c.method === 'editMessageText' && String(c.body.chat_id) === '111').some((c) => (c.body.text || '').includes('قبلاً تأیید شده')), 'پیام «قبلاً تأیید شده» (editMessageText)');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE note = ?').bind(lic.note).first().n === 1, 'هنوز فقط یک کد');

section('T8 — pending منقضی (>۲۴ ساعت) پاک می‌شود');
seedReg(8300);  // ثبت‌نام‌شده — «اول پلن» ببیند نه گیتِ ثبت‌نام
env.DB.prepare('INSERT INTO pending_receipts (tg_user_id, plan, ts) VALUES (?, ?, ?)').bind(8300, 'm1', Math.floor(Date.now() / 1000) - 90000).run();
ok(await drive(photoMsg(8300)) === 200, 'عکس با pending منقضی → 200');
ok(lastSentTo(8300).includes('اول پلن'), 'راهنمای انتخاب پلن دوباره آمد');
ok(!env.DB.prepare('SELECT tg_user_id FROM pending_receipts WHERE tg_user_id=8300').first(), 'ردیف منقضی sweep شد');

section('T9 — متن خریدار (نه عکس) هنگام خرید');
env.DB.prepare('INSERT INTO pending_receipts (tg_user_id, plan, ts) VALUES (?, ?, ?) ON CONFLICT(tg_user_id) DO UPDATE SET plan = excluded.plan, ts = excluded.ts').bind(777, 'm3', Math.floor(Date.now() / 1000)).run();
ok(await drive(textMsg(777, 'پرداخت کردم جواب بده')) === 200, 'متن بدون کامند → 200');
ok(lastSentTo(777).includes('اسکرین‌شات'), 'یادآوری «عکس/اسکرین‌شات بفرست» به‌جای منوی عمومی');

section('T10 — /diag');
ok(await drive(textMsg(111, '/diag')) === 200, '/diag → 200');
const diag = sentTo(111).at(-1)?.body?.text || '';
ok(diag.includes('وضعیت ربات'), 'گزارش diag ساخته شد');
ok(diag.includes('وب‌هوک') && diag.includes('ادمین'), 'وب‌هوک و وضعیت ادمین‌ها در گزارش');
ok(diag.includes('دریافت پیام ✅'), 'تست ارسال به ادمین‌ها موفق');

section('T11 — رد ❌');
env.DB.prepare('INSERT INTO pending_receipts (tg_user_id, plan, ts) VALUES (?, ?, ?)').bind(555, 'm1', Math.floor(Date.now() / 1000)).run();
ok(await drive(photoMsg(555)) === 200, 'عکس خریدار 555 → 200');
const noData = sentTo(111).map((c) => JSON.stringify(c.body.reply_markup || {})).map((s) => s.match(/no:\d+:\w+:\d+/)?.[0]).filter(Boolean).at(-1);
ok(!!noData, 'دکمهٔ رد موجود');
ok(await drive(cb(222, noData)) === 200, 'تپ ❌ → 200');
ok(lastSentTo(555).includes('تأیید نشد'), 'خریدار از رد باخبر شد');

section('T12 — دودِ فرمان‌های ادمین (بدون crash)');
for (const c of ['/list', '/stats', '/buyers', '/price', '/helpadmin', '/cancel', '/who BEE-XXXX-XXXX-XXXX', '/xyz']) {
  const r = await drive(textMsg(111, c));
  ok(r === 200, `${c} → 200`);
}
ok(await drive(textMsg(905556034, '/price')) === 200, 'غیرادمین /price → 200 (بدون پنل)');
ok(!lastSentTo(905556034).includes('قیمت‌های فعلی'), 'پنل قیمت برای غیرادمین باز نشد');

section('T14 — پروفایل کاربر (👤 پروفایل من) + تاریخ شمسی');
ok(await drive(cb(905556034, 'mysub')) === 200, 'mysub → 200');
ok(lastSentTo(905556034).includes('پروفایل تو در کندو'), 'کارت پروفایل ساخته شد');
const licCode = (sentTo(905556034).map((c) => c.body.text || '').join('\n').match(/BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}/) || [])[0];
ok(!!licCode, 'کد خریدار پیدا شد');
const expT = Math.floor(Date.now() / 1000) + 15 * 86400; // همیشه در آینده — منقضی نشود
env.DB.prepare('UPDATE licenses SET expires_at = ?, bound_install_id = ? WHERE code = ?').bind(expT, 'deadbeef1234', licCode).run();
ok(await drive(cb(905556034, 'mysub')) === 200, 'mysub دوباره → 200');
const prof = lastSentTo(905556034);
// همان مسیر رندر ربات: Intl تقویم فارسی به‌وقت تهران
const faJ = (epoch) => {
  try {
    const parts = new Intl.DateTimeFormat('fa-IR-u-ca-persian-nu-arabext', {
      timeZone: 'Asia/Tehran', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date(epoch * 1000));
    const g = (t) => (parts.find((x) => x.type === t) || {}).value || '';
    return `${g('year')}/${g('month')}/${g('day')}`;
  } catch { return ''; }
};
ok(prof.includes(faJ(expT)), `تاریخ شمسی درست: ${faJ(expT)}`);
ok(prof.includes('باقی‌مانده'), 'روزهای باقی‌مانده نشان داده شد');
ok(prof.includes('در جریان'), 'خلاصهٔ اشتراک در جریان');
ok(await drive(cb(424242, 'mysub')) === 200 && lastSentTo(424242).includes('هنوز ثبت‌نام نکردی'), 'پروفایل کاربر ثبت‌نام‌نکرده: مسیر 📝 نشان داده شد');
ok(JSON.stringify(sentTo(424242).at(-1)?.body?.reply_markup || {}).includes('callback_data":"reg"'), 'پروفایل خالی: دکمهٔ 📝 ثبت‌نام به‌جای خریدِ قفل');

section('T14b — کامند /profile (همان کارت دکمهٔ پروفایل)');
ok(await drive(textMsg(905556034, '/profile')) === 200, '/profile → 200');
ok(lastSentTo(905556034).includes('پروفایل تو در کندو'), 'کارت پروفایل با کامند هم آمد');
ok(await drive(textMsg(111, '/profile')) === 200, '/profile ادمین → 200');

section('T15 — پنل مدیریت /admin');
ok(await drive(textMsg(111, '/admin')) === 200, '/admin → 200');
const panelMsg = sentTo(111).at(-1)?.body || {};
ok((panelMsg.text || '').includes('پنل مدیریت کندو'), 'متن پنل');
ok(JSON.stringify(panelMsg.reply_markup || {}).includes('adm:stats'), 'دکمه‌های پنل');
ok(await drive(cb(111, 'adm:stats')) === 200, 'adm:stats → 200');
ok((tg.filter((c) => c.method === 'editMessageText').at(-1)?.body?.text || '').includes('آمار کندو'), 'آمار داخل پنل');
ok(await drive(cb(111, 'adm:buyers:1')) === 200, 'adm:buyers → 200');
ok((tg.filter((c) => c.method === 'editMessageText').at(-1)?.body?.text || '').includes('خریداران'), 'لیست خریداران با صفحه‌بندی');
ok(await drive(cb(111, 'adm:list:1')) === 200, 'adm:list → 200');
ok((tg.filter((c) => c.method === 'editMessageText').at(-1)?.body?.text || '').includes('آخرین کدها'), 'لیست کدها');

section('T16 — ساخت کد راهنمادار (پلن → تعداد → ساخت)');
const nLic = env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n;
ok(await drive(cb(111, 'adm:gen')) === 200, 'adm:gen → 200');
ok(await drive(cb(111, 'adm:gen:m3')) === 200, 'پلن m3 انتخاب شد');
ok(await drive(cb(111, 'adm:genc:m3:5')) === 200, '۵ کد انتخاب شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n === nLic + 5, 'دقیقاً ۵ کد m3 ساخته شد');
ok((tg.filter((c) => c.method === 'editMessageText').at(-1)?.body?.text || '').includes('۵ کد'), 'پیام نتیجه با شمار فارسی');

section('T17 — ساخت حساب راهنمادار (نام → رمز → پلن)');
ok(await drive(cb(111, 'adm:au')) === 200, 'adm:au → 200');
ok(await drive(textMsg(111, 'seller2')) === 200, 'نام کاربری فرستاده شد');
ok(await drive(textMsg(111, 'secret99')) === 200, 'رمز فرستاده شد');
ok(await drive(cb(111, 'adm:au:m1')) === 200, 'پلن m1 انتخاب شد');
const accRow = env.DB.prepare('SELECT plan FROM accounts WHERE username = ?').bind('seller2').first();
ok(accRow && accRow.plan === 'm1', 'حساب seller2/m1 ساخته شد');
ok(!env.DB.prepare('SELECT value FROM settings WHERE key = ?').bind('edit:111').first(), 'state پس از ساخت پاک شد');

section('T18 — جستجو و کارت مدیریت (تمدید/لغو/ریست)');
ok(await drive(cb(111, 'adm:search')) === 200, 'adm:search → 200');
ok(await drive(textMsg(111, licCode)) === 200, 'کد فرستاده شد');
const card = sentTo(111).at(-1)?.body || {};
ok((card.text || '').includes('باقی‌مانده'), 'کارت: انقضا + روز باقی‌مانده');
const cardKb = JSON.stringify(card.reply_markup || {});
ok(cardKb.includes(`adm:ext:L:${licCode}:30`), 'دکمهٔ تمدید ۳۰ روزه');
const expBefore = env.DB.prepare('SELECT expires_at FROM licenses WHERE code = ?').bind(licCode).first().expires_at;
ok(await drive(cb(111, `adm:ext:L:${licCode}:30`)) === 200, 'تمدید +۳۰ روز → 200');
ok(env.DB.prepare('SELECT expires_at FROM licenses WHERE code = ?').bind(licCode).first().expires_at === expBefore + 30 * 86400, 'انقضا دقیقاً +۳۰ روز');
ok(await drive(cb(111, `adm:st:L:${licCode}:revoked`)) === 200, 'لغو اشتراک → 200');
ok(env.DB.prepare('SELECT status FROM licenses WHERE code = ?').bind(licCode).first().status === 'revoked', 'وضعیت revoked شد');
ok(await drive(cb(111, `adm:rst:L:${licCode}`)) === 200, 'ریست دستگاه → 200');
const afterRst = env.DB.prepare('SELECT status, bound_install_id, expires_at FROM licenses WHERE code = ?').bind(licCode).first();
ok(afterRst.status === 'revoked' && !afterRst.bound_install_id && !afterRst.expires_at, 'بایند/انقضا پاک شد، وضعیت حفظ شد');
ok(await drive(cb(111, 'adm:search')) === 200, 'جستجوی دوباره → 200');
ok(await drive(textMsg(111, '905556034')) === 200, 'شناسهٔ عددی فرستاده شد');
ok(lastSentTo(111).includes('خریدار'), 'تاریخچهٔ کامل خریدار (whoBuyer)');

section('T19 — محافظت پنل در برابر غیرادمین');
ok(await drive(cb(777, 'adm:stats')) === 200, 'غیرادمین adm:stats → 200');
const lastTwoAns = tg.filter((c) => c.method === 'answerCallbackQuery').slice(-2).map((c) => c.body?.text || '');
ok(lastTwoAns.includes('فقط ادمین'), 'پاسخ «فقط ادمین»');

// ===========================================================================
// سیستم ثبت‌نام — 📝 در منو → حساب کاربری (نام کاربری و رمز) از تلگرام
// ===========================================================================

section('T20 — ثبت‌نام تست ۱ روزه: حساب کاربری همان لحظه صادر می‌شود');
ok(await drive(cb(777, 'reg')) === 200, 'تپ 📝 ثبت‌نام → 200');
ok(lastSentTo(777).includes('ثبت‌نام در کندو'), 'مقدمهٔ ثبت‌نام آمد');
ok(await drive(cb(777, 'regplan:trial')) === 200, 'تپ regplan:trial → 200');
const trialAcc = env.DB.prepare(
  'SELECT username, plan, telegram_user_id, telegram_name, note FROM accounts WHERE telegram_user_id = 777 AND plan = ?',
).bind('trial').first();
ok(!!trialAcc && /^bee[a-z0-9]{7}$/.test(trialAcc.username), `حساب تست با نام کاربری تولیدشده ساخته شد (${trialAcc ? trialAcc.username : '—'})`);
ok(trialAcc.telegram_user_id === 777 && trialAcc.note === 'trial:777', 'شناسهٔ تلگرام و note ثبت شد');
ok(trialAcc.telegram_name === 'U777', 'نام تلگرام از from ذخیره شد');
const credMsg = sentTo(777).map((c) => c.body.text || '').find((t) => t.includes('نام کاربری:'));
ok(!!credMsg && credMsg.includes('رمز عبور') && credMsg.includes(trialAcc.username), 'نام کاربری و رمز برای کاربر ارسال شد');
ok(await drive(cb(777, 'regplan:trial')) === 200, 'تپ دوبارهٔ regplan:trial → 200');
ok(lastSentTo(777).includes('قبلاً تست'), 'دومین تست برای همان حساب تلگرام رد شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 777 AND plan = ?').bind('trial').first().n === 1, 'هنوز فقط یک حساب تست');

section('T20b — 🎁 و 📝 یک بسته می‌دهند (حساب + لایسنس) و سقفشان یکی است');
ok(await drive(cb(888, 'trial')) === 200, 'کاربر ۸۸۸ 🎁 را زد');
ok(lastSentTo(888).includes('نام کاربری') && lastSentTo(888).includes('رمز عبور'), 'بستهٔ تست: نام کاربری و رمز آمد');
ok(lastSentTo(888).includes('لایسنس برنامه'), 'بستهٔ تست: لایسنس کد هم آمد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 888 AND plan = ?').bind('trial').first().n === 1, 'حساب تست صادر شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 888 AND plan = ?').bind('trial').first().n === 1, 'لایسنس تست صادر شد (با حساب، هم‌زمان)');
ok(await drive(cb(888, 'regplan:trial')) === 200, 'دوباره از 📝 تست زد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 888 AND plan = ?').bind('trial').first().n === 1, 'حساب تست دومی صادر نشد (سقف مشترک)');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 888 AND plan = ?').bind('trial').first().n === 1, 'لایسنس تست دومی هم صادر نشد');
ok(lastSentTo(888).includes('قبلاً تست'), 'پاسخ «قبلاً تست» آمد');

section('T21 — ثبت‌نام خریدنی: رسید → تأیید → پروفایل + لایسنس با هم');
ok(await drive(cb(555, 'regplan:m1')) === 200, 'تپ regplan:m1 → 200');
const pendAcc = env.DB.prepare('SELECT plan, kind FROM pending_receipts WHERE tg_user_id = 555').first();
ok(!!pendAcc && pendAcc.plan === 'm1' && pendAcc.kind === 'account', 'pending با kind=account ثبت شد');
ok(lastSentTo(555).includes('ثبت‌نام'), 'راهنمای پرداخت ثبت‌نام آمد');
ok(await drive(photoMsg(555)) === 200, 'عکس رسید ثبت‌نام → 200');
const okaBtn = sentTo(111).map((c) => JSON.stringify(c.body.reply_markup || {})).map((s) => s.match(/oka:555:m1:\d+/)?.[0]).filter(Boolean).at(-1);
ok(!!okaBtn, 'دکمهٔ «تأیید و ساخت حساب» با oka: آمد');
ok(sentTo(111).some((c) => (c.body.text || '').includes('رسید ثبت‌نام')), 'کپشن «رسید ثبت‌نام» برای ادمین');
const nAccBefore = env.DB.prepare('SELECT COUNT(*) AS n FROM accounts').first().n;
const nLicBefore555 = env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n;
ok(await drive(cb(222, okaBtn)) === 200, 'تپ ✅ ساخت حساب توسط ادمین 222 → 200');
const tsNote = 'receipt:' + okaBtn.split(':')[3] + ':555';
const licNote = env.DB.prepare('SELECT code, plan, telegram_user_id FROM licenses WHERE note = ?').bind(tsNote).first();
ok(!!licNote && licNote.plan === 'm1', 'لایسنس با note رسید صادر شد (ضد-دوباره‌کاری)');
ok(licNote && licNote.telegram_user_id === 555, 'لایسنس به خریدار تلگرامی بسته شد');
const accNew = env.DB.prepare('SELECT username, plan, telegram_user_id FROM accounts WHERE telegram_user_id = 555').first();
ok(!!accNew && accNew.plan === 'm1', 'پروفایل خریدار ساخته شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts').first().n === nAccBefore + 1, 'دقیقاً یک حساب اضافه شد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses').first().n === nLicBefore555 + 1, 'دقیقاً یک لایسنس اضافه شد');
const credToBuyer = sentTo(555).map((c) => c.body.text || '').find((t) => t.includes('پرداخت شما تأیید شد'));
ok(!!credToBuyer && credToBuyer.includes(accNew.username), 'نام کاربری پروفایل برای خریدار آمد');
ok(!!credToBuyer && credToBuyer.includes('رمز عبور'), 'رمز برای خریدار آمد');
ok(!!credToBuyer && credToBuyer.includes(licNote.code), 'لایسنس هم در همان پیام آمد');
ok(!env.DB.prepare('SELECT tg_user_id FROM pending_receipts WHERE tg_user_id=555').first(), 'pending پس از تأیید پاک شد');
ok(await drive(cb(111, okaBtn)) === 200, 'تپ ✅ تکراری → 200');
ok(tg.filter((c) => c.method === 'editMessageText').some((c) => (c.body.text || '').includes('قبلاً تأیید شده')), 'تأیید تکراری همان پاسخ امن را داد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE note = ?').bind(tsNote).first().n === 1, 'هنوز فقط یک لایسنس با آن رسید');

section('T22 — پنل «📝 ثبت‌نامی‌ها»: لینک تلگرام، حساب، زمان‌ها');
ok(await drive(cb(111, 'adm:regs:1')) === 200, 'adm:regs:1 → 200');
const regsEdit = tg.filter((c) => c.method === 'editMessageText').at(-1)?.body || {};
ok((regsEdit.text || '').includes('ثبت‌نامی‌ها'), 'عنوان «ثبت‌نامی‌ها»');
ok((regsEdit.text || '').includes(`tg://user?id=777`), 'لینک تلگرام کاربر ثبت‌نامی (tg://user?id=…)');
ok((regsEdit.text || '').includes(trialAcc.username), 'حساب صادرشده در لیست');
ok((regsEdit.text || '').includes('ثبت‌نام:'), 'زمان ثبت‌نام (شمسی) نشان داده شد');
ok(JSON.stringify(regsEdit.reply_markup || {}).includes(`adm:vw:A:${trialAcc.username}`), 'دکمهٔ ⚙️ بازکردن کارت مدیریت');
ok(await drive(cb(111, `adm:vw:A:${trialAcc.username}`)) === 200, 'کارت مدیریت حساب → 200');
const accCard = tg.filter((c) => c.method === 'editMessageText').at(-1)?.body || {};
ok((accCard.text || '').includes('تلگرام:'), 'کارت: ردیف تلگرام با لینک');
ok(JSON.stringify(accCard.reply_markup || {}).includes(`adm:ext:A:${trialAcc.username}:30`), 'کارت: دکمهٔ تمدید ۳۰ روزه');
ok(await drive(textMsg(111, '/regs')) === 200, 'فرمان /regs → 200');
ok(lastSentTo(111).includes('ثبت‌نامی‌ها'), '/regs همان فهرست را فرستاد');
ok(await drive(textMsg(111, '/stats')) === 200, '/stats → 200');
ok(lastSentTo(111).includes('ثبت‌نامی‌ها'), 'آمار شامل ردیف ثبت‌نامی‌ها');

section('T23 — دیپ‌لینک اپ: /start register');
ok(await drive(textMsg(424242, '/start register')) === 200, '/start register → 200');
ok(lastSentTo(424242).includes('ثبت‌نام در کندو'), 'دیپ‌لینک مستقیم به جریان ثبت‌نام رسید');

section('T24 — /card و /usdt: تغییر اطلاعات پرداخت بدون استقرار');
ok(await drive(textMsg(111, '/card')) === 200, '/card بدون متن → 200');
ok(lastSentTo(111).includes('CARD-INFO'), 'مقدار فعلی از Secret نشان داده شد');
ok(await drive(textMsg(111, '/card 6037-9912-3456-7890 به نام تست')) === 200, '/card با متن → 200');
ok(lastSentTo(111).includes('6037-9912-3456-7890'), 'تأیید به‌روزرسانی آمد');
ok(await drive(textMsg(111, '/usdt TRC20: TXtestAddress123')) === 200, '/usdt با متن → 200');
ok(await drive(cb(905556034, 'plan:m1')) === 200, 'تپ پلن خریدار → 200');
const payPage = sentTo(905556034).at(-1)?.body?.text || '';
ok(payPage.includes('6037-9912-3456-7890'), 'صفحهٔ پرداخت کارت جدید را نشان داد');
ok(payPage.includes('TXtestAddress123'), 'صفحهٔ پرداخت آدرس تتر تازه را نشان داد');
ok(!payPage.includes('CARD-INFO'), 'مقدار Secret دیگر دیده نمی‌شود');
ok(await drive(textMsg(111, '/diag')) === 200, '/diag → 200');
ok(lastSentTo(111).includes('ویرایش در ربات فعال'), '/diag وضعیت ویرایش را گزارش کرد');
ok(await drive(textMsg(111, '/card clear')) === 200, '/card clear → 200');
ok(lastSentTo(111).includes('CARD-INFO'), 'clear به مقدار Secret برگشت');
const settingsBefore = env.DB.prepare('SELECT COUNT(*) AS n FROM settings').first().n;
ok(await drive(textMsg(999999, '/card hack-attempt')) === 200, '/card از غیرادمین → 200 (نادیده گرفته شد)');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM settings').first().n === settingsBefore, 'غیرادمین چیزی تغییر نداد');

section('T25 — سفر چندکاربره: هر تأییدی، پروفایل + لایسنس با هم می‌دهد');
// U2: خریدار تازه از مسیر 🛍 (ثبت‌نام‌شدهٔ فرضی — گیت رد می‌شود)
seedReg(7001);
ok(await drive(cb(7001, 'plan:m1')) === 200, 'U2 پلن m1 را زد');
ok(await drive(photoMsg(7001)) === 200, 'U2 رسید فرستاد');
const btnU2a = tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '111')
  .map((c) => JSON.stringify(c.body.reply_markup || {})).reverse().find((s) => s.includes('ok:7001:m1:'));
ok(!!btnU2a, 'دکمهٔ تأیید برای ادمین آمد');
ok(await drive(cb(111, `ok:7001:m1:${btnU2a.match(/ok:7001:m1:(\d+)/)[1]}`)) === 200, 'ادمین ✅ زد');
const u2msg = lastSentTo(7001);
ok(u2msg.includes('پروفایل تو'), 'U2: نام کاربری پروفایل آمد');
// گیتِ جدید: خریدار اول ثبت‌نام می‌کند (رمز همان‌جا می‌رسد) — رسیدِ خرید حسابِ موجود
// را استفاده می‌کند و رمز دیگر تکرار نمی‌شود؛ یادآوریِ «رمز قبلی» می‌آید.
ok(u2msg.includes('رمز قبلی'), 'U2: رمز دوباره لو نمی‌رود — یادآوری «رمز قبلی» آمد');
ok(/BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}/.test(u2msg), 'U2: لایسنس کد آمد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 7001').first().n === 1, 'U2: دقیقاً یک پروفایل');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 7001').first().n === 1, 'U2: دقیقاً یک لایسنس');
// U2 خرید دوم — همان پروفایل، لایسنس تازه، رمز تکرار نمی‌شود
ok(await drive(cb(7001, 'plan:m3')) === 200, 'U2 دوباره m3 زد');
ok(await drive(photoMsg(7001)) === 200, 'U2 رسید دوم را فرستاد');
const btnU2b = tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '111')
  .map((c) => JSON.stringify(c.body.reply_markup || {})).reverse().find((s) => s.includes('ok:7001:m3:'));
ok(await drive(cb(111, `ok:7001:m3:${btnU2b.match(/ok:7001:m3:(\d+)/)[1]}`)) === 200, 'ادمین ✅ دوم را زد');
const u2msg2 = lastSentTo(7001);
ok(/BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}/.test(u2msg2), 'U2: لایسنس دوم آمد');
ok(u2msg2.includes('رمز قبلی'), 'U2: به‌جای رمز، «رمز قبلی» یادآوری شد');
ok(!u2msg2.includes('رمز عبور:'), 'U2: رمز دوباره نشان داده نشد (hash نگه‌داری می‌شود)');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 7001').first().n === 1, 'U2: پروفایل تکراری ساخته نشد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 7001').first().n === 2, 'U2: دو لایسنس مستقل');
// U3: اول ثبت‌نام (تست) بعد خرید — ثبت‌نام بن‌بست نیست و گیت را هم باز می‌کند
ok(await drive(cb(7002, 'regplan:trial')) === 200, 'U3 اول ثبت‌نام تست زد');
ok(await drive(cb(7002, 'plan:m1')) === 200, 'U3 بلافاصله خرید زد (گیت با ثبت‌نام باز شد)');
ok(!!env.DB.prepare('SELECT tg_user_id FROM pending_receipts WHERE tg_user_id = 7002').first(), 'U3: pending خرید ثبت شد — ثبت‌نام مانع خرید نیست');
ok(await drive(photoMsg(7002)) === 200, 'U3 رسید فرستاد');
const btnU3 = tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '111')
  .map((c) => JSON.stringify(c.body.reply_markup || {})).reverse().find((s) => s.includes('ok:7002:m1:'));
ok(await drive(cb(111, `ok:7002:m1:${btnU3.match(/ok:7002:m1:(\d+)/)[1]}`)) === 200, 'ادمین برای U3 ✅ زد');
const u3msg = lastSentTo(7002);
ok(u3msg.includes('رمز قبلی'), 'U3: پروفایل قبلی‌اش دوباره استفاده شد (ثبت‌نام بن‌بست نیست)');
ok(/BEE-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}/.test(u3msg), 'U3: لایسنس خرید آمد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 7002').first().n === 1, 'U3: یک پروفایل برای هر دو (تست + خرید)');
// پروفایل: دکمهٔ کانال و پشتیبانی
ok(await drive(cb(7002, 'mysub')) === 200, 'U3 پروفایل را باز کرد');
const profKb = JSON.stringify(tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '7002').at(-1)?.body?.reply_markup || {});
ok(profKb.includes('BEESELL_GP'), 'پروفایل: دکمهٔ کانال');
ok(profKb.includes('Bee_hive_VPN'), 'پروفایل: دکمهٔ پشتیبانی');
// انقضا: پروفایل زنده می‌ماند، منقضی نشان داده می‌شود، دکمه‌ها هست
const licU3 = env.DB.prepare('SELECT code FROM licenses WHERE telegram_user_id = 7002 AND plan = ?').bind('m1').first();
env.DB.prepare('UPDATE licenses SET expires_at = ? WHERE code = ?').bind(Math.floor(Date.now() / 1000) - 100, licU3.code).run();
ok(await drive(cb(7002, 'mysub')) === 200, 'پروفایل بعد از انقضا → 200');
const profExp = lastSentTo(7002);
ok(profExp.includes('منقضی'), 'انقضا در پروفایل نشان داده شد');
ok(profExp.includes('BEE-'), 'لایسنس منقضی هم در پروفایل دیده می‌شود');
ok(JSON.stringify(tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '7002').at(-1)?.body?.reply_markup || {}).includes('BEESELL_GP'), 'بعد از انقضا هم کانال/خرید در پروفایل هست');
// دو ادمین روی همان رسید ثبت‌نام — فقط یک بسته
ok(await drive(cb(7003, 'regplan:m3')) === 200, 'U3b پلن m3 برای ثبت‌نام زد');
ok(await drive(photoMsg(7003)) === 200, 'U3b رسید فرستاد');
const btnU3bA = tg.filter((c) => c.method === 'sendMessage' && String(c.body.chat_id) === '111')
  .map((c) => JSON.stringify(c.body.reply_markup || {})).reverse().find((s) => s.includes('oka:7003:m3:'));
ok(!!btnU3bA, 'دکمهٔ oka (ثبت‌نام) آمد');
ok(await drive(cb(111, `oka:7003:m3:${btnU3bA.match(/oka:7003:m3:(\d+)/)[1]}`)) === 200, 'ادمین ۱ ✅ زد');
ok(await drive(cb(222, `oka:7003:m3:${btnU3bA.match(/oka:7003:m3:(\d+)/)[1]}`)) === 200, 'ادمین ۲ هم ✅ زد (مسابقه)');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 7003').first().n === 1, 'با دو ✅ هم‌زمان، فقط یک لایسنس');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 7003').first().n === 1, 'با دو ✅ هم‌زمان، فقط یک پروفایل');

// ===========================================================================
// گیت ثبت‌نام — «🎁 کد تست» و «🛍 خرید» برای ثبت‌نام‌نکرده‌ها باز نیست
// (درخواست اپراتور: دکمه‌ها فقط بعد از ثبت‌نام کار کنند)
// ===========================================================================

section('T26 — گیت ثبت‌نام: دکمه‌ها برای ثبت‌نام‌نکرده‌ها قفل است');
// قفل روی 🎁 کد تست
ok(await drive(cb(8101, 'trial')) === 200, 'تازه‌کار 🎁 زد → 200');
ok(lastSentTo(8101).includes('اول ثبت‌نام'), '🎁 قفل بود: پیام «اول ثبت‌نام» آمد');
ok(JSON.stringify(sentTo(8101).at(-1)?.body?.reply_markup || {}).includes('callback_data":"reg"'), 'دکمهٔ 📝 در پیام گیت هست');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 8101').first().n === 0, 'هیچ حسابی برای تازه‌کار ساخته نشد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM licenses WHERE telegram_user_id = 8101').first().n === 0, 'هیچ لایسنسی برای تازه‌کار صادر نشد');
ok(tg.filter((c) => c.method === 'answerCallbackQuery').some((c) => (c.body?.text || '').includes('اول ثبت‌نام')), 'توست دکمه هم «اول ثبت‌نام» گفت');
// قفل روی 🛍 خرید
ok(await drive(cb(8101, 'buy')) === 200, 'تازه‌کار 🛍 زد → 200');
ok(!lastSentTo(8101).includes('پلن موردنظر'), 'فهرست پلن‌ها برای ثبت‌نام‌نکرده باز نشد');
// تزریق مستقیم callback_data هم از گیت رد نمی‌شود
ok(await drive(cb(8101, 'plan:m1')) === 200, 'plan:m1 مستقیم (تزریق) → 200');
ok(!env.DB.prepare('SELECT tg_user_id FROM pending_receipts WHERE tg_user_id = 8101').first(), 'pending برای ثبت‌نام‌نکرده ساخته نشد');
// رسید عکسی بدون pending و بدون ثبت‌نام → گیت، نه صفحهٔ انتخاب پلن
const nCopyGate = count('copyMessage');
ok(await drive(photoMsg(8101)) === 200, 'عکس بدون pending از ثبت‌نام‌نکرده → 200');
ok(count('copyMessage') === nCopyGate, 'عکس او به ادمین‌ها نرفت');
ok(lastSentTo(8101).includes('اول ثبت‌نام'), 'عکسِ او هم به گیت خورد');
// دیپ‌لینک‌های اپ هم قفل‌اند
ok(await drive(textMsg(8101, '/start buy')) === 200, '/start buy ثبت‌نام‌نکرده → 200');
ok(!lastSentTo(8101).includes('پلن موردنظر'), 'دیپ‌لینک خرید هم قفل بود');
ok(await drive(textMsg(8101, '/start trial')) === 200, '/start trial ثبت‌نام‌نکرده → 200');
ok(!lastSentTo(8101).includes('تست ۱ روزهٔ تو ساخته شد'), 'دیپ‌لینک تست هم قفل بود');

section('T26b — بعد از ثبت‌نام، همان دکمه‌ها برای همان کاربر باز می‌شود');
ok(await drive(cb(8101, 'reg')) === 200, '📝 ثبت‌نام زد');
ok(lastSentTo(8101).includes('ثبت‌نام در کندو'), 'مقدمهٔ ثبت‌نام از پیام گیت باز شد');
ok(await drive(cb(8101, 'regplan:trial')) === 200, 'ثبت‌نام تست را کامل کرد');
ok(env.DB.prepare('SELECT COUNT(*) AS n FROM accounts WHERE telegram_user_id = 8101').first().n === 1, 'حسابش ساخته شد');
ok(await drive(cb(8101, 'buy')) === 200, 'حالا 🛍 زد → باز شد');
ok(lastEditTo(8101).includes('پلن موردنظر'), 'فهرست پلن‌ها برایش باز شد');
ok(await drive(cb(8101, 'trial')) === 200, '🎁 هم باز شد (ولی تستش را برداشته)');
ok(lastSentTo(8101).includes('قبلاً تست'), '🎁 بعد از ثبت‌نام: سقف تست، همان پاسخ آشنا را داد');
// عکسِ ثبت‌نام‌شدهٔ بدون pending همچنان «اول پلن» می‌گیرد (نه گیت)
ok(await drive(photoMsg(8101)) === 200, 'عکسِ ثبت‌نامی بدون pending → 200');
ok(lastSentTo(8101).includes('اول پلن'), 'مسیر عکسِ ثبت‌نامی همان «اول پلن» است');

section('T26c — ادمین از گیت رد می‌شود (تستِ جریان‌ها و خرید برای دیگران)');
ok(await drive(cb(222, 'buy')) === 200, 'ادمین 222 🛍 زد');
ok(lastEditTo(222).includes('پلن موردنظر'), 'ادمین بدون ثبت‌نام هم به فروشگاه رسید');
ok(await drive(cb(222, 'trial')) === 200, 'ادمین 🎁 زد');
ok(lastSentTo(222).includes('نام کاربری'), 'ادمین توانست بستهٔ تست بگیرد');

console.log(`\n═══ نتیجه: ${pass} پاس ✅ — ${fail} شکست ❌ ═══`);
process.exitCode = fail ? 1 : 0;
