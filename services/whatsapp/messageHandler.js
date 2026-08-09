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

const MSG_RATE_LIMIT = parseInt(process.env.WHATSAPP_RATE_LIMIT || '20', 10);
const MSG_RATE_WINDOW_SEC = parseInt(process.env.WHATSAPP_RATE_WINDOW_SEC || '60', 10);

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
  const text = incoming.text;

  const send = async (message) => {
    if (!instance) {
      console.warn('[whatsapp] لا توجد نسخة لإرسال الرد إلى', jid);
      return;
    }
    const res = await evolution.sendText(instance, jid, message);
    if (!res.ok) console.warn('[whatsapp] فشل إرسال الرد:', res.error);
  };

  // 1) تحديد المعدّل — يُطبَّق قبل أي عمل، حتى على الأرقام غير المسجّلة
  const withinLimit = await identity.checkRateLimit(jid, 'messages', MSG_RATE_LIMIT, MSG_RATE_WINDOW_SEC);
  if (!withinLimit) {
    await identity.logAccess({ phoneJid: jid, action: 'rate_limited', allowed: false });
    return { handled: false, reason: 'rate_limited' };
  }

  // 2) تحديد الهوية من قاعدة البيانات وحدها
  const resolved = await identity.resolveScope(jid);

  // 3) الهوية غير جاهزة → مسار التفعيل، ولا شيء غيره
  if (!resolved.allowed) {
    return handleUnverified(resolved, { jid, text, send });
  }

  const scope = resolved.scope;

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

  const { reply, history: newHistory } = await agent.generateReply(scope, history, text);

  await identity.saveSession(jid, {
    identityId: scope.identityId,
    state: 'active',
    history: newHistory,
  });

  await send(reply);
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
