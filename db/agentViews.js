/**
 * العروض الدلالية للوكيل — سطح البيانات الذي يؤلّف عليه استعلاماته.
 *
 * كل عرض يحمل شرط النطاق داخل تعريفه، ويُبوّب حسب نوع الهوية:
 *   • عروض my_*  ← تُرجع صفوفاً للعضو المُتحقَّق منه فقط
 *   • عروض غيرها ← تُرجع صفوفاً للمشغّل فقط، ضمن مؤسسته
 *
 * العروض مملوكة لدور التطبيق، وتُنفَّذ بصلاحياته (السلوك الافتراضي)،
 * بينما دور الوكيل لا يملك أي صلاحية على الجداول الأساسية. فحتى لو حاول
 * الوكيل `SELECT * FROM funds` مباشرةً يتلقى «permission denied» من
 * PostgreSQL. الوصول ممكن عبر هذه العروض حصراً، وهي مفلترة بالتعريف.
 *
 * الأسماء والأوصاف عربية لأن النموذج يقرؤها ليقرر ماذا يستعلم.
 */

const VIEWS = [
  // ══════════════ عروض العضو ══════════════
  {
    name: 'agent_v_my_profile',
    scope: 'member',
    description: 'ملخص حساب العضو: إجمالي الراتب المدقّق، الرصيد المؤجل، الدين على الشركة.',
    sql: `
      SELECT p.member_user_id            AS "آيدي_العضو",
             p.display_name              AS "الاسم",
             p.total_salary_audited_usd  AS "إجمالي_الراتب_المدقق_دولار",
             p.deferred_balance_usd      AS "الرصيد_المؤجل_دولار",
             p.debt_to_company_usd       AS "الدين_على_الشركة_دولار",
             p.updated_at                AS "آخر_تحديث"
        FROM member_profiles p
       WHERE p.user_id = agent_owner_id()
         AND p.member_user_id = agent_member_id()
         AND agent_subject_type() = 'member'`,
  },
  {
    name: 'agent_v_my_cycles',
    scope: 'member',
    description: 'الدورات المدقّقة للعضو مع راتب كل دورة وحالة التدقيق. صف لكل دورة.',
    sql: `
      SELECT a.cycle_id                                       AS "رقم_الدورة",
             c.name                                           AS "اسم_الدورة",
             a.audit_status                                   AS "حالة_التدقيق",
             a.audit_source                                   AS "مصدر_التدقيق",
             COALESCE((a.details_json::jsonb ->> 'salaryAfterDiscount')::numeric,
                      (a.details_json::jsonb ->> 'salary')::numeric,
                      (a.details_json::jsonb ->> 'salaryAudited')::numeric)  AS "الراتب_المدقق_دولار",
             COALESCE((a.details_json::jsonb ->> 'salaryBefore')::numeric,
                      (a.details_json::jsonb ->> 'salaryBeforeDiscount')::numeric) AS "الراتب_قبل_الخصم_دولار",
             a.updated_at                                     AS "تاريخ_التحديث"
        FROM payroll_user_audit_cache a
        LEFT JOIN financial_cycles c ON c.id = a.cycle_id AND c.user_id = a.user_id
       WHERE a.user_id = agent_owner_id()
         AND a.member_user_id = agent_member_id()
         AND agent_subject_type() = 'member'`,
  },
  {
    name: 'agent_v_my_deferred',
    scope: 'member',
    description: 'حركة الرصيد المؤجل للعضو عبر الدورات. balance_d هو الرصيد المرحّل لكل دورة.',
    sql: `
      SELECT d.cycle_id                AS "رقم_الدورة",
             c.name                    AS "اسم_الدورة",
             d.balance_d               AS "الرصيد_المؤجل_دولار",
             d.salary_before_discount  AS "الراتب_قبل_الخصم_دولار",
             d.sheet_source            AS "المصدر",
             d.updated_at              AS "تاريخ_التحديث"
        FROM deferred_salary_lines d
        LEFT JOIN financial_cycles c ON c.id = d.cycle_id AND c.user_id = d.user_id
       WHERE d.user_id = agent_owner_id()
         AND d.member_user_id = agent_member_id()
         AND agent_subject_type() = 'member'`,
  },
  {
    name: 'agent_v_my_adjustments',
    scope: 'member',
    description: 'التعديلات على حساب العضو. kind: deduct خصم، add إضافة، reward مكافأة.',
    sql: `
      SELECT m.id            AS "الرقم",
             m.kind          AS "النوع",
             m.amount        AS "المبلغ_دولار",
             m.status        AS "الحالة",
             m.notes         AS "الملاحظات",
             m.cycle_id      AS "رقم_الدورة",
             c.name          AS "اسم_الدورة",
             m.created_at    AS "تاريخ_الإنشاء",
             m.processed_at  AS "تاريخ_المعالجة"
        FROM member_adjustments m
        LEFT JOIN financial_cycles c ON c.id = m.cycle_id AND c.user_id = m.user_id
       WHERE m.user_id = agent_owner_id()
         AND m.member_user_id = agent_member_id()
         AND agent_subject_type() = 'member'`,
  },
  {
    name: 'agent_v_my_events',
    scope: 'member',
    description: 'الأحداث المسجّلة على ملف العضو: صرف راتب، تسجيل دين، تسوية، وغيرها.',
    sql: `
      SELECT e.event_type  AS "نوع_الحدث",
             e.amount      AS "المبلغ_دولار",
             e.cycle_id    AS "رقم_الدورة",
             e.notes       AS "الملاحظات",
             e.status      AS "الحالة",
             e.created_at  AS "التاريخ"
        FROM member_profile_events e
       WHERE e.user_id = agent_owner_id()
         AND e.member_user_id = agent_member_id()
         AND agent_subject_type() = 'member'`,
  },
  {
    name: 'agent_v_my_shipping',
    scope: 'member',
    description: 'مشتريات الشحن المسجّلة على العضو: النوع، الكمية، المبلغ، طريقة الدفع.',
    sql: `
      SELECT s.id              AS "الرقم",
             s.type            AS "نوع_العملية",
             s.item_type       AS "نوع_السلعة",
             s.quantity        AS "الكمية",
             s.unit_price      AS "سعر_الوحدة",
             s.total           AS "الإجمالي",
             s.payment_method  AS "طريقة_الدفع",
             s.status          AS "الحالة",
             s.notes           AS "الملاحظات",
             s.created_at      AS "التاريخ"
        FROM shipping_transactions s
       WHERE s.buyer_type = 'user'
         AND s.buyer_user_id = agent_member_id()
         AND agent_subject_type() = 'member'
         AND agent_owner_id() IS NOT NULL`,
  },

  // ══════════════ عروض المشغّل ══════════════
  {
    name: 'agent_v_members',
    scope: 'operator',
    description: 'كل أعضاء المؤسسة مع أرصدتهم. للبحث والمقارنة والتجميع.',
    sql: `
      SELECT p.member_user_id            AS "آيدي_العضو",
             p.display_name              AS "الاسم",
             p.last_seen_name            AS "آخر_اسم_ظاهر",
             p.total_salary_audited_usd  AS "إجمالي_الراتب_المدقق_دولار",
             p.deferred_balance_usd      AS "الرصيد_المؤجل_دولار",
             p.debt_to_company_usd       AS "الدين_على_الشركة_دولار",
             p.updated_at                AS "آخر_تحديث"
        FROM member_profiles p
       WHERE p.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_funds',
    scope: 'operator',
    description: 'صناديق المؤسسة وأرصدتها بكل عملة. is_main يشير للصندوق الرئيسي.',
    sql: `
      SELECT f.id                    AS "رقم_الصندوق",
             f.name                  AS "اسم_الصندوق",
             f.fund_number           AS "رقم_الحساب",
             f.country               AS "الدولة",
             f.region_syria          AS "المنطقة",
             (f.is_main = 1)         AS "صندوق_رئيسي",
             b.currency              AS "العملة",
             COALESCE(b.amount, 0)   AS "الرصيد"
        FROM funds f
        LEFT JOIN fund_balances b ON b.fund_id = f.id
       WHERE f.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_fund_ledger',
    scope: 'operator',
    description: 'حركات الصناديق: إيداع وسحب وتحويل. الصفوف الملغاة لها تاريخ_الإلغاء.',
    sql: `
      SELECT l.id            AS "الرقم",
             f.name          AS "اسم_الصندوق",
             l.type          AS "نوع_الحركة",
             l.amount        AS "المبلغ",
             l.currency      AS "العملة",
             l.notes         AS "الملاحظات",
             l.created_at    AS "التاريخ",
             l.cancelled_at  AS "تاريخ_الإلغاء"
        FROM fund_ledger l
        JOIN funds f ON f.id = l.fund_id
       WHERE f.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_cycles',
    scope: 'operator',
    description: 'الدورات المالية للمؤسسة. تاريخ_إغلاق_التدقيق فارغ يعني الدورة مفتوحة.',
    sql: `
      SELECT c.id                        AS "رقم_الدورة",
             c.name                      AS "اسم_الدورة",
             c.transfer_discount_pct     AS "نسبة_خصم_التحويل",
             c.created_at                AS "تاريخ_الإنشاء",
             c.payroll_audit_closed_at   AS "تاريخ_إغلاق_التدقيق"
        FROM financial_cycles c
       WHERE c.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_ledger',
    scope: 'operator',
    description: 'القيود المالية العامة. direction: in وارد، out صادر. bucket يصنّف القيد.',
    sql: `
      SELECT e.id           AS "الرقم",
             e.bucket       AS "التصنيف",
             e.source_type  AS "المصدر",
             e.amount       AS "المبلغ",
             e.currency     AS "العملة",
             e.direction    AS "الاتجاه",
             e.cycle_id     AS "رقم_الدورة",
             e.notes        AS "الملاحظات",
             e.created_at   AS "التاريخ"
        FROM ledger_entries e
       WHERE e.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_expenses',
    scope: 'operator',
    description: 'مصاريف المؤسسة مصنّفة حسب الفئة.',
    sql: `
      SELECT x.id          AS "الرقم",
             x.category    AS "الفئة",
             x.amount      AS "المبلغ",
             x.currency    AS "العملة",
             x.notes       AS "الملاحظات",
             x.created_at  AS "التاريخ"
        FROM expense_entries x
       WHERE x.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_accreditations',
    scope: 'operator',
    description: 'جهات الاعتماد وأرصدتها: المستحق لنا والمستحق علينا.',
    sql: `
      SELECT a.id                 AS "رقم_الجهة",
             a.name               AS "اسم_الجهة",
             a.code               AS "الرمز",
             a.balance_amount     AS "الرصيد",
             a.balance_payable    AS "مستحق_علينا",
             a.balance_receivable AS "مستحق_لنا",
             (a.is_primary = 1)   AS "جهة_رئيسية",
             a.created_at         AS "تاريخ_الإنشاء"
        FROM accreditation_entities a
       WHERE a.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_accreditation_ledger',
    scope: 'operator',
    description: 'حركات جهات الاعتماد مع نسب ومبالغ الوساطة.',
    sql: `
      SELECT l.id                AS "الرقم",
             a.name              AS "اسم_الجهة",
             l.entry_type        AS "نوع_الحركة",
             l.amount            AS "المبلغ",
             l.currency          AS "العملة",
             l.direction         AS "الاتجاه",
             l.brokerage_pct     AS "نسبة_الوساطة",
             l.brokerage_amount  AS "مبلغ_الوساطة",
             l.cycle_id          AS "رقم_الدورة",
             l.notes             AS "الملاحظات",
             l.created_at        AS "التاريخ"
        FROM accreditation_ledger l
        JOIN accreditation_entities a ON a.id = l.accreditation_id
       WHERE a.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_returns',
    scope: 'operator',
    description: 'المرتجعات المالية: المبلغ المرسل والمستخدم وصافي المرتجع.',
    sql: `
      SELECT r.id               AS "الرقم",
             r.entity_type      AS "نوع_الجهة",
             r.entity_id        AS "رقم_الجهة",
             r.amount           AS "المبلغ",
             r.sent_amount      AS "المبلغ_المرسل",
             r.utilized_amount  AS "المبلغ_المستخدم",
             r.net_amount       AS "الصافي",
             r.currency         AS "العملة",
             r.disposition      AS "المآل",
             r.notes            AS "الملاحظات",
             r.created_at       AS "التاريخ",
             r.cancelled_at     AS "تاريخ_الإلغاء"
        FROM financial_returns r
       WHERE r.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_payables',
    scope: 'operator',
    description: 'المبالغ المستحقة على المؤسسة لجهات مختلفة.',
    sql: `
      SELECT p.id               AS "الرقم",
             p.entity_type      AS "نوع_الجهة",
             p.entity_id        AS "رقم_الجهة",
             p.amount           AS "المبلغ",
             p.currency         AS "العملة",
             p.settlement_mode  AS "طريقة_التسوية",
             p.notes            AS "الملاحظات",
             p.created_at       AS "التاريخ"
        FROM entity_payables p
       WHERE p.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_fx_spread',
    scope: 'operator',
    description: 'فروق التصريف: الفرق بين السعر الداخلي وسعر التسوية بالدولار.',
    sql: `
      SELECT x.id              AS "الرقم",
             x.currency        AS "العملة",
             x.amount_foreign  AS "المبلغ_بالعملة",
             x.internal_rate   AS "السعر_الداخلي",
             x.settlement_rate AS "سعر_التسوية",
             x.spread_usd      AS "الفرق_دولار",
             x.cycle_id        AS "رقم_الدورة",
             x.notes           AS "الملاحظات",
             x.created_at      AS "التاريخ"
        FROM fx_spread_entries x
       WHERE x.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_admin_brokerage',
    scope: 'operator',
    description: 'الوساطة الإدارية: المبلغ ونسبته وحصة الربح وحصة الصندوق الرئيسي.',
    sql: `
      SELECT b.id                AS "الرقم",
             b.amount            AS "المبلغ",
             b.brokerage_pct     AS "نسبة_الوساطة",
             b.profit_amount     AS "مبلغ_الربح",
             b.main_fund_amount  AS "حصة_الصندوق_الرئيسي",
             b.cycle_id          AS "رقم_الدورة",
             b.notes             AS "الملاحظات",
             b.created_at        AS "التاريخ"
        FROM admin_brokerage_entries b
       WHERE b.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_transfer_companies',
    scope: 'operator',
    description: 'شركات التحويل التابعة للمؤسسة.',
    sql: `
      SELECT t.id          AS "الرقم",
             t.name        AS "اسم_الشركة",
             t.created_at  AS "تاريخ_الإنشاء"
        FROM transfer_companies t
       WHERE t.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
  {
    name: 'agent_v_deferred_all',
    scope: 'operator',
    description: 'الأرصدة المؤجلة لكل الأعضاء عبر الدورات. للتجميع والمقارنة.',
    sql: `
      SELECT d.member_user_id  AS "آيدي_العضو",
             d.cycle_id        AS "رقم_الدورة",
             c.name            AS "اسم_الدورة",
             d.balance_d       AS "الرصيد_المؤجل_دولار",
             d.updated_at      AS "تاريخ_التحديث"
        FROM deferred_salary_lines d
        LEFT JOIN financial_cycles c ON c.id = d.cycle_id AND c.user_id = d.user_id
       WHERE d.user_id = agent_owner_id()
         AND agent_subject_type() = 'operator'`,
  },
];

/**
 * إنشاء أو تحديث كل العروض ومنح دور الوكيل حق القراءة عليها وحدها.
 * @returns {Promise<number>} عدد العروض التي أُنشئت بنجاح
 */
async function ensureAgentViews(pool, agentRole) {
  let created = 0;

  for (const view of VIEWS) {
    try {
      await pool.query(`CREATE OR REPLACE VIEW ${view.name} AS ${view.sql}`);
      await pool.query(`GRANT SELECT ON ${view.name} TO ${agentRole}`);
      await pool.query(
        `COMMENT ON VIEW ${view.name} IS ${literal(view.description)}`
      );
      created++;
    } catch (e) {
      console.warn(`[agent-views] تعذّر إنشاء ${view.name}:`, e.message);
    }
  }

  return created;
}

function literal(s) {
  return `'${String(s).replace(/'/g, "''")}'`;
}

/** كتالوج العروض المتاحة لنوع هوية محدّد — يُرسل للنموذج ليعرف ما يستعلم */
function catalogFor(subjectType) {
  return VIEWS.filter((v) => v.scope === subjectType).map((v) => ({
    name: v.name,
    description: v.description,
  }));
}

/** هل هذا العرض مسموح لهذا النوع؟ يُستخدم في التحقق قبل التنفيذ */
function isViewAllowed(name, subjectType) {
  return VIEWS.some((v) => v.name === name && v.scope === subjectType);
}

/** كل أسماء العروض المعروفة */
function allViewNames() {
  return VIEWS.map((v) => v.name);
}

module.exports = { ensureAgentViews, catalogFor, isViewAllowed, allViewNames, VIEWS };
