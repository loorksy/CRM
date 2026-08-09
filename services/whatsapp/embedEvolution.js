/**
 * تركيب Evolution API داخل خادم LorkERP.
 *
 * Evolution مكتوب بـ TypeScript ويُبنى إلى integrations/evolution-api/dist
 * عبر `npm run evolution:build`. هذا الملف يحمّل الحزمة المبنية ويركّبها
 * كتطبيق فرعي على نفس المنفذ، فيصبح النظام عملية واحدة وخدمة واحدة.
 *
 * التركيب على مرحلتين لأن تهيئة Evolution غير متزامنة بينما يجب حجز
 * مسارها في سلسلة Express قبل معالج 404:
 *   1) reserveMountPoint(app) — تُستدعى مبكراً مع باقي المسارات
 *   2) mountEvolution(httpServer) — تُستدعى بعد إقلاع الخادم فتملأ الحجز
 *
 * التحميل متسامح: إن لم تكن الحزمة مبنية أو الاعتماديات غير مثبّتة،
 * يُسجَّل تحذير ويتابع النظام عمله كاملاً — لا شيء في الـ ERP يتوقف.
 */

const path = require('path');
const fs = require('fs');

const EVOLUTION_DIR = path.join(__dirname, '..', '..', 'integrations', 'evolution-api');
const DIST_ENTRY = path.join(EVOLUTION_DIR, 'dist', 'embedded.js');

/** المسار الذي يُركَّب عليه Evolution داخل النظام */
const MOUNT_PATH = process.env.EVOLUTION_MOUNT_PATH || '/whatsapp-api';

/** التطبيق الفرعي بعد التهيئة — يبقى null إن كان التضمين معطّلاً */
let evolutionApp = null;

function isEnabled() {
  return process.env.EVOLUTION_EMBEDDED === '1';
}

function isBuilt() {
  return fs.existsSync(DIST_ENTRY);
}

/** هل الطلب موجَّه إلى Evolution؟ يُستخدم لتخطي محلّلات الجسم العامة */
function isEvolutionPath(reqPath) {
  if (!isEnabled()) return false;
  return String(reqPath || '').startsWith(MOUNT_PATH);
}

/**
 * حجز مسار Evolution في سلسلة Express قبل معالج 404.
 * لا يفعل شيئاً إن كان التضمين معطّلاً، فتبقى السلسلة كما كانت تماماً.
 */
function reserveMountPoint(app) {
  if (!isEnabled()) return false;

  app.use(MOUNT_PATH, (req, res, next) => {
    if (!evolutionApp) {
      return res.status(503).json({
        status: 503,
        error: 'Service Unavailable',
        response: { message: 'خدمة واتساب قيد التهيئة أو غير مبنية' },
      });
    }
    return evolutionApp(req, res, next);
  });

  return true;
}

/**
 * تهيئة Evolution وربطه بخادم HTTP الخاص بالنظام.
 * @param {import('http').Server} httpServer
 * @returns {Promise<{ mounted: boolean, reason?: string }>}
 */
async function mountEvolution(httpServer) {
  if (!isEnabled()) {
    return { mounted: false, reason: 'disabled' };
  }

  if (!isBuilt()) {
    console.warn(
      '[LorkERP] Evolution غير مبني — تخطّي التركيب.\n' +
      '          شغّل: npm run evolution:setup'
    );
    return { mounted: false, reason: 'not_built' };
  }

  // Evolution يقرأ جذره ومساراته من هذا المتغير — يجب ضبطه قبل التحميل
  if (!process.env.EVOLUTION_ROOT_DIR) {
    process.env.EVOLUTION_ROOT_DIR = EVOLUTION_DIR;
  }

  try {
    const { createEvolutionApp } = require(DIST_ENTRY);
    evolutionApp = await createEvolutionApp({ httpServer });

    console.log(`[LorkERP] Evolution API مُركَّب على ${MOUNT_PATH} (نفس العملية والمنفذ)`);
    return { mounted: true };
  } catch (e) {
    // فشل Evolution يجب ألا يمنع النظام من الإقلاع
    console.error('[LorkERP] فشل تركيب Evolution API:', e.message);
    console.error(e.stack);
    return { mounted: false, reason: e.message };
  }
}

module.exports = {
  reserveMountPoint,
  mountEvolution,
  isEvolutionPath,
  isEnabled,
  isBuilt,
  MOUNT_PATH,
  EVOLUTION_DIR,
};
