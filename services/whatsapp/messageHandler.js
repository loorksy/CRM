/**
 * معالج الرسائل الواردة — البوابة بين واتساب والنظام.
 *
 * تسلسل كل رسالة، بلا استثناء:
 *   1) تحديد المعدّل (منع الإغراق)
 *   2) تحديد الهوية من رقم المرسِل عبر قاعدة البيانات
 *   3) إن لم تكن الهوية مُتحقَّقاً منها → مسار التفعيل فقط، بلا أي بيانات
 *   4) إن كانت مُتحقَّقاً منها → الوكيل، بأدوات نطاقه هو حصراً
 *
 * لا يوجد أي مسار يقفز فوق الخطوة (2).
 */

const evolution = require('./evolutionClient');
const identity = require('./identityService');
const agent = require('./agentBrain');
const humanize = require('./humanize');
const transcription = require('./transcription');

function msgRateLimit() { return parseInt(process.env.WHATSAPP_RATE_LIMIT || '20', 10); }
function msgRateWindowSec() { return parseInt(process.env.WHATSAPP_RATE_WINDOW_SEC || '60', 10); }

/** حماية من إعادة تسليم نفس الحدث من Evolution (تكرار الـ webhook) */
const recentMessageIds = new Map();
const RECENT_TTL_MS = 5 * 60 * 1000;

function isDuplicate(messageId) {
  if (!messageId) return false;
  const now = Date.now();
  for (const [id, ts] of recentMessageIds) {
    if (now - ts > RECENT_TTL_MS) recentMessageIds.delete(id);
  }
  if (recentMessageIds.has(messageId)) return true;
  recentMessageIds.set(messageId, now);
  return false;
}

const HELP_TEXT = [
  'المساعد الآلي لنظام LorkERP.',
  '',
  'يمكنك السؤال عن حسابك مباشرة، مثال:',
  '• كم رصيدي المؤجل؟',
  '• كم راتبي في آخر دورة؟',
  '• هل عليّ خصومات؟',
  '',
  'أوامر: «مساعدة» لعرض هذه الرسالة، «خروج» لإنهاء الجلسة.',
].join('\n');

function isHelpCommand(text) {
  return /^(مساعدة|مساعده|help|\?|؟)$/i.test(String(text).trim());
}

function isLogoutCommand(text) {
  return /^(خروج|انهاء|إنهاء|logout|exit)$/i.test(String(text).trim());
}

function looksLikeCode(text) {
  return /^\d{6}$/.test(String(text).trim().replace(/\s/g, ''));
}

/**
 * الرسالة الوحيدة التي يراها رقم غير مسجّل.
 * مقصود ألا تكشف شيئاً: لا تؤكد ولا تنفي وجود أي حساب.
 */
const UNKNOWN_NUMBER_REPLY =
  'هذا الرقم غير مسجّل في النظام. للتسجيل يرجى التواصل مع إدارة النظام.';

async function handleIncoming(payload, { instanceName } = {}) {
  const incoming = evolution.parseIncomingMessage(payload);
  if (!incoming) return { handled: false, reason: 'ignored' };
  if (isDuplicate(incoming.messageId)) return { handled: false, reason: 'duplicate' };

  const jid = incoming.phoneJid;
  const instance = incoming.instanceName || instanceName;

  /** إرسال بسيط بإيقاع بشري خفيف — للرسائل القصيرة في مسار التفعيل */
  const send = async (message) => {
    if (!instance) {
      console.warn('[whatsapp] لا توجد نسخة لإرسال الرد إلى', jid);
      return;
    }
    const res = await evolution.sendText(instance, jid, humanize.toWhatsAppFormatting(message), {
      delay: humanize.typingDelayFor(message),
    });
    if (!res.ok) console.warn('[whatsapp] فشل إرسال الرد:', res.error);
  };

  // 1) تحديد المعدّل — يُطبَّق قبل أي عمل، حتى على الأرقام غير المسجّلة
  const withinLimit = await identity.checkRateLimit(jid, 'messages', msgRateLimit(), msgRateWindowSec());
  if (!withinLimit) {
    await identity.logAccess({ phoneJid: jid, action: 'rate_limited', allowed: false });
    return { handled: false, reason: 'rate_limited' };
  }

  // 2) تحديد الهوية من قاعدة البيانات وحدها
  const resolved = await identity.resolveScope(jid);

  // العلامتان الزرقاوان: نعلّم القراءة فقط لمن نتعامل معه فعلاً.
  // الأرقام المجهولة لا تحصل على تأكيد قراءة — لا نؤكد لها وجود نظام.
  if (instance && resolved.identity) {
    evolution.markAsRead(instance, incoming.messageKey).catch(() => {});
  }

  // 3) الهوية غير جاهزة → مسار التفعيل، ولا شيء غيره
  if (!resolved.allowed) {
    const textForAuth = incoming.type === 'text' ? incoming.text : '';
    if (incoming.type === 'audio') {
      await send('لتفعيل حسابك أرسل رمز التفعيل نصاً، مو تسجيل صوتي.');
      return { handled: true, reason: 'audio_before_verification' };
    }
    return handleUnverified(resolved, { jid, text: textForAuth, send });
  }

  const scope = resolved.scope;

  // 4) رسالة صوتية → نفرّغها إلى نص قبل أي شيء آخر
  let text = incoming.text;
  if (incoming.type === 'audio') {
    // «يسجّل الآن» أثناء التفريغ: إشارة مفهومة أننا نستمع
    if (instance) evolution.sendPresence(instance, jid, 'recording', 4000).catch(() => {});

    const stt = await transcription.transcribeVoiceNote(
      evolution, instance, incoming.messageKey, incoming.audio
    );

    await identity.logAccess({
      phoneJid: jid, identityId: scope.identityId, ownerUserId: scope.ownerUserId,
      action: stt.ok ? 'voice_transcribed' : 'voice_failed',
      allowed: stt.ok, detail: stt.ok ? stt.text.slice(0, 300) : stt.reason,
    });

    if (!stt.ok) {
      await send(transcription.failureReply(stt.reason));
      return { handled: true, reason: `voice_${stt.reason}` };
    }
    text = stt.text;
  }

  if (!text || !text.trim()) return { handled: false, reason: 'empty' };

  if (isLogoutCommand(text)) {
    await identity.clearSession(jid);
    await identity.logAccess({
      phoneJid: jid, identityId: scope.identityId, ownerUserId: scope.ownerUserId,
      action: 'session_cleared',
    });
    await send('تم إنهاء الجلسة. أرسل أي رسالة للبدء من جديد.');
    return { handled: true, reason: 'logout' };
  }

  if (isHelpCommand(text)) {
    await send(HELP_TEXT);
    return { handled: true, reason: 'help' };
  }

  // 4) الوكيل — بأدوات هذا النطاق حصراً
  const session = await identity.getSession(jid);
  let history = [];
  if (session?.history_json) {
    try {
      history = JSON.parse(session.history_json) || [];
    } catch (_) {
      history = [];
    }
  }

  await identity.logAccess({
    phoneJid: jid, identityId: scope.identityId, ownerUserId: scope.ownerUserId,
    subjectRef: scope.subjectType === 'member' ? scope.memberUserId : `operator:${scope.operatorUserId}`,
    action: 'message_received', detail: text.slice(0, 500),
  });

  /**
   * «يكتب الآن» من اللحظة الأولى. تفكير النموذج واستعلاماته قد تستغرق
   * ثوانٍ، وتأخير sendText لا يبدأ إلا بعد جهوز الرد — فلولا هذه النبضة
   * لرأى المستخدم صمتاً ثم رداً مفاجئاً.
   *
   * حالة الكتابة في واتساب تنطفئ وحدها، لذلك نجدّدها دورياً حتى ينتهي
   * التفكير.
   */
  let typingTimer = null;
  if (instance) {
    const pulse = () => evolution.sendPresence(instance, jid, 'composing', 8000).catch(() => {});
    pulse();
    typingTimer = setInterval(pulse, 7000);
  }

  let reply, newHistory;
  try {
    ({ reply, history: newHistory } = await agent.generateReply(scope, history, text));
  } finally {
    if (typingTimer) clearInterval(typingTimer);
  }

  await identity.saveSession(jid, {
    identityId: scope.identityId,
    state: 'active',
    history: newHistory,
  });

  if (instance) {
    const result = await humanize.sendHumanized(evolution, instance, jid, reply, {
      enabled: process.env.WHATSAPP_HUMANIZE !== '0',
      speed: parseFloat(process.env.WHATSAPP_TYPING_SPEED || '1'),
      quoted: incoming.type === 'audio' ? { key: incoming.messageKey } : undefined,
    });
    if (result.error) console.warn('[whatsapp] فشل إرسال جزء من الرد:', result.error);
  }

  return { handled: true, reason: 'answered' };
}

/**
 * مسار الأرقام غير المُتحقَّق منها.
 * الرمز يُصدره مسؤول النظام من لوحة التحكم ويسلّمه للمستخدم خارج واتساب —
 * إرسال الرمز إلى نفس الرقم الذي يراسلنا لا يثبت شيئاً، لأن المرسِل
 * يتحكم بذلك الرقم أصلاً. التسليم خارج القناة هو ما يجعل الرمز عاملاً حقيقياً.
 */
async function handleUnverified(resolved, { jid, text, send }) {
  const { reason, identity: ident } = resolved;

  if (reason === 'unknown_number') {
    await identity.logAccess({ phoneJid: jid, action: 'unknown_number', allowed: false });
    await send(UNKNOWN_NUMBER_REPLY);
    return { handled: true, reason: 'unknown_number' };
  }

  if (reason === 'blocked') {
    await identity.logAccess({
      phoneJid: jid, identityId: ident.id, ownerUserId: ident.owner_user_id,
      action: 'blocked_attempt', allowed: false,
    });
    await send('هذا الحساب موقوف. يرجى التواصل مع إدارة النظام.');
    return { handled: true, reason: 'blocked' };
  }

  if (reason === 'temporarily_blocked') {
    await send('تم تعليق المحاولات مؤقتاً بسبب إدخال رمز خاطئ عدة مرات. حاول لاحقاً أو راجع الإدارة.');
    return { handled: true, reason: 'temporarily_blocked' };
  }

  // reason === 'not_verified' — بانتظار رمز التفعيل
  if (looksLikeCode(text)) {
    const result = await identity.verifyOtp(ident, text.trim());

    if (result.ok) {
      await identity.saveSession(jid, { identityId: ident.id, state: 'active', history: [] });
      await send(`تم التفعيل بنجاح. أهلاً ${ident.display_name || ''}\n\n${HELP_TEXT}`.trim());
      return { handled: true, reason: 'verified' };
    }

    const messages = {
      no_pending_code: 'لا يوجد رمز تفعيل نشط. اطلب رمزاً جديداً من إدارة النظام.',
      expired: 'انتهت صلاحية الرمز. اطلب رمزاً جديداً من إدارة النظام.',
      too_many_attempts: 'تم تجاوز عدد المحاولات المسموح. تم تعليق التفعيل مؤقتاً.',
      invalid_format: 'الرمز يجب أن يكون ٦ أرقام.',
      wrong_code: result.remaining != null
        ? `الرمز غير صحيح. المحاولات المتبقية: ${result.remaining}`
        : 'الرمز غير صحيح.',
    };
    await send(messages[result.reason] || 'تعذّر التحقق من الرمز.');
    return { handled: true, reason: result.reason };
  }

  await send('رقمك مسجّل لكنه غير مُفعّل بعد.\nأرسل رمز التفعيل المكوّن من ٦ أرقام الذي زوّدتك به إدارة النظام.');
  return { handled: true, reason: 'awaiting_code' };
}

module.exports = { handleIncoming, HELP_TEXT };
