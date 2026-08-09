/**
 * اختبار عزل المستخدمين — يثبت أن مستخدماً لا يصل لبيانات مستخدم آخر
 * حتى لو حاول النموذج تمرير هوية بديلة.
 */
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const { initDatabase, getDb } = require('../db/database');
const identity = require('../services/whatsapp/identityService');
const scoped = require('../services/whatsapp/scopedData');

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✅ ${name}`); }
  else { fail++; console.log(`  ❌ ${name}`, extra !== undefined ? JSON.stringify(extra) : ''); }
}

(async () => {
  await initDatabase();
  const db = getDb();

  // ── تجهيز: مالكان مختلفان، ولكل منهما عضو ──
  await db.query(`DELETE FROM whatsapp_identities WHERE phone_jid LIKE '9999%'`);
  await db.query(`DELETE FROM member_profiles WHERE member_user_id IN ('AAA111','BBB222')`);
  await db.query(`DELETE FROM deferred_salary_lines WHERE member_user_id IN ('AAA111','BBB222')`);
  await db.query(`DELETE FROM users WHERE username IN ('ownerA','ownerB')`);

  const ownerA = (await db.query(
    `INSERT INTO users (username, password, display_name, role) VALUES ('ownerA','x','Owner A','admin')`)).lastInsertRowid;
  const ownerB = (await db.query(
    `INSERT INTO users (username, password, display_name, role) VALUES ('ownerB','x','Owner B','admin')`)).lastInsertRowid;

  await db.query(
    `INSERT INTO member_profiles (user_id, member_user_id, display_name, total_salary_audited_usd, deferred_balance_usd, debt_to_company_usd)
     VALUES ($1,'AAA111','عضو ألف', 1000, 250, 40)`, [ownerA]);
  await db.query(
    `INSERT INTO member_profiles (user_id, member_user_id, display_name, total_salary_audited_usd, deferred_balance_usd, debt_to_company_usd)
     VALUES ($1,'BBB222','عضو باء', 7777, 8888, 9999)`, [ownerB]);

  // الرصيد المؤجل مشتق من deferred_salary_lines في النظام، فنزرعه هناك
  await db.query(
    `INSERT INTO deferred_salary_lines (user_id, cycle_id, member_user_id, balance_d)
     VALUES ($1, 1, 'AAA111', 250)`, [ownerA]);
  await db.query(
    `INSERT INTO deferred_salary_lines (user_id, cycle_id, member_user_id, balance_d)
     VALUES ($1, 1, 'BBB222', 8888)`, [ownerB]);

  console.log('\n── 1) رقم غير مسجّل لا يحصل على نطاق ──');
  const unknown = await identity.resolveScope('999900000000');
  check('رقم غير معروف مرفوض', unknown.allowed === false && unknown.reason === 'unknown_number', unknown.reason);

  console.log('\n── 2) الربط وحده لا يكفي: يبقى بلا وصول قبل التفعيل ──');
  const linkA = await identity.linkIdentity({
    ownerUserId: ownerA, subjectType: 'member', memberUserId: 'AAA111',
    phone: '999900000001', displayName: 'عضو ألف', createdBy: ownerA,
  });
  const beforeVerify = await identity.resolveScope('999900000001');
  check('مربوط لكن غير مُفعّل ⇒ لا وصول', beforeVerify.allowed === false && beforeVerify.reason === 'not_verified', beforeVerify.reason);

  console.log('\n── 3) رمز خاطئ يُرفض، والصحيح يُفعّل ──');
  const identA = await identity.getIdentityByJid('999900000001');
  const issued = await identity.issueOtp(identA);
  check('صدر رمز', issued.ok === true);

  const wrong = await identity.verifyOtp(identA, issued.code === '000000' ? '111111' : '000000');
  check('الرمز الخاطئ مرفوض', wrong.ok === false, wrong.reason);

  const right = await identity.verifyOtp(await identity.getIdentityByJid('999900000001'), issued.code);
  check('الرمز الصحيح يُفعّل', right.ok === true, right.reason);

  console.log('\n── 4) العضو المُفعّل يرى بياناته هو فقط ──');
  const scopeA = (await identity.resolveScope('999900000001')).scope;
  check('صدر نطاق', !!scopeA && scopeA.memberUserId === 'AAA111');

  const summary = await scoped.executeTool(scopeA, 'get_my_summary', {});
  check('يرى راتبه هو (1000)', summary.totalSalaryAuditedUsd === 1000, summary);
  check('يرى مؤجله هو (250)', summary.deferredBalanceUsd === 250, summary);

  console.log('\n── 5) محاولة حقن هوية أخرى عبر معاملات الأداة ──');
  const injected = await scoped.executeTool(scopeA, 'get_my_summary', {
    memberUserId: 'BBB222', member_user_id: 'BBB222', ownerUserId: ownerB, user_id: ownerB,
    scope: { ownerUserId: ownerB, memberUserId: 'BBB222' },
  });
  // المقارنة مع النتيجة الأصلية: الحقن يجب ألا يغيّر شيئاً إطلاقاً
  const strip = (o) => JSON.stringify({ ...o, updatedAt: null });
  check('المعاملات المحقونة أُسقطت — النتيجة مطابقة تماماً للاستدعاء النظيف',
    strip(injected) === strip(summary), { injected, summary });
  check('لم تتسرب أي قيمة من العضو الآخر',
    JSON.stringify(injected).indexOf('7777') === -1 && JSON.stringify(injected).indexOf('8888') === -1, injected);

  console.log('\n── 6) أدوات المشغّل غير متاحة للعضو ──');
  const denied = await scoped.executeTool(scopeA, 'find_member', { query: 'عضو' });
  check('find_member مرفوضة لعضو', !!denied.error, denied);

  const memberTools = scoped.getToolDefinitions(scopeA).map(t => t.name);
  check('العضو لا يرى أدوات المشغّل إطلاقاً', !memberTools.includes('find_member'), memberTools);

  console.log('\n── 7) المشغّل يبحث داخل مؤسسته فقط ──');
  await identity.linkIdentity({
    ownerUserId: ownerA, subjectType: 'operator', operatorUserId: ownerA,
    phone: '999900000002', displayName: 'مشغّل ألف', createdBy: ownerA,
  });
  const identOp = await identity.getIdentityByJid('999900000002');
  const issuedOp = await identity.issueOtp(identOp);
  await identity.verifyOtp(identOp, issuedOp.code);
  const scopeOp = (await identity.resolveScope('999900000002')).scope;

  const foundOwn = await scoped.executeTool(scopeOp, 'find_member', { query: 'AAA111' });
  check('يجد عضو مؤسسته', foundOwn.count === 1, foundOwn);

  const foundOther = await scoped.executeTool(scopeOp, 'find_member', { query: 'BBB222' });
  check('لا يجد عضو مؤسسة أخرى رغم معرفة آيديه', foundOther.count === 0, foundOther);

  console.log('\n── 8) الحظر يقطع الوصول فوراً ──');
  await identity.setIdentityStatus(ownerA, scopeA.identityId, 'blocked');
  const blocked = await identity.resolveScope('999900000001');
  check('المحظور بلا وصول', blocked.allowed === false && blocked.reason === 'blocked', blocked.reason);

  console.log('\n── 9) كل محاولة سُجّلت في التدقيق ──');
  const logs = (await db.query(
    `SELECT action, COUNT(*)::int c FROM whatsapp_access_log WHERE phone_jid LIKE '9999%' GROUP BY action ORDER BY action`)).rows;
  check('سجل التدقيق يحتوي أحداثاً', logs.length > 0, logs);
  const actions = logs.map(l => l.action);
  check('سُجّل تنفيذ الأدوات', actions.includes('tool_executed'), actions);
  check('سُجّل رفض الأداة', actions.includes('tool_denied'), actions);
  check('سُجّل فشل الرمز', actions.includes('otp_failed'), actions);

  // تنظيف
  await db.query(`DELETE FROM whatsapp_identities WHERE phone_jid LIKE '9999%'`);
  await db.query(`DELETE FROM whatsapp_access_log WHERE phone_jid LIKE '9999%'`);
  await db.query(`DELETE FROM member_profiles WHERE member_user_id IN ('AAA111','BBB222')`);
  await db.query(`DELETE FROM deferred_salary_lines WHERE member_user_id IN ('AAA111','BBB222')`);
  await db.query(`DELETE FROM users WHERE username IN ('ownerA','ownerB')`);

  console.log(`\n══════ النتيجة: ${pass} ناجح، ${fail} فاشل ══════`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('خطأ في الاختبار:', e); process.exit(1); });
