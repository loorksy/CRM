const crypto = require('crypto');
const store = require('./store');

const MASTER_EMAIL = process.env.WA_APP_ADMIN_EMAIL || 'loorksy@gmail.com';
const MASTER_PASSWORD = process.env.WA_APP_ADMIN_PASSWORD || 'lork0009';
const JWT_SECRET = process.env.WA_APP_JWT_SECRET || process.env.SESSION_SECRET || 'lork-wa-app-license-secret';

function hashPassword(password) {
  return crypto.createHash('sha256').update(String(password)).digest('hex');
}

function b64url(input) {
  return Buffer.from(input)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

function signToken(user) {
  const payload = {
    email: user.email,
    permissions: user.permissions || { is_admin: true },
    exp: Math.floor(Date.now() / 1000) + 7 * 24 * 60 * 60,
  };
  const body = b64url(JSON.stringify(payload));
  const sig = crypto.createHmac('sha256', JWT_SECRET).update(body).digest('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
  return `${body}.${sig}`;
}

function verifyToken(token) {
  if (!token || typeof token !== 'string' || !token.includes('.')) return null;
  const [body, sig] = token.split('.');
  const expected = crypto.createHmac('sha256', JWT_SECRET).update(body).digest('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const json = JSON.parse(Buffer.from(body.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
    if (!json.exp || json.exp < Math.floor(Date.now() / 1000)) return null;
    return json;
  } catch (_) {
    return null;
  }
}

function getCookie(req, name) {
  const raw = req.headers.cookie || '';
  const parts = raw.split(';');
  for (const part of parts) {
    const [k, ...rest] = part.trim().split('=');
    if (k === name) return decodeURIComponent(rest.join('=') || '');
  }
  return '';
}

async function ensureMasterUser() {
  store.ensureSync();
  const users = (await store.read('users.json')) || [];
  const idx = users.findIndex((u) => u.email === MASTER_EMAIL);
  const master = {
    email: MASTER_EMAIL,
    password: hashPassword(MASTER_PASSWORD),
    permissions: { is_admin: true },
  };
  if (idx === -1) {
    users.push(master);
    await store.write('users.json', users);
    return;
  }
  // Keep password in sync with env/defaults so admin APK login works after reset
  users[idx] = {
    ...users[idx],
    password: hashPassword(MASTER_PASSWORD),
    permissions: { ...(users[idx].permissions || {}), is_admin: true },
  };
  await store.write('users.json', users);
}

async function findUserByEmail(email) {
  const users = (await store.read('users.json')) || [];
  return users.find((u) => u.email === email) || null;
}

function sanitizeUser(user) {
  if (!user) return null;
  const { password, ...rest } = user;
  return rest;
}

module.exports = {
  MASTER_EMAIL,
  hashPassword,
  signToken,
  verifyToken,
  getCookie,
  ensureMasterUser,
  findUserByEmail,
  sanitizeUser,
};
