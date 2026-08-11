const express = require('express');
const router = express.Router();
const bcrypt = require('bcryptjs');
const { requireAuth, requireRole } = require('../middleware/auth');
const { getDb } = require('../db/database');

const { getCurrencySymbol } = require('../utils/numbers');

const CURRENCY_OPTIONS = [
  { code: 'USD', label: 'دولار أمريكي ($)' },
  { code: 'SAR', label: 'ريال سعودي (ر.س)' },
  { code: 'KWD', label: 'دينار كويتي (د.ك)' },
  { code: 'AED', label: 'درهم إماراتي (د.إ)' },
  { code: 'EGP', label: 'جنيه مصري (ج.م)' },
  { code: 'IQD', label: 'دينار عراقي (د.ع)' },
  { code: 'JOD', label: 'دينار أردني (د.أ)' },
  { code: 'BHD', label: 'دينار بحريني (د.ب)' },
  { code: 'OMR', label: 'ريال عماني (ر.ع)' },
  { code: 'QAR', label: 'ريال قطري (ر.ق)' },
  { code: 'NONE', label: 'بدون رمز' }
];

router.get('/', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const sheetsConfig = (await db.query('SELECT * FROM google_sheets_config WHERE id = 1')).rows[0];
    const currencyRow = (await db.query('SELECT value FROM settings WHERE key = $1', ['currency'])).rows[0];
    const currency = currencyRow?.value || 'USD';
    const prefRow = (await db.query(
      'SELECT COALESCE(use_simple_financial_terms, false) AS use_simple_financial_terms FROM users WHERE id = $1',
      [req.session.userId]
    )).rows[0];
    const useSimpleFinancialTerms = !!prefRow?.use_simple_financial_terms;
    if (req.session.user) req.session.user.useSimpleFinancialTerms = useSimpleFinancialTerms;
    res.render('dashboard', {
      title: 'الإعدادات',
      page: 'settings',
      user: req.session.user,
      sheetsConfig: sheetsConfig || null,
      currency,
      currencyOptions: CURRENCY_OPTIONS,
      useSimpleFinancialTerms,
    });
  } catch (e) {
    console.error('[settings] Error:', e.message);
    res.status(500).render('error', { title: 'خطأ', error: e.message });
  }
});

router.get('/currency', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const row = (await db.query('SELECT value FROM settings WHERE key = $1', ['currency'])).rows[0];
    const currency = row?.value || 'USD';
    const symbol = getCurrencySymbol(currency);
    res.json({ success: true, currency, symbol });
  } catch (e) {
    res.json({ success: false, currency: 'USD', symbol: '$' });
  }
});

router.post('/simple-financial-terms', requireAuth, async (req, res) => {
  try {
    const body = req.body || {};
    const on =
      body.useSimpleTerms === true ||
      body.useSimpleTerms === 'true' ||
      body.useSimpleTerms === '1' ||
      body.useSimpleTerms === 1;
    const db = getDb();
    await db.query(
      'UPDATE users SET use_simple_financial_terms = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2',
      [!!on, req.session.userId]
    );
    if (req.session.user) req.session.user.useSimpleFinancialTerms = !!on;
    res.json({ success: true, message: 'تم حفظ التفضيل', useSimpleTerms: !!on });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل الحفظ' });
  }
});

router.post('/currency', requireAuth, async (req, res) => {
  try {
    const { currency } = req.body || {};
    const code = CURRENCY_OPTIONS.some(c => c.code === currency) ? currency : 'USD';
    const db = getDb();
    await db.query('INSERT INTO settings (key, value, updated_at) VALUES ($1, $2, CURRENT_TIMESTAMP) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP', ['currency', code]);
    res.json({ success: true, message: 'تم حفظ العملة', currency: code, symbol: getCurrencySymbol(code) });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل' });
  }
});

router.post('/change-password', requireAuth, async (req, res) => {
  try {
    const { currentPassword, newPassword, confirmPassword } = req.body;
    const db = getDb();

    if (newPassword !== confirmPassword) {
      return res.json({ success: false, message: 'كلمات المرور غير متطابقة' });
    }

    if (newPassword.length < 6) {
      return res.json({ success: false, message: 'كلمة المرور يجب أن تكون 6 أحرف على الأقل' });
    }

    const user = (await db.query('SELECT * FROM users WHERE id = $1', [req.session.userId])).rows[0];
    if (!bcrypt.compareSync(currentPassword, user.password)) {
      return res.json({ success: false, message: 'كلمة المرور الحالية غير صحيحة' });
    }

    const hashedPassword = bcrypt.hashSync(newPassword, 10);
    await db.query('UPDATE users SET password = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2', [hashedPassword, req.session.userId]);

    res.json({ success: true, message: 'تم تغيير كلمة المرور بنجاح' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ أثناء تغيير كلمة المرور' });
  }
});

router.post('/update-profile', requireAuth, async (req, res) => {
  try {
    const { displayName } = req.body;
    const db = getDb();

    await db.query('UPDATE users SET display_name = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2', [displayName, req.session.userId]);
    req.session.user.displayName = displayName;

    res.json({ success: true, message: 'تم تحديث الملف الشخصي بنجاح' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ أثناء تحديث الملف الشخصي' });
  }
});

router.get('/system', requireAuth, requireRoleAdmin, async (req, res) => {
  try {
    const systemSettings = require('../services/systemSettings');
    const snap = await systemSettings.getPublicSnapshot();
    res.json({ success: true, ...snap });
  } catch (e) {
    res.status(500).json({ success: false, message: e.message || 'فشل التحميل' });
  }
});

router.post('/system', requireAuth, requireRoleAdmin, async (req, res) => {
  try {
    const systemSettings = require('../services/systemSettings');
    const values = (req.body && req.body.values) || {};
    const saved = await systemSettings.setMany(values);

    // مزامنة Google credentials مع جدول الربط إن وُجدت
    if (saved.includes('GOOGLE_CLIENT_ID') || saved.includes('GOOGLE_CLIENT_SECRET')) {
      const clientId = process.env.GOOGLE_CLIENT_ID;
      const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
      if (clientId && clientSecret) {
        const db = getDb();
        const row = (await db.query('SELECT id FROM google_sheets_config WHERE id = 1')).rows[0];
        if (!row) {
          await db.query(
            `INSERT INTO google_sheets_config (id, credentials, sync_enabled, updated_at)
             VALUES (1, $1, 0, CURRENT_TIMESTAMP)`,
            [JSON.stringify({ client_id: clientId, client_secret: clientSecret })]
          );
        } else {
          await db.query(
            `UPDATE google_sheets_config
             SET credentials = $1, updated_at = CURRENT_TIMESTAMP WHERE id = 1`,
            [JSON.stringify({ client_id: clientId, client_secret: clientSecret })]
          );
        }
      }
    }

    res.json({
      success: true,
      message: saved.length
        ? `تم حفظ ${saved.length} إعداداً وتطبيقها فوراً`
        : 'لم يُحفظ شيء (اترك الأسرار المقنّعة كما هي إن لم ترد تغييرها)',
      saved,
    });
  } catch (e) {
    console.error('[settings] system save:', e);
    res.status(500).json({ success: false, message: e.message || 'فشل الحفظ' });
  }
});

function requireRoleAdmin(req, res, next) {
  if (req.session?.user?.role === 'admin') return next();
  return res.status(403).json({ success: false, message: 'صلاحية المسؤول مطلوبة' });
}

router.get('/reset-data-options', requireAuth, (req, res) => {
  const { RESET_CATEGORIES } = require('../services/resetDataService');
  res.json({ success: true, categories: RESET_CATEGORIES });
});

router.post('/reset-data', requireAuth, async (req, res) => {
  try {
    const { categories = [], wipeAll = false, confirmPhrase } = req.body || {};
    const phrase = String(confirmPhrase || '').trim();
    if (phrase !== 'حذف نهائي' && phrase !== 'DELETE') {
      return res.json({ success: false, message: 'اكتب كلمة التأكيد بالضبط: حذف نهائي' });
    }
    const { RESET_CATEGORIES, executeReset } = require('../services/resetDataService');
    const { runTransaction } = require('../db/database');
    const validIds = new Set(RESET_CATEGORIES.map((c) => c.id));
    const filtered = (Array.isArray(categories) ? categories : []).filter((c) => validIds.has(c));
    const wipe = !!wipeAll;
    if (!wipe && filtered.length === 0) {
      return res.json({ success: false, message: 'فعّل «حذف كل شيء» أو اختر فئة واحدة على الأقل.' });
    }
    await runTransaction((client) =>
      executeReset(client, req.session.userId, filtered, wipe, {
        preserveIntegrations: wipe,
      })
    );
    res.json({ success: true, message: 'تم حذف البيانات المحددة من الخادم.' });
  } catch (error) {
    console.error('[settings] reset-data:', error);
    res.json({ success: false, message: 'فشل حذف البيانات: ' + (error.message || '') });
  }
});

module.exports = router;
