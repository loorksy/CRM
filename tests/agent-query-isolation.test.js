/**
 * اختبار عزل طبقة الاستعلام الحر.
 *
 * الاختبار السابق (whatsapp-isolation) يغطي الأدوات الجاهزة. هذا الاختبار
 * يهاجم الطبقة الجديدة: النموذج هنا يكتب SQL بنفسه، فالسؤال هو هل يستطيع
 * الخروج من نطاقه بأي حيلة.
 */
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const { initDatabase, getDb } = require('../db/database');
const agentQuery = require('../services/whatsapp/agentQuery');

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✅ ${name}`); }
  else { fail++; console.log(`  ❌ ${name}`, extra !== undefined ? JSON.stringify(extra).slice(0, 300) : ''); }
}

const q = (scope, sql) => agentQuery.runScopedQuery(scope, sql);

(async () => {
  await initDatabase();
  const db = getDb();

  if (!agentQuery.isEnabled()) {
    console.log('⚠️  طبقة الاستعلام غير مُهيّأة (AGENT_DB_PASSWORD غير مضبوط). تخطّي.');
    process.exit(0);
  }

  // ── تجهيز مؤسستين ──
  await db.query(`DELETE FROM member_profiles WHERE member_user_id IN ('QAAA','QBBB')`);
  await db.query(`DELETE FROM deferred_salary_lines WHERE member_user_id IN ('QAAA','QBBB')`);
  await db.query(`DELETE FROM users WHERE username IN ('qOwnerA','qOwnerB')`);

  const ownerA = (await db.query(`INSERT INTO users (username,password,display_name,role) VALUES ('qOwnerA','x','A','admin')`)).lastInsertRowid;
  const ownerB = (await db.query(`INSERT INTO users (username,password,display_name,role) VALUES ('qOwnerB','x','B','admin')`)).lastInsertRowid;

  await db.query(`INSERT INTO member_profiles (user_id,member_user_id,display_name,total_salary_audited_usd,deferred_balance_usd,debt_to_company_usd)
                  VALUES ($1,'QAAA','عضو ألف',1000,250,40)`, [ownerA]);
  await db.query(`INSERT INTO member_profiles (user_id,member_user_id,display_name,total_salary_audited_usd,deferred_balance_usd,debt_to_company_usd)
                  VALUES ($1,'QBBB','عضو باء',7777,8888,9999)`, [ownerB]);
  await db.query(`INSERT INTO funds (user_id,name) VALUES ($1,'صندوق ألف')`, [ownerA]);
  await db.query(`INSERT INTO funds (user_id,name) VALUES ($1,'صندوق باء')`, [ownerB]);

  const memberA  = { ownerUserId: ownerA, subjectType: 'member',   memberUserId: 'QAAA', identityId: 1, phoneJid: 'test@s.whatsapp.net' };
  const operatorA = { ownerUserId: ownerA, subjectType: 'operator', operatorUserId: ownerA, memberUserId: null, identityId: 2, phoneJid: 'op@s.whatsapp.net' };

  const json = (o) => JSON.stringify(o);
  const leaksB = (o) => /7777|8888|9999|عضو باء|صندوق باء/.test(json(o));

  console.log('\n── 1) الاستعلام المشروع يعمل ──');
  const own = await q(memberA, 'SELECT * FROM agent_v_my_profile');
  check('العضو يرى ملفه', own.rowCount === 1 && !own.error, own);
  check('القيم صحيحة', json(own).includes('1000') && json(own).includes('250'), own);

  console.log('\n── 2) الجداول الأساسية ممنوعة تماماً ──');
  for (const t of ['member_profiles', 'users', 'funds', 'whatsapp_identities', 'whatsapp_otp']) {
    const r = await q(memberA, `SELECT * FROM ${t}`);
    check(`SELECT من ${t} مرفوض`, !!r.error && !r.rows, r);
  }

  console.log('\n── 3) عروض المشغّل محجوبة عن العضو ──');
  for (const v of ['agent_v_members', 'agent_v_funds', 'agent_v_ledger']) {
    const r = await q(memberA, `SELECT * FROM ${v}`);
    check(`${v} يُرجع صفراً للعضو`, r.rowCount === 0 && !leaksB(r), r);
  }

  console.log('\n── 4) محاولة الوصول لعضو آخر عبر SQL ──');
  const attempts = [
    `SELECT * FROM agent_v_my_profile WHERE "آيدي_العضو" = 'QBBB'`,
    `SELECT * FROM agent_v_my_profile WHERE 1=1 OR "آيدي_العضو" = 'QBBB'`,
    `SELECT * FROM agent_v_my_profile UNION ALL SELECT * FROM agent_v_my_profile`,
    `WITH a AS (SELECT * FROM agent_v_my_profile) SELECT * FROM a`,
    `SELECT * FROM agent_v_members`,
    `SELECT * FROM agent_v_deferred_all`,
  ];
  for (const sql of attempts) {
    const r = await q(memberA, sql);
    check(`لا تسريب: ${sql.slice(0, 52)}…`, !leaksB(r), r);
  }

  console.log('\n── 5) العبث بمتغيرات النطاق ──');
  const tamper = [
    `SELECT set_config('app.member_id','QBBB',false)`,
    `SELECT pg_catalog.set_config('app.member_id','QBBB',false)`,
    `WITH t AS (SELECT set_config('app.owner_id','${ownerB}',false)) SELECT * FROM agent_v_my_profile`,
    `SELECT SET_CONFIG('app.subject_type','operator',false)`,
  ];
  for (const sql of tamper) {
    const r = await q(memberA, sql);
    check(`مرفوض: ${sql.slice(0, 52)}…`, !!r.error && !leaksB(r), r);
  }

  console.log('\n── 6) بعد محاولة العبث، النطاق ما زال سليماً ──');
  const after = await q(memberA, 'SELECT * FROM agent_v_my_profile');
  check('ما زال يرى بياناته هو فقط', after.rowCount === 1 && !leaksB(after), after);

  console.log('\n── 7) الكتابة مستحيلة ──');
  for (const sql of [
    `UPDATE member_profiles SET debt_to_company_usd = 0`,
    `DELETE FROM member_profiles`,
    `INSERT INTO member_profiles (user_id,member_user_id) VALUES (1,'X')`,
    `DROP VIEW agent_v_my_profile`,
    `CREATE TABLE hack (id int)`,
    `GRANT SELECT ON member_profiles TO lorkerp_agent`,
  ]) {
    const r = await q(memberA, sql);
    check(`مرفوض: ${sql.slice(0, 44)}…`, !!r.error, r);
  }

  console.log('\n── 8) جداول النظام الداخلية محجوبة ──');
  for (const sql of [
    `SELECT * FROM pg_catalog.pg_tables`,
    `SELECT * FROM information_schema.tables`,
    `SELECT current_setting('app.owner_id')`,
  ]) {
    const r = await q(memberA, sql);
    check(`مرفوض/فارغ: ${sql.slice(0, 44)}…`, !!r.error || !leaksB(r), r);
  }

  console.log('\n── 9) المشغّل يرى مؤسسته ولا يرى غيرها ──');
  const opFunds = await q(operatorA, `SELECT * FROM agent_v_funds`);
  check('المشغّل يرى صندوق مؤسسته', json(opFunds).includes('صندوق ألف'), opFunds);
  check('ولا يرى صندوق المؤسسة الأخرى', !json(opFunds).includes('صندوق باء'), opFunds);

  const opMembers = await q(operatorA, `SELECT * FROM agent_v_members`);
  check('يرى أعضاء مؤسسته', json(opMembers).includes('QAAA'), opMembers);
  check('ولا يرى أعضاء غيرها', !json(opMembers).includes('QBBB'), opMembers);

  const opMine = await q(operatorA, `SELECT * FROM agent_v_my_profile`);
  check('عروض العضو فارغة للمشغّل', opMine.rowCount === 0, opMine);

  console.log('\n── 10) التجميع والتحليل يعمل فعلاً ──');
  const agg = await q(operatorA, `
    SELECT COUNT(*) AS "العدد", SUM("الرصيد_المؤجل_دولار") AS "الإجمالي"
      FROM agent_v_members`);
  check('استعلام تجميعي ينجح', !agg.error && agg.rowCount === 1, agg);
  check('التجميع محصور في المؤسسة', json(agg).includes('250') && !json(agg).includes('8888'), agg);

  console.log('\n── 11) سقف الصفوف ومهلة التنفيذ ──');
  const capped = await q(operatorA, `SELECT * FROM agent_v_members LIMIT 100000`);
  check('السقف مفروض من الخارج', capped.rowCount <= agentQuery.MAX_ROWS, capped);

  const slow = await q(operatorA, `SELECT COUNT(*) FROM generate_series(1,1000000000) g`);
  check('الاستعلام الثقيل يُقطع أو يُرفض', !!slow.error, slow);

  console.log('\n── 12) نطاق بلا هوية يفشل مغلقاً ──');
  const empty = await q({ ownerUserId: 999999, subjectType: 'member', memberUserId: 'NOBODY' }, `SELECT * FROM agent_v_my_profile`);
  check('هوية غير موجودة ⇒ صفر صفوف', empty.rowCount === 0 && !empty.error, empty);

  // تنظيف
  await db.query(`DELETE FROM funds WHERE user_id IN ($1,$2)`, [ownerA, ownerB]);
  await db.query(`DELETE FROM member_profiles WHERE member_user_id IN ('QAAA','QBBB')`);
  await db.query(`DELETE FROM deferred_salary_lines WHERE member_user_id IN ('QAAA','QBBB')`);
  await db.query(`DELETE FROM users WHERE username IN ('qOwnerA','qOwnerB')`);
  await agentQuery.closePool();

  console.log(`\n══════ النتيجة: ${pass} ناجح، ${fail} فاشل ══════`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('خطأ في الاختبار:', e); process.exit(1); });
