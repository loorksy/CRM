/**
 * اختبار تجربة المحادثة كما يراها المستخدم على هاتفه.
 *
 * نستبدل عميل Evolution بعميل وهمي يسجّل كل نداء، فنتحقق من التسلسل
 * الفعلي: تأكيد القراءة، حالة الكتابة، تفريغ الصوت، تقسيم الرد، التنسيق.
 */
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const path = require('path');

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✅ ${name}`); }
  else { fail++; console.log(`  ❌ ${name}`, extra !== undefined ? JSON.stringify(extra).slice(0, 250) : ''); }
}

// ── عميل Evolution وهمي ──
const calls = [];
const mockEvolution = {
  parseIncomingMessage: require('../services/whatsapp/evolutionClient').parseIncomingMessage,
  toWhatsAppFormatting: null,
  async sendText(instance, jid, text, opts = {}) {
    calls.push({ op: 'sendText', text, delay: opts.delay, presence: opts.presence });
    return { ok: true, data: {} };
  },
  async sendPresence(instance, jid, presence, delay) {
    calls.push({ op: 'presence', presence, delay });
    return { ok: true };
  },
  async markAsRead(instance, key) {
    calls.push({ op: 'markAsRead', id: key?.id });
    return { ok: true };
  },
  async getMediaBase64() {
    calls.push({ op: 'getMedia' });
    return { ok: true, data: { base64: Buffer.from('fake-audio').toString('base64'), mimetype: 'audio/ogg' } };
  },
};

// حقن العميل الوهمي قبل تحميل المعالج
const clientPath = require.resolve('../services/whatsapp/evolutionClient');
const realClient = require(clientPath);
require.cache[clientPath].exports = { ...realClient, ...mockEvolution };

const { initDatabase, getDb } = require('../db/database');
const identity = require('../services/whatsapp/identityService');
const humanize = require('../services/whatsapp/humanize');

// وكيل وهمي: يعيد رداً ثابتاً بلا نداء نموذج حقيقي
const brainPath = require.resolve('../services/whatsapp/agentBrain');
require.cache[brainPath] = {
  id: brainPath, filename: brainPath, loaded: true, exports: {
    generateReply: async (scope, history, text) => {
      const reply = text.includes('طويل')
        ? Array.from({ length: 8 }, (_, i) =>
            `فقرة ${i + 1}: **تفاصيل** عن الدورة رقم ${i + 1} تشمل الراتب المدقّق ` +
            'والرصيد المؤجل المرحّل والخصومات المسجّلة والمكافآت المضافة، ' +
            'مع بيان تاريخ كل حركة والجهة التي سجّلتها وسبب التعديل.').join('\n\n')
        : 'رصيدك المؤجل حالياً 250 دولار.';
      return { reply, history: [...history, { role: 'user', content: text }, { role: 'assistant', content: reply }] };
    },
    buildSystemPrompt: () => '',
  },
};

// تفريغ صوتي وهمي
const sttPath = require.resolve('../services/whatsapp/transcription');
require.cache[sttPath] = {
  id: sttPath, filename: sttPath, loaded: true, exports: {
    transcribeVoiceNote: async () => ({ ok: true, text: 'كم رصيدي المؤجل؟' }),
    failureReply: (r) => `تعذّر: ${r}`,
    isEnabled: () => true,
  },
};

const { handleIncoming } = require('../services/whatsapp/messageHandler');

const upsert = (jid, message, id) => ({
  event: 'messages.upsert',
  instance: 'test-instance',
  data: { key: { remoteJid: jid, fromMe: false, id }, pushName: 'مستخدم', message, messageTimestamp: 1770000000 },
});

(async () => {
  await initDatabase();
  const db = getDb();
  const JID = '963900555444@s.whatsapp.net';

  await db.query(`DELETE FROM whatsapp_identities WHERE phone_jid = $1`, [JID]);
  await db.query(`DELETE FROM whatsapp_rate_limit WHERE phone_jid = $1`, [JID]);
  await db.query(`DELETE FROM member_profiles WHERE member_user_id = 'CONV1'`);
  await db.query(`DELETE FROM users WHERE username = 'convOwner'`);

  const owner = (await db.query(
    `INSERT INTO users (username,password,display_name,role) VALUES ('convOwner','x','Conv','admin')`)).lastInsertRowid;
  await db.query(
    `INSERT INTO member_profiles (user_id,member_user_id,display_name,deferred_balance_usd)
     VALUES ($1,'CONV1','عضو المحادثة',250)`, [owner]);

  console.log('\n── 1) رقم غير مسجّل: لا تأكيد قراءة ──');
  calls.length = 0;
  await handleIncoming(upsert(JID, { conversation: 'مرحبا' }, 'M1'));
  check('لم يُعلَّم كمقروء', !calls.some((c) => c.op === 'markAsRead'), calls);
  check('رُدّ عليه برسالة واحدة', calls.filter((c) => c.op === 'sendText').length === 1, calls);

  // ربط وتفعيل
  await identity.linkIdentity({
    ownerUserId: owner, subjectType: 'member', memberUserId: 'CONV1',
    phone: JID, displayName: 'عضو المحادثة', createdBy: owner,
  });
  const ident = await identity.getIdentityByJid(JID);
  const otp = await identity.issueOtp(ident);
  if (!otp.ok) throw new Error('تعذّر إصدار رمز التفعيل: ' + otp.reason);
  const verified = await identity.verifyOtp(ident, otp.code);
  if (!verified.ok) throw new Error('تعذّر التفعيل: ' + verified.reason);

  console.log('\n── 2) رسالة نصية من عضو مُفعّل ──');
  calls.length = 0;
  await handleIncoming(upsert(JID, { conversation: 'كم رصيدي؟' }, 'M2'));

  const read = calls.find((c) => c.op === 'markAsRead');
  check('عُلّمت مقروءة (علامتان زرقاوان)', !!read && read.id === 'M2', calls);

  const firstPresence = calls.findIndex((c) => c.op === 'presence' && c.presence === 'composing');
  const firstSend = calls.findIndex((c) => c.op === 'sendText');
  check('ظهر «يكتب الآن» قبل وصول الرد', firstPresence >= 0 && firstPresence < firstSend, calls);

  const sends = calls.filter((c) => c.op === 'sendText');
  check('الرد وصل', sends.length >= 1 && sends[0].text.includes('250'), sends);
  check('الرد مصحوب بمدة كتابة', sends[0].delay > 0, sends[0]);

  console.log('\n── 3) رد طويل يصل مقسّماً ──');
  calls.length = 0;
  await handleIncoming(upsert(JID, { conversation: 'أعطني تقرير طويل' }, 'M3'));
  const longSends = calls.filter((c) => c.op === 'sendText');
  check('قُسّم إلى أكثر من فقاعة', longSends.length > 1, longSends.length);
  check('كل فقاعة لها مدة كتابة', longSends.every((s) => s.delay > 0), longSends.map((s) => s.delay));
  check('لا ماركداون خام في أي فقاعة', longSends.every((s) => !s.text.includes('**')), longSends.map((s) => s.text.slice(0, 40)));
  check('التنسيق حُوّل إلى نجمة واحدة', longSends.some((s) => s.text.includes('*تفاصيل*')), longSends[0]?.text?.slice(0, 60));

  console.log('\n── 4) رسالة صوتية ──');
  calls.length = 0;
  await handleIncoming(upsert(JID, {
    audioMessage: { mimetype: 'audio/ogg; codecs=opus', seconds: 6, ptt: true },
  }, 'M4'));

  check('عُلّمت مقروءة', calls.some((c) => c.op === 'markAsRead'), calls);
  check('ظهرت حالة «يسجّل»', calls.some((c) => c.op === 'presence' && c.presence === 'recording'), calls);
  const voiceSends = calls.filter((c) => c.op === 'sendText');
  check('وصل رد نصي على الصوت', voiceSends.length >= 1 && voiceSends[0].text.includes('250'), voiceSends);

  console.log('\n── 5) تكرار الحدث لا يُرد عليه مرتين ──');
  calls.length = 0;
  await handleIncoming(upsert(JID, { conversation: 'كم رصيدي؟' }, 'M2'));
  check('التكرار مُهمَل', calls.filter((c) => c.op === 'sendText').length === 0, calls);

  console.log('\n── 6) المجموعات مُهمَلة ──');
  calls.length = 0;
  await handleIncoming(upsert('120363000000@g.us', { conversation: 'مرحبا' }, 'M5'));
  check('لا رد على المجموعات', calls.length === 0, calls);

  console.log('\n── 7) إيقاع الكتابة معقول ──');
  const d1 = humanize.typingDelayFor('تمام');
  const d2 = humanize.typingDelayFor('x'.repeat(300));
  check('الرد القصير أسرع من الطويل', d1 < d2, { d1, d2 });
  check('ضمن حدود بشرية (1.2s - 9s)', d1 >= 1200 && d2 <= 9000, { d1, d2 });

  await db.query(`DELETE FROM whatsapp_identities WHERE phone_jid = $1`, [JID]);
  await db.query(`DELETE FROM whatsapp_access_log WHERE phone_jid = $1`, [JID]);
  await db.query(`DELETE FROM whatsapp_rate_limit WHERE phone_jid = $1`, [JID]);
  await db.query(`DELETE FROM member_profiles WHERE member_user_id = 'CONV1'`);
  await db.query(`DELETE FROM users WHERE username = 'convOwner'`);

  console.log(`\n══════ النتيجة: ${pass} ناجح، ${fail} فاشل ══════`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('خطأ في الاختبار:', e); process.exit(1); });
