const express = require('express');
const router = express.Router();
const { requireAuth } = require('../middleware/auth');
const { getDb } = require('../db/database');
const { google } = require('googleapis');

function redirectUri() {
  return process.env.GOOGLE_REDIRECT_URI
    || `${process.env.PUBLIC_BASE_URL || process.env.BASE_URL || 'http://localhost:3000'}/sheets/callback`;
}

function getOAuth2Client(credentials) {
  const clientId = credentials?.client_id || process.env.GOOGLE_CLIENT_ID;
  const clientSecret = credentials?.client_secret || process.env.GOOGLE_CLIENT_SECRET;
  if (!clientId || !clientSecret) return null;
  return new google.auth.OAuth2(clientId, clientSecret, redirectUri());
}

async function ensureConfigRow(db) {
  const row = (await db.query('SELECT id FROM google_sheets_config WHERE id = 1')).rows[0];
  if (!row) {
    await db.query(`
      INSERT INTO google_sheets_config (id, spreadsheet_id, credentials, token, sync_enabled, updated_at)
      VALUES (1, NULL, NULL, NULL, 0, CURRENT_TIMESTAMP)
    `);
  }
}

router.get('/', requireAuth, (req, res) => {
  res.redirect('/settings');
});

/** حالة الربط: هل الاعتماد من env، متصل، ومعرّف الجدول */
router.get('/status', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    await ensureConfigRow(db);
    const config = (await db.query('SELECT spreadsheet_id, token, credentials FROM google_sheets_config WHERE id = 1')).rows[0];
    const hasEnv = !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
    const creds = config?.credentials ? JSON.parse(config.credentials) : null;
    const canAuth = hasEnv || (creds?.client_id && creds?.client_secret);
    const connected = !!(config?.token && config.token !== 'null');
    res.json({
      success: true,
      hasEnvCredentials: !!hasEnv,
      canAuth,
      connected,
      spreadsheet_id: config?.spreadsheet_id || null
    });
  } catch (e) {
    res.json({ success: false, message: e.message });
  }
});

router.post('/configure', requireAuth, async (req, res) => {
  try {
    const { spreadsheet_id, client_id, client_secret } = req.body;
    const db = getDb();
    await ensureConfigRow(db);
    const credentials = (client_id && client_secret) ? JSON.stringify({ client_id, client_secret }) : null;
    await db.query(`
      UPDATE google_sheets_config SET spreadsheet_id = $1, credentials = $2, updated_at = CURRENT_TIMESTAMP WHERE id = 1
    `, [spreadsheet_id || null, credentials]);
    res.json({ success: true, message: 'تم حفظ الإعدادات بنجاح' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ أثناء حفظ الإعدادات' });
  }
});

/** حفظ معرّف جدول البيانات فقط (بعد تسجيل الدخول بـ Google) */
router.post('/save-spreadsheet', requireAuth, async (req, res) => {
  try {
    const { spreadsheet_id } = req.body;
    const db = getDb();
    await ensureConfigRow(db);
    await db.query('UPDATE google_sheets_config SET spreadsheet_id = $1, updated_at = CURRENT_TIMESTAMP WHERE id = 1', [spreadsheet_id || null]);
    res.json({ success: true, message: 'تم حفظ معرّف الجدول' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ' });
  }
});

router.post('/toggle-sync', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const config = (await db.query('SELECT * FROM google_sheets_config WHERE id = 1')).rows[0];
    if (!config) {
      return res.json({ success: false, message: 'يرجى إعداد Google Sheets أولاً' });
    }
    const newState = config.sync_enabled ? 0 : 1;
    await db.query('UPDATE google_sheets_config SET sync_enabled = $1, updated_at = CURRENT_TIMESTAMP WHERE id = 1', [newState]);
    res.json({ success: true, enabled: !!newState, message: newState ? 'تم تفعيل المزامنة' : 'تم إيقاف المزامنة' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ' });
  }
});

/** قطع الاتصال بحساب Google (لمن يريد إعادة تسجيل الدخول) */
router.post('/disconnect', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    await ensureConfigRow(db);
    await db.query('UPDATE google_sheets_config SET token = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = 1');
    res.json({ success: true, message: 'تم قطع الاتصال. يمكنك تسجيل الدخول مرة أخرى.' });
  } catch (error) {
    res.json({ success: false, message: 'حدث خطأ' });
  }
});

/** تسجيل الدخول باستخدام Google — يستخدم env أو credentials المحفوظة */
router.get('/auth', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    await ensureConfigRow(db);
    const config = (await db.query('SELECT credentials FROM google_sheets_config WHERE id = 1')).rows[0];
    const credentials = config?.credentials ? JSON.parse(config.credentials) : null;
    const oauth2Client = getOAuth2Client(credentials);
    if (!oauth2Client) {
      return res.redirect('/settings?sheets=no_credentials');
    }
    const authUrl = oauth2Client.generateAuthUrl({
      access_type: 'offline',
      prompt: 'consent',
      scope: ['https://www.googleapis.com/auth/spreadsheets', 'https://www.googleapis.com/auth/drive.readonly']
    });
    res.redirect(authUrl);
  } catch (error) {
    res.redirect('/settings?sheets=auth_failed');
  }
});

router.get('/callback', requireAuth, async (req, res) => {
  try {
    const { code } = req.query;
    if (!code) return res.redirect('/settings?sheets=callback_failed');
    const db = getDb();
    await ensureConfigRow(db);
    const config = (await db.query('SELECT credentials, spreadsheet_id FROM google_sheets_config WHERE id = 1')).rows[0];
    const credentials = config?.credentials ? JSON.parse(config.credentials) : null;
    const oauth2Client = getOAuth2Client(credentials);
    if (!oauth2Client) return res.redirect('/settings?sheets=no_credentials');
    const { tokens } = await oauth2Client.getToken(code);
    await db.query(`
      UPDATE google_sheets_config SET token = $1, updated_at = CURRENT_TIMESTAMP WHERE id = 1
    `, [JSON.stringify(tokens)]);
    res.redirect('/settings?sheets=connected');
  } catch (error) {
    res.redirect('/settings?sheets=callback_failed');
  }
});

/** قائمة أوراق الجدول (للاختيار عند الإلحاق) */
router.get('/sheets-list', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const config = (await db.query('SELECT spreadsheet_id, token, credentials FROM google_sheets_config WHERE id = 1')).rows[0];
    if (!config?.spreadsheet_id || !config?.token) {
      return res.json({ success: false, message: 'لم يتم ربط جدول أو تسجيل الدخول' });
    }
    const creds = config.credentials ? JSON.parse(config.credentials) : null;
    const oauth2Client = getOAuth2Client(creds);
    if (!oauth2Client) return res.json({ success: false, message: 'بيانات الاعتماد غير متوفرة' });
    oauth2Client.setCredentials(JSON.parse(config.token));
    const sheets = google.sheets({ version: 'v4', auth: oauth2Client });
    const meta = await sheets.spreadsheets.get({ spreadsheetId: config.spreadsheet_id });
    const titles = (meta.data.sheets || []).map(s => ({ id: s.properties.sheetId, title: s.properties.title }));
    res.json({ success: true, sheets: titles });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل جلب قائمة الأوراق' });
  }
});

/** تحويل جدول markdown إلى مصفوفة صفوف */
function markdownTableToRows(tableMarkdown) {
  if (!tableMarkdown || typeof tableMarkdown !== 'string') return [];
  const lines = tableMarkdown.trim().split('\n').filter(l => l.trim().startsWith('|'));
  if (lines.length < 2) return [];
  const header = lines[0].split('|').map(c => c.trim()).filter(Boolean);
  const separator = lines[1];
  const dataLines = lines.slice(2);
  const rows = [header];
  for (const line of dataLines) {
    const cells = line.split('|');
    cells.shift();
    if (cells[cells.length - 1]?.trim() === '') cells.pop();
    rows.push(cells.map(c => c.trim()));
  }
  return rows;
}

/** تصدير النتائج إلى Google Sheet — ورقة جديدة أو إلحاق بورقة موجودة */
router.post('/export', requireAuth, async (req, res) => {
  try {
    const { tableMarkdown, rows: rawRows, sheetName, mode, targetSheetTitle, jobId } = req.body;
    const db = getDb();
    const config = (await db.query('SELECT spreadsheet_id, token, credentials FROM google_sheets_config WHERE id = 1')).rows[0];
    if (!config?.spreadsheet_id || !config?.token) {
      return res.json({ success: false, message: 'لم يتم ربط جدول أو تسجيل الدخول. اربط من الإعدادات → Google Sheets.' });
    }
    const dataRows = Array.isArray(rawRows) && rawRows.length
      ? rawRows
      : markdownTableToRows(tableMarkdown);
    if (!dataRows.length) {
      return res.json({ success: false, message: 'لا توجد بيانات للتصدير' });
    }
    const credentials = config.credentials ? JSON.parse(config.credentials) : null;
    const oauth2Client = getOAuth2Client(credentials);
    if (!oauth2Client) return res.json({ success: false, message: 'بيانات الاعتماد غير متوفرة' });
    oauth2Client.setCredentials(JSON.parse(config.token));
    const sheets = google.sheets({ version: 'v4', auth: oauth2Client });
    const spreadsheetId = config.spreadsheet_id;
    let sheetTitle = (sheetName && String(sheetName).trim()) || `تحليل رسائل ${new Date().toLocaleString('ar-SA', { dateStyle: 'short', timeStyle: 'short' })}`;

    if (mode === 'appendToSheet' && targetSheetTitle) {
      const range = `'${targetSheetTitle}'!A1`;
      await sheets.spreadsheets.values.append({
        spreadsheetId,
        range,
        valueInputOption: 'USER_ENTERED',
        insertDataOption: 'INSERT_ROWS',
        requestBody: { values: dataRows }
      });
      if (jobId != null && Number.isInteger(Number(jobId))) {
        const db2 = getDb();
        await db2.query('UPDATE analysis_jobs SET exported_to_sheets = 1, updated_at = CURRENT_TIMESTAMP WHERE id = $1 AND user_id = $2', [Number(jobId), req.session.userId]);
      }
      return res.json({ success: true, message: `تم إلحاق ${dataRows.length} صف بورقة "${targetSheetTitle}"` });
    }

    const createRes = await sheets.spreadsheets.batchUpdate({
      spreadsheetId,
      requestBody: {
        requests: [{
          addSheet: {
            properties: { title: sheetTitle }
          }
        }]
      }
    });
    const newSheetId = createRes.data.replies[0].addSheet.properties.sheetId;
    await sheets.spreadsheets.values.update({
      spreadsheetId,
      range: `${sheetTitle}!A1`,
      valueInputOption: 'USER_ENTERED',
      requestBody: { values: dataRows }
    });
    if (jobId != null && Number.isInteger(Number(jobId))) {
      const db = getDb();
      await db.query('UPDATE analysis_jobs SET exported_to_sheets = 1, updated_at = CURRENT_TIMESTAMP WHERE id = $1 AND user_id = $2', [Number(jobId), req.session.userId]);
    }
    res.json({ success: true, message: `تم إنشاء الورقة "${sheetTitle}" وتصدير ${dataRows.length} صف` });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل التصدير' });
  }
});

module.exports = router;
