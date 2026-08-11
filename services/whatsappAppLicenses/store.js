const fs = require('fs');
const path = require('path');
const fsp = fs.promises;

const dataDir = path.join(__dirname, '..', '..', 'data', 'whatsapp-app');

const defaults = {
  'users.json': [],
  'licenses.json': [],
};

function ensureSync() {
  fs.mkdirSync(dataDir, { recursive: true });
  for (const [name, value] of Object.entries(defaults)) {
    const fp = path.join(dataDir, name);
    if (!fs.existsSync(fp)) {
      fs.writeFileSync(fp, JSON.stringify(value, null, 2), 'utf8');
    }
  }
}

async function read(name) {
  ensureSync();
  const fp = path.join(dataDir, name);
  try {
    const raw = await fsp.readFile(fp, 'utf8');
    return JSON.parse(raw);
  } catch (err) {
    if (err && (err.code === 'ENOENT' || err instanceof SyntaxError)) {
      const fallback = JSON.parse(JSON.stringify(defaults[name] !== undefined ? defaults[name] : {}));
      await fsp.writeFile(fp, JSON.stringify(fallback, null, 2), 'utf8');
      return fallback;
    }
    throw err;
  }
}

async function write(name, data) {
  ensureSync();
  const fp = path.join(dataDir, name);
  await fsp.writeFile(fp, JSON.stringify(data, null, 2), 'utf8');
}

module.exports = { dataDir, ensureSync, read, write };
