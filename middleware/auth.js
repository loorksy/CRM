function requireAuth(req, res, next) {
  if (req.session && req.session.userId) {
    return next();
  }
  if (req.xhr || req.headers.accept?.includes('application/json')) {
    return res.status(401).json({ error: 'غير مصرح' });
  }
  return res.redirect('/login');
}

/**
 * التحقق من الدور. حقل users.role كان موجوداً لكن بلا فحص فعلي —
 * هذا الوسيط يفعّله دون تغيير سلوك أي مسار قائم (يُطبَّق حيث يُضاف فقط).
 *
 * @param {...string} roles الأدوار المسموح لها
 */
function requireRole(...roles) {
  const allowed = roles.flat().filter(Boolean);
  return function roleGuard(req, res, next) {
    if (!req.session || !req.session.userId) {
      if (req.xhr || req.headers.accept?.includes('application/json')) {
        return res.status(401).json({ error: 'غير مصرح' });
      }
      return res.redirect('/login');
    }

    const role = req.session.user?.role || 'admin';
    if (allowed.length && !allowed.includes(role)) {
      if (req.path.startsWith('/api/') || req.xhr || req.headers.accept?.includes('application/json')) {
        return res.status(403).json({ error: 'صلاحية غير كافية' });
      }
      return res.status(403).render('error', {
        title: 'صلاحية غير كافية',
        error: 'ليس لديك صلاحية للوصول إلى هذه الصفحة',
      });
    }
    return next();
  };
}

module.exports = { requireAuth, requireRole };
