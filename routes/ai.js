const express = require('express');
const { getDb } = require('../db/database');
const { requireAuth } = require('../middleware/auth');
const aiService = require('../services/aiService');

function createRouter(io) {
  const router = express.Router();

  async function createJob(userId) {
    const db = getDb();
    const result = await db.query('INSERT INTO analysis_jobs (user_id, status) VALUES ($1, $2)', [userId, 'pending']);
    return result && result.lastInsertRowid ? result.lastInsertRowid : null;
  }

  async function updateJobProgress(jobId, current, total) {
    const db = getDb();
    await db.query('UPDATE analysis_jobs SET progress_current = $1, progress_total = $2, updated_at = CURRENT_TIMESTAMP WHERE id = $3', [current, total, jobId]);
  }

  async function completeJob(jobId, result) {
    const db = getDb();
    await db.query(
      `UPDATE analysis_jobs SET status = 'completed', progress_current = $1, progress_total = $2,
       output_table = $3, provider = $4, model = $5, chunks_count = $6, updated_at = CURRENT_TIMESTAMP WHERE id = $7`,
      [result.chunks, result.chunks, result.table, result.provider, result.model, result.chunks, jobId]
    );
  }

  async function failJob(jobId, errMessage) {
    const db = getDb();
    await db.query("UPDATE analysis_jobs SET status = 'failed', error_message = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2", [errMessage || 'خطأ غير معروف', jobId]);
  }

  async function setJobRunning(jobId, total) {
    const db = getDb();
    await db.query("UPDATE analysis_jobs SET status = 'running', progress_total = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2", [total, jobId]);
  }

  async function getJob(jobId, userId) {
    const db = getDb();
    const row = (await db.query('SELECT * FROM analysis_jobs WHERE id = $1 AND user_id = $2', [jobId, userId])).rows[0];
    return row;
  }

  router.get('/status', requireAuth, async (req, res) => {
    try {
      const status = await aiService.getAIStatus();
      res.json(status);
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.post('/save-key', requireAuth, async (req, res) => {
    try {
      const { provider, apiKey } = req.body;
      if (!provider || !apiKey) {
        return res.json({ success: false, message: 'المزوّد ومفتاح API مطلوبان' });
      }
      const PROVIDER_LABELS = { openai: 'OpenAI', gemini: 'Gemini', openrouter: 'OpenRouter' };
      if (!PROVIDER_LABELS[provider]) {
        return res.json({ success: false, message: 'مزوّد غير مدعوم' });
      }

      const trimmedKey = apiKey.trim();
      let models = [];
      try {
        models = await aiService.fetchModels(provider, trimmedKey);
      } catch (fetchErr) {
        return res.json({
          success: false,
          message: 'فشل الاتصال بخدمة ' + PROVIDER_LABELS[provider] + ': ' + (fetchErr.message || 'تحقق من المفتاح والشبكة'),
        });
      }
      if (!models || models.length === 0) {
        const emptyMsg = provider === 'openrouter'
          ? 'لم يُعثر على نموذج مجاني يدعم استدعاء الأدوات. القائمة لدى OpenRouter تتغيّر باستمرار — جرّب لاحقاً دون إرخاء شرط الأدوات.'
          : 'لم يتم جلب أي موديلات. تحقق من صحة المفتاح وأن الحساب يسمح باستخدام واجهة الـ API.';
        return res.json({
          success: false,
          message: emptyMsg,
        });
      }

      await aiService.saveApiKey(provider, trimmedKey);
      await aiService.saveModelsCache(provider, models);

      let defaultModel = models[0];
      if (provider === 'openai') {
        const preferred = models.find((m) => m.includes('gpt-4o-mini')) || models.find((m) => m.includes('gpt-4o'));
        if (preferred) defaultModel = preferred;
      }
      if (provider === 'gemini') {
        const preferred = models.find((m) => m.includes('gemini-1.5-flash')) || models.find((m) => m.includes('gemini-1.5-pro'));
        if (preferred) defaultModel = preferred;
      }
      if (provider === 'openrouter') {
        // القائمة مرتّبة أصلاً بسعة السياق تنازلياً، والأكبر أقدر على حمل
        // تعليمات الوكيل ونتائج الاستعلامات معاً
        defaultModel = models[0];
      }
      await aiService.saveSelectedModel(provider, defaultModel);

      res.json({
        success: true,
        message: `تم حفظ المفتاح وربطه بأداة ترتيب الرسائل. الموديل: ${defaultModel}`,
        models,
        selectedModel: defaultModel,
      });
    } catch (err) {
      res.json({ success: false, message: 'فشل حفظ المفتاح: ' + err.message });
    }
  });

  router.post('/select-model', requireAuth, async (req, res) => {
    try {
      const { provider, model } = req.body;
      if (!provider || !model) {
        return res.json({ success: false, message: 'المزوّد والموديل مطلوبان' });
      }
      await aiService.saveSelectedModel(provider, model);
      res.json({ success: true, message: `تم اختيار الموديل: ${model}` });
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.post('/refresh-models', requireAuth, async (req, res) => {
    try {
      const { provider } = req.body;
      const config = await aiService.getProviderConfig(provider);
      if (!config || !config.apiKey) {
        return res.json({ success: false, message: 'مفتاح API غير موجود لهذا المزوّد' });
      }
      const models = await aiService.fetchModels(provider, config.apiKey);
      await aiService.saveModelsCache(provider, models);
      res.json({ success: true, models });
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.post('/analyze', requireAuth, async (req, res) => {
    try {
      const { text } = req.body;
      if (!text || text.trim().length < 10) {
        return res.json({ success: false, message: 'النص قصير جداً للتحليل' });
      }
      const userId = req.session.userId;
      const jobId = await createJob(userId);
      if (!jobId) {
        return res.json({ success: false, message: 'فشل إنشاء المهمة. جرّب مرة أخرى.' });
      }
      const totalChunks = Math.ceil(text.length / 12000) || 1;
      await setJobRunning(jobId, totalChunks);

      res.json({ success: true, jobId, message: 'تم استلام المهمة، جاري التحليل في الخلفية' });

      setImmediate(async () => {
        const emit = (event, data) => {
          if (io) io.to(`analysis:${jobId}`).emit(event, { jobId, ...data });
        };
        try {
          const result = await aiService.analyzeMessages(text, async (progress) => {
            await updateJobProgress(jobId, progress.chunk, progress.total);
            emit('analysis:progress', { chunk: progress.chunk, total: progress.total, status: progress.status });
          });
          await completeJob(jobId, result);
          emit('analysis:done', {
            status: 'completed',
            table: result.table,
            provider: result.provider,
            model: result.model,
            chunks: result.chunks,
          });
        } catch (err) {
          await failJob(jobId, err.message);
          emit('analysis:error', { status: 'failed', message: err.message });
        }
      });
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.get('/analysis/:jobId', requireAuth, async (req, res) => {
    try {
      const userId = req.session.userId;
      const job = await getJob(Number(req.params.jobId), userId);
      if (!job) {
        return res.json({ success: false, message: 'المهمة غير موجودة أو لا تخصك' });
      }
      const payload = {
        success: true,
        jobId: job.id,
        status: job.status,
        progressCurrent: job.progress_current,
        progressTotal: job.progress_total,
      };
      if (job.status === 'completed') {
        payload.table = job.output_table;
        payload.provider = job.provider;
        payload.model = job.model;
        payload.chunks = job.chunks_count;
      }
      if (job.status === 'failed') {
        payload.message = job.error_message;
      }
      res.json(payload);
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.get('/jobs', requireAuth, async (req, res) => {
    try {
      const db = getDb();
      const rows = (await db.query(
        'SELECT id, status, progress_current, progress_total, provider, model, chunks_count, exported_to_sheets, created_at, updated_at FROM analysis_jobs WHERE user_id = $1 ORDER BY created_at DESC LIMIT 30',
        [req.session.userId]
      )).rows;
      res.json({ success: true, jobs: rows });
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  router.get('/history', requireAuth, async (req, res) => {
    try {
      const db = getDb();
      const rows = (await db.query('SELECT id, provider, model, chunks_count, status, created_at FROM message_analyses ORDER BY created_at DESC LIMIT 20')).rows;
      res.json({ success: true, history: rows });
    } catch (err) {
      res.json({ success: false, message: err.message });
    }
  });

  return router;
}

module.exports = createRouter;
