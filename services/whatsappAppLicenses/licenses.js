const crypto = require('crypto');
const store = require('./store');

function normalizeCode(code) {
  return String(code || '')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '');
}

function formatCode(raw) {
  const clean = normalizeCode(raw);
  const parts = clean.match(/.{1,4}/g) || [];
  return parts.join('-');
}

function generateCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  const bytes = crypto.randomBytes(12);
  for (let i = 0; i < 12; i += 1) {
    out += alphabet[bytes[i] % alphabet.length];
  }
  return formatCode(out);
}

async function listLicenses() {
  return (await store.read('licenses.json')) || [];
}

async function saveLicenses(licenses) {
  await store.write('licenses.json', licenses);
}

async function createLicense({ note = '', createdBy = '' } = {}) {
  const licenses = await listLicenses();
  let code = generateCode();
  while (licenses.some((l) => normalizeCode(l.code) === normalizeCode(code))) {
    code = generateCode();
  }
  const license = {
    id: crypto.randomUUID(),
    code,
    note: String(note || '').trim(),
    active: true,
    createdAt: Date.now(),
    createdBy: createdBy || '',
    deviceId: null,
    activatedAt: null,
    lastSeenAt: null,
  };
  licenses.unshift(license);
  await saveLicenses(licenses);
  return license;
}

async function findByCode(code) {
  const normalized = normalizeCode(code);
  const licenses = await listLicenses();
  return licenses.find((l) => normalizeCode(l.code) === normalized) || null;
}

function isLicenseActive(license) {
  return !!(license && license.active === true);
}

async function updateLicense(id, patch = {}) {
  const licenses = await listLicenses();
  const idx = licenses.findIndex((l) => l.id === id);
  if (idx === -1) return null;
  const current = licenses[idx];
  const updated = {
    id: current.id,
    code: current.code,
    note: current.note || '',
    active: isLicenseActive(current),
    createdAt: current.createdAt,
    createdBy: current.createdBy || '',
    deviceId: current.deviceId || null,
    activatedAt: current.activatedAt || null,
    lastSeenAt: current.lastSeenAt || null,
  };
  if (typeof patch.active === 'boolean') updated.active = patch.active;
  if (patch.note !== undefined) updated.note = String(patch.note || '').trim();
  if (patch.resetDevice) {
    updated.deviceId = null;
    updated.activatedAt = null;
  }
  licenses[idx] = updated;
  await saveLicenses(licenses);
  return updated;
}

async function deleteLicense(id) {
  const licenses = await listLicenses();
  const next = licenses.filter((l) => l.id !== id);
  if (next.length === licenses.length) return false;
  await saveLicenses(next);
  return true;
}

async function activate({ code, deviceId }) {
  if (!code || !deviceId) {
    const err = new Error('MISSING_FIELDS');
    err.code = 'MISSING_FIELDS';
    throw err;
  }
  const licenses = await listLicenses();
  const normalized = normalizeCode(code);
  const idx = licenses.findIndex((l) => normalizeCode(l.code) === normalized);
  if (idx === -1) {
    const err = new Error('INVALID_CODE');
    err.code = 'INVALID_CODE';
    throw err;
  }
  const license = licenses[idx];
  if (!isLicenseActive(license)) {
    const err = new Error('DISABLED');
    err.code = 'DISABLED';
    throw err;
  }
  if (license.deviceId && license.deviceId !== deviceId) {
    const err = new Error('DEVICE_MISMATCH');
    err.code = 'DEVICE_MISMATCH';
    throw err;
  }
  const now = Date.now();
  const updated = {
    ...license,
    deviceId,
    activatedAt: license.activatedAt || now,
    lastSeenAt: now,
  };
  licenses[idx] = updated;
  await saveLicenses(licenses);
  return {
    ok: true,
    active: true,
    code: updated.code,
    note: updated.note || '',
    deviceId: updated.deviceId,
  };
}

async function checkStatus({ code, deviceId }) {
  if (!code || !deviceId) {
    return { ok: false, active: false, error: 'MISSING_FIELDS' };
  }
  const license = await findByCode(code);
  if (!license) {
    return { ok: false, active: false, error: 'INVALID_CODE' };
  }
  if (!isLicenseActive(license)) {
    return { ok: false, active: false, error: 'DISABLED' };
  }
  if (license.deviceId && license.deviceId !== deviceId) {
    return { ok: false, active: false, error: 'DEVICE_MISMATCH' };
  }
  if (license.deviceId) {
    const licenses = await listLicenses();
    const idx = licenses.findIndex((l) => l.id === license.id);
    if (idx !== -1) {
      licenses[idx] = { ...licenses[idx], lastSeenAt: Date.now() };
      await saveLicenses(licenses);
    }
  }
  return {
    ok: true,
    active: true,
    code: license.code,
    note: license.note || '',
  };
}

module.exports = {
  listLicenses,
  createLicense,
  updateLicense,
  deleteLicense,
  activate,
  checkStatus,
  formatCode,
  normalizeCode,
  isLicenseActive,
};
