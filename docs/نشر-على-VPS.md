# نشر LorkERP على VPS — erp.lork.cloud

## المتطلبات على السيرفر

- Ubuntu 24.04 LTS (أو أحدث)
- دومين يشير بسجل A إلى IP السيرفر (مثال: `erp.lork.cloud` → VPS)
- منافذ 22 / 80 / 443 مفتوحة

## النشر السريع (سيرفر جديد)

```bash
# 1) على السيرفر كـ root بعد رفع المشروع إلى /root/ERPSYSTEM-
cd /root/ERPSYSTEM-
bash scripts/vps-bootstrap.sh

# 2) نشر التطبيق + Postgres + nginx (بدون HTTPS بعد)
export GITHUB_TOKEN=...   # فقط إن لم يكن المستودع مستنسخاً مسبقاً
bash scripts/vps-deploy.sh

# 3) شهادة SSL
certbot --nginx -d erp.lork.cloud -d www.erp.lork.cloud \
  --non-interactive --agree-tos -m YOUR_EMAIL --redirect

# 4) بعد نجاح Certbot انسخ إعداد الـ proxy الكامل (sockets / واتساب)
cp deploy/nginx/erp.lork.cloud.conf /etc/nginx/sites-available/erp.lork.cloud
nginx -t && systemctl reload nginx
pm2 save
```

## التحديث لاحقاً

```bash
cd /root/ERPSYSTEM-
git pull origin main
npm install --omit=dev
npm run css:build
# إن تغيّر Evolution:
# npm run evolution:setup
pm2 restart lorkerp --update-env
```

## المسارات المهمة

| العنصر | المسار / القيمة |
|--------|------------------|
| التطبيق | `/root/ERPSYSTEM-` |
| PM2 | `lorkerp` على المنفذ `3020` |
| nginx | `/etc/nginx/sites-available/erp.lork.cloud` |
| SSL | `/etc/letsencrypt/live/erp.lork.cloud/` |
| Postgres | قاعدة `lorkerp` / مستخدم `lork` |
| الواجهة العامة | `https://erp.lork.cloud` |

## ملاحظات أمنية

- ملف `.env` يجب أن يبقى `chmod 600`
- غيّر `ADMIN_PASSWORD` فور أول دخول
- لا ترفع أسرار الإنتاج إلى Git
