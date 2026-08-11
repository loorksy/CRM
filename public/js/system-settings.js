/**
 * تبويب إعدادات النظام — حفظ متغيرات التشغيل في قاعدة البيانات
 * دون الحاجة لتعديل .env أو إعادة تشغيل الخادم.
 */
(function () {
  function toast(msg, type) {
    if (typeof showToast === 'function') showToast(msg, type || 'success');
    else alert(msg);
  }

  function fieldHtml(field) {
    var id = 'sys_' + field.key;
    var sourceLabel =
      field.source === 'db' ? 'محفوظ في النظام' :
      field.source === 'env' ? 'من ملف البيئة' :
      field.source === 'default' ? 'افتراضي' : 'غير مضبوط';
    var sourceClass =
      field.source === 'db' ? 'text-emerald-600' :
      field.source === 'env' ? 'text-amber-600' : 'text-slate-400';

    var input;
    if (field.type === 'toggle') {
      var on = field.value === '1' || field.value === 'true';
      input =
        '<select id="' + id + '" data-sys-key="' + field.key + '" class="w-full max-w-xs py-2.5 px-3 border-2 border-slate-200 rounded-xl text-sm bg-slate-50">' +
        '<option value="1"' + (on ? ' selected' : '') + '>مفعّل</option>' +
        '<option value="0"' + (!on ? ' selected' : '') + '>معطّل</option>' +
        '</select>';
    } else {
      var type = field.type === 'password' ? 'password' : (field.type === 'number' ? 'number' : 'text');
      var dir = field.type === 'url' || field.type === 'password' || /_URL|_KEY|_SECRET|_ID|_URI|ORDER|PREFERRED|MODEL/i.test(field.key)
        ? ' dir="ltr"' : '';
      input =
        '<input id="' + id + '" data-sys-key="' + field.key + '" type="' + type + '"' + dir +
        ' value="' + String(field.value || '').replace(/"/g, '&quot;') + '"' +
        ' placeholder="' + String(field.placeholder || '').replace(/"/g, '&quot;') + '"' +
        ' autocomplete="off" class="w-full py-2.5 px-3 border-2 border-slate-200 rounded-xl text-sm bg-slate-50 text-slate-800 focus:outline-none focus:border-indigo-600 focus:bg-white focus:ring-2 focus:ring-indigo-600/10">';
    }

    return (
      '<div class="mb-4">' +
        '<div class="flex flex-wrap items-center justify-between gap-2 mb-1.5">' +
          '<label for="' + id + '" class="font-semibold text-sm text-slate-800">' + field.label + '</label>' +
          '<span class="text-[11px] ' + sourceClass + '">' + sourceLabel + '</span>' +
        '</div>' +
        input +
        (field.help ? '<p class="mt-1 text-xs text-slate-500 leading-relaxed">' + field.help + '</p>' : '') +
        '<p class="mt-0.5 text-[11px] text-slate-400 font-mono" dir="ltr">' + field.key + '</p>' +
      '</div>'
    );
  }

  window.loadSystemSettings = async function loadSystemSettings() {
    var root = document.getElementById('systemSettingsRoot');
    if (!root) return;
    root.innerHTML = '<p class="text-sm text-slate-500 py-6 text-center">جاري التحميل…</p>';
    try {
      var res = await fetch('/settings/system', { credentials: 'same-origin' });
      var data = await res.json();
      if (!data.success) throw new Error(data.message || 'فشل التحميل');

      var html = '';
      (data.groups || []).forEach(function (group) {
        html +=
          '<section class="mb-8 last:mb-0">' +
            '<h4 class="text-base font-bold text-slate-800 mb-3 flex items-center gap-2">' +
              '<i class="fas ' + group.icon + ' text-indigo-500"></i> ' + group.label +
            '</h4>' +
            '<div class="p-4 sm:p-5 rounded-xl border border-slate-200 bg-slate-50/60">';
        group.fields.forEach(function (f) { html += fieldHtml(f); });
        html += '</div></section>';
      });

      html +=
        '<div class="flex flex-wrap gap-2 pt-2">' +
          '<button type="button" id="systemSettingsSaveBtn" class="inline-flex items-center justify-center gap-2 px-6 py-3 rounded-xl font-semibold text-sm bg-indigo-600 text-white hover:bg-indigo-700 active:scale-[0.97]">' +
            '<i class="fas fa-save"></i> حفظ إعدادات النظام' +
          '</button>' +
        '</div>' +
        '<p class="mt-3 text-xs text-slate-500">تُحفظ القيم في قاعدة البيانات وتُطبَّق فوراً دون إعادة تشغيل. الأسرار تُعرض مقنّعة — اترك الحقل كما هو إن لم ترد تغييره.</p>';

      root.innerHTML = html;
      var btn = document.getElementById('systemSettingsSaveBtn');
      if (btn) btn.addEventListener('click', saveSystemSettings);
    } catch (e) {
      root.innerHTML = '<p class="text-sm text-red-600 py-4">تعذّر التحميل: ' + (e.message || e) + '</p>';
    }
  };

  async function saveSystemSettings() {
    var root = document.getElementById('systemSettingsRoot');
    if (!root) return;
    var values = {};
    root.querySelectorAll('[data-sys-key]').forEach(function (el) {
      values[el.getAttribute('data-sys-key')] = el.value;
    });
    var btn = document.getElementById('systemSettingsSaveBtn');
    if (btn) {
      btn.disabled = true;
      btn.classList.add('opacity-70');
    }
    try {
      var res = await fetch('/settings/system', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ values: values }),
      });
      var data = await res.json();
      toast(data.message || (data.success ? 'تم الحفظ' : 'فشل الحفظ'), data.success ? 'success' : 'error');
      if (data.success) loadSystemSettings();
    } catch (e) {
      toast(e.message || 'فشل الحفظ', 'error');
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.classList.remove('opacity-70');
      }
    }
  }
})();
