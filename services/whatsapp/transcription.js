/**
 * تفريغ الرسائل الصوتية إلى نص.
 *
 * المستخدم يرسل بصمة صوتية، فننزّلها من Evolution ونمرّرها إلى Whisper،
 * ثم يكمل الوكيل مساره الطبيعي على النص الناتج. لا فرق بعد ذلك بين رسالة
 * كُتبت ورسالة قيلت.
 *
 * يعيد استعمال مفتاح OpenAI المضبوط في إعدادات النظام (ai_config)، فلا
 * يحتاج المسؤول إعداداً إضافياً. إن كان المزوّد المضبوط هو Gemini وحده،
 * يمكن ضبط OPENAI_API_KEY في البيئة للتفريغ فقط.
 */

const OpenAI = require('openai');
const aiService = require('../aiService');

function sttModel() { return process.env.WHATSAPP_STT_MODEL || 'whisper-1'; }
function maxSeconds() { return parseInt(process.env.WHATSAPP_STT_MAX_SECONDS || '300', 10); }
function maxBytes() { return parseInt(process.env.WHATSAPP_STT_MAX_BYTES || '20000000', 10); }

/** مفتاح OpenAI: من إعدادات النظام أولاً، ثم من البيئة */
async function resolveOpenAiKey() {
  try {
    const cfg = await aiService.getProviderConfig('openai');
    if (cfg?.apiKey) return cfg.apiKey;
  } catch (_) {
    /* الإعدادات قد لا تكون مهيّأة بعد */
  }
  return process.env.OPENAI_API_KEY || null;
}

function isEnabled() {
  return process.env.WHATSAPP_STT_ENABLED !== '0';
}

/** امتداد الملف المناسب — Whisper يعتمد عليه في تحديد الصيغة */
function extensionFor(mimetype) {
  const m = String(mimetype || '').toLowerCase();
  if (m.includes('mp4') || m.includes('m4a')) return 'm4a';
  if (m.includes('mpeg') || m.includes('mp3')) return 'mp3';
  if (m.includes('wav')) return 'wav';
  if (m.includes('webm')) return 'webm';
  return 'ogg';
}

/**
 * تنزيل الصوت من Evolution وتفريغه.
 *
 * @param {object} evolution عميل Evolution
 * @param {string} instanceName اسم النسخة
 * @param {object} messageKey مفتاح الرسالة الواردة
 * @param {object} audioMeta { mimetype, seconds, isVoiceNote }
 * @returns {Promise<{ok: boolean, text?: string, reason?: string}>}
 */
async function transcribeVoiceNote(evolution, instanceName, messageKey, audioMeta = {}) {
  if (!isEnabled()) return { ok: false, reason: 'disabled' };

  if (audioMeta.seconds && audioMeta.seconds > maxSeconds()) {
    return { ok: false, reason: 'too_long' };
  }

  const apiKey = await resolveOpenAiKey();
  if (!apiKey) return { ok: false, reason: 'no_key' };

  const media = await evolution.getMediaBase64(instanceName, messageKey);
  if (!media.ok) {
    console.warn('[whatsapp] تعذّر تنزيل الصوت:', media.error);
    return { ok: false, reason: 'download_failed' };
  }

  const base64 = media.data?.base64 || media.data?.media || null;
  if (!base64) return { ok: false, reason: 'empty_media' };

  const buffer = Buffer.from(base64, 'base64');
  if (!buffer.length) return { ok: false, reason: 'empty_media' };
  if (buffer.length > maxBytes()) return { ok: false, reason: 'too_large' };

  const mimetype = media.data?.mimetype || audioMeta.mimetype || 'audio/ogg';
  const filename = `voice.${extensionFor(mimetype)}`;

  try {
    const client = new OpenAI({ apiKey });
    const file = await OpenAI.toFile(buffer, filename, { type: mimetype });

    const result = await client.audio.transcriptions.create({
      file,
      model: sttModel(),
      language: 'ar',
      // تلميح بالمجال يحسّن دقة المصطلحات المحاسبية والأسماء
      prompt: 'محادثة محاسبية عن الرواتب والأرصدة المؤجلة والديون والدورات المالية.',
    });

    const text = String(result?.text || '').trim();
    if (!text) return { ok: false, reason: 'empty_transcript' };

    return { ok: true, text };
  } catch (e) {
    console.error('[whatsapp] فشل تفريغ الصوت:', e.message);
    return { ok: false, reason: 'stt_failed' };
  }
}

/** رسائل الاعتذار حسب سبب الفشل — بلا تفاصيل تقنية للمستخدم */
const FAILURE_REPLIES = {
  disabled: 'ما بقدر أسمع الرسائل الصوتية حالياً. اكتبلي سؤالك نصاً لو سمحت.',
  no_key: 'ما بقدر أسمع الرسائل الصوتية حالياً. اكتبلي سؤالك نصاً لو سمحت.',
  too_long: 'التسجيل طويل شوي. جرّب تسجيل أقصر أو اكتبلي سؤالك.',
  too_large: 'حجم التسجيل كبير. جرّب تسجيل أقصر أو اكتبلي سؤالك.',
  download_failed: 'ما وصلني التسجيل كامل. جرّب تبعته مرة تانية أو اكتبلي.',
  empty_media: 'ما وصلني التسجيل كامل. جرّب تبعته مرة تانية أو اكتبلي.',
  empty_transcript: 'ما قدرت أفهم التسجيل. جرّب تحكي أوضح شوي أو اكتبلي سؤالك.',
  stt_failed: 'ما قدرت أفهم التسجيل. جرّب تبعته مرة تانية أو اكتبلي سؤالك.',
};

function failureReply(reason) {
  return FAILURE_REPLIES[reason] || FAILURE_REPLIES.stt_failed;
}

module.exports = { transcribeVoiceNote, failureReply, isEnabled };
