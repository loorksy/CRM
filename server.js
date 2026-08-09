const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '.env') });

process.on('uncaughtException', (err) => {
  console.error('[LorkERP] uncaughtException:', err.message);
  console.error(err.stack);
});
process.on('unhandledRejection', (reason, p) => {
  console.error('[LorkERP] unhandledRejection:', reason);
});

const express = require('express');
const session = require('express-session');
const helmet = require('helmet');
const compression = require('compression');
const fs = require('fs');
const http = require('http');
const net = require('net');
const { Server } = require('socket.io');
const { initDatabase } = require('./db/database');
const { startBackgroundSync } = require('./services/cycleSyncWorker');

const PORT = parseInt(process.env.PORT || '3020', 10);
const LOCK_FILE = path.join(__dirname, '.server.lock');
const isPM2 = !!process.env.pm_id;

/** منع تشغيل أكثر من نسخة واحدة على نفس المنفذ (لا يُطبّق تحت PM2 لأن PM2 يدير النسخ) */
function ensureSingleInstance() {
  if (isPM2) return;
  if (fs.existsSync(LOCK_FILE)) {
    try {
      const content = fs.readFileSync(LOCK_FILE, 'utf8').trim();
      const [oldPid, oldPort] = content.split(':');
      if (oldPort && parseInt(oldPort, 10) === PORT) {
        try {
          process.kill(parseInt(oldPid, 10), 0);
          console.error(`[LorkERP] نسخة أخرى تعمل بالفعل (PID: ${oldPid}) على المنفذ ${PORT}. أوقفها أولاً.`);
          process.exit(1);
        } catch (_) {}
      }
    } catch (_) {}
  }
  try {
    fs.writeFileSync(LOCK_FILE, `${process.pid}:${PORT}`, 'utf8');
  } catch (e) {
    console.error('[LorkERP] فشل إنشاء ملف القفل:', e.message);
    process.exit(1);
  }
  function removeLock() {
    try {
      if (fs.existsSync(LOCK_FILE)) {
        const c = fs.readFileSync(LOCK_FILE, 'utf8').trim();
        if (c.startsWith(process.pid + ':')) fs.unlinkSync(LOCK_FILE);
      }
    } catch (_) {}
  }
  process.on('exit', removeLock);
  process.on('SIGTERM', () => { removeLock(); });
  process.on('SIGINT', () => { removeLock(); });
}

/** التحقق من أن المنفذ غير مستخدم (محاولة اتصال بدل الربط لتجنب TIME_WAIT) */
function isPortInUse(port) {
  return new Promise((resolve) => {
    const client = net.createConnection({ port, host: '127.0.0.1' }, () => {
      client.destroy();
      resolve(true);
    });
    client.once('error', () => resolve(false));
  });
}

const app = express();
const server = http.createServer(app);
const io = new Server(server);

/** مدة بقاء الجلسة: 7 أيام (بالمللي ثانية للـ cookie وبالثواني لـ session store) */
const SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const SESSION_MAX_AGE_SECONDS = Math.floor(SESSION_MAX_AGE_MS / 1000);

const sessionsDir = path.join(__dirname, 'sessions');
if (!fs.existsSync(sessionsDir)) fs.mkdirSync(sessionsDir, { recursive: true });

/** في وضع التطوير (dev) نستخدم MemoryStore لتجنب خطأ EPERM على Windows عند rename ملفات الجلسات */
const isDev = process.env.NODE_ENV === 'development' || process.env.USE_MEMORY_SESSION === '1';
let sessionStore;
if (isDev) {
  console.log('[LorkERP] Using MemoryStore for sessions (dev mode - avoids EPERM on Windows)');
} else {
  sessionStore = new (require('session-file-store')(require('express-session')))({
    path: sessionsDir,
    ttl: SESSION_MAX_AGE_SECONDS,
    retries: 5,
    reapInterval: 3600,
    reapAsync: true,
    logFn: () => {},
  });
}

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));

app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      styleSrc: ["'self'", "'unsafe-inline'", "https://fonts.googleapis.com", "https://cdnjs.cloudflare.com", "https://cdn.jsdelivr.net"],
      fontSrc: ["'self'", "https://fonts.gstatic.com", "https://cdnjs.cloudflare.com"],
      scriptSrc: ["'self'", "'unsafe-inline'", "https://cdnjs.cloudflare.com", "https://cdn.jsdelivr.net"],
      scriptSrcAttr: ["'unsafe-inline'"],
      imgSrc: ["'self'", "data:", "blob:"],
      connectSrc: ["'self'", "ws:", "wss:", "https://cdnjs.cloudflare.com"],
    },
  },
}));
app.use(compression());

/**
 * Evolution API يحلّل جسم الطلب بنفسه بحدّ 136 ميجابايت (رفع وسائط واتساب).
 * لو حلّلناه هنا أولاً بحدّ 25 ميجابايت لفشل رفع الملفات الكبيرة، لذلك
 * نتخطّى محلّلات الجسم العامة لمسارات Evolution وحدها.
 */
const { isEvolutionPath, reserveMountPoint, mountEvolution } = require('./services/whatsapp/embedEvolution');
const skipForEvolution = (parser) => (req, res, next) =>
  isEvolutionPath(req.path) ? next() : parser(req, res, next);

app.use(skipForEvolution(express.json({ limit: '25mb' })));
app.use(skipForEvolution(express.urlencoded({ extended: true, limit: '25mb' })));
app.use(express.static(path.join(__dirname, 'public')));

const sessionConfig = {
  secret: process.env.SESSION_SECRET || 'lorkerp-secret',
  resave: true,
  saveUninitialized: false,
  cookie: {
    secure: false,
    httpOnly: true,
    maxAge: SESSION_MAX_AGE_MS,
    sameSite: 'lax',
  },
};
if (sessionStore) sessionConfig.store = sessionStore;

app.use(session(sessionConfig));

app.use((req, res, next) => {
  res.locals.user = req.session.user || null;
  next();
});

const authRoutes = require('./routes/auth');
const dashboardRoutes = require('./routes/dashboard');
const sheetsRoutes = require('./routes/sheets');
const sheetRoutes = require('./routes/sheet');
const settingsRoutes = require('./routes/settings');
const pagesRoutes = require('./routes/pages');
const searchRoutes = require('./routes/search');
const shippingRoutes = require('./routes/shipping');
const subAgenciesRoutes = require('./routes/subAgencies');
const fundsRoutes = require('./routes/funds');
const transferCompaniesRoutes = require('./routes/transferCompanies');
const accreditationsRoutes = require('./routes/accreditations');
const aiRoutes = require('./routes/ai');
const returnsRoutes = require('./routes/returns');
const debtsRoutes = require('./routes/debts');
const fxSpreadRoutes = require('./routes/fxSpread');
const cycleAccountingRoutes = require('./routes/cycleAccounting');
const expensesRoutes = require('./routes/expenses');
const adminBrokerageRoutes = require('./routes/adminBrokerage');
const payablesRoutes = require('./routes/payables');
const financialMovementsRoutes = require('./routes/financialMovements');
const quickActionRoutes = require('./routes/quickAction');
const paymentDueRoutes = require('./routes/paymentDue');
const reportsRoutes = require('./routes/reports');
const memberDirectoryRoutes = require('./routes/member-directory');
const memberAdjustmentsRoutes = require('./routes/member-adjustments');
const whatsappRoutes = require('./routes/whatsapp');

app.use('/', authRoutes);
app.use('/dashboard', dashboardRoutes);
app.use('/sheets', sheetsRoutes);
app.use('/api/sheet', sheetRoutes);
app.use('/settings', settingsRoutes);
app.use('/api/search', searchRoutes);
app.use('/api/shipping', shippingRoutes);
app.use('/api/sub-agencies', subAgenciesRoutes);
app.use('/api/funds', fundsRoutes);
app.use('/api/transfer-companies', transferCompaniesRoutes);
app.use('/api/accreditations', accreditationsRoutes);
app.use('/api/returns', returnsRoutes);
app.use('/api/debts', debtsRoutes);
app.use('/api/fx-spread', fxSpreadRoutes);
app.use('/api/cycle-accounting', cycleAccountingRoutes);
app.use('/api/expenses', expensesRoutes);
app.use('/api/admin-brokerage', adminBrokerageRoutes);
app.use('/api/payables', payablesRoutes);
app.use('/api/financial-movements', financialMovementsRoutes);
app.use('/api/quick-action', quickActionRoutes);
app.use('/api/payment-due', paymentDueRoutes);
app.use('/api/reports', reportsRoutes);
app.use('/api/member-directory', memberDirectoryRoutes);
app.use('/api/member-adjustments', memberAdjustmentsRoutes);
app.use('/api/whatsapp', whatsappRoutes);
app.use('/ai', aiRoutes(io));

// حجز مسار Evolution قبل معالج 404 — يُملأ بعد إقلاع الخادم
reserveMountPoint(app);

app.use('/', pagesRoutes);

app.get('/', (req, res) => {
  if (req.session.userId) {
    return res.redirect('/dashboard');
  }
  res.redirect('/login');
});

app.use((req, res) => {
  res.status(404).render('404', { title: 'الصفحة غير موجودة' });
});

app.use((err, req, res, next) => {
  console.error('[LorkERP] Error:', err.message);
  console.error(err.stack);

  if (res.headersSent) return next(err);

  if (req.path.startsWith('/api/')) {
    return res.status(500).json({ success: false, message: err.message || 'حدث خطأ في الخادم' });
  }
  res.status(500).render('error', {
    title: 'خطأ في الخادم',
    error: err && err.message ? err.message : 'حدث خطأ غير متوقع',
  });
});

io.on('connection', (socket) => {
  socket.on('subscribe_analysis', (jobId) => {
    if (jobId) socket.join(`analysis:${jobId}`);
  });
});

let isShuttingDown = false;
function gracefulShutdown() {
  if (isShuttingDown) return;
  isShuttingDown = true;
  console.log('[LorkERP] Server shutting down (SIGTERM/SIGINT)...');
  server.close(() => {
    console.log('[LorkERP] Server stopped.');
    process.exit(0);
  });
  setTimeout(() => {
    console.log('[LorkERP] Forced exit after timeout.');
    process.exit(0);
  }, 5000);
}
process.on('SIGTERM', gracefulShutdown);
process.on('SIGINT', gracefulShutdown);

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`[LorkERP] المنفذ ${PORT} مشغول. أوقف العملية الأخرى أولاً: taskkill /PID <رقم_العملية> /F`);
    process.exit(1);
  }
  console.error('[LorkERP] Server error:', err);
});

initDatabase()
  .then(async () => {
    ensureSingleInstance();
    const inUse = await isPortInUse(PORT);
    if (inUse) {
      console.error(`[LorkERP] المنفذ ${PORT} مشغول. أوقف العملية الأخرى أولاً: taskkill /F /PID <رقم_العملية>`);
      process.exit(1);
    }
    server.listen(PORT, '0.0.0.0', () => {
      console.log(`[LorkERP] Server started on http://0.0.0.0:${PORT} (PID ${process.pid})`);
      try {
        startBackgroundSync(undefined, 5);
        console.log('[LorkERP] Payroll cycle background sync started (interval: env CYCLE_SYNC_INTERVAL_MS or 120s)');
      } catch (e) {
        console.error('[LorkERP] Failed to start background sync', e.message);
      }

      // تركيب Evolution API داخل نفس العملية. لا يوقف الإقلاع إن فشل.
      mountEvolution(server).catch((e) => {
        console.error('[LorkERP] Evolution mount error:', e.message);
      });
    });
  })
  .catch((err) => {
    console.error('[LorkERP] Database init failed:', err.message);
    console.error('[LorkERP] Server cannot start without database. Fix the error and restart.');
    process.exit(1);
  });

module.exports = { app, server, io };
