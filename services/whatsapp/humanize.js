/**
 * محاكاة السلوك البشري في المحادثة.
 *
 * غرضان معاً:
 *  • تجربة أفضل — رد يصل كأن شخصاً كتبه، لا كتلة تنفجر فجأة
 *  • حماية الرقم — واتساب يرصد الأنماط الآلية: رد فوري بطول ثابت وبلا
 *    حالة كتابة هو توقيع بوت. الإيقاع البشري يقلّل خطر الحظر فعلياً.
 *
 * لا شيء هنا يمس البيانات أو الصلاحيات — تنسيق وتوقيت فقط.
 */

function charsPerSecond() { return parseFloat(process.env.WHATSAPP_TYPING_CPS || '22'); }
function minTypingMs() { return parseInt(process.env.WHATSAPP_TYPING_MIN_MS || '1200', 10); }
function maxTypingMs() { return parseInt(process.env.WHATSAPP_TYPING_MAX_MS || '9000', 10); }
function chunkTarget() { return parseInt(process.env.WHATSAPP_CHUNK_CHARS || '600', 10); }
function maxChunks() { return parseInt(process.env.WHATSAPP_MAX_CHUNKS || '4', 10); }

/**
 * زمن الكتابة المناسب لنص معيّن، مع تشويش بسيط.
 * التشويش مقصود: التوقيت الثابت تماماً هو ما يميّز الآلة من الإنسان.
 */
function typingDelayFor(text, { speed = 1 } = {}) {
  const length = String(text || '').length;
  const base = (length / Math.max(charsPerSecond() * speed, 1)) * 1000;
  const jitter = base * (0.85 + Math.random() * 0.3);
  return Math.round(Math.min(Math.max(jitter, minTypingMs()), maxTypingMs()));
}

/**
 * تحويل تنسيق ماركداون الشائع إلى تنسيق واتساب.
 *
 * واتساب لا يفهم `**عريض**` — يفهم `*عريض*`. النماذج تُخرج ماركداون
 * بالعادة، فيصل للمستخدم نجوم حرفية قبيحة. هذه الدالة تصلحها.
 */
function toWhatsAppFormatting(text) {
  let out = String(text || '');

  // ```كتلة``` تبقى كما هي — واتساب يدعمها
  // **عريض** أو __عريض__ ⇒ *عريض*
  out = out.replace(/\*\*(.+?)\*\*/gs, '*$1*');
  out = out.replace(/__(.+?)__/gs, '*$1*');

  // عناوين ماركداون ⇒ سطر عريض
  out = out.replace(/^#{1,6}\s*(.+)$/gm, '*$1*');

  // قوائم بشرطة أو نجمة ⇒ نقطة عربية مريحة
  out = out.replace(/^\s*[-*+]\s+/gm, '• ');

  // جداول ماركداون لا تُعرض في واتساب — نحولها إلى أسطر.
  // نستعمل [ \t]* لا \s* في نهاية السطر: \s يبتلع السطر الجديد فتلتحم الصفوف.
  out = out.replace(/^[ \t]*\|?[ \t:-]*\|[ \t|:-]*$/gm, '');
  out = out.replace(/^[ \t]*\|(.+)\|[ \t]*$/gm, (_, row) =>
    row.split('|').map((c) => c.trim()).filter(Boolean).join(' — ')
  );

  // تنظيف الأسطر الفارغة المتراكمة
  out = out.replace(/\n{3,}/g, '\n\n');

  return out.trim();
}

/**
 * تقسيم رد طويل إلى فقاعات متتابعة عند حدود طبيعية.
 * الأولوية: فقرة فارغة، ثم نهاية سطر، ثم نهاية جملة.
 */
function splitReply(text, { target = chunkTarget(), maxChunks: max = maxChunks() } = {}) {
  const clean = String(text || '').trim();
  if (!clean) return [];
  if (clean.length <= target) return [clean];

  const paragraphs = clean.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  const chunks = [];
  let current = '';

  const push = () => {
    if (current.trim()) chunks.push(current.trim());
    current = '';
  };

  for (const para of paragraphs) {
    if (!current) {
      current = para;
      continue;
    }
    if ((current + '\n\n' + para).length <= target) {
      current += '\n\n' + para;
    } else {
      push();
      current = para;
    }
  }
  push();

  // فقرة واحدة أطول من الحد: نقسّمها عند نهايات الجمل
  const refined = [];
  for (const chunk of chunks) {
    if (chunk.length <= target * 1.6) {
      refined.push(chunk);
      continue;
    }
    const sentences = chunk.split(/(?<=[.!؟?\n])\s+/);
    let buf = '';
    for (const s of sentences) {
      if (buf && (buf + ' ' + s).length > target) {
        refined.push(buf.trim());
        buf = s;
      } else {
        buf = buf ? buf + ' ' + s : s;
      }
    }
    if (buf.trim()) refined.push(buf.trim());
  }

  // احترام السقف: ما زاد يُدمج في الفقاعة الأخيرة بدل ضياعه
  if (refined.length <= max) return refined;
  const head = refined.slice(0, max - 1);
  head.push(refined.slice(max - 1).join('\n\n'));
  return head;
}

/**
 * إرسال رد بإيقاع بشري: حالة كتابة، ثم فقاعة، ثم توقف قصير، ثم التالية.
 *
 * @param {object} evolution عميل Evolution
 * @param {string} instanceName اسم النسخة
 * @param {string} jid المستلم
 * @param {string} text نص الرد
 * @param {object} opts { enabled, speed, quoted }
 */
async function sendHumanized(evolution, instanceName, jid, text, opts = {}) {
  const formatted = toWhatsAppFormatting(text);
  const enabled = opts.enabled !== false;
  const speed = opts.speed || 1;

  if (!enabled) {
    const res = await evolution.sendText(instanceName, jid, formatted, { delay: 0 });
    return { sent: res.ok ? 1 : 0, chunks: 1, error: res.ok ? null : res.error };
  }

  const chunks = splitReply(formatted);
  let sent = 0;
  let firstError = null;

  for (let i = 0; i < chunks.length; i++) {
    const delay = typingDelayFor(chunks[i], { speed });

    // الفقاعة الأولى: التأخير مدمج في sendText فيظهر «يكتب» ثم يصل النص.
    // الفقاعات التالية: توقف قصير بينها يشبه إيقاع الإنسان.
    const res = await evolution.sendText(instanceName, jid, chunks[i], {
      delay,
      presence: 'composing',
      quoted: i === 0 ? opts.quoted : undefined,
    });

    if (res.ok) sent++;
    else if (!firstError) firstError = res.error;
  }

  return { sent, chunks: chunks.length, error: firstError };
}

module.exports = {
  typingDelayFor,
  toWhatsAppFormatting,
  splitReply,
  sendHumanized,
  get CHUNK_TARGET() { return chunkTarget(); },
};
