const OpenAI = require('openai');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const { getDb } = require('../db/database');
const { encrypt, decrypt } = require('../utils/crypto');

const CHUNK_SIZE = 12000;
const CHUNK_OVERLAP = 200;

const SYSTEM_PROMPT = `أنت خبير في إدخال البيانات وتحليل النصوص غير المهيكلة. مهمتك هي استخراج البيانات من رسائل واتساب عشوائية وتحويلها إلى جدول منظم (Markdown Table) جاهز للنسخ إلى Excel، بدقة 100% ودون تغيير في محتوى البيانات الأصلي.

إليك القواعد الصارمة التي يجب اتباعها (الخوارزمية):

1. هيكل الجدول (الأعمدة):
عامود A (الاسم): الاسم الشخصي (أول معلومة تدل على شخص).
عامود B (رقم الهاتف): استخرج الرقم مع الرمز الدولي، احذف أي نصوص مرافقة له.
عامود C (الآيدي): الرقم المميز للحساب (عادة 8 خانات أو أكثر).
عامود D (اسم الوكالة): ابحث عن كلمات مثل "وكالة"، "فريق".
عامود E (الدولة): الموقع الجغرافي الأكبر (سوريا، لبنان، تركيا، إلخ).
عامود F (المدينة): المدينة (دمشق، حلب، طرابلس..).
عامود G (العنوان): التفاصيل الأدق (الشارع، اسم مكتب محدد، معلم معروف).
عامود H (ملاحظة): ضع هنا أي عناوين محافظ إلكترونية (TR... أو أكواد طويلة - ممنوع حذفها)، وقيمة الراتب أو التاركت.
عامود I (طريقة التحويل): ابحث عن كلمات مفتاحية مثل (شام كاش، الهرم، ويش مني، سيرياتيل، فؤاد، زمرد، إلخ).
عامود J (ملاحظة إضافية): مخصص لحالات تكرار الأشخاص.

2. قواعد المنطق والشرطية (Logic Rules):
- قاعدة تعدد الآيديات (هام جداً): إذا وجدت في الرسالة الواحدة أكثر من رقم "آيدي" (ID) لنفس الشخص، يجب عليك إنشاء صف مستقل لكل آيدي. قم بنسخ نفس المعلومات (الاسم، الهاتف، الوكالة..) لكل الصفوف، واكتب في العامود J عبارة "نفس الشخص".
- البيانات المفقودة: إذا لم تجد معلومة محددة في الرسالة، اكتب مكانها عبارة "غير مذكور". لا تترك الخانة فارغة.
- عناوين المحافظ: لا تقم أبداً بحذف أي كود طويل أو عنوان محفظة رقمية، ضعه في عامود الملاحظات (H).
- الدقة: لا تقم بتلخيص النص أو تغيير المعنى. انقل البيانات كما هي.

3. المخرج المطلوب:
جدول Markdown فقط، بدون مقدمات أو شروحات إضافية. ابدأ مباشرة بالجدول.`;

function splitIntoChunks(text) {
  if (text.length <= CHUNK_SIZE) return [text];

  const chunks = [];
  const lines = text.split('\n');
  let current = '';

  for (const line of lines) {
    if ((current + '\n' + line).length > CHUNK_SIZE && current.length > 0) {
      chunks.push(current.trim());
      const overlapLines = current.split('\n').slice(-3).join('\n');
      current = overlapLines + '\n' + line;
    } else {
      current += (current ? '\n' : '') + line;
    }
  }
  if (current.trim()) chunks.push(current.trim());

  return chunks;
}

function mergeMarkdownTables(tables) {
  if (tables.length === 0) return '';
  if (tables.length === 1) return tables[0];

  let headerLine = '';
  let separatorLine = '';
  const allRows = [];

  for (const table of tables) {
    const lines = table.split('\n').filter(l => l.trim().startsWith('|'));
    if (lines.length < 2) continue;

    if (!headerLine) {
      headerLine = lines[0];
      separatorLine = lines[1];
    }

    for (let i = 2; i < lines.length; i++) {
      allRows.push(lines[i]);
    }
  }

  if (!headerLine) return tables.join('\n\n');
  return [headerLine, separatorLine, ...allRows].join('\n');
}

async function getProviderConfig(provider) {
  const db = getDb();
  const row = (await db.query('SELECT * FROM ai_config WHERE provider = $1', [provider])).rows[0];
  if (!row) return null;
  return {
    ...row,
    apiKey: decrypt(row.api_key_encrypted),
  };
}

async function getActiveProvider() {
  const db = getDb();
  const configs = (await db.query('SELECT * FROM ai_config ORDER BY updated_at DESC')).rows;
  for (const c of configs) {
    const key = decrypt(c.api_key_encrypted);
    if (key) return { ...c, apiKey: key };
  }
  return null;
}

async function getPreferredProvider() {
  const db = getDb();
  const configs = (await db.query('SELECT * FROM ai_config WHERE selected_model IS NOT NULL ORDER BY updated_at DESC')).rows;
  for (const c of configs) {
    const key = decrypt(c.api_key_encrypted);
    if (key && c.selected_model) return { ...c, apiKey: key };
  }
  return await getActiveProvider();
}

/**
 * OpenRouter — مزوّد احتياطي بواجهة متوافقة مع OpenAI.
 *
 * نقصر الاختيار على النماذج المجانية التي **تدعم استدعاء الأدوات**.
 * الشرط الثاني ليس تفصيلاً: وكيل واتساب كله مبني على استدعاء الأدوات
 * (describe_data ثم query_data)، ونموذج بلا هذا الدعم سيهلوس أرقاماً بدل
 * أن يستعلم عنها — وهو أسوأ من عدم الرد إطلاقاً في نظام محاسبي.
 */
function openRouterBaseUrl() { return process.env.OPENROUTER_BASE_URL || 'https://openrouter.ai/api/v1'; }

/** عميل OpenRouter. الترويستان الاختياريتان لنسبة الاستخدام في لوحة OpenRouter. */
function openRouterClient(apiKey) {
  return new OpenAI({
    apiKey,
    baseURL: openRouterBaseUrl(),
    defaultHeaders: {
      'HTTP-Referer': process.env.PUBLIC_BASE_URL || 'https://lorkerp.local',
      'X-Title': 'LorkERP',
    },
  });
}

/** هل النموذج مجاني فعلاً؟ التسعير نصوص في استجابة OpenRouter. */
function isFreeModel(model) {
  const p = model?.pricing || {};
  const zero = (v) => v === undefined || v === null || parseFloat(v) === 0;
  return zero(p.prompt) && zero(p.completion) && zero(p.request);
}

/** هل يدعم استدعاء الأدوات؟ */
function supportsTools(model) {
  const params = model?.supported_parameters || [];
  return Array.isArray(params) && (params.includes('tools') || params.includes('tool_choice'));
}

/**
 * جلب النماذج المجانية الداعمة للأدوات من OpenRouter.
 * @returns {Promise<Array<{id, name, contextLength, supportsTools}>>}
 */
async function fetchOpenRouterModels(apiKey) {
  const res = await fetch(`${openRouterBaseUrl()}/models`, {
    headers: apiKey ? { Authorization: `Bearer ${apiKey}` } : {},
  });

  if (!res.ok) {
    throw new Error(`OpenRouter رفض الطلب (${res.status}). تحقق من المفتاح والشبكة.`);
  }

  const body = await res.json();
  const all = Array.isArray(body?.data) ? body.data : [];

  const free = all.filter(isFreeModel);
  const withTools = free.filter(supportsTools);

  // الترتيب حسب سعة السياق: الأكبر أقدر على حمل تعليمات الوكيل ونتائج الاستعلام
  return withTools
    .map((m) => ({
      id: m.id,
      name: m.name || m.id,
      contextLength: m.context_length || 0,
      supportsTools: true,
    }))
    .sort((a, b) => b.contextLength - a.contextLength);
}

async function fetchModels(provider, apiKey) {
  try {
    if (provider === 'openrouter') {
      const models = await fetchOpenRouterModels(apiKey);
      if (!models.length) {
        throw new Error(
          'لم يُعثر على نموذج مجاني يدعم استدعاء الأدوات في OpenRouter حالياً. ' +
          'قائمة النماذج المجانية تتغيّر باستمرار — أعد المحاولة لاحقاً.'
        );
      }
      return models.map((m) => m.id);
    }

    if (provider === 'openai') {
      const client = new OpenAI({ apiKey });
      const list = await client.models.list();
      const models = [];
      for await (const m of list) {
        if (m.id.includes('gpt')) models.push(m.id);
      }
      return models.sort();
    }

    if (provider === 'gemini') {
      const genAI = new GoogleGenerativeAI(apiKey);
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`
      );
      const data = await response.json();
      if (!data.models) return [];
      return data.models
        .filter(m => m.supportedGenerationMethods?.includes('generateContent'))
        .map(m => m.name.replace('models/', ''))
        .sort();
    }

    return [];
  } catch (err) {
    console.error(`[AI] Error fetching models for ${provider}:`, err.message);
    throw new Error(err.message || 'فشل جلب الموديلات');
  }
}

async function callOpenRouter(apiKey, model, userMessage) {
  const client = openRouterClient(apiKey);
  const response = await client.chat.completions.create({
    model,
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: userMessage },
    ],
    temperature: 0.1,
    max_tokens: 16000,
  });
  return response.choices[0]?.message?.content || '';
}

async function callOpenAI(apiKey, model, userMessage) {
  const client = new OpenAI({ apiKey });
  const response = await client.chat.completions.create({
    model,
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: userMessage },
    ],
    temperature: 0.1,
    max_tokens: 16000,
  });
  return response.choices[0]?.message?.content || '';
}

async function callGemini(apiKey, model, userMessage) {
  const genAI = new GoogleGenerativeAI(apiKey);
  const genModel = genAI.getGenerativeModel({
    model,
    systemInstruction: SYSTEM_PROMPT,
  });
  const result = await genModel.generateContent({
    contents: [{ role: 'user', parts: [{ text: userMessage }] }],
    generationConfig: { temperature: 0.1, maxOutputTokens: 16000 },
  });
  return result.response.text();
}

async function analyzeChunk(config, text, chunkIndex, totalChunks) {
  const prefix = totalChunks > 1
    ? `هذا الجزء ${chunkIndex + 1} من ${totalChunks} من الرسائل. حلّل واستخرج البيانات:\n\n`
    : 'حلّل واستخرج البيانات من الرسائل التالية:\n\n';

  const userMessage = prefix + text;

  if (config.provider === 'openai') {
    return callOpenAI(config.apiKey, config.selected_model, userMessage);
  }
  if (config.provider === 'gemini') {
    return callGemini(config.apiKey, config.selected_model, userMessage);
  }
  if (config.provider === 'openrouter') {
    return callOpenRouter(config.apiKey, config.selected_model, userMessage);
  }
  throw new Error('مزوّد غير مدعوم: ' + config.provider);
}

async function analyzeMessages(text, onProgress) {
  const config = await getPreferredProvider();
  if (!config || !config.apiKey || !config.selected_model) {
    throw new Error('لم يتم إعداد مزوّد الذكاء الاصطناعي. اذهب للإعدادات أولاً.');
  }

  const chunks = splitIntoChunks(text);
  const tables = [];

  for (let i = 0; i < chunks.length; i++) {
    if (onProgress) onProgress({ chunk: i + 1, total: chunks.length, status: 'processing' });

    const result = await analyzeChunk(config, chunks[i], i, chunks.length);
    tables.push(result);

    if (i < chunks.length - 1) {
      await new Promise(r => setTimeout(r, 1000));
    }
  }

  const merged = mergeMarkdownTables(tables);

  try {
    const db = getDb();
    await db.query(`
      INSERT INTO message_analyses (input_text, output_table, provider, model, chunks_count)
      VALUES ($1, $2, $3, $4, $5)
    `, [
      text.substring(0, 5000),
      merged,
      config.provider,
      config.selected_model,
      chunks.length
    ]);
  } catch {}

  return {
    table: merged,
    provider: config.provider,
    model: config.selected_model,
    chunks: chunks.length,
  };
}

async function saveApiKey(provider, apiKey) {
  const db = getDb();
  const encrypted = encrypt(apiKey);
  const existing = (await db.query('SELECT id FROM ai_config WHERE provider = $1', [provider])).rows[0];
  if (existing) {
    await db.query('UPDATE ai_config SET api_key_encrypted = $1, updated_at = CURRENT_TIMESTAMP WHERE provider = $2', [encrypted, provider]);
  } else {
    await db.query('INSERT INTO ai_config (provider, api_key_encrypted) VALUES ($1, $2)', [provider, encrypted]);
  }
}

async function saveSelectedModel(provider, model) {
  const db = getDb();
  await db.query('UPDATE ai_config SET selected_model = $1, updated_at = CURRENT_TIMESTAMP WHERE provider = $2', [model, provider]);
}

async function saveModelsCache(provider, models) {
  const db = getDb();
  await db.query('UPDATE ai_config SET models_cache = $1, updated_at = CURRENT_TIMESTAMP WHERE provider = $2', [JSON.stringify(models), provider]);
}

async function getAIStatus() {
  const db = getDb();
  const configs = (await db.query('SELECT provider, selected_model, models_cache, updated_at FROM ai_config')).rows;
  const result = {};
  for (const c of configs) {
    result[c.provider] = {
      configured: true,
      selectedModel: c.selected_model,
      models: c.models_cache ? JSON.parse(c.models_cache) : [],
      updatedAt: c.updated_at,
    };
  }
  return result;
}

module.exports = {
  fetchModels,
  analyzeMessages,
  saveApiKey,
  saveSelectedModel,
  saveModelsCache,
  getAIStatus,
  getProviderConfig,
  // يستخدمه وكيل واتساب لإعادة استعمال نفس مزوّد الذكاء المضبوط في الإعدادات
  getPreferredProvider,
  // OpenRouter: مزوّد احتياطي بنماذج مجانية داعمة للأدوات
  fetchOpenRouterModels,
  openRouterClient,
  get OPENROUTER_BASE_URL() { return openRouterBaseUrl(); },
};
