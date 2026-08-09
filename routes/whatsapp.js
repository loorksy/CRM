/**
 * مسارات تكامل واتساب.
 *
 * قسمان منفصلان تماماً:
 *  • /api/whatsapp/webhook  — عام بالضرورة (يناديه Evolution)، محمي بسر مشترك
 *  • باقي المسارات          — إدارية، خلف requireAuth + requireRole('admin')
 */

const express = require('express');
const crypto = require('crypto');
const { getDb } = require('../db/database');
const { requireAuth, requireRole } = require('../middleware/auth');
const evolution = require('../services/whatsapp/evolutionClient');
const identity = require('../services/whatsapp/identityService');
const { handleIncoming } = require('../services/whatsapp/messageHandler');

const router = express.Router();

const WEBHOOK_SECRET = process.env.WHATSAPP_WEBHOOK_SECRET || '';

/** مقارنة ثابتة الزمن للسر المشترك */
function secretMatches(provided) {
  if (!WEBHOOK_SECRET) return false;
  const a = Buffer.from(String(provided || ''));
  const b = Buffer.from(WEBHOOK_SECRET);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

// ═══════════════════════ الـ Webhook ═══════════════════════

/**
 * يستقبل أحداث Evolution API.
 *
 * نردّ 200 فوراً ثم نعالج بشكل غير متزامن: Evolution يعيد المحاولة عند
 * التأخر أو الفشل، وتوليد رد الوكيل قد يستغرق ثوانٍ. المعالجة المتزامنة
 * كانت ستنتج رسائل مكرّرة.
 */
router.post('/webhook', async (req, res) => {
  if (!secretMatches(req.get('x-lorkerp-webhook-secret'))) {
    console.warn('[whatsapp] رُفض webhook بسر غير صحيح من', req.ip);
    return res.status(401).json({ error: 'unauthorized' });
  }

  res.status(200).json({ received: true });

  const payload = req.body;
  const event = String(payload?.event || '').toLowerCase();

  if (event !== 'messages.upsert') return;

  setImmediate(async () => {
    try {
      await handleIncoming(payload, { instanceName: payload.instance });
    } catch (e) {
      console.error('[whatsapp] خطأ في معالجة الرسالة:', e.message);
      console.error(e.stack);
    }
  });
});

// ═══════════════════════ إدارة النسخة ═══════════════════════

const admin = [requireAuth, requireRole('admin')];

/** حالة التكامل: هل Evolution مضبوط ومتصل؟ */
router.get('/status', ...admin, async (req, res) => {
  try {
    const db = getDb();
    const cfg = (await db.query('SELECT * FROM whatsapp_config WHERE user_id = $1', [req.session.userId])).rows[0];

    if (!evolution.isConfigured()) {
      return res.json({
        success: true,
        configured: false,
        message: 'EVOLUTION_API_KEY غير مضبوط في ملف البيئة',
        config: cfg || null,
      });
    }

    let state = null;
    if (cfg?.instance_name) {
      const r = await evolution.connectionState(cfg.instance_name);
      state = r.ok ? (r.data?.instance?.state || r.data?.state || null) : null;
    }

    res.json({
      success: true,
      configured: true,
      webhookSecretSet: Boolean(WEBHOOK_SECRET),
      config: cfg || null,
      connectionState: state,
    });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

/** إنشاء نسخة وربط الـ webhook بها */
router.post('/instance', ...admin, async (req, res) => {
  try {
    if (!evolution.isConfigured()) {
      return res.status(400).json({ success: false, message: 'EVOLUTION_API_KEY غير مضبوط' });
    }
    if (!WEBHOOK_SECRET) {
      return res.status(400).json({ success: false, message: 'WHATSAPP_WEBHOOK_SECRET غير مضبوط' });
    }

    const instanceName = String(req.body.instanceName || `lorkerp-${req.session.userId}`).trim();
    if (!/^[a-zA-Z0-9_-]{3,50}$/.test(instanceName)) {
      return res.status(400).json({ success: false, message: 'اسم النسخة يجب أن يكون حروفاً لاتينية وأرقاماً (3-50)' });
    }

    const created = await evolution.createInstance(instanceName);
    // 403/409 تعني أن النسخة موجودة مسبقاً — نتابع لضبط الـ webhook عليها
    if (!created.ok && ![403, 409].includes(created.status)) {
      return res.status(502).json({ success: false, message: created.error });
    }

    // عند التضمين يصل Evolution إلينا عبر العنوان المحلي — لا حاجة لعنوان عام
    const embedded = process.env.EVOLUTION_EMBEDDED === '1';
    const publicUrl = (
      process.env.PUBLIC_BASE_URL ||
      (embedded ? `http://127.0.0.1:${process.env.PORT || 3020}` : '')
    ).replace(/\/+$/, '');

    if (!publicUrl) {
      return res.status(400).json({ success: false, message: 'PUBLIC_BASE_URL غير مضبوط — يحتاجه Evolution للوصول إلى الـ webhook' });
    }

    const hook = await evolution.setWebhook(
      instanceName,
      `${publicUrl}/api/whatsapp/webhook`,
      WEBHOOK_SECRET
    );
    if (!hook.ok) {
      return res.status(502).json({ success: false, message: `فشل ضبط الـ webhook: ${hook.error}` });
    }

    const db = getDb();
    await db.query(
      `INSERT INTO whatsapp_config (user_id, instance_name, enabled, updated_at)
       VALUES ($1, $2, TRUE, CURRENT_TIMESTAMP)
       ON CONFLICT (user_id) DO UPDATE SET
         instance_name = EXCLUDED.instance_name,
         enabled = TRUE,
         updated_at = CURRENT_TIMESTAMP`,
      [req.session.userId, instanceName]
    );

    res.json({ success: true, instanceName, alreadyExisted: !created.ok });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

/** رمز QR لربط الهاتف */
router.get('/qr', ...admin, async (req, res) => {
  try {
    const db = getDb();
    const cfg = (await db.query('SELECT instance_name FROM whatsapp_config WHERE user_id = $1', [req.session.userId])).rows[0];
    if (!cfg?.instance_name) {
      return res.status(400).json({ success: false, message: 'لم يتم إنشاء نسخة بعد' });
    }

    const r = await evolution.connect(cfg.instance_name);
    if (!r.ok) return res.status(502).json({ success: false, message: r.error });

    res.json({
      success: true,
      qrcode: r.data?.base64 || r.data?.qrcode?.base64 || null,
      pairingCode: r.data?.pairingCode || null,
      code: r.data?.code || null,
    });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

/** قطع الاتصال بواتساب دون حذف النسخة */
router.post('/logout', ...admin, async (req, res) => {
  try {
    const db = getDb();
    const cfg = (await db.query('SELECT instance_name FROM whatsapp_config WHERE user_id = $1', [req.session.userId])).rows[0];
    if (!cfg?.instance_name) return res.status(400).json({ success: false, message: 'لا توجد نسخة' });

    const r = await evolution.logout(cfg.instance_name);
    if (!r.ok) return res.status(502).json({ success: false, message: r.error });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

// ═══════════════════════ إدارة الهويات ═══════════════════════

router.get('/identities', ...admin, async (req, res) => {
  try {
    const rows = await identity.listIdentities(req.session.userId);
    res.json({ success: true, identities: rows });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

/** ربط رقم بعضو أو مشغّل. الخطوة الأولى الإلزامية قبل أي وصول. */
router.post('/identities', ...admin, async (req, res) => {
  try {
    const { phone, subjectType, memberUserId, operatorUserId, displayName } = req.body;

    const result = await identity.linkIdentity({
      ownerUserId: req.session.userId,
      subjectType,
      memberUserId,
      operatorUserId,
      phone,
      displayName,
      createdBy: req.session.userId,
    });

    res.json({ success: true, ...result });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

/**
 * إصدار رمز تفعيل. يُعرض للمسؤول مرة واحدة فقط، ولا يُخزَّن نصاً صريحاً.
 *
 * التسليم للمستخدم يتم خارج واتساب (شفهياً / عبر قناة الوكالة) — وهذا
 * ما يجعله عاملاً ثانياً حقيقياً. إرساله إلى نفس الرقم المُراسِل لا يثبت شيئاً.
 */
router.post('/identities/:id/issue-code', ...admin, async (req, res) => {
  try {
    const db = getDb();
    const ident = (await db.query(
      'SELECT * FROM whatsapp_identities WHERE id = $1 AND owner_user_id = $2',
      [req.params.id, req.session.userId]
    )).rows[0];

    if (!ident) return res.status(404).json({ success: false, message: 'الهوية غير موجودة' });

    const result = await identity.issueOtp(ident);
    if (!result.ok) {
      return res.status(429).json({ success: false, message: 'تم إصدار رموز كثيرة لهذا الرقم. انتظر قليلاً.' });
    }

    res.json({
      success: true,
      code: result.code,
      ttlMinutes: result.ttlMinutes,
      note: 'سلّم الرمز للمستخدم خارج واتساب. لن يُعرض مرة أخرى.',
    });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

router.patch('/identities/:id/status', ...admin, async (req, res) => {
  try {
    const row = await identity.setIdentityStatus(req.session.userId, req.params.id, req.body.status);
    res.json({ success: true, ...row });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

router.delete('/identities/:id', ...admin, async (req, res) => {
  try {
    await identity.deleteIdentity(req.session.userId, req.params.id);
    res.json({ success: true });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

// ═══════════════════════ شخصية الوكيل ═══════════════════════

const persona = require('../services/whatsapp/personaService');

/** الشخصية الحالية + كل الخيارات المتاحة لبناء القوائم في اللوحة */
router.get('/persona', ...admin, async (req, res) => {
  try {
    res.json({
      success: true,
      persona: await persona.getPersona(req.session.userId),
      options: persona.getOptions(),
    });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

/** تخصيص الدور واللهجة والنبرة والمهارات والتعليمات الحرة */
router.put('/persona', ...admin, async (req, res) => {
  try {
    const saved = await persona.savePersona(req.session.userId, req.body || {});
    res.json({ success: true, persona: saved });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

/** معاينة برومبت الشخصية كما سيراه النموذج — يساعد على ضبطها */
router.get('/persona/preview', ...admin, async (req, res) => {
  try {
    const p = await persona.getPersona(req.session.userId);
    res.json({ success: true, prompt: persona.renderPersonaPrompt(p) });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

// ═══════════════════════ سجل التدقيق ═══════════════════════

router.get('/access-log', ...admin, async (req, res) => {
  try {
    const db = getDb();
    const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 100, 1), 500);
    const rows = (await db.query(
      `SELECT id, phone_jid, identity_id, subject_ref, action, tool, detail, allowed, created_at
         FROM whatsapp_access_log
        WHERE owner_user_id = $1 OR owner_user_id IS NULL
        ORDER BY created_at DESC
        LIMIT $2`,
      [req.session.userId, limit]
    )).rows;
    res.json({ success: true, entries: rows });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message });
  }
});

module.exports = router;
