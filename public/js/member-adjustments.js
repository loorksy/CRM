(function () {
  const memberId = document.getElementById('maMemberId');
  const kind = document.getElementById('maKind');
  const amount = document.getElementById('maAmount');
  const cycleId = document.getElementById('maCycleId');
  const notes = document.getElementById('maNotes');
  const msg = document.getElementById('maMsg');

  let copyStats = { totalDebt: 0, totalDeducted: 0, totalNotFound: 0 };
  var DEFAULT_COLS = { id: 'C', salary: 'L', status: 'O' };

  function setMaMessage(text, variant) {
    if (!msg) return;
    msg.textContent = text || '';
    msg.classList.remove('text-slate-500', 'text-red-600', 'text-emerald-600', 'font-medium');
    if (variant === 'error') msg.classList.add('text-red-600');
    else if (variant === 'success') msg.classList.add('text-emerald-600', 'font-medium');
    else msg.classList.add('text-slate-500');
  }

  function colLetters() {
    var out = [];
    for (var i = 0; i < 26; i++) out.push(String.fromCharCode(65 + i));
    return out;
  }

  function fillColSelect(el, selected) {
    if (!el) return;
    var letters = colLetters();
    var sel = String(selected || '').toUpperCase() || letters[0];
    el.innerHTML = letters.map(function (L) {
      return '<option value="' + L + '"' + (L === sel ? ' selected' : '') + '>' + L + '</option>';
    }).join('');
    if (!el.value) el.value = sel;
  }

  function initColumnSelects() {
    fillColSelect(document.getElementById('maIdCol'), DEFAULT_COLS.id);
    fillColSelect(document.getElementById('maSalaryCol'), DEFAULT_COLS.salary);
    fillColSelect(document.getElementById('maStatusCol'), DEFAULT_COLS.status);
  }

  function initSheetModes() {
    var btns = document.querySelectorAll('.ma-sheet-mode-btn');
    var single = document.getElementById('maSheetSingle');
    var bulk = document.getElementById('maSheetBulk');
    if (!btns.length) return;
    function show(mode) {
      if (single) single.classList.toggle('hidden', mode !== 'single');
      if (bulk) bulk.classList.toggle('hidden', mode !== 'bulk');
      btns.forEach(function (b) {
        var on = b.getAttribute('data-ma-sheet-mode') === mode;
        b.classList.toggle('text-indigo-700', on);
        b.classList.toggle('bg-white', on);
        b.classList.toggle('shadow-sm', on);
        b.classList.toggle('ring-1', on);
        b.classList.toggle('ring-slate-200/90', on);
        b.classList.toggle('font-semibold', on);
        b.classList.toggle('font-medium', !on);
        b.classList.toggle('text-slate-600', !on);
      });
    }
    btns.forEach(function (b) {
      b.addEventListener('click', function () {
        show(b.getAttribute('data-ma-sheet-mode') || 'bulk');
      });
    });
    show('bulk');
  }

  function initKindPills() {
    if (!kind) return;
    var tabs = document.querySelectorAll('.ma-kind-btn');
    if (!tabs.length) return;
    function sync() {
      var v = kind.value;
      tabs.forEach(function (btn) {
        var k = btn.getAttribute('data-ma-kind');
        var on = k === v;
        btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        btn.classList.toggle('bg-white', on);
        btn.classList.toggle('shadow-md', on);
        btn.classList.toggle('ring-2', on);
        btn.classList.toggle('ring-indigo-500', on);
        btn.classList.toggle('text-indigo-900', on);
        btn.classList.toggle('bg-slate-100', !on);
        btn.classList.toggle('text-slate-600', !on);
      });
    }
    tabs.forEach(function (btn) {
      btn.addEventListener('click', function () {
        kind.value = btn.getAttribute('data-ma-kind') || 'deduct';
        kind.dispatchEvent(new Event('change', { bubbles: true }));
        sync();
      });
    });
    kind.addEventListener('change', sync);
    sync();
  }

  async function loadCycles() {
    if (!cycleId) return;
    try {
      const res = await fetch('/api/sheet/cycles', { credentials: 'same-origin' });
      const data = await res.json();
      if (!data.success || !data.cycles || !data.cycles.length) {
        cycleId.innerHTML = '<option value="">— لا توجد دورات —</option>';
        return;
      }
      cycleId.innerHTML =
        '<option value="">— اختر الدورة —</option>' +
        data.cycles.map(function (c) {
          return '<option value="' + c.id + '">' + (c.name || 'دورة #' + c.id) + '</option>';
        }).join('');
      if (data.cycles[0] && data.cycles[0].id) cycleId.value = String(data.cycles[0].id);
    } catch (_) {
      cycleId.innerHTML = '<option value="">— خطأ في التحميل —</option>';
    }
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  async function loadSpreadsheets() {
    var ss = document.getElementById('maSsId');
    if (!ss) return;
    ss.innerHTML = '<option value="">جاري جلب الملفات…</option>';
    try {
      var res = await fetch('/api/sheet/spreadsheets-list', { credentials: 'same-origin' });
      var data = await res.json().catch(function () { return {}; });
      if (!res.ok || !data.success) {
        ss.innerHTML = '<option value="">' + escapeHtml(data.message || ('فشل جلب الملفات (' + res.status + ') — اربط Google من الإعدادات')) + '</option>';
        return;
      }
      var files = data.spreadsheets || [];
      if (!files.length) {
        ss.innerHTML = '<option value="">لا توجد ملفات — تحقق من ربط Google</option>';
        return;
      }
      ss.innerHTML = '<option value="">— اختر جدول البيانات —</option>' +
        files.map(function (f) {
          return '<option value="' + escapeHtml(f.id) + '">' + escapeHtml(f.name || f.id) + '</option>';
        }).join('');
    } catch (e) {
      ss.innerHTML = '<option value="">خطأ في التحميل — أعد تحميل الصفحة</option>';
    }
  }

  async function loadSheetsForSelected() {
    var ss = document.getElementById('maSsId');
    var sh = document.getElementById('maSheetName');
    if (!ss || !sh) return;
    var id = ss.value;
    if (!id) {
      sh.innerHTML = '<option value="">اختر الجدول أولاً</option>';
      return;
    }
    sh.innerHTML = '<option value="">جاري جلب الأوراق…</option>';
    try {
      var res = await fetch('/api/sheet/spreadsheet-sheets?spreadsheetId=' + encodeURIComponent(id), { credentials: 'same-origin' });
      var data = await res.json();
      if (!data.success) {
        sh.innerHTML = '<option value="">' + escapeHtml(data.message || 'فشل') + '</option>';
        return;
      }
      sh.innerHTML = (data.sheets || []).map(function (s) {
        return '<option value="' + escapeHtml(s.title || '') + '">' + escapeHtml(s.title || '') + '</option>';
      }).join('') || '<option value="">لا توجد أوراق</option>';
    } catch (_) {
      sh.innerHTML = '<option value="">خطأ</option>';
    }
  }

  function sheetConfig() {
    return {
      spreadsheetId: (document.getElementById('maSsId') || {}).value || '',
      sheetName: (document.getElementById('maSheetName') || {}).value || '',
      idCol: ((document.getElementById('maIdCol') || {}).value || DEFAULT_COLS.id).trim() || DEFAULT_COLS.id,
      salaryCol: ((document.getElementById('maSalaryCol') || {}).value || DEFAULT_COLS.salary).trim() || DEFAULT_COLS.salary,
      statusCol: ((document.getElementById('maStatusCol') || {}).value || DEFAULT_COLS.status).trim() || DEFAULT_COLS.status,
    };
  }

  function formatMoneyUI(num) {
    var rounded = Math.round((parseFloat(num) || 0) * 100) / 100;
    return '$' + String(rounded);
  }

  function formatMoneyCopy(num) {
    var rounded = Math.round((parseFloat(num) || 0) * 100) / 100;
    return '$' + String(rounded).replace('.', ',');
  }

  function setModalVisible(el, show) {
    if (!el) return;
    if (show) {
      el.classList.remove('hidden');
      el.classList.add('flex');
      document.body.classList.add('overflow-hidden');
    } else {
      el.classList.add('hidden');
      el.classList.remove('flex');
      var proc = document.getElementById('maProcessModal');
      var res = document.getElementById('maResultsModal');
      var anyOpen = (proc && !proc.classList.contains('hidden')) || (res && !res.classList.contains('hidden'));
      if (!anyOpen) document.body.classList.remove('overflow-hidden');
    }
  }

  function toggleLoader(show, btnId) {
    setModalVisible(document.getElementById('maProcessModal'), !!show);
    var btn = document.getElementById(btnId);
    if (btn) btn.disabled = !!show;
  }

  function openResultsModal() {
    setModalVisible(document.getElementById('maResultsModal'), true);
  }

  function closeResultsModal() {
    setModalVisible(document.getElementById('maResultsModal'), false);
  }

  function renderResults(results, summary) {
    var body = document.getElementById('maResultsBody');
    if (!body) return;
    body.innerHTML = '';

    copyStats = { totalDebt: 0, totalDeducted: 0, totalNotFound: 0 };
    var totalAdded = summary ? summary.totalAdded : 0;
    var debtUsers = summary && summary.debtUsers ? summary.debtUsers.slice() : [];

    (results || []).forEach(function (r) {
      if (!summary) {
        totalAdded += r.added || 0;
        copyStats.totalDeducted += r.deducted || 0;
        copyStats.totalDebt += r.debt || 0;
        copyStats.totalNotFound += (r.notFoundAmt || 0) + (r.zeroSalAmt || 0);
        if ((r.debt || 0) > 0) debtUsers.push(r.id);
      } else {
        copyStats.totalDeducted = summary.totalDeducted || 0;
        copyStats.totalDebt = summary.totalDebt || 0;
        copyStats.totalNotFound = summary.totalNotFound || 0;
      }

      var st = String(r.status || '');
      var badge =
        st.indexOf('باقي') >= 0
          ? 'bg-amber-100 text-amber-800 border-amber-200'
          : st.indexOf('غير موجود') >= 0 || st.indexOf('سحب ادارة') >= 0 || st.indexOf('خطأ') >= 0
            ? 'bg-red-100 text-red-700 border-red-200'
            : 'bg-emerald-100 text-emerald-800 border-emerald-200';

      body.innerHTML +=
        '<tr class="hover:bg-slate-50/80">' +
        '<td class="px-3 py-2.5 text-slate-800 tabular-nums">' + escapeHtml(r.id || '') + '</td>' +
        '<td class="px-3 py-2.5"><span class="inline-block rounded-full border px-2 py-0.5 text-[11px] font-bold ' + badge + '">' + escapeHtml(st) + '</span></td>' +
        '<td class="px-3 py-2.5 font-bold text-slate-800 tabular-nums" data-raw="' + (r.amount || 0) + '">' + formatMoneyUI(r.amount) + '</td>' +
        '</tr>';
    });

    var elA = document.getElementById('maSumAdded');
    var elD = document.getElementById('maSumDeducted');
    var elB = document.getElementById('maSumDebt');
    if (elA) elA.textContent = formatMoneyUI(totalAdded);
    if (elD) elD.textContent = formatMoneyUI(copyStats.totalDeducted);
    if (elB) elB.textContent = formatMoneyUI(copyStats.totalDebt);

    var debtBox = document.getElementById('maDebtUsersContainer');
    var debtList = document.getElementById('maDebtUsersList');
    if (debtBox && debtList) {
      if (debtUsers.length) {
        debtBox.classList.remove('hidden');
        debtList.textContent = debtUsers.join(' ، ');
      } else {
        debtBox.classList.add('hidden');
      }
    }

    openResultsModal();
  }

  async function runSheetSingle() {
    var conf = sheetConfig();
    var type = (document.getElementById('maSheetType') || {}).value || 'اضافة';
    var amountVal = (document.getElementById('maSheetAmount') || {}).value;
    var userId = ((document.getElementById('maSheetUserId') || {}).value || '').trim();
    if (type === 'خصم كامل') amountVal = 'خصم كامل';
    if (!conf.spreadsheetId || !conf.sheetName || !userId || !amountVal) {
      alert('الرجاء إكمال البيانات');
      return;
    }
    toggleLoader(true, 'maSheetSingleBtn');
    try {
      var res = await fetch('/api/member-adjustments/sheet-apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          spreadsheetId: conf.spreadsheetId,
          sheetName: conf.sheetName,
          idCol: conf.idCol,
          salaryCol: conf.salaryCol,
          statusCol: conf.statusCol,
          userId: userId,
          type: type,
          amount: amountVal,
          alsoRegisterErp: !!(document.getElementById('maAlsoErp') || {}).checked,
          cycleId: cycleId && cycleId.value ? parseInt(cycleId.value, 10) : null,
        }),
      });
      var data = await res.json();
      if (!data.success) {
        alert(data.message || 'فشل');
      } else {
        renderResults(data.results || [], data.summary);
        var uid = document.getElementById('maSheetUserId');
        if (uid) uid.value = '';
      }
    } catch (e) {
      alert('حدث خطأ: ' + (e.message || e));
    }
    toggleLoader(false, 'maSheetSingleBtn');
  }

  async function runSheetBulk() {
    var conf = sheetConfig();
    var bulkData = (document.getElementById('maBulkData') || {}).value || '';
    if (!conf.spreadsheetId || !conf.sheetName || !bulkData.trim()) {
      alert('الرجاء إكمال البيانات');
      return;
    }
    toggleLoader(true, 'maSheetBulkBtn');
    try {
      var res = await fetch('/api/member-adjustments/sheet-bulk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          spreadsheetId: conf.spreadsheetId,
          sheetName: conf.sheetName,
          idCol: conf.idCol,
          salaryCol: conf.salaryCol,
          statusCol: conf.statusCol,
          bulkData: bulkData,
        }),
      });
      var data = await res.json();
      if (!data.success) alert(data.message || 'فشل');
      else renderResults(data.results || [], data.summary);
    } catch (e) {
      alert('حدث خطأ: ' + (e.message || e));
    }
    toggleLoader(false, 'maSheetBulkBtn');
  }

  function copyTableToSheet() {
    var text = 'رقم المستخدم\tالحالة\tالمبلغ\n';
    var rows = document.querySelectorAll('#maResultsBody tr');
    rows.forEach(function (row) {
      var cols = row.querySelectorAll('td');
      var rowText = [];
      cols.forEach(function (col, index) {
        if (index === 2) rowText.push(formatMoneyCopy(col.getAttribute('data-raw')));
        else rowText.push(col.innerText.trim());
      });
      text += rowText.join('\t') + '\n';
    });
    text += '\n';
    text += 'مجموع المتبقي (لنا عليهم)\t\t' + formatMoneyCopy(copyStats.totalDebt) + '\n';
    text += 'مجموع تم خصم\t\t' + formatMoneyCopy(copyStats.totalDeducted) + '\n';
    text += 'مجموع غير موجود\t\t' + formatMoneyCopy(copyStats.totalNotFound) + '\n';
    navigator.clipboard.writeText(text).then(function () {
      alert('تم نسخ الجدول والملخص بنجاح! الصقه الآن في الإكسل.');
    }).catch(function () {
      alert('تعذّر النسخ — انسخ يدوياً من الجدول');
    });
  }

  try { initColumnSelects(); } catch (e) { console.error('initColumnSelects', e); }
  try { initSheetModes(); } catch (e) { console.error('initSheetModes', e); }
  try { initKindPills(); } catch (e) { console.error('initKindPills', e); }
  loadCycles();
  loadSpreadsheets();

  var ssEl = document.getElementById('maSsId');
  if (ssEl) ssEl.addEventListener('change', loadSheetsForSelected);

  var singleBtn = document.getElementById('maSheetSingleBtn');
  if (singleBtn) singleBtn.addEventListener('click', runSheetSingle);
  var bulkBtn = document.getElementById('maSheetBulkBtn');
  if (bulkBtn) bulkBtn.addEventListener('click', runSheetBulk);
  var copyBtn = document.getElementById('maCopyResultsBtn');
  if (copyBtn) copyBtn.addEventListener('click', copyTableToSheet);

  document.querySelectorAll('[data-ma-results-close]').forEach(function (el) {
    el.addEventListener('click', closeResultsModal);
  });
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') closeResultsModal();
  });

  var typeEl = document.getElementById('maSheetType');
  var amountEl = document.getElementById('maSheetAmount');
  if (typeEl && amountEl) {
    typeEl.addEventListener('change', function () {
      var full = typeEl.value === 'خصم كامل';
      amountEl.disabled = full;
      if (full) amountEl.value = '';
    });
  }

  const syncSheet = document.getElementById('maSyncSheet');
  const uiUidCol = document.getElementById('maUiUidCol');
  const uiSalCol = document.getElementById('maUiSalCol');
  const btn = document.getElementById('maSubmitBtn');
  const pasteBtn = document.getElementById('maPasteBtn');

  if (pasteBtn) {
    pasteBtn.addEventListener('click', async () => {
      try {
        const t = await navigator.clipboard.readText();
        if (memberId) memberId.value = (t || '').trim();
      } catch (_) {}
    });
  }

  if (btn) {
    btn.addEventListener('click', async () => {
      setMaMessage('', 'neutral');
      const cidRaw = cycleId ? (cycleId.value || '').trim() : '';
      const cid = cidRaw ? parseInt(cidRaw, 10) : null;
      const body = {
        memberUserId: (memberId.value || '').trim(),
        kind: kind.value,
        amount: parseFloat(amount.value),
        notes: (notes.value || '').trim() || null,
        cycleId: cid && !Number.isNaN(cid) ? cid : null,
        syncUserInfoSheet: syncSheet ? syncSheet.checked : true,
        userInfoUserIdCol: (uiUidCol && uiUidCol.value ? uiUidCol.value : 'C').trim() || 'C',
        userInfoSalaryCol: (uiSalCol && uiSalCol.value ? uiSalCol.value : 'L').trim() || 'L',
      };
      if (!body.memberUserId) {
        setMaMessage('أدخل رقم المستخدم', 'error');
        return;
      }
      if (!(body.amount > 0)) {
        setMaMessage('أدخل مبلغاً صالحاً', 'error');
        return;
      }
      btn.disabled = true;
      try {
        const res = await fetch('/api/member-adjustments/apply', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify(body),
        });
        let data;
        try {
          data = await res.json();
        } catch (_) {
          setMaMessage('استجابة غير صالحة من الخادم (' + res.status + ')', 'error');
          btn.disabled = false;
          return;
        }
        if (res.status === 401) {
          setMaMessage('انتهت الجلسة — أعد تسجيل الدخول', 'error');
          btn.disabled = false;
          return;
        }
        if (data.success) {
          setMaMessage(data.message || 'تم', 'success');
          amount.value = '';
        } else {
          setMaMessage(data.message || 'فشل', 'error');
        }
      } catch (e) {
        setMaMessage(e.message || 'فشل', 'error');
      }
      btn.disabled = false;
    });
  }
})();
