/**
 * إعدادات النظام القابلة للتعديل من الواجهة (بدون تعديل .env أو إعادة تشغيل).
 *
 * التخزين: جدول settings (key/value)
 * الأسرار: تُشفَّر بـ utils/crypto قبل الحفظ
 * القراءة: قيمة DB إن وُجدت، وإلا process.env، وإلا الافتراضي
 * الكاش: قصير العمر حتى تنعكس التعديلات فوراً دون إغراق قاعدة البيانات
 */

const { getDb } = require('../db/database');
const { encrypt, decrypt } = require('../utils/crypto');

const CACHE_TTL_MS = 5000;
let cache = { at: 0, map: null };

/** تعريف الحقول الظاهرة في الإعدادات */
const DEFINITIONS = [
  // عام / عنوان
  {
    key: 'PUBLIC_BASE_URL',
    label: 'العنوان العام للنظام',
    group: 'general',
    type: 'url',
    secret: false,
    placeholder: 'https://erp.lork.cloud',
    help: 'يُستخدم لروابط الـ webhook وOpenRouter. مثال: https://erp.lork.cloud',
  },
  {
    key: 'OPENROUTER_BASE_URL',
    label: 'عنوان OpenRouter',
    group: 'ai',
    type: 'url',
    secret: false,
    default: 'https://openrouter.ai/api/v1',
    help: 'اتركه افتراضياً إلا إذا كنت تستخدم وكيلاً مخصّصاً.',
  },
  {
    key: 'WHATSAPP_AI_ORDER',
    label: 'ترتيب مزوّدي واتساب',
    group: 'ai',
    type: 'text',
    secret: false,
    placeholder: 'openai,openrouter,gemini',
    help: 'قائمة مفصولة بفواصل. فارغ = حسب آخر تحديث في إعدادات الذكاء.',
  },
  {
    key: 'WHATSAPP_AI_PREFERRED',
    label: 'المزوّد المفضّل لواتساب',
    group: 'ai',
    type: 'text',
    secret: false,
    placeholder: 'openrouter',
    help: 'اختياري: openai أو openrouter أو gemini.',
  },

  // Google
  {
    key: 'GOOGLE_CLIENT_ID',
    label: 'Google Client ID',
    group: 'google',
    type: 'text',
    secret: false,
    placeholder: 'xxxx.apps.googleusercontent.com',
  },
  {
    key: 'GOOGLE_CLIENT_SECRET',
    label: 'Google Client Secret',
    group: 'google',
    type: 'password',
    secret: true,
    placeholder: '********',
  },
  {
    key: 'GOOGLE_REDIRECT_URI',
    label: 'Google Redirect URI',
    group: 'google',
    type: 'url',
    secret: false,
    placeholder: 'https://erp.lork.cloud/sheets/callback',
    help: 'يجب أن يطابق العنوان المضبوط في Google Cloud Console.',
  },

  // WhatsApp / Evolution
  {
    key: 'EVOLUTION_API_KEY',
    label: 'مفتاح Evolution API',
    group: 'whatsapp',
    type: 'password',
    secret: true,
    help: 'يجب أن يطابق AUTHENTICATION_API_KEY لخدمة Evolution. عند التضمين يُحدَّث ملف Evolution تلقائياً.',
  },
  {
    key: 'EVOLUTION_API_URL',
    label: 'عنوان Evolution API',
    group: 'whatsapp',
    type: 'url',
    secret: false,
    help: 'اتركه فارغاً عند التضمين (يُشتق تلقائياً من المنفذ ومسار التركيب).',
  },
  {
    key: 'EVOLUTION_TIMEOUT_MS',
    label: 'مهلة Evolution (ms)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '20000',
  },
  {
    key: 'WHATSAPP_WEBHOOK_SECRET',
    label: 'سر Webhook واتساب',
    group: 'whatsapp',
    type: 'password',
    secret: true,
    help: 'سر مشترك يحمي /api/whatsapp/webhook. بعد تغييره أعد ربط النسخة من صفحة واتساب.',
  },
  {
    key: 'WHATSAPP_OTP_TTL_MINUTES',
    label: 'صلاحية رمز OTP (دقائق)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '10',
  },
  {
    key: 'WHATSAPP_OTP_MAX_ATTEMPTS',
    label: 'أقصى محاولات OTP',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '5',
  },
  {
    key: 'WHATSAPP_BLOCK_MINUTES',
    label: 'مدة الحظر بعد فشل OTP (دقائق)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '60',
  },
  {
    key: 'WHATSAPP_SESSION_TTL_MINUTES',
    label: 'مدة جلسة واتساب (دقائق)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '720',
  },
  {
    key: 'WHATSAPP_RATE_LIMIT',
    label: 'حد الرسائل',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '20',
  },
  {
    key: 'WHATSAPP_RATE_WINDOW_SEC',
    label: 'نافذة حد الرسائل (ثوانٍ)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '60',
  },
  {
    key: 'WHATSAPP_AGENT_MAX_ROUNDS',
    label: 'أقصى جولات للوكيل',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '4',
  },
  {
    key: 'WHATSAPP_MAX_REPLY_CHARS',
    label: 'أقصى طول للرد',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '3000',
  },
  {
    key: 'WHATSAPP_HUMANIZE',
    label: 'إيقاع بشري للردود',
    group: 'whatsapp',
    type: 'toggle',
    secret: false,
    default: '1',
    help: '1 = مفعّل، 0 = إرسال فوري دفعة واحدة.',
  },
  {
    key: 'WHATSAPP_TYPING_CPS',
    label: 'سرعة الكتابة (حرف/ث)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '22',
  },
  {
    key: 'WHATSAPP_TYPING_MIN_MS',
    label: 'أدنى تأخير كتابة (ms)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '1200',
  },
  {
    key: 'WHATSAPP_TYPING_MAX_MS',
    label: 'أقصى تأخير كتابة (ms)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '9000',
  },
  {
    key: 'WHATSAPP_TYPING_SPEED',
    label: 'مضاعف سرعة الكتابة',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '1',
  },
  {
    key: 'WHATSAPP_CHUNK_CHARS',
    label: 'حجم جزء الرسالة',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '600',
  },
  {
    key: 'WHATSAPP_MAX_CHUNKS',
    label: 'أقصى عدد أجزاء',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '4',
  },
  {
    key: 'WHATSAPP_STT_ENABLED',
    label: 'تفريغ الرسائل الصوتية',
    group: 'whatsapp',
    type: 'toggle',
    secret: false,
    default: '1',
  },
  {
    key: 'WHATSAPP_STT_MODEL',
    label: 'نموذج التفريغ الصوتي',
    group: 'whatsapp',
    type: 'text',
    secret: false,
    default: 'whisper-1',
  },
  {
    key: 'WHATSAPP_MEMORY_ENABLED',
    label: 'ذاكرة الوكيل',
    group: 'whatsapp',
    type: 'toggle',
    secret: false,
    default: '1',
  },
  {
    key: 'WHATSAPP_MEMORY_MAX',
    label: 'أقصى ذكريات',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '40',
  },
  {
    key: 'WHATSAPP_MEMORY_RECALL',
    label: 'عدد الذكريات المسترجعة',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '12',
  },
  {
    key: 'WHATSAPP_MEMORY_MAX_CHARS',
    label: 'أقصى حروف للذكرى',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '400',
  },
  {
    key: 'WHATSAPP_SUMMARIZE_AFTER',
    label: 'تلخيص المحادثة بعد (أدوار)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '10',
  },
  {
    key: 'AGENT_QUERY_MAX_ROWS',
    label: 'أقصى صفوف لاستعلام الوكيل',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '200',
  },
  {
    key: 'AGENT_QUERY_TIMEOUT_MS',
    label: 'مهلة استعلام الوكيل (ms)',
    group: 'whatsapp',
    type: 'number',
    secret: false,
    default: '8000',
  },
];

const BY_KEY = Object.fromEntries(DEFINITIONS.map((d) => [d.key, d]));
const GROUPS = [
  { id: 'general', label: 'عام', icon: 'fa-globe' },
  { id: 'google', label: 'Google', icon: 'fa-brands fa-google' },
  { id: 'ai', label: 'الذكاء الاصطناعي', icon: 'fa-robot' },
  { id: 'whatsapp', label: 'واتساب', icon: 'fa-brands fa-whatsapp' },
];

function dbKey(envKey) {
  return `sys:${envKey}`;
}

function invalidateCache() {
  cache = { at: 0, map: null };
}

async function loadMap(force = false) {
  const now = Date.now();
  if (!force && cache.map && now - cache.at < CACHE_TTL_MS) return cache.map;
  const db = getDb();
  const rows = (await db.query("SELECT key, value FROM settings WHERE key LIKE 'sys:%'")).rows;
  const map = {};
  for (const row of rows) {
    const envKey = String(row.key).slice(4);
    const def = BY_KEY[envKey];
    if (!def) continue;
    let value = row.value;
    if (def.secret && value) {
      value = decrypt(value);
      if (value == null) value = '';
    }
    map[envKey] = value == null ? '' : String(value);
  }
  cache = { at: now, map };
  return map;
}

/**
 * قراءة قيمة حية: DB → env → default
 * @param {string} key
 * @param {string} [fallback]
 */
async function get(key, fallback) {
  const def = BY_KEY[key];
  const map = await loadMap();
  if (Object.prototype.hasOwnProperty.call(map, key) && map[key] !== '') {
    return map[key];
  }
  const fromEnv = process.env[key];
  if (fromEnv != null && fromEnv !== '') return String(fromEnv);
  if (fallback !== undefined) return fallback;
  if (def?.default != null) return String(def.default);
  return '';
}

async function getInt(key, fallback) {
  const raw = await get(key, fallback != null ? String(fallback) : undefined);
  const n = parseInt(String(raw), 10);
  return Number.isFinite(n) ? n : (fallback ?? 0);
}

async function getFloat(key, fallback) {
  const raw = await get(key, fallback != null ? String(fallback) : undefined);
  const n = parseFloat(String(raw));
  return Number.isFinite(n) ? n : (fallback ?? 0);
}

/** true ما لم تكن القيمة صراحةً '0' أو 'false' أو 'off' */
async function getBool(key, defaultTrue = true) {
  const raw = (await get(key, defaultTrue ? '1' : '0')).trim().toLowerCase();
  if (!raw) return defaultTrue;
  return !(raw === '0' || raw === 'false' || raw === 'off' || raw === 'no');
}

async function setMany(values) {
  const db = getDb();
  const saved = [];
  for (const [key, rawValue] of Object.entries(values || {})) {
    const def = BY_KEY[key];
    if (!def) continue;
    let value = rawValue == null ? '' : String(rawValue).trim();
    // لا تكتب فوق السر بقيمة القناع
    if (def.secret && (!value || value === '********')) continue;
    const storeValue = def.secret ? encrypt(value) : value;
    await db.query(
      `INSERT INTO settings (key, value, updated_at)
       VALUES ($1, $2, CURRENT_TIMESTAMP)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP`,
      [dbKey(key), storeValue]
    );
    // حدّث process.env فوراً حتى المسارات التي ما زالت تقرأه تعمل بلا إعادة تشغيل
    process.env[key] = value;
    saved.push(key);
  }
  invalidateCache();

  // مزامنة مفتاح Evolution مع ملف الخدمة المضمّنة إن وُجد
  if (saved.includes('EVOLUTION_API_KEY') && process.env.EVOLUTION_EMBEDDED === '1') {
    try {
      syncEvolutionEnvFile(process.env.EVOLUTION_API_KEY);
    } catch (e) {
      console.warn('[systemSettings] تعذّر تحديث .env الخاص بـ Evolution:', e.message);
    }
  }

  return saved;
}

function syncEvolutionEnvFile(apiKey) {
  const fs = require('fs');
  const path = require('path');
  const file = path.join(__dirname, '..', 'integrations', 'evolution-api', '.env');
  if (!fs.existsSync(file)) return;
  let text = fs.readFileSync(file, 'utf8');
  if (/^AUTHENTICATION_API_KEY=/m.test(text)) {
    text = text.replace(/^AUTHENTICATION_API_KEY=.*$/m, `AUTHENTICATION_API_KEY=${apiKey}`);
  } else {
    text += `\nAUTHENTICATION_API_KEY=${apiKey}\n`;
  }
  fs.writeFileSync(file, text, { mode: 0o600 });
}

/** عرض آمن للواجهة: الأسرار تُقنَّع */
async function getPublicSnapshot() {
  const map = await loadMap();
  const groups = GROUPS.map((g) => ({
    ...g,
    fields: DEFINITIONS.filter((d) => d.group === g.id).map((d) => {
      const fromDb = Object.prototype.hasOwnProperty.call(map, d.key);
      const resolved = fromDb && map[d.key] !== ''
        ? map[d.key]
        : (process.env[d.key] || d.default || '');
      const hasValue = Boolean(resolved);
      return {
        key: d.key,
        label: d.label,
        type: d.type,
        secret: d.secret,
        help: d.help || '',
        placeholder: d.placeholder || '',
        source: fromDb && map[d.key] !== '' ? 'db' : (process.env[d.key] ? 'env' : (d.default != null ? 'default' : 'empty')),
        value: d.secret ? (hasValue ? '********' : '') : resolved,
        hasValue,
      };
    }),
  }));
  return { groups };
}

/** عند الإقلاع: املأ process.env من القيم المحفوظة في قاعدة البيانات */
async function hydrateEnvFromDb() {
  try {
    const map = await loadMap(true);
    let n = 0;
    for (const [key, value] of Object.entries(map)) {
      if (value !== '' && value != null) {
        process.env[key] = value;
        n += 1;
      }
    }
    if (n) console.log(`[systemSettings] تم تحميل ${n} إعداداً من قاعدة البيانات`);
  } catch (e) {
    console.warn('[systemSettings] تعذّر التحميل من قاعدة البيانات:', e.message);
  }
}

module.exports = {
  DEFINITIONS,
  GROUPS,
  get,
  getInt,
  getFloat,
  getBool,
  setMany,
  getPublicSnapshot,
  invalidateCache,
  hydrateEnvFromDb,
};
