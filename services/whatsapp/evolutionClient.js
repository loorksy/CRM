/**
 * عميل Evolution API — الطبقة الوحيدة التي تتحدث مع خدمة واتساب.
 *
 * Evolution API يعمل كعملية مستقلة (integrations/evolution-api) على منفذ خاص،
 * والنظام يتصل به عبر REST فقط. هذا الفصل مقصود: انهيار جلسة Baileys
 * لا يُسقط الـ ERP، وتحديث Evolution من المصدر لا يتطلب تعديل كودنا.
 *
 * التوثيق يتم عبر ترويسة apikey (المفتاح العام AUTHENTICATION_API_KEY).
 */

/**
 * عند التضمين (EVOLUTION_EMBEDDED=1) يعمل Evolution داخل نفس العملية على
 * نفس المنفذ، فنناديه عبر العنوان المحلي على مسار التركيب. نبقى على REST
 * بدل استدعاء الدوال الداخلية حتى تمر الطلبات بحُرّاس التوثيق نفسها.
 */
const EMBEDDED = process.env.EVOLUTION_EMBEDDED === '1';
const MOUNT_PATH = process.env.EVOLUTION_MOUNT_PATH || '/whatsapp-api';
const DEFAULT_URL = EMBEDDED
  ? `http://127.0.0.1:${process.env.PORT || 3020}${MOUNT_PATH}`
  : 'http://127.0.0.1:8080';

const EVOLUTION_URL = (process.env.EVOLUTION_API_URL || DEFAULT_URL).replace(/\/+$/, '');
const EVOLUTION_KEY = process.env.EVOLUTION_API_KEY || '';
const REQUEST_TIMEOUT_MS = parseInt(process.env.EVOLUTION_TIMEOUT_MS || '20000', 10);

/** هل التكامل مُهيّأ أصلاً؟ يُستخدم لتعطيل الميزة بهدوء بدل رمي أخطاء */
function isConfigured() {
  return Boolean(EVOLUTION_KEY);
}

/**
 * نداء REST مع مهلة زمنية. لا يرمي أبداً أخطاء شبكة خام —
 * يُعيد دائماً { ok, status, data, error } ليتعامل معها المستدعي بأمان.
 */
async function request(method, path, body) {
  if (!isConfigured()) {
    return { ok: false, status: 0, data: null, error: 'EVOLUTION_API_KEY غير مضبوط' };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const res = await fetch(`${EVOLUTION_URL}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        apikey: EVOLUTION_KEY,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });

    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (_) {
      data = text;
    }

    if (!res.ok) {
      const message = (data && (data.message || data.error)) || `HTTP ${res.status}`;
      return { ok: false, status: res.status, data, error: String(message) };
    }
    return { ok: true, status: res.status, data, error: null };
  } catch (e) {
    const error = e.name === 'AbortError' ? 'انتهت مهلة الاتصال بخدمة واتساب' : e.message;
    return { ok: false, status: 0, data: null, error };
  } finally {
    clearTimeout(timer);
  }
}

/** تطبيع رقم/معرّف واتساب إلى صيغة JID موحّدة: 963900000000@s.whatsapp.net */
function normalizeJid(input) {
  if (!input) return null;
  const raw = String(input).trim();
  if (raw.includes('@')) {
    const [user, domain] = raw.split('@');
    const digits = user.split(':')[0].replace(/\D/g, '');
    if (!digits) return null;
    return `${digits}@${domain}`;
  }
  const digits = raw.replace(/\D/g, '');
  if (!digits) return null;
  return `${digits}@s.whatsapp.net`;
}

/** الجزء الرقمي من الـ JID — يُستخدم عند الإرسال وفي العرض */
function jidToNumber(jid) {
  if (!jid) return null;
  return String(jid).split('@')[0].split(':')[0].replace(/\D/g, '') || null;
}

/** هل هذا JID لمجموعة أو قناة أو بث؟ الوكيل يتجاهلها بالكامل */
function isGroupOrBroadcast(jid) {
  const s = String(jid || '');
  return s.endsWith('@g.us') || s.endsWith('@broadcast') || s.endsWith('@newsletter');
}

/** إنشاء نسخة (instance) جديدة بقناة Baileys */
async function createInstance(instanceName, options = {}) {
  return request('POST', '/instance/create', {
    instanceName,
    integration: options.integration || 'WHATSAPP-BAILEYS',
    qrcode: options.qrcode !== false,
    ...(options.number ? { number: options.number } : {}),
  });
}

/** طلب رمز QR / إعادة الاتصال */
async function connect(instanceName) {
  return request('GET', `/instance/connect/${encodeURIComponent(instanceName)}`);
}

/** حالة الاتصال: open | connecting | close */
async function connectionState(instanceName) {
  return request('GET', `/instance/connectionState/${encodeURIComponent(instanceName)}`);
}

async function fetchInstances() {
  return request('GET', '/instance/fetchInstances');
}

async function logout(instanceName) {
  return request('DELETE', `/instance/logout/${encodeURIComponent(instanceName)}`);
}

async function deleteInstance(instanceName) {
  return request('DELETE', `/instance/delete/${encodeURIComponent(instanceName)}`);
}

/**
 * توجيه أحداث النسخة إلى الـ webhook الخاص بنا.
 * نمرّر السر في ترويسة مخصّصة ليتحقق منها المستقبِل — نقطة الـ webhook
 * عامة بالضرورة، فالسر هو ما يمنع أي طرف من حقن رسائل مزيفة.
 */
async function setWebhook(instanceName, url, secret, events) {
  return request('POST', `/webhook/set/${encodeURIComponent(instanceName)}`, {
    webhook: {
      enabled: true,
      url,
      byEvents: false,
      base64: false,
      headers: {
        'Content-Type': 'application/json',
        'x-lorkerp-webhook-secret': secret || '',
      },
      events: events || ['MESSAGES_UPSERT', 'CONNECTION_UPDATE'],
    },
  });
}

async function findWebhook(instanceName) {
  return request('GET', `/webhook/find/${encodeURIComponent(instanceName)}`);
}

/** إرسال رسالة نصية. `to` يقبل رقماً أو JID. */
async function sendText(instanceName, to, text, options = {}) {
  const number = jidToNumber(to);
  if (!number) return { ok: false, status: 0, data: null, error: 'رقم مستلم غير صالح' };

  return request('POST', `/message/sendText/${encodeURIComponent(instanceName)}`, {
    number,
    text: String(text ?? ''),
    delay: options.delay ?? 0,
    linkPreview: options.linkPreview ?? false,
  });
}

/**
 * استخراج نص الرسالة من حمولة Baileys بمختلف أشكالها.
 * يُعيد null للرسائل غير النصية (صوت/صورة بلا تعليق/ملصق).
 */
function extractMessageText(message) {
  if (!message || typeof message !== 'object') return null;
  return (
    message.conversation ||
    message.extendedTextMessage?.text ||
    message.imageMessage?.caption ||
    message.videoMessage?.caption ||
    message.documentMessage?.caption ||
    message.buttonsResponseMessage?.selectedDisplayText ||
    message.listResponseMessage?.title ||
    message.templateButtonReplyMessage?.selectedDisplayText ||
    message.ephemeralMessage?.message?.conversation ||
    message.ephemeralMessage?.message?.extendedTextMessage?.text ||
    null
  );
}

/**
 * تحويل حدث MESSAGES_UPSERT إلى شكل بسيط يفهمه الوكيل.
 * يُعيد null لكل ما يجب تجاهله: رسائلنا نحن، المجموعات، غير النصي.
 */
function parseIncomingMessage(payload) {
  const data = payload?.data;
  if (!data) return null;

  const entry = Array.isArray(data) ? data[0] : data;
  const key = entry?.key;
  if (!key || key.fromMe) return null;

  const remoteJid = key.remoteJid;
  if (!remoteJid || isGroupOrBroadcast(remoteJid)) return null;

  const text = extractMessageText(entry.message);
  if (!text || !String(text).trim()) return null;

  return {
    instanceName: payload.instance || null,
    phoneJid: normalizeJid(remoteJid),
    messageId: key.id || null,
    pushName: entry.pushName || null,
    text: String(text).trim(),
    timestamp: entry.messageTimestamp || null,
  };
}

module.exports = {
  isConfigured,
  request,
  normalizeJid,
  jidToNumber,
  isGroupOrBroadcast,
  createInstance,
  connect,
  connectionState,
  fetchInstances,
  logout,
  deleteInstance,
  setWebhook,
  findWebhook,
  sendText,
  extractMessageText,
  parseIncomingMessage,
  EVOLUTION_URL,
};
