/**
 * منفّذ الاستعلامات الحرة للوكيل.
 *
 * هذا ما يحوّل الوكيل من أتمتة إلى وكيل حقيقي: بدل تسع دوال جاهزة، يؤلّف
 * النموذج استعلام SQL يجيب عن السؤال المطروح مهما كان — مقارنات، تجميعات،
 * ترتيب، نِسَب، فترات زمنية — على سطح العروض الدلالية.
 *
 * ══════════════════════════════════════════════════════════════════
 *  لماذا هذا آمن رغم أن النموذج يكتب SQL؟
 *
 *  الأمان هنا ليس في فحص النص — فحص النصوص يُخدع. الأمان في أن الاتصال
 *  نفسه عاجز عن رؤية ما لا يخصّه:
 *
 *   1. الاتصال بدور lorkerp_agent الذي لا يملك أي صلاحية على أي جدول
 *      أساسي. `SELECT * FROM member_profiles` ⇒ permission denied.
 *   2. العروض المسموحة تحمل شرط النطاق داخل تعريفها، والنطاق يأتي من
 *      متغيرات جلسة يضبطها هذا الملف — لا النموذج.
 *   3. الجلسة للقراءة فقط على مستوى الدور، والمعاملة تُلغى دائماً.
 *   4. RLS طبقة أخيرة على الجداول الأساسية.
 *
 *  فحص النص أدناه موجود لتحسين رسائل الخطأ وتوفير الرحلات، لا كخط دفاع.
 * ══════════════════════════════════════════════════════════════════
 */

const { Pool } = require('pg');
const { catalogFor } = require('../../db/agentViews');

const MAX_ROWS = parseInt(process.env.AGENT_QUERY_MAX_ROWS || '200', 10);
const TIMEOUT_MS = parseInt(process.env.AGENT_QUERY_TIMEOUT_MS || '8000', 10);

let agentPool = null;

/** رابط اتصال دور الوكيل، مشتق من رابط التطبيق مع تبديل بيانات الاعتماد */
function buildAgentUrl() {
  if (process.env.AGENT_DATABASE_URL) return process.env.AGENT_DATABASE_URL;

  const base = process.env.DATABASE_URL || process.env.PG_CONNECTION_STRING;
  if (!base || !process.env.AGENT_DB_PASSWORD) return null;

  try {
    const url = new URL(base);
    url.username = process.env.AGENT_DB_USER || 'lorkerp_agent';
    url.password = process.env.AGENT_DB_PASSWORD;
    return url.toString();
  } catch (_) {
    return null;
  }
}

function isEnabled() {
  return Boolean(buildAgentUrl());
}

/** اقتباس قيمة نصية لعبارة SET LOCAL — القيم من قاعدة البيانات لا من النموذج */
function quote(value) {
  return `'${String(value).replace(/'/g, "''")}'`;
}

function getPool() {
  if (agentPool) return agentPool;
  const url = buildAgentUrl();
  if (!url) return null;

  agentPool = new Pool({
    connectionString: url,
    max: 4,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 5000,
  });
  agentPool.on('error', (e) => console.warn('[agent-query] خطأ في المجمّع:', e.message));
  return agentPool;
}

// ─────────────────────── فحص مبدئي للاستعلام ───────────────────────

const FORBIDDEN = /\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|COPY|VACUUM|REINDEX|CALL|DO|SET|RESET|LISTEN|NOTIFY)\b/i;

/**
 * `set_config` قابل للاستدعاء داخل SELECT عادي، وهو المسار الوحيد الذي
 * يستطيع به استعلامٌ تغيير متغيرات النطاق التي تعتمد عليها العروض في
 * الفلترة. حظره صريح ولازم — لا تحذفه.
 * (`pg_catalog.set_config` يسقط أصلاً في فحص pg_* أدناه.)
 */
const SCOPE_TAMPERING = /\bset_config\b/i;

/**
 * فحص شكلي سريع. ليس خط الدفاع — الدفاع في صلاحيات الدور — لكنه يمنع
 * رحلة ذهاب وإياب إلى قاعدة البيانات ويعطي النموذج رسالة يتعلّم منها.
 */
function validateSql(sql) {
  const q = String(sql || '').trim().replace(/;+\s*$/, '');

  if (!q) return { ok: false, error: 'استعلام فارغ.' };
  if (q.includes(';')) return { ok: false, error: 'عبارة واحدة فقط، بلا فاصلة منقوطة داخل الاستعلام.' };
  if (!/^(SELECT|WITH)\b/i.test(q)) return { ok: false, error: 'يُسمح بـ SELECT فقط.' };
  if (FORBIDDEN.test(q)) return { ok: false, error: 'يُسمح بالقراءة فقط.' };
  if (SCOPE_TAMPERING.test(q)) return { ok: false, error: 'دوال ضبط الجلسة غير متاحة.' };
  if (/\bpg_[a-z_]+\b/i.test(q)) return { ok: false, error: 'جداول النظام الداخلية غير متاحة.' };
  if (/\binformation_schema\b/i.test(q)) return { ok: false, error: 'مخطط المعلومات غير متاح. استعمل أداة استكشاف البنية.' };
  if (q.length > 4000) return { ok: false, error: 'الاستعلام طويل جداً.' };

  return { ok: true, sql: q };
}

// ─────────────────────── التنفيذ ───────────────────────

/**
 * تنفيذ استعلام قراءة ضمن نطاق هوية مُتحقَّق منها.
 *
 * @param {object} scope النطاق الصادر عن identityService.resolveScope
 * @param {string} sql   استعلام SELECT ألّفه النموذج
 */
async function runScopedQuery(scope, sql) {
  const pool = getPool();
  if (!pool) {
    return { error: 'طبقة الاستعلام غير مُهيّأة على هذا الخادم.' };
  }

  const check = validateSql(sql);
  if (!check.ok) return { error: check.error };

  // سقف صفوف مفروض من الخارج: لا يستطيع النموذج تجاوزه بحذف LIMIT
  const wrapped = `SELECT * FROM (${check.sql}) AS agent_result LIMIT ${MAX_ROWS}`;

  const client = await pool.connect();
  try {
    await client.query('BEGIN READ ONLY');
    await client.query(`SET LOCAL statement_timeout = ${TIMEOUT_MS}`);

    // نضبط النطاق بـ SET LOCAL لا بدالة set_config، حتى نستطيع منع الدور
    // من استدعاء set_config أصلاً — فلا يبقى للاستعلام أي طريق لتغيير نطاقه.
    // القيم مأخوذة من الهوية المُتحقَّق منها ومُعقّمة أدناه، لا من النموذج.
    const ownerId = Number.parseInt(scope.ownerUserId, 10);
    if (!Number.isInteger(ownerId)) return { error: 'نطاق غير صالح.' };

    const subjectType = scope.subjectType === 'operator' ? 'operator' : 'member';

    await client.query(`SET LOCAL app.owner_id = ${quote(String(ownerId))}`);
    await client.query(`SET LOCAL app.member_id = ${quote(scope.memberUserId || '')}`);
    await client.query(`SET LOCAL app.subject_type = ${quote(subjectType)}`);

    const result = await client.query(wrapped);

    return {
      rowCount: result.rowCount,
      truncated: result.rowCount >= MAX_ROWS,
      columns: result.fields.map((f) => f.name),
      rows: result.rows,
    };
  } catch (e) {
    // نمرّر رسالة PostgreSQL للنموذج ليصحّح استعلامه، لكن بلا تفاصيل داخلية
    const message = String(e.message || 'خطأ في الاستعلام');
    const clean = message.includes('permission denied')
      ? 'لا صلاحية على هذا المصدر. استعمل العروض المتاحة في كتالوج البنية فقط.'
      : message.slice(0, 300);
    return { error: clean };
  } finally {
    try {
      await client.query('ROLLBACK');
    } catch (_) {
      /* المعاملة قد تكون أُنهيت فعلاً */
    }
    client.release();
  }
}

/**
 * كتالوج البنية: أسماء العروض المتاحة لهذه الهوية وأعمدتها ووصفها.
 * النموذج يستدعيه أولاً ليعرف ماذا يستطيع أن يسأل.
 */
async function describeSchema(scope) {
  const pool = getPool();
  const catalog = catalogFor(scope.subjectType);
  if (!pool) return { views: catalog };

  const client = await pool.connect();
  try {
    const names = catalog.map((v) => v.name);
    if (!names.length) return { views: [] };

    const cols = await client.query(
      `SELECT table_name, column_name, data_type
         FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = ANY($1)
        ORDER BY table_name, ordinal_position`,
      [names]
    );

    const byView = new Map();
    for (const row of cols.rows) {
      if (!byView.has(row.table_name)) byView.set(row.table_name, []);
      byView.get(row.table_name).push(`${row.column_name} (${row.data_type})`);
    }

    return {
      views: catalog.map((v) => ({
        name: v.name,
        description: v.description,
        columns: byView.get(v.name) || [],
      })),
      note: 'أسماء الأعمدة عربية — ضعها بين علامتَي اقتباس مزدوجتين في الاستعلام.',
    };
  } catch (e) {
    console.warn('[agent-query] فشل وصف البنية:', e.message);
    return { views: catalog };
  } finally {
    client.release();
  }
}

async function closePool() {
  if (agentPool) {
    await agentPool.end().catch(() => {});
    agentPool = null;
  }
}

module.exports = { runScopedQuery, describeSchema, validateSql, isEnabled, closePool, MAX_ROWS };
