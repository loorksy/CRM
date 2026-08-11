/**
 * عمليات إضافة/خصم على جدول Google بنفس منطق تطبيق Apps Script
 * (فردي + جماعي) — دون استبدال مسار التسجيل المحاسبي في النظام.
 */

const { google } = require('googleapis');
const { withSheetsRetry, fetchSheetValuesBatched, escapeSheetTitleForRange } = require('./googleSheetsReadHelpers');
const { normalizeUserId, columnLetterToIndex, parseLocaleDecimal } = require('./payrollSearchService');
const { isHeaderRowUserInfo } = require('./payrollAuditEngine');

function redirectUri() {
  return (
    process.env.GOOGLE_REDIRECT_URI ||
    `${process.env.PUBLIC_BASE_URL || process.env.BASE_URL || 'http://localhost:3000'}/sheets/callback`
  );
}

function getOAuth2Client(credentials) {
  const clientId = credentials?.client_id || process.env.GOOGLE_CLIENT_ID;
  const clientSecret = credentials?.client_secret || process.env.GOOGLE_CLIENT_SECRET;
  if (!clientId || !clientSecret) return null;
  return new google.auth.OAuth2(clientId, clientSecret, redirectUri());
}

function columnIndexToLetter(idx) {
  let s = '';
  let n = idx + 1;
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s || 'A';
}

function round2(num) {
  return Math.round((parseFloat(num) || 0) * 100) / 100;
}

function parseSalaryCellNumber(v) {
  const n = parseLocaleDecimal(v);
  return Number.isFinite(n) ? n : 0;
}

function emptyResult(userId, status, amountNum = 0, extra = {}) {
  return {
    id: userId,
    status,
    amount: amountNum,
    added: 0,
    deducted: 0,
    debt: 0,
    notFoundAmt: 0,
    zeroSalAmt: 0,
    ...extra,
  };
}

/**
 * منطق الصف الواحد — مطابق لتطبيق الرواتب (Apps Script).
 * @returns {{ result: object, newSalary: number|null, statusText: string|null }}
 */
function computeSheetOp({ currentSalary, type, amountRaw }) {
  const typeStr = String(type || '').trim();
  const amountStr = amountRaw == null ? '' : String(amountRaw).trim();
  const isFullDeduction =
    typeStr.includes('خصم كامل') || amountStr.includes('خصم كامل');

  let amountNum = 0;
  if (!isFullDeduction) {
    amountNum = round2(amountStr.replace(',', '.'));
  }

  const cur = round2(currentSalary);
  let status = 'تم التحديث';
  let displayedAmount = amountNum;
  let added = 0;
  let deducted = 0;
  let debt = 0;
  let zeroSalAmt = 0;
  let newSalary = cur;
  let statusText = '';

  if (isFullDeduction || typeStr === 'خصم') {
    if (isFullDeduction) {
      deducted = cur > 0 ? cur : 0;
      status = 'تم خصم الدين';
      displayedAmount = deducted;
      newSalary = 0;
      statusText = 'تم خصم الدين';
    } else {
      if (cur <= 0) {
        debt = 0;
        zeroSalAmt = amountNum;
        status = 'سحب ادارة';
        displayedAmount = amountNum;
        newSalary = 0;
      } else if (cur < amountNum) {
        debt = round2(amountNum - cur);
        if (debt < 1) {
          debt = 0;
          deducted = cur;
          status = 'تم الخصم بالكامل';
          displayedAmount = amountNum;
        } else {
          deducted = cur;
          status = 'باقي عليه';
          displayedAmount = debt;
        }
        newSalary = 0;
      } else {
        deducted = amountNum;
        newSalary = round2(cur - amountNum);
        status = 'تم الخصم بالكامل';
      }
      statusText = `تم خصم ${amountNum}`;
    }
  } else {
    // إضافة (أو أي نوع غير الخصم)
    added = amountNum;
    newSalary = round2(cur + amountNum);
    status = 'تمت الإضافة';
    statusText = `تم اضافة ${amountNum}`;
  }

  return {
    result: {
      id: null,
      status,
      amount: displayedAmount,
      added,
      deducted,
      debt,
      notFoundAmt: 0,
      zeroSalAmt,
    },
    newSalary,
    statusText,
    amountNum,
    isFullDeduction,
  };
}

async function getAuthedSheets(db) {
  const config = (await db.query('SELECT token, credentials FROM google_sheets_config WHERE id = 1')).rows[0];
  if (!config?.token) {
    return { error: 'اربط Google من الإعدادات → Google Sheets' };
  }
  const credentials = config.credentials ? JSON.parse(config.credentials) : null;
  const oauth2Client = getOAuth2Client(credentials);
  if (!oauth2Client) return { error: 'بيانات اعتماد Google غير مكتملة' };
  oauth2Client.setCredentials(typeof config.token === 'string' ? JSON.parse(config.token) : config.token);
  return { sheets: google.sheets({ version: 'v4', auth: oauth2Client }) };
}

async function loadSheetContext(db, { spreadsheetId, sheetName, idCol, salaryCol, statusCol }) {
  const ssId = String(spreadsheetId || '').trim();
  const title = String(sheetName || '').trim();
  if (!ssId) return { error: 'اختر ملف جدول البيانات' };
  if (!title) return { error: 'اختر الورقة المطلوبة' };

  const auth = await getAuthedSheets(db);
  if (auth.error) return { error: auth.error };
  const { sheets } = auth;

  const uidCol = columnLetterToIndex(idCol || 'C') ?? 2;
  const salCol = columnLetterToIndex(salaryCol || 'L') ?? 11;
  const stCol = columnLetterToIndex(statusCol || 'O') ?? 14;

  const rows = await fetchSheetValuesBatched(sheets, ssId, title, 1);
  const dataStart = rows.length > 0 && isHeaderRowUserInfo(rows[0], uidCol) ? 1 : 0;

  return { sheets, ssId, title, rows, dataStart, uidCol, salCol, stCol };
}

async function writeCell(sheets, ssId, title, colIdx, row1, value) {
  const letter = columnIndexToLetter(colIdx);
  const esc = escapeSheetTitleForRange(title);
  const range = `'${esc}'!${letter}${row1}`;
  await withSheetsRetry(() =>
    sheets.spreadsheets.values.update({
      spreadsheetId: ssId,
      range,
      valueInputOption: 'USER_ENTERED',
      requestBody: { values: [[value]] },
    })
  );
  return range;
}

/**
 * عملية فردية على الشيت.
 */
async function processSheetOperation(db, data) {
  const userId = String(data.userId || '').trim();
  const type = String(data.type || '').trim();
  const amount = data.amount;

  if (!userId) return emptyResult('', 'خطأ: رقم المستخدم مطلوب');
  if (!type) return emptyResult(userId, 'خطأ: نوع العملية مطلوب');

  const isFull = type.includes('خصم كامل') || String(amount || '').includes('خصم كامل');
  if (!isFull && !(String(amount || '').trim())) {
    return emptyResult(userId, 'خطأ: المبلغ مطلوب');
  }

  try {
    const ctx = await loadSheetContext(db, data);
    if (ctx.error) return emptyResult(userId, `خطأ: ${ctx.error}`);

    const mid = normalizeUserId(userId) || userId;
    let found = -1;
    for (let i = ctx.dataStart; i < ctx.rows.length; i++) {
      if (normalizeUserId(ctx.rows[i][ctx.uidCol]) === mid || String(ctx.rows[i][ctx.uidCol] || '').trim() === userId) {
        found = i;
        break;
      }
    }

    if (found < 0) {
      const amountNum = isFull ? 0 : round2(String(amount).replace(',', '.'));
      return emptyResult(userId, 'غير موجود', amountNum, { notFoundAmt: amountNum });
    }

    const row = ctx.rows[found];
    while (row.length <= Math.max(ctx.salCol, ctx.stCol)) row.push('');
    const currentSalary = parseSalaryCellNumber(row[ctx.salCol]);
    const computed = computeSheetOp({ currentSalary, type, amountRaw: amount });
    computed.result.id = userId;

    const cellDisplay = round2(computed.newSalary).toFixed(2);
    await writeCell(ctx.sheets, ctx.ssId, ctx.title, ctx.salCol, found + 1, cellDisplay);
    await writeCell(ctx.sheets, ctx.ssId, ctx.title, ctx.stCol, found + 1, computed.statusText);
    row[ctx.salCol] = computed.newSalary;
    row[ctx.stCol] = computed.statusText;

    return computed.result;
  } catch (e) {
    return emptyResult(userId, `خطأ: ${e.message || e}`, 0);
  }
}

/**
 * معالجة جماعية — أسطر مفصولة بـ Tab (أو مسافات متعددة): ID | النوع | المبلغ
 */
async function processSheetBulk(db, bulkData, config) {
  const lines = String(bulkData || '').split('\n');
  const results = [];

  try {
    const ctx = await loadSheetContext(db, config);
    if (ctx.error) {
      return [emptyResult('تنبيه', `حدث خطأ: ${ctx.error}`)];
    }

    // فهرس سريع: userId → row index
    const index = new Map();
    for (let i = ctx.dataStart; i < ctx.rows.length; i++) {
      const key = normalizeUserId(ctx.rows[i][ctx.uidCol]) || String(ctx.rows[i][ctx.uidCol] || '').trim();
      if (key && !index.has(key)) index.set(key, i);
    }

    for (const line of lines) {
      if (!line.trim()) continue;
      let parts = line.split('\t');
      if (parts.length < 2) {
        parts = line.trim().split(/\s{2,}|\s+/);
      }
      if (parts.length < 2) continue;

      const userId = parts[0].trim();
      const type = parts[1].trim();
      let isFullDeduction = type.includes('خصم كامل');
      let amountNum = 0;
      let amountRaw = '';

      if (parts.length >= 3) {
        amountRaw = parts[2].trim();
        if (amountRaw.includes('خصم كامل')) isFullDeduction = true;
        else if (!isFullDeduction) amountNum = round2(amountRaw.replace(',', '.'));
      }

      if (!isFullDeduction && parts.length < 3) continue;

      const mid = normalizeUserId(userId) || userId;
      const found = index.has(mid) ? index.get(mid) : -1;

      if (found < 0) {
        results.push(
          emptyResult(userId, 'غير موجود', isFullDeduction ? 0 : amountNum, {
            notFoundAmt: isFullDeduction ? 0 : amountNum,
          })
        );
        continue;
      }

      const row = ctx.rows[found];
      while (row.length <= Math.max(ctx.salCol, ctx.stCol)) row.push('');
      const currentSalary = parseSalaryCellNumber(row[ctx.salCol]);
      const computed = computeSheetOp({
        currentSalary,
        type: isFullDeduction ? 'خصم كامل' : type,
        amountRaw: isFullDeduction ? 'خصم كامل' : amountRaw,
      });
      computed.result.id = userId;

      const cellDisplay = round2(computed.newSalary).toFixed(2);
      await writeCell(ctx.sheets, ctx.ssId, ctx.title, ctx.salCol, found + 1, cellDisplay);
      await writeCell(ctx.sheets, ctx.ssId, ctx.title, ctx.stCol, found + 1, computed.statusText);
      row[ctx.salCol] = computed.newSalary;
      row[ctx.stCol] = computed.statusText;

      results.push(computed.result);
    }

    return results;
  } catch (e) {
    return [emptyResult('تنبيه', `حدث خطأ: ${e.message || e}`)];
  }
}

function summarizeResults(results) {
  let totalAdded = 0;
  let totalDeducted = 0;
  let totalDebt = 0;
  let totalNotFound = 0;
  const debtUsers = [];
  for (const r of results || []) {
    totalAdded += r.added || 0;
    totalDeducted += r.deducted || 0;
    totalDebt += r.debt || 0;
    totalNotFound += (r.notFoundAmt || 0) + (r.zeroSalAmt || 0);
    if ((r.debt || 0) > 0) debtUsers.push(r.id);
  }
  return {
    totalAdded: round2(totalAdded),
    totalDeducted: round2(totalDeducted),
    totalDebt: round2(totalDebt),
    totalNotFound: round2(totalNotFound),
    debtUsers,
  };
}

module.exports = {
  processSheetOperation,
  processSheetBulk,
  summarizeResults,
  computeSheetOp,
  round2,
};
