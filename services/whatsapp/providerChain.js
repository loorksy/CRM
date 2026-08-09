/**
 * سلسلة المزوّدات: أي مزوّد يجيب، وماذا نفعل حين يسقط.
 *
 * الدافع من الواقع: نفاد رصيد OpenAI (429) أوقف الوكيل كلياً رغم أن
 * الرد كان ممكناً بمزوّد آخر. مزوّد واحد = نقطة فشل واحدة.
 *
 * الترتيب: المزوّد المفضّل أولاً، ثم البقية حسب أحدث تحديث. عند فشل
 * قابل لإعادة المحاولة (رصيد، حدّ معدّل، عطل مؤقت) ننتقل للتالي. عند
 * فشل غير قابل (مفتاح خاطئ، نموذج غير موجود) ننتقل أيضاً لكن نسجّله
 * كخطأ إعداد يحتاج تدخّل المسؤول.
 */

const { getDb } = require('../../db/database');
const { decrypt } = require('../../utils/crypto');

/** المزوّد الذي يُجرَّب أولاً. الافتراضي: الأحدث تحديثاً في الإعدادات. */
const PREFERRED = (process.env.WHATSAPP_AI_PREFERRED || '').trim();

/** ترتيب احتياطي صريح، مفصول بفواصل. مثال: openai,openrouter,gemini */
const ORDER = (process.env.WHATSAPP_AI_ORDER || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

/**
 * هل يستحق هذا الخطأ الانتقال إلى المزوّد التالي؟
 *
 * نعم لكل ما هو عابر أو متعلق بالحصة: 429، نفاد رصيد، 5xx، انقطاع شبكة.
 * ونعم أيضاً لأخطاء الإعداد (مفتاح/نموذج) — لأن سقوط الوكيل كلياً بسبب
 * إعداد خاطئ في مزوّد واحد نتيجة أسوأ من تجربة مزوّد سليم.
 */
function shouldFallover(error) {
  const status = error?.status || error?.response?.status || 0;
  const message = String(error?.message || '').toLowerCase();

  if ([401, 402, 403, 404, 408, 409, 429].includes(status)) return true;
  if (status >= 500) return true;

  return /quota|credit|billing|rate limit|insufficient|overloaded|unavailable|timeout|econnreset|etimedout|enotfound|fetch failed/i
    .test(message);
}

/** تصنيف السبب لسجلّ مفهوم بدل رسالة خام */
function classify(error) {
  const status = error?.status || error?.response?.status || 0;
  const message = String(error?.message || '').toLowerCase();

  if (status === 429 || /rate limit/.test(message)) return 'حدّ المعدّل';
  if (status === 402 || /credit|billing|insufficient|quota/.test(message)) return 'نفاد الرصيد';
  if (status === 401 || status === 403) return 'مفتاح غير صالح';
  if (status === 404) return 'نموذج غير موجود';
  if (status >= 500) return 'عطل لدى المزوّد';
  if (/timeout|econnreset|etimedout|enotfound|fetch failed/.test(message)) return 'تعذّر الاتصال';
  return 'خطأ غير متوقع';
}

/**
 * كل المزوّدات المهيّأة (مفتاح + نموذج مختار)، مرتّبة حسب الأولوية.
 * @returns {Promise<Array<{provider, apiKey, selected_model}>>}
 */
async function getProviderChain() {
  const db = getDb();
  const rows = (await db.query(
    'SELECT * FROM ai_config WHERE selected_model IS NOT NULL ORDER BY updated_at DESC'
  )).rows;

  const usable = [];
  for (const row of rows) {
    const apiKey = decrypt(row.api_key_encrypted);
    if (apiKey && row.selected_model) {
      usable.push({ provider: row.provider, apiKey, selected_model: row.selected_model });
    }
  }

  if (!usable.length) return [];

  // ترتيب صريح من البيئة إن وُجد
  if (ORDER.length) {
    const ranked = [];
    for (const name of ORDER) {
      const found = usable.find((u) => u.provider === name);
      if (found && !ranked.includes(found)) ranked.push(found);
    }
    for (const u of usable) if (!ranked.includes(u)) ranked.push(u);
    return ranked;
  }

  // وإلا: المفضّل أولاً ثم الباقي بترتيب التحديث
  if (PREFERRED) {
    const idx = usable.findIndex((u) => u.provider === PREFERRED);
    if (idx > 0) {
      const [pref] = usable.splice(idx, 1);
      usable.unshift(pref);
    }
  }

  return usable;
}

/**
 * تشغيل دالة على أول مزوّد ينجح.
 *
 * @param {(config) => Promise<any>} run تُستدعى بإعدادات كل مزوّد بالدور
 * @returns {Promise<{ok, result?, provider?, attempts, lastError?}>}
 */
async function runWithFallback(run) {
  const chain = await getProviderChain();
  const attempts = [];

  if (!chain.length) {
    return { ok: false, attempts, lastError: 'لم يُضبط أي مزوّد ذكاء اصطناعي' };
  }

  let lastError = null;

  for (const config of chain) {
    try {
      const result = await run(config);
      if (attempts.length) {
        console.log(
          `[whatsapp] أجاب ${config.provider} بعد فشل: ${attempts.map((a) => `${a.provider} (${a.reason})`).join('، ')}`
        );
      }
      return { ok: true, result, provider: config.provider, attempts };
    } catch (e) {
      const reason = classify(e);
      attempts.push({ provider: config.provider, reason, message: e.message });
      lastError = e;

      console.warn(`[whatsapp] ${config.provider} فشل (${reason}): ${e.message}`);

      if (!shouldFallover(e)) {
        // خطأ في منطقنا نحن لا في المزوّد — الانتقال لن يساعد
        return { ok: false, attempts, lastError: e.message, fatal: true };
      }
    }
  }

  return { ok: false, attempts, lastError: lastError?.message || 'كل المزوّدات فشلت' };
}

/** رسالة مفهومة للمسؤول تلخّص سبب سقوط كل المزوّدات */
function summarizeFailure(attempts) {
  if (!attempts?.length) return 'لم يُضبط أي مزوّد ذكاء اصطناعي.';
  return attempts.map((a) => `${a.provider}: ${a.reason}`).join(' — ');
}

module.exports = { getProviderChain, runWithFallback, shouldFallover, classify, summarizeFailure };
