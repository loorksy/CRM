# LorkERP - نظام المحاسبة المتكامل

نظام محاسبة متجاوب يعمل على الموبايل واللابتوب، مبني بلغة عربية كاملة مع دعم RTL.

## المميزات

- **تصميم متجاوب** - يعمل كتطبيق موبايل على الهاتف وكداشبورد على اللابتوب
- **واجهة عربية كاملة** - دعم RTL مع خط Tajawal
- **مزامنة Google Sheets** - ربط وتزامن البيانات مع جداول بيانات Google
- **نظام مصادقة آمن** - تسجيل دخول مع تشفير كلمات المرور
- **وكيل واتساب مدمج** - Evolution API بملفاته داخل المشروع ويعمل في نفس العملية،
  مع نظام تحقق من الهوية يمنع أي مستخدم من الوصول لبيانات غيره
  ([التفاصيل](docs/تكامل-واتساب.md))
- **قابل للتوسع** - مبني بهيكلية جاهزة لإضافة أقسام وصفحات جديدة

## المتطلبات

- Node.js 18+
- npm
- PostgreSQL 14+

## التثبيت

```bash
git clone <repository-url>
cd LorkERP
cp .env.example .env
npm install
```

## التشغيل

```bash
# تشغيل عادي
npm start

# تشغيل للتطوير (إعادة تشغيل تلقائي)
npm run dev
```

التطبيق سيعمل على: `http://localhost:3020`

## بيانات الدخول الافتراضية

- **اسم المستخدم:** admin
- **كلمة المرور:** admin123

> قم بتغيير كلمة المرور فوراً بعد أول تسجيل دخول

## هيكلية المشروع

```
LorkERP/
├── server.js              # الخادم الرئيسي
├── package.json
├── .env                   # متغيرات البيئة
├── ecosystem.config.cjs   # إعدادات PM2
├── postcss.config.js      # إعدادات Tailwind/PostCSS
├── db/
│   ├── database.js        # إعداد قاعدة البيانات PostgreSQL
│   └── schema.pg.sql      # مخطط قاعدة البيانات
├── middleware/
│   └── auth.js            # وسيط المصادقة
├── routes/                # مسارات التطبيق (22 ملف)
│   ├── auth.js            # مسارات تسجيل الدخول
│   ├── dashboard.js       # لوحة التحكم
│   ├── sheets.js          # مزامنة Google Sheets
│   ├── settings.js        # الإعدادات
│   ├── shipping.js        # الشحنات
│   ├── funds.js           # الصناديق
│   ├── debts.js           # الديون
│   ├── expenses.js        # المصاريف
│   ├── reports.js         # التقارير
│   └── ...                # مسارات أخرى
├── services/              # طبقة المنطق التجاري
│   ├── payrollAuditEngine.js
│   ├── fundService.js
│   ├── ledgerService.js
│   └── ...                # خدمات أخرى
├── views/
│   ├── login.ejs          # صفحة تسجيل الدخول
│   ├── dashboard.ejs      # قالب الداشبورد
│   ├── 404.ejs
│   ├── error.ejs
│   └── partials/          # أجزاء الصفحات (18+ ملف)
│       ├── home.ejs       # الصفحة الرئيسية
│       ├── settings.ejs   # صفحة الإعدادات
│       └── ...            # صفحات أخرى
├── public/
│   ├── css/
│   │   └── tailwind.css   # مخرجات البناء من src/input.css (Tailwind + أي قواعد موروثة ضرورية)
│   └── js/
│       ├── app.js         # السكريبت الرئيسي
│       └── sheets.js      # سكريبت Google Sheets
└── src/
    └── input.css          # ملف إدخال Tailwind CSS
```

## النشر على VPS

```bash
# تثبيت Node.js
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

# استنساخ المشروع وتثبيت التبعيات
git clone <repository-url>
cd LorkERP
cp .env.example .env
npm install --production

# تشغيل بـ PM2
npm install -g pm2
pm2 start server.js --name lorkerp
pm2 save
pm2 startup
```
