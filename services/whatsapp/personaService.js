/**
 * شخصية الوكيل: من هو، كيف يتكلم، وماذا يُتقن.
 *
 * الهدف أن يقرأ الردّ كأنه من محاسب حقيقي في المكتب، لا من روبوت.
 * كل شيء هنا قابل للتخصيص من لوحة التحكم: الدور، اللهجة، النبرة،
 * طول الرد، الاسم، تحية الافتتاح، تعليمات حرة، ومهارات إضافية.
 *
 * ملاحظة أمنية: كل ما في هذا الملف يؤثر على **الأسلوب** فقط. لا شيء
 * منه يوسّع الوصول للبيانات — حتى التعليمات الحرة التي يكتبها المسؤول
 * لا تستطيع منح الوكيل صفاً واحداً خارج نطاق الهوية، لأن النطاق مفروض
 * في قاعدة البيانات لا في البرومبت.
 */

const { getDb } = require('../../db/database');

const DIALECTS = {
  levantine: {
    label: 'شامي',
    guidance: 'تكلّم بالعامية الشامية الطبيعية (سوريا/لبنان). استعمل «شو»، «هلق»، «مبلغ», «تمام», «ما في». تجنّب الفصحى المتكلّفة.',
  },
  gulf: {
    label: 'خليجي',
    guidance: 'تكلّم بالعامية الخليجية. استعمل «وش»، «الحين»، «زين»، «ما فيه». تجنّب الفصحى المتكلّفة.',
  },
  egyptian: {
    label: 'مصري',
    guidance: 'تكلّم بالعامية المصرية. استعمل «إيه»، «دلوقتي»، «تمام»، «مفيش». تجنّب الفصحى المتكلّفة.',
  },
  iraqi: {
    label: 'عراقي',
    guidance: 'تكلّم بالعامية العراقية. استعمل «شنو»، «هسه»، «زين»، «ماكو».',
  },
  maghrebi: {
    label: 'مغاربي',
    guidance: 'تكلّم بالدارجة المغاربية. استعمل «شنو»، «دابا»، «مزيان»، «ماكاينش».',
  },
  msa: {
    label: 'فصحى',
    guidance: 'تكلّم بالعربية الفصحى المبسّطة، واضحة ومهنية بلا تكلّف.',
  },
};

const TONES = {
  friendly: 'ودود ودافئ. تعامل مع المستخدم كزميل تعرفه، لا كطلب وارد.',
  formal: 'مهني ورسمي ومحترم، بلا مبالغة في التودّد.',
  concise: 'مباشر ومختصر جداً. الجواب أولاً، بلا مقدمات.',
  patient: 'صبور وشارح. افترض أن المستخدم غير محاسب واشرح المصطلحات ببساطة.',
};

const VERBOSITY = {
  brief: 'سطر أو سطران. الرقم المطلوب وكفى.',
  balanced: 'ثلاثة إلى ستة أسطر. الجواب مع سياق قصير يفيد.',
  detailed: 'اشرح بتفصيل مع الأرقام الداعمة والمقارنات المفيدة، مع بقاء الرد صالحاً لشاشة هاتف.',
};

const DEFAULTS = {
  role_title: 'محاسب',
  dialect: 'levantine',
  tone: 'friendly',
  verbosity: 'balanced',
  agent_name: null,
  greeting: null,
  custom_instructions: null,
  skills: [],
  emoji_enabled: true,
};

/** مهارات جاهزة يمكن للمسؤول تفعيلها */
const BUILTIN_SKILLS = {
  explain_variance: {
    label: 'تفسير الفروقات',
    instruction: 'عندما يتغيّر رقم بين دورتين، لا تكتفِ بعرض الفرق — ابحث عن سببه في التعديلات والأحداث والخصومات واذكره صراحةً.',
  },
  proactive_summary: {
    label: 'ملخص استباقي',
    instruction: 'بعد الإجابة عن السؤال، أضف سطراً واحداً عن أي شيء لافت في حساب المستخدم يستحق انتباهه (دين مرتفع، خصم جديد، رصيد متغيّر).',
  },
  numeric_discipline: {
    label: 'انضباط رقمي',
    instruction: 'اذكر دائماً الوحدة والعملة والفترة الزمنية مع كل رقم. عند التجميع، بيّن عدد الصفوف التي جُمعت.',
  },
  teach_terms: {
    label: 'شرح المصطلحات',
    instruction: 'عند استعمال مصطلح محاسبي (مؤجل، وساطة، اعتماد، فرق تصريف)، أضف شرحاً من بضع كلمات بين قوسين أول مرة فقط.',
  },
  escalate: {
    label: 'التحويل للإدارة',
    instruction: 'إن طلب المستخدم تعديلاً أو اعتراضاً أو أي إجراء لا تملكه، وضّح أنك للاستعلام فقط، واطلب منه مراجعة الإدارة بجملة واحدة مهذّبة.',
  },
};

function parseSkills(raw) {
  if (!raw) return [];
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

/** جلب شخصية الوكيل لمالك نظام، مع القيم الافتراضية عند غيابها */
async function getPersona(ownerUserId) {
  const db = getDb();
  const row = (await db.query(
    'SELECT * FROM whatsapp_agent_persona WHERE user_id = $1',
    [ownerUserId]
  )).rows[0];

  if (!row) return { ...DEFAULTS, user_id: ownerUserId };

  return {
    user_id: row.user_id,
    role_title: row.role_title || DEFAULTS.role_title,
    dialect: DIALECTS[row.dialect] ? row.dialect : DEFAULTS.dialect,
    tone: TONES[row.tone] ? row.tone : DEFAULTS.tone,
    verbosity: VERBOSITY[row.verbosity] ? row.verbosity : DEFAULTS.verbosity,
    agent_name: row.agent_name || null,
    greeting: row.greeting || null,
    custom_instructions: row.custom_instructions || null,
    skills: parseSkills(row.skills_json),
    emoji_enabled: row.emoji_enabled !== false,
  };
}

/** حفظ الشخصية. الحقول غير الممرّرة تبقى كما هي. */
async function savePersona(ownerUserId, input) {
  const db = getDb();
  const current = await getPersona(ownerUserId);

  const dialect = DIALECTS[input.dialect] ? input.dialect : current.dialect;
  const tone = TONES[input.tone] ? input.tone : current.tone;
  const verbosity = VERBOSITY[input.verbosity] ? input.verbosity : current.verbosity;

  const skills = Array.isArray(input.skills)
    ? input.skills.filter((s) => typeof s === 'string' && (BUILTIN_SKILLS[s] || s.startsWith('custom:')))
    : current.skills;

  const clamp = (v, max) => (v == null ? null : String(v).slice(0, max));

  await db.query(
    `INSERT INTO whatsapp_agent_persona
       (user_id, role_title, dialect, tone, verbosity, agent_name, greeting,
        custom_instructions, skills_json, emoji_enabled, updated_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, CURRENT_TIMESTAMP)
     ON CONFLICT (user_id) DO UPDATE SET
       role_title = EXCLUDED.role_title,
       dialect = EXCLUDED.dialect,
       tone = EXCLUDED.tone,
       verbosity = EXCLUDED.verbosity,
       agent_name = EXCLUDED.agent_name,
       greeting = EXCLUDED.greeting,
       custom_instructions = EXCLUDED.custom_instructions,
       skills_json = EXCLUDED.skills_json,
       emoji_enabled = EXCLUDED.emoji_enabled,
       updated_at = CURRENT_TIMESTAMP`,
    [
      ownerUserId,
      clamp(input.role_title ?? current.role_title, 60),
      dialect,
      tone,
      verbosity,
      clamp(input.agent_name ?? current.agent_name, 40),
      clamp(input.greeting ?? current.greeting, 300),
      clamp(input.custom_instructions ?? current.custom_instructions, 2000),
      JSON.stringify(skills),
      input.emoji_enabled ?? current.emoji_enabled,
    ]
  );

  return getPersona(ownerUserId);
}

/** الخيارات المتاحة — تُغذّي قوائم لوحة التحكم */
function getOptions() {
  return {
    dialects: Object.entries(DIALECTS).map(([id, d]) => ({ id, label: d.label })),
    tones: Object.entries(TONES).map(([id, description]) => ({ id, description })),
    verbosity: Object.entries(VERBOSITY).map(([id, description]) => ({ id, description })),
    skills: Object.entries(BUILTIN_SKILLS).map(([id, s]) => ({ id, label: s.label, instruction: s.instruction })),
  };
}

/** تحويل الشخصية إلى نص تعليمات يُدرج في برومبت النظام */
function renderPersonaPrompt(persona) {
  const dialect = DIALECTS[persona.dialect] || DIALECTS.levantine;
  const lines = [];

  const name = persona.agent_name ? `اسمك ${persona.agent_name}. ` : '';
  lines.push(`${name}أنت ${persona.role_title} تعمل في هذه المؤسسة، وتردّ على واتساب.`);
  lines.push('');
  lines.push('أسلوبك:');
  lines.push(`- اللهجة: ${dialect.guidance}`);
  lines.push(`- النبرة: ${TONES[persona.tone] || TONES.friendly}`);
  lines.push(`- الطول: ${VERBOSITY[persona.verbosity] || VERBOSITY.balanced}`);
  lines.push(persona.emoji_enabled
    ? '- إيموجي واحد أو اثنان عند المناسبة فقط، لا أكثر.'
    : '- بلا إيموجي إطلاقاً.');

  const skillInstructions = (persona.skills || [])
    .map((id) => BUILTIN_SKILLS[id]?.instruction || (id.startsWith('custom:') ? id.slice(7) : null))
    .filter(Boolean);

  if (skillInstructions.length) {
    lines.push('');
    lines.push('مهاراتك:');
    for (const s of skillInstructions) lines.push(`- ${s}`);
  }

  if (persona.custom_instructions) {
    lines.push('');
    lines.push('تعليمات إضافية من الإدارة:');
    lines.push(persona.custom_instructions);
  }

  return lines.join('\n');
}

module.exports = {
  getPersona,
  savePersona,
  getOptions,
  renderPersonaPrompt,
  DIALECTS,
  TONES,
  VERBOSITY,
  BUILTIN_SKILLS,
  DEFAULTS,
};
