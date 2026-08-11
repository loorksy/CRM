/**
 * APIs لتطبيقات Android (الإرسال + الأدمن) — بدون بوت واتساب Web.
 * متوافقة مع https://erp.lork.cloud كما هو مضبوط في الـ APK.
 */
const path = require('path');
const fs = require('fs');
const express = require('express');
const licenses = require('../services/whatsappAppLicenses/licenses');
const auth = require('../services/whatsappAppLicenses/auth');

const router = express.Router();
const TOKEN_NAME = 'token';

auth.ensureMasterUser().catch((e) => {
  console.warn('[whatsapp-app-licenses] ensureMasterUser:', e.message);
});

function setAuthCookie(res, token) {
  const secure = true;
  const parts = [
    `${TOKEN_NAME}=${token}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${7 * 24 * 60 * 60}`,
  ];
  if (secure) parts.push('Secure');
  res.append('Set-Cookie', parts.join('; '));
}

function requireAdmin(req, res, next) {
  const bearer = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const token = auth.getCookie(req, TOKEN_NAME) || bearer;
  const user = auth.verifyToken(token);
  if (!user) return res.status(401).json({ error: 'UNAUTHORIZED' });
  if (!user.permissions || !user.permissions.is_admin) {
    return res.status(403).json({ error: 'FORBIDDEN' });
  }
  req.waAppUser = user;
  return next();
}

router.post('/api/login', async (req, res) => {
  try {
    const { email, password } = req.body || {};
    if (!email || !password) return res.status(400).json({ error: 'MISSING_CREDENTIALS' });
    await auth.ensureMasterUser();
    const user = await auth.findUserByEmail(String(email).trim());
    if (!user) return res.status(401).json({ error: 'INVALID_CREDENTIALS' });
    if (auth.hashPassword(password) !== user.password) {
      return res.status(401).json({ error: 'INVALID_CREDENTIALS' });
    }
    const token = auth.signToken(user);
    setAuthCookie(res, token);
    return res.json({ success: true, user: auth.sanitizeUser(user) });
  } catch (e) {
    console.error('[whatsapp-app-licenses] login', e);
    return res.status(500).json({ error: 'ERROR' });
  }
});

router.post('/api/license/activate', async (req, res) => {
  try {
    const result = await licenses.activate({
      code: req.body?.code,
      deviceId: req.body?.deviceId,
    });
    return res.json(result);
  } catch (err) {
    const code = err.code || 'ERROR';
    const status =
      code === 'INVALID_CODE' || code === 'DISABLED' || code === 'DEVICE_MISMATCH'
        ? 403
        : code === 'MISSING_FIELDS'
          ? 400
          : 500;
    return res.status(status).json({ error: code });
  }
});

router.post('/api/license/status', async (req, res) => {
  const result = await licenses.checkStatus({
    code: req.body?.code,
    deviceId: req.body?.deviceId,
  });
  if (!result.ok) return res.status(403).json(result);
  return res.json(result);
});

router.get('/api/licenses', requireAdmin, async (req, res) => {
  const list = await licenses.listLicenses();
  return res.json(list);
});

router.post('/api/licenses', requireAdmin, async (req, res) => {
  const license = await licenses.createLicense({
    note: req.body?.note || '',
    createdBy: req.waAppUser?.email || '',
  });
  return res.json({ success: true, license });
});

router.put('/api/licenses/:id', requireAdmin, async (req, res) => {
  const patch = {};
  if (typeof req.body?.active === 'boolean') patch.active = req.body.active;
  if (req.body?.note !== undefined) patch.note = req.body.note;
  if (req.body?.resetDevice) patch.resetDevice = true;
  const updated = await licenses.updateLicense(req.params.id, patch);
  if (!updated) return res.status(404).json({ error: 'NOT_FOUND' });
  return res.json({ success: true, license: updated });
});

router.delete('/api/licenses/:id', requireAdmin, async (req, res) => {
  const ok = await licenses.deleteLicense(req.params.id);
  if (!ok) return res.status(404).json({ error: 'NOT_FOUND' });
  return res.json({ success: true });
});

router.get('/api/app/update', async (req, res) => {
  const candidates = [
    path.join(__dirname, '..', 'public', 'downloads', 'whatsapp-app', 'app-update.json'),
  ];
  const filePath = candidates.find((p) => fs.existsSync(p));
  if (!filePath) return res.status(404).json({ error: 'UPDATE_INFO_MISSING' });
  try {
    const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return res.json({
      versionCode: Number(raw.versionCode) || 0,
      versionName: String(raw.versionName || ''),
      force: !!raw.force,
      apkUrl: String(raw.apkUrl || 'https://erp.lork.cloud/download/bulk-sender.apk'),
      title: String(raw.title || 'يتوفر تحديث جديد'),
      changelog: Array.isArray(raw.changelog) ? raw.changelog.map(String) : [],
    });
  } catch (_) {
    return res.status(500).json({ error: 'UPDATE_INFO_INVALID' });
  }
});

router.get('/download/bulk-sender.apk', (req, res) => {
  const filePath = path.join(__dirname, '..', 'public', 'downloads', 'whatsapp-app', 'bulk-sender.apk');
  if (!fs.existsSync(filePath)) return res.status(404).send('APK not found');
  return res.download(filePath, 'bulk-sender.apk');
});

router.get('/download/bulk-admin.apk', (req, res) => {
  const filePath = path.join(__dirname, '..', 'public', 'downloads', 'whatsapp-app', 'bulk-admin.apk');
  if (!fs.existsSync(filePath)) return res.status(404).send('APK not found');
  return res.download(filePath, 'bulk-admin.apk');
});

module.exports = router;
