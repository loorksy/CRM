/**
 * أساس العزل لوكيل واتساب: دور قاعدة بيانات مقيَّد + عروض دلالية + RLS.
 *
 * ══════════════════════════════════════════════════════════════════
 *  لماذا نُنزِل العزل إلى قاعدة البيانات؟
 *
 *  في التصميم السابق كان العزل شروط WHERE مكتوبة بيد المطوّر داخل تسع
 *  أدوات جاهزة. هذا يربط بين الأمان والاتساع: كل جدول جديد يعني فلتراً
 *  يدوياً جديداً وفرصة جديدة لنسيان واحد، فبقي الوكيل حبيس تسعة أسئلة.
 *
 *  هنا يصبح العزل خاصية في قاعدة البيانات نفسها:
 *   • دور lorkerp_agent لا يملك أي صلاحية على أي جدول أساسي — إطلاقاً
 *   • يملك SELECT على عروض agent_v_* فقط، وكل عرض يحمل شرط النطاق داخله
 *   • النطاق يأتي من متغيرات جلسة (GUC) يضبطها الخادم لا النموذج
 *   • RLS طبقة ثانية على الجداول الأساسية موجّهة لهذا الدور وحده
 *
 *  النتيجة: يستطيع الوكيل تأليف أي استعلام يشاء، ولا يستطيع رؤية صف
 *  واحد خارج نطاق الهوية المُتحقَّق منها — لأن الرفض يأتي من PostgreSQL،
 *  لا من تعليمات نصية يمكن خداع النموذج لتجاهلها.
 * ══════════════════════════════════════════════════════════════════
 *
 * أثر صفري على النظام القائم:
 *  • لا يُعدَّل ولا يُحذف أي جدول أو عمود
 *  • سياسات RLS موجّهة إلى lorkerp_agent وحده، ويُضاف للدور الذي يستعمله
 *    التطبيق سياسة سماح صريحة، فيبقى سلوك الـ ERP كما هو حتى لو لم يكن
 *    مالك الجداول
 *  • إن لم يُضبط AGENT_DB_PASSWORD تُتخطّى الوحدة بالكامل
 */

/** الجداول المملوكة لمستخدم النظام مباشرةً (تحمل عمود user_id) */
const OWNER_SCOPED_TABLES = [
  'member_profiles', 'member_profile_events', 'member_adjustments',
  'deferred_salary_lines', 'payroll_user_audit_cache', 'financial_cycles',
  'funds', 'ledger_entries', 'expense_entries', 'financial_returns',
  'entity_payables', 'fx_spread_entries', 'admin_brokerage_entries',
  'accreditation_entities', 'transfer_companies', 'salary_swap_entries',
  'shipping_inventory', 'shipping_carrier_agencies', 'profit_transfer_batches',
];

/** اسم الدور الذي يستعمله الوكيل */
const AGENT_ROLE = process.env.AGENT_DB_USER || 'lorkerp_agent';

/** هل الميزة مُفعّلة؟ بلا كلمة مرور لا يُنشأ شيء ولا يتغير شيء */
function isEnabled() {
  return Boolean(process.env.AGENT_DB_PASSWORD);
}

/** استخراج اسم دور التطبيق من رابط الاتصال */
function appRoleFromUrl(databaseUrl) {
  try {
    return decodeURIComponent(new URL(databaseUrl).username) || null;
  } catch (_) {
    return null;
  }
}

/** تنفيذ متسامح: بعض العبارات تفشل بأمان (موجود مسبقاً) فلا نُسقط الإقلاع */
async function run(pool, sql, { quiet = false } = {}) {
  try {
    await pool.query(sql);
    return true;
  } catch (e) {
    if (!quiet && !/already exists|duplicate|does not exist/i.test(String(e.message))) {
      console.warn('[agent-rls]', e.message, '←', sql.slice(0, 90).replace(/\s+/g, ' '));
    }
    return false;
  }
}

// ═════════════════════ 1) الدور ودوال النطاق ═════════════════════

async function ensureAgentRole(pool) {
  const password = process.env.AGENT_DB_PASSWORD;

  // NOBYPASSRLS صراحةً: هذا الدور يجب أن يخضع لكل سياسة دائماً
  const exists = (await pool.query('SELECT 1 FROM pg_roles WHERE rolname = $1', [AGENT_ROLE])).rowCount > 0;

  if (!exists) {
    await run(pool, `CREATE ROLE ${AGENT_ROLE} LOGIN PASSWORD ${quoteLiteral(password)}
                     NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS`);
  } else {
    await run(pool, `ALTER ROLE ${AGENT_ROLE} LOGIN PASSWORD ${quoteLiteral(password)}
                     NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`);
  }

  // قراءة فقط على مستوى الجلسة — طبقة إضافية فوق انعدام صلاحيات الكتابة
  await run(pool, `ALTER ROLE ${AGENT_ROLE} SET default_transaction_read_only = on`);
  await run(pool, `ALTER ROLE ${AGENT_ROLE} SET statement_timeout = '8s'`);

  // تجريد كامل: لا وصول لأي جدول أساسي، ولا إنشاء أي شيء
  await run(pool, `REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ${AGENT_ROLE}`);
  await run(pool, `REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ${AGENT_ROLE}`);
  await run(pool, `REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM ${AGENT_ROLE}`);
  await run(pool, `REVOKE CREATE ON SCHEMA public FROM ${AGENT_ROLE}`);
  await run(pool, `REVOKE ALL ON SCHEMA public FROM ${AGENT_ROLE}`);
  await run(pool, `GRANT USAGE ON SCHEMA public TO ${AGENT_ROLE}`);
}

/**
 * إغلاق المسار الوحيد الذي يستطيع به استعلامٌ تغيير نطاقه بنفسه.
 *
 * `set_config` دالة قابلة للاستدعاء داخل SELECT عادي، فلو استدعاها
 * استعلام الوكيل لأمكنه تبديل app.member_id قبل أن تُقيّمه العروض.
 * منعها نصياً يعمل (لا يمكن تركيب أسماء الدوال ديناميكياً في SQL)، لكن
 * المنع البنيوي أقوى: نسحب صلاحية التنفيذ من PUBLIC ونعيدها لدور التطبيق.
 *
 * هذا تغيير على مستوى قاعدة البيانات، لذلك لا يُنفَّذ إلا بطلب صريح عبر
 * AGENT_HARDEN_SET_CONFIG=1 — لئلا نفاجئ أدوات أخرى تتصل بأدوار مختلفة.
 * النظام نفسه لا يستدعي set_config في أي مكان، وطبقة الوكيل تستعمل
 * SET LOCAL بدلاً منها، فالتفعيل آمن في التركيبات المعتادة.
 */
async function hardenSetConfig(pool, appRole) {
  if (process.env.AGENT_HARDEN_SET_CONFIG !== '1') return false;
  if (!appRole) {
    console.warn('[agent-rls] تخطّي تقييد set_config: تعذّر تحديد دور التطبيق');
    return false;
  }

  const sig = 'pg_catalog.set_config(text, text, boolean)';
  const revoked = await run(pool, `REVOKE EXECUTE ON FUNCTION ${sig} FROM PUBLIC`);
  if (!revoked) return false;

  await run(pool, `GRANT EXECUTE ON FUNCTION ${sig} TO ${appRole}`);
  console.log('[LorkERP] تم تقييد set_config: دور الوكيل لا يستطيع تغيير نطاقه بنفسه');
  return true;
}

function quoteLiteral(s) {
  return `'${String(s).replace(/'/g, "''")}'`;
}

/**
 * دوال قراءة النطاق من متغيرات الجلسة.
 * الوسيط الثاني `true` يعني: أعِد NULL بدل رمي خطأ عند غياب المتغير،
 * فتُرجع العروض صفراً من الصفوف بدل الانفجار — فشل مغلق لا مفتوح.
 */
async function ensureScopeFunctions(pool) {
  await run(pool, `
    CREATE OR REPLACE FUNCTION agent_owner_id() RETURNS INTEGER
    LANGUAGE sql STABLE PARALLEL SAFE AS $fn$
      SELECT NULLIF(current_setting('app.owner_id', true), '')::integer
    $fn$`);

  await run(pool, `
    CREATE OR REPLACE FUNCTION agent_member_id() RETURNS TEXT
    LANGUAGE sql STABLE PARALLEL SAFE AS $fn$
      SELECT NULLIF(current_setting('app.member_id', true), '')
    $fn$`);

  await run(pool, `
    CREATE OR REPLACE FUNCTION agent_subject_type() RETURNS TEXT
    LANGUAGE sql STABLE PARALLEL SAFE AS $fn$
      SELECT COALESCE(NULLIF(current_setting('app.subject_type', true), ''), 'none')
    $fn$`);

  for (const fn of ['agent_owner_id()', 'agent_member_id()', 'agent_subject_type()']) {
    await run(pool, `GRANT EXECUTE ON FUNCTION ${fn} TO ${AGENT_ROLE}`);
  }
}

// ═════════════════════ 2) سياسات RLS ═════════════════════

/**
 * طبقة الدفاع الثانية. الوكيل أصلاً بلا صلاحية على هذه الجداول، لكن لو
 * مُنحت له صلاحية يوماً بالخطأ، تبقى السياسات تحصره في نطاقه.
 *
 * كل جدول يحصل على سياستين:
 *  • سياسة سماح كاملة لدور التطبيق — تضمن أن الـ ERP لا يتأثر إطلاقاً
 *  • سياسة قراءة مقيّدة بالنطاق لدور الوكيل وحده
 */
async function ensureRlsPolicies(pool, appRole) {
  for (const table of OWNER_SCOPED_TABLES) {
    const present = (await pool.query(
      `SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=$1`,
      [table]
    )).rowCount > 0;
    if (!present) continue;

    await run(pool, `ALTER TABLE ${table} ENABLE ROW LEVEL SECURITY`);

    // سماح صريح للتطبيق: يعمل حتى لو لم يكن دور التطبيق مالك الجدول
    if (appRole) {
      await run(pool, `DROP POLICY IF EXISTS agent_rls_app_all ON ${table}`, { quiet: true });
      await run(pool, `CREATE POLICY agent_rls_app_all ON ${table}
                       FOR ALL TO ${appRole} USING (true) WITH CHECK (true)`);
    }

    const hasMember = (await pool.query(
      `SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name=$1 AND column_name='member_user_id'`,
      [table]
    )).rowCount > 0;

    // العضو يُقيَّد بآيديه أيضاً؛ المشغّل يرى نطاق مؤسسته
    const predicate = hasMember
      ? `user_id = agent_owner_id() AND (
           agent_subject_type() = 'operator'
           OR (agent_subject_type() = 'member' AND member_user_id = agent_member_id())
         )`
      : `user_id = agent_owner_id()`;

    await run(pool, `DROP POLICY IF EXISTS agent_rls_scope ON ${table}`, { quiet: true });
    await run(pool, `CREATE POLICY agent_rls_scope ON ${table}
                     FOR SELECT TO ${AGENT_ROLE} USING (${predicate})`);
  }
}

// ═════════════════════ 3) التركيب ═════════════════════

/**
 * تهيئة كاملة لطبقة الوكيل. تُستدعى مرة عند إقلاع النظام.
 * @param {import('pg').Pool} pool اتصال التطبيق (مالك الجداول عادةً)
 */
async function ensureAgentDataLayer(pool) {
  if (!isEnabled()) {
    return { enabled: false, reason: 'AGENT_DB_PASSWORD غير مضبوط' };
  }

  const appRole = appRoleFromUrl(process.env.DATABASE_URL || process.env.PG_CONNECTION_STRING || '');

  try {
    await ensureAgentRole(pool);
    await ensureScopeFunctions(pool);
    await ensureRlsPolicies(pool, appRole);
    await hardenSetConfig(pool, appRole);

    const { ensureAgentViews } = require('./agentViews');
    const viewCount = await ensureAgentViews(pool, AGENT_ROLE);

    console.log(`[LorkERP] طبقة بيانات الوكيل جاهزة: دور ${AGENT_ROLE}، ${viewCount} عرضاً دلالياً`);
    return { enabled: true, role: AGENT_ROLE, views: viewCount, appRole };
  } catch (e) {
    console.error('[LorkERP] فشل تهيئة طبقة بيانات الوكيل:', e.message);
    return { enabled: false, reason: e.message };
  }
}

module.exports = {
  ensureAgentDataLayer,
  isEnabled,
  appRoleFromUrl,
  AGENT_ROLE,
  OWNER_SCOPED_TABLES,
};
