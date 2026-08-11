const express = require('express');
const router = express.Router();
const { requireAuth } = require('../middleware/auth');
const { getDb } = require('../db/database');
const { processAdjustment } = require('../services/memberAdjustmentsService');
const {
  processSheetOperation,
  processSheetBulk,
  summarizeResults,
} = require('../services/memberAdjustmentSheetOps');

/** التسجيل المحاسبي في النظام (المسار الحالي — لا يُعطَّل) */
router.post('/apply', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const {
      memberUserId,
      kind,
      amount,
      notes,
      cycleId,
      syncUserInfoSheet,
      userInfoUserIdCol,
      userInfoSalaryCol,
    } = req.body || {};
    let cid = null;
    if (cycleId != null && cycleId !== '') {
      const n = parseInt(cycleId, 10);
      if (!Number.isNaN(n)) cid = n;
    }
    const r = await processAdjustment(db, req.session.userId, {
      memberUserId,
      kind,
      amount,
      notes,
      cycleId: cid,
      syncUserInfoSheet,
      userInfoUserIdCol,
      userInfoSalaryCol,
    });
    if (!r.success) return res.json(r);
    let msg = 'تم التسجيل في النظام';
    if (r.sheetMessage) msg += ' — ' + r.sheetMessage;
    res.json({
      success: true,
      message: msg,
      id: r.id,
      sheetSynced: r.sheetSynced,
      sheetMessage: r.sheetMessage || null,
    });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل' });
  }
});

/** عملية فردية على الشيت — منطق تطبيق الرواتب */
router.post('/sheet-apply', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const body = req.body || {};
    const result = await processSheetOperation(db, {
      spreadsheetId: body.spreadsheetId || body.ssId,
      sheetName: body.sheetName,
      idCol: body.idCol || 'C',
      salaryCol: body.salaryCol || 'L',
      statusCol: body.statusCol || 'O',
      userId: body.userId || body.memberUserId,
      type: body.type,
      amount: body.amount,
    });

    // اختياري: تسجيل محاسبي أيضاً في النظام (لا يغيّر سلوك الشيت)
    let erp = null;
    if (body.alsoRegisterErp && result && !String(result.status || '').startsWith('خطأ') && result.status !== 'غير موجود') {
      const kindMap = {
        اضافة: 'add',
        إضافة: 'add',
        خصم: 'deduct',
        'خصم كامل': 'deduct',
      };
      const kind = kindMap[String(body.type || '').trim()] || 'deduct';
      const amt =
        kind === 'deduct' && String(body.type || '').includes('خصم كامل')
          ? result.deducted || result.amount || 0
          : result.added || result.deducted || result.amount || 0;
      if (amt > 0) {
        erp = await processAdjustment(db, req.session.userId, {
          memberUserId: body.userId || body.memberUserId,
          kind,
          amount: amt,
          notes: body.notes || `من تطبيق الشيت: ${result.status}`,
          cycleId: body.cycleId || null,
          syncUserInfoSheet: false,
        });
      }
    }

    res.json({
      success: true,
      results: [result],
      summary: summarizeResults([result]),
      erp,
    });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل' });
  }
});

/** معالجة جماعية على الشيت */
router.post('/sheet-bulk', requireAuth, async (req, res) => {
  try {
    const db = getDb();
    const body = req.body || {};
    const results = await processSheetBulk(db, body.bulkData || body.text || '', {
      spreadsheetId: body.spreadsheetId || body.ssId,
      sheetName: body.sheetName,
      idCol: body.idCol || 'C',
      salaryCol: body.salaryCol || 'L',
      statusCol: body.statusCol || 'O',
    });
    res.json({
      success: true,
      results,
      summary: summarizeResults(results),
    });
  } catch (e) {
    res.json({ success: false, message: e.message || 'فشل' });
  }
});

module.exports = router;
