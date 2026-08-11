/**
 * خدمة الهوية والتحقق لوكيل واتساب.
 *
 * القاعدة الحاكمة: لا يُقرأ أي بيان من النظام قبل أن يكون الرقم مربوطاً
 * بهوية واحدة حالتها 'verified'. الربط يتم بخطوتين:
 *   1) الإدارة تربط رقم الهاتف بعضو أو مشغّل من لوحة التحكم (linkIdentity)
 *   2) الوكيل يرسل رمزاً للرقم نفسه، والمستخدم يؤكده (issueOtp/verifyOtp)
 *
 * لا توجد مسار "ربط ذاتي": المستخدم لا يستطيع إعلان هويته بنفسه، فمن يعرف
 * آيدي غيره لا يستطيع الوصول إليه.
 */

const crypto = require('crypto');
const { getDb } = require('../../db/database');
const { normalizeJid } = require('./evolutionClient');

function otpTtlMinutes() { return parseInt(process.env.WHATSAPP_OTP_TTL_MINUTES || '10', 10); }
function otpMaxAttempts() { return parseInt(process.env.WHATSAPP_OTP_MAX_ATTEMPTS || '5', 10); }
function blockMinutes() { return parseInt(process.env.WHATSAPP_BLOCK_MINUTES || '60', 10); }
function sessionTtlMinutes() { return parseInt(process.env.WHATSAPP_SESSION_TTL_MINUTES || '720', 10); }

/** تجزئة الرمز بمفتاح الخادم — لا يُخزَّن الرمز نصاً صريحاً أبداً */
function hashCode(code) {
  const secret = process.env.SESSION_SECRET || 'lorkerp-secret';
  return crypto.createHmac('sha256', secret).update(String(code)).digest('hex');
}

/** مقارنة ثابتة الزمن لمنع تسريب المعلومات عبر توقيت الاستجابة */
function safeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function generateCode() {
  return String(crypto.randomInt(100000, 1000000));
}

// ─────────────────────────── سجل التدقيق ───────────────────────────

/**
 * تسجيل كل محاولة وصول. لا يرمي أبداً: فشل التدقيق يجب ألا يُسقط الطلب،
 * لكنه يُسجَّل في الكونسول حتى لا يمر بصمت.
 */
async function logAccess(entry) {
  try {
    const db = getDb();
    await db.query(
      `INSERT INTO whatsapp_access_log
        (phone_jid, identity_id, owner_user_id, subject_ref, action, tool, detail, allowed)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [
        entry.phoneJid || null,
        entry.identityId || null,
        entry.ownerUserId || null,
        entry.subjectRef || null,
        entry.action,
        entry.tool || null,
        entry.detail ? String(entry.detail).slice(0, 2000) : null,
        entry.allowed !== false,
      ]
    );
  } catch (e) {
    console.warn('[whatsapp] فشل تسجيل التدقيق:', e.message);
  }
}

// ─────────────────────────── تحديد المعدّل ───────────────────────────

/**
 * نافذة منزلقة بسيطة مخزّنة في قاعدة البيانات.
 * تُعيد true إذا كان الطلب مسموحاً، و false إذا تجاوز الحد.
 */
async function checkRateLimit(phoneJid, bucket, limit, windowSeconds) {
  const db = getDb();
  const jid = normalizeJid(phoneJid);
  if (!jid) return false;

  const res = await db.query(
    `INSERT INTO whatsapp_rate_limit (phone_jid, bucket, window_start, count)
     VALUES ($1, $2, CURRENT_TIMESTAMP, 1)
     ON CONFLICT (phone_jid, bucket) DO UPDATE SET
       count = CASE
         WHEN whatsapp_rate_limit.window_start < CURRENT_TIMESTAMP - ($3 || ' seconds')::interval THEN 1
         ELSE whatsapp_rate_limit.count + 1
       END,
       window_start = CASE
         WHEN whatsapp_rate_limit.window_start < CURRENT_TIMESTAMP - ($3 || ' seconds')::interval THEN CURRENT_TIMESTAMP
         ELSE whatsapp_rate_limit.window_start
       END
     RETURNING count`,
    [jid, bucket, String(windowSeconds)]
  );

  const count = res.rows[0]?.count ?? 0;
  return count <= limit;
}

// ─────────────────────────── إدارة الهويات ───────────────────────────

/**
 * ربط رقم واتساب بعضو أو مشغّل. تُستدعى من لوحة التحكم فقط.
 * الهوية تبدأ 'pending' ولا تُمنح أي صلاحية قراءة حتى تكتمل خطوة الرمز.
 */
async function linkIdentity({ ownerUserId, subjectType, memberUserId, operatorUserId, phone, displayName, createdBy }) {
  const db = getDb();
  const jid = normalizeJid(phone);
  if (!jid) throw new Error('رقم هاتف غير صالح');
  if (!ownerUserId) throw new Error('معرّف المالك مطلوب');

  const type = subjectType === 'operator' ? 'operator' : 'member';
  if (type === 'member' && !memberUserId) throw new Error('آيدي العضو مطلوب');
  if (type === 'operator' && !operatorUserId) throw new Error('معرّف المشغّل مطلوب');

  // رقم واحد = هوية واحدة. إعادة الربط تستبدل الهوية وتُلغي التحقق السابق.
  const existing = (await db.query('SELECT id FROM whatsapp_identities WHERE phone_jid = $1', [jid])).rows[0];

  if (existing) {
    await db.query(
      `UPDATE whatsapp_identities SET
         owner_user_id = $1, subject_type = $2, member_user_id = $3, operator_user_id = $4,
         display_name = $5, status = 'pending', verified_at = NULL,
         failed_attempts = 0, blocked_until = NULL, updated_at = CURRENT_TIMESTAMP
       WHERE id = $6`,
      [ownerUserId, type, type === 'member' ? String(memberUserId) : null,
       type === 'operator' ? operatorUserId : null, displayName || null, existing.id]
    );
    await db.query('DELETE FROM whatsapp_sessions WHERE identity_id = $1', [existing.id]);
    await logAccess({
      phoneJid: jid, identityId: existing.id, ownerUserId,
      subjectRef: type === 'member' ? String(memberUserId) : `operator:${operatorUserId}`,
      action: 'identity_relinked', detail: `أعاد الربط المستخدم ${createdBy || '?'}`,
    });
    return { id: existing.id, phoneJid: jid, status: 'pending', relinked: true };
  }

  const inserted = await db.query(
    `INSERT INTO whatsapp_identities
      (owner_user_id, subject_type, member_user_id, operator_user_id, phone_jid, display_name, status, created_by)
     VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7)`,
    [ownerUserId, type, type === 'member' ? String(memberUserId) : null,
     type === 'operator' ? operatorUserId : null, jid, displayName || null, createdBy || null]
  );

  await logAccess({
    phoneJid: jid, identityId: inserted.lastInsertRowid, ownerUserId,
    subjectRef: type === 'member' ? String(memberUserId) : `operator:${operatorUserId}`,
    action: 'identity_linked', detail: `أنشأ الربط المستخدم ${createdBy || '?'}`,
  });

  return { id: inserted.lastInsertRowid, phoneJid: jid, status: 'pending', relinked: false };
}

async function getIdentityByJid(phoneJid) {
  const db = getDb();
  const jid = normalizeJid(phoneJid);
  if (!jid) return null;
  return (await db.query('SELECT * FROM whatsapp_identities WHERE phone_jid = $1', [jid])).rows[0] || null;
}

async function listIdentities(ownerUserId) {
  const db = getDb();
  const res = await db.query(
    `SELECT id, owner_user_id, subject_type, member_user_id, operator_user_id, phone_jid,
            display_name, status, verified_at, last_seen_at, blocked_until, created_at
       FROM whatsapp_identities
      WHERE owner_user_id = $1
      ORDER BY created_at DESC`,
    [ownerUserId]
  );
  return res.rows;
}

/** حظر هوية: تُقطع الجلسة فوراً ولا يُقبل منها أي استعلام لاحق */
async function setIdentityStatus(ownerUserId, identityId, status) {
  const db = getDb();
  if (!['pending', 'verified', 'blocked'].includes(status)) throw new Error('حالة غير صالحة');

  const res = await db.query(
    `UPDATE whatsapp_identities
        SET status = $1,
            verified_at = CASE WHEN $1 = 'verified' THEN CURRENT_TIMESTAMP ELSE NULL END,
            blocked_until = NULL,
            failed_attempts = 0,
            updated_at = CURRENT_TIMESTAMP
      WHERE id = $2 AND owner_user_id = $3
      RETURNING id, phone_jid`,
    [status, identityId, ownerUserId]
  );
  const row = res.rows[0];
  if (!row) throw new Error('الهوية غير موجودة');

  if (status !== 'verified') {
    await db.query('DELETE FROM whatsapp_sessions WHERE identity_id = $1', [identityId]);
  }
  await logAccess({
    phoneJid: row.phone_jid, identityId, ownerUserId,
    action: 'identity_status_changed', detail: status,
  });
  return row;
}

async function deleteIdentity(ownerUserId, identityId) {
  const db = getDb();
  const res = await db.query(
    'DELETE FROM whatsapp_identities WHERE id = $1 AND owner_user_id = $2 RETURNING phone_jid',
    [identityId, ownerUserId]
  );
  if (!res.rows[0]) throw new Error('الهوية غير موجودة');
  await logAccess({
    phoneJid: res.rows[0].phone_jid, ownerUserId,
    action: 'identity_deleted', detail: `حذف المستخدم الهوية ${identityId}`,
  });
  return true;
}

// ─────────────────────────── رموز التحقق ───────────────────────────

/**
 * إصدار رمز تحقق لهوية مربوطة. يُعيد الرمز الصريح مرة واحدة فقط
 * ليرسله المستدعي عبر واتساب — لا يُخزَّن ولا يُسجَّل في أي مكان.
 */
async function issueOtp(identity) {
  const db = getDb();
  if (!identity) throw new Error('هوية غير موجودة');

  const allowed = await checkRateLimit(identity.phone_jid, 'otp_issue', 3, 600);
  if (!allowed) {
    await logAccess({
      phoneJid: identity.phone_jid, identityId: identity.id, ownerUserId: identity.owner_user_id,
      action: 'otp_rate_limited', allowed: false,
    });
    return { ok: false, reason: 'rate_limited' };
  }

  const code = generateCode();
  await db.query('UPDATE whatsapp_otp SET consumed_at = CURRENT_TIMESTAMP WHERE identity_id = $1 AND consumed_at IS NULL', [identity.id]);
  await db.query(
    `INSERT INTO whatsapp_otp (identity_id, code_hash, expires_at, max_attempts)
     VALUES ($1, $2, CURRENT_TIMESTAMP + ($3 || ' minutes')::interval, $4)`,
    [identity.id, hashCode(code), String(otpTtlMinutes()), otpMaxAttempts()]
  );

  await logAccess({
    phoneJid: identity.phone_jid, identityId: identity.id, ownerUserId: identity.owner_user_id,
    action: 'otp_issued',
  });

  return { ok: true, code, ttlMinutes: otpTtlMinutes() };
}

/**
 * التحقق من رمز. عند استنفاد المحاولات تُحظر الهوية مؤقتاً
 * حتى لا يتحوّل الرمز السداسي إلى هدف تخمين.
 */
async function verifyOtp(identity, submittedCode) {
  const db = getDb();
  if (!identity) return { ok: false, reason: 'no_identity' };

  const code = String(submittedCode || '').replace(/\D/g, '');
  if (code.length !== 6) return { ok: false, reason: 'invalid_format' };

  const otp = (await db.query(
    `SELECT * FROM whatsapp_otp
      WHERE identity_id = $1 AND consumed_at IS NULL
      ORDER BY created_at DESC LIMIT 1`,
    [identity.id]
  )).rows[0];

  if (!otp) return { ok: false, reason: 'no_pending_code' };

  if (new Date(otp.expires_at) < new Date()) {
    await db.query('UPDATE whatsapp_otp SET consumed_at = CURRENT_TIMESTAMP WHERE id = $1', [otp.id]);
    return { ok: false, reason: 'expired' };
  }

  if (otp.attempts >= otp.max_attempts) {
    await blockTemporarily(identity);
    return { ok: false, reason: 'too_many_attempts' };
  }

  if (!safeEqual(hashCode(code), otp.code_hash)) {
    await db.query('UPDATE whatsapp_otp SET attempts = attempts + 1 WHERE id = $1', [otp.id]);
    const remaining = otp.max_attempts - (otp.attempts + 1);
    await logAccess({
      phoneJid: identity.phone_jid, identityId: identity.id, ownerUserId: identity.owner_user_id,
      action: 'otp_failed', allowed: false, detail: `المتبقي ${remaining}`,
    });
    if (remaining <= 0) {
      await blockTemporarily(identity);
      return { ok: false, reason: 'too_many_attempts' };
    }
    return { ok: false, reason: 'wrong_code', remaining };
  }

  await db.query('UPDATE whatsapp_otp SET consumed_at = CURRENT_TIMESTAMP WHERE id = $1', [otp.id]);
  await db.query(
    `UPDATE whatsapp_identities
        SET status = 'verified', verified_at = CURRENT_TIMESTAMP,
            failed_attempts = 0, blocked_until = NULL, updated_at = CURRENT_TIMESTAMP
      WHERE id = $1`,
    [identity.id]
  );
  await logAccess({
    phoneJid: identity.phone_jid, identityId: identity.id, ownerUserId: identity.owner_user_id,
    action: 'otp_verified',
  });

  return { ok: true };
}

async function blockTemporarily(identity) {
  const db = getDb();
  await db.query(
    `UPDATE whatsapp_identities
        SET blocked_until = CURRENT_TIMESTAMP + ($1 || ' minutes')::interval,
            failed_attempts = failed_attempts + 1,
            updated_at = CURRENT_TIMESTAMP
      WHERE id = $2`,
    [String(blockMinutes()), identity.id]
  );
  await db.query('UPDATE whatsapp_otp SET consumed_at = CURRENT_TIMESTAMP WHERE identity_id = $1 AND consumed_at IS NULL', [identity.id]);
  await logAccess({
    phoneJid: identity.phone_jid, identityId: identity.id, ownerUserId: identity.owner_user_id,
    action: 'identity_temp_blocked', allowed: false, detail: `${blockMinutes()} دقيقة`,
  });
}

// ─────────────────────────── الجلسات ───────────────────────────

async function getSession(phoneJid) {
  const db = getDb();
  const jid = normalizeJid(phoneJid);
  if (!jid) return null;
  const row = (await db.query('SELECT * FROM whatsapp_sessions WHERE phone_jid = $1', [jid])).rows[0];
  if (!row) return null;
  if (row.expires_at && new Date(row.expires_at) < new Date()) {
    await db.query('DELETE FROM whatsapp_sessions WHERE phone_jid = $1', [jid]);
    return null;
  }
  return row;
}

async function saveSession(phoneJid, { identityId, state, history }) {
  const db = getDb();
  const jid = normalizeJid(phoneJid);
  if (!jid) return;

  // نحتفظ بآخر 12 دوراً فقط: يكفي للسياق ويمنع تضخم الحمولة
  const trimmed = Array.isArray(history) ? history.slice(-12) : [];

  await db.query(
    `INSERT INTO whatsapp_sessions (phone_jid, identity_id, state, history_json, expires_at, updated_at)
     VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP + ($5 || ' minutes')::interval, CURRENT_TIMESTAMP)
     ON CONFLICT (phone_jid) DO UPDATE SET
       identity_id = EXCLUDED.identity_id,
       state = EXCLUDED.state,
       history_json = EXCLUDED.history_json,
       expires_at = EXCLUDED.expires_at,
       updated_at = CURRENT_TIMESTAMP`,
    [jid, identityId || null, state || 'unverified', JSON.stringify(trimmed), String(sessionTtlMinutes())]
  );
}

async function clearSession(phoneJid) {
  const db = getDb();
  const jid = normalizeJid(phoneJid);
  if (!jid) return;
  await db.query('DELETE FROM whatsapp_sessions WHERE phone_jid = $1', [jid]);
}

// ─────────────────────────── بوابة الوصول ───────────────────────────

/**
 * البوابة الوحيدة التي تُنتج "نطاقاً" (scope) صالحاً للاستعلام.
 *
 * كل ما يخرج من هنا مُشتق من قاعدة البيانات وحدها — لا شيء منه يأتي
 * من نص الرسالة. هذا ما يجعل حقن الأوامر (prompt injection) عديم الأثر:
 * حتى لو أقنع المستخدم النموذج، لا توجد أداة تقبل معرّفاً بديلاً.
 */
async function resolveScope(phoneJid) {
  const identity = await getIdentityByJid(phoneJid);

  if (!identity) {
    return { allowed: false, reason: 'unknown_number', identity: null };
  }
  if (identity.status === 'blocked') {
    return { allowed: false, reason: 'blocked', identity };
  }
  if (identity.blocked_until && new Date(identity.blocked_until) > new Date()) {
    return { allowed: false, reason: 'temporarily_blocked', identity };
  }
  if (identity.status !== 'verified') {
    return { allowed: false, reason: 'not_verified', identity };
  }

  const db = getDb();
  await db.query('UPDATE whatsapp_identities SET last_seen_at = CURRENT_TIMESTAMP WHERE id = $1', [identity.id]);

  return {
    allowed: true,
    reason: null,
    identity,
    scope: Object.freeze({
      identityId: identity.id,
      ownerUserId: identity.owner_user_id,
      subjectType: identity.subject_type,
      memberUserId: identity.member_user_id,
      operatorUserId: identity.operator_user_id,
      phoneJid: identity.phone_jid,
      displayName: identity.display_name,
    }),
  };
}

module.exports = {
  hashCode,
  generateCode,
  logAccess,
  checkRateLimit,
  linkIdentity,
  getIdentityByJid,
  listIdentities,
  setIdentityStatus,
  deleteIdentity,
  issueOtp,
  verifyOtp,
  getSession,
  saveSession,
  clearSession,
  resolveScope,
  get OTP_TTL_MINUTES() { return otpTtlMinutes(); },
};
