(function () {
  'use strict';

  var memberSearchTimer = null;
  var qrPollTimer = null;
  var qrPollDeadline = 0;
  var lastOtpCode = '';
  var allLogEntries = [];

  function toast(msg, type) {
    if (typeof window.showToast === 'function') window.showToast(msg, type || 'success');
  }

  function esc(s) {
    var d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  function phoneFromJid(jid) {
    if (!jid) return '—';
    return String(jid).split('@')[0].split(':')[0] || String(jid);
  }

  function fmtDate(v) {
    if (!v) return '—';
    try {
      return new Date(v).toLocaleString('ar');
    } catch (_) {
      return String(v);
    }
  }

  function setBadge(el, text, tone) {
    if (!el) return;
    var tones = {
      ok: 'bg-emerald-50 text-emerald-700',
      warn: 'bg-amber-50 text-amber-700',
      bad: 'bg-rose-50 text-rose-700',
      mute: 'bg-slate-100 text-slate-600',
      info: 'bg-sky-50 text-sky-700',
    };
    el.className =
      'mt-2 inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-sm font-semibold ' +
      (tones[tone] || tones.mute);
    el.textContent = text;
  }

  function openModal(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.classList.remove('hidden');
    el.classList.add('flex');
    el.setAttribute('aria-hidden', 'false');
  }

  function closeModal(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.classList.add('hidden');
    el.classList.remove('flex');
    el.setAttribute('aria-hidden', 'true');
  }

  function stopQrPoll() {
    if (qrPollTimer) {
      clearInterval(qrPollTimer);
      qrPollTimer = null;
    }
  }

  async function loadStatus() {
    var msg = document.getElementById('waConnMessage');
    var data = await apiCall('/api/whatsapp/status');
    if (!data || !data.success) {
      if (msg) msg.textContent = (data && data.message) || 'تعذّر جلب الحالة';
      setBadge(document.getElementById('waConfiguredBadge'), 'خطأ', 'bad');
      return;
    }

    setBadge(
      document.getElementById('waConfiguredBadge'),
      data.configured ? 'مضبوط' : 'غير مضبوط',
      data.configured ? 'ok' : 'bad'
    );
    setBadge(
      document.getElementById('waWebhookBadge'),
      data.webhookSecretSet ? 'مضبوط' : 'غير مضبوط',
      data.webhookSecretSet ? 'ok' : 'warn'
    );

    var nameEl = document.getElementById('waInstanceName');
    var instanceName = (data.config && data.config.instance_name) || '';
    if (nameEl) nameEl.textContent = instanceName || '—';
    var input = document.getElementById('waInstanceInput');
    if (input && !input.value && instanceName) input.value = instanceName;

    var state = data.connectionState || (instanceName ? 'unknown' : 'none');
    var stateMap = {
      open: { t: 'متصل (open)', tone: 'ok' },
      connecting: { t: 'جاري الاتصال…', tone: 'warn' },
      close: { t: 'مغلق (close)', tone: 'bad' },
      closed: { t: 'مغلق', tone: 'bad' },
      none: { t: 'لا توجد نسخة', tone: 'mute' },
      unknown: { t: 'غير معروف', tone: 'mute' },
    };
    var st = stateMap[state] || { t: String(state), tone: 'mute' };
    setBadge(document.getElementById('waConnectionBadge'), st.t, st.tone);

    if (msg) {
      if (!data.configured) msg.textContent = data.message || 'اضبط EVOLUTION_API_KEY في ملف البيئة';
      else if (!data.webhookSecretSet) msg.textContent = 'اضبط WHATSAPP_WEBHOOK_SECRET في ملف البيئة';
      else msg.textContent = '';
    }
  }

  async function createInstance() {
    var input = document.getElementById('waInstanceInput');
    var name = ((input && input.value) || '').trim();
    if (!/^[a-zA-Z0-9_-]{3,50}$/.test(name)) {
      toast('اسم النسخة يجب أن يكون حروفاً لاتينية وأرقاماً (3-50)', 'error');
      return;
    }
    var btn = document.getElementById('waCreateInstanceBtn');
    if (btn) btn.disabled = true;
    var res = await apiCall('/api/whatsapp/instance', {
      method: 'POST',
      body: JSON.stringify({ instanceName: name }),
    });
    if (btn) btn.disabled = false;
    toast(res.message || (res.success ? (res.alreadyExisted ? 'النسخة موجودة — تم ضبط الـ webhook' : 'تم إنشاء النسخة') : 'فشل'), res.success ? 'success' : 'error');
    if (res.success) loadStatus();
  }

  function resetQrModalUi() {
    var loading = document.getElementById('waQrLoading');
    var wrap = document.getElementById('waQrImageWrap');
    var err = document.getElementById('waQrError');
    var pairing = document.getElementById('waQrPairing');
    if (loading) loading.classList.remove('hidden');
    if (wrap) wrap.classList.add('hidden');
    if (err) {
      err.classList.add('hidden');
      err.textContent = '';
    }
    if (pairing) {
      pairing.classList.add('hidden');
      pairing.textContent = '';
    }
  }

  function showQrResult(data) {
    var loading = document.getElementById('waQrLoading');
    var wrap = document.getElementById('waQrImageWrap');
    var img = document.getElementById('waQrImage');
    var pairing = document.getElementById('waQrPairing');
    var err = document.getElementById('waQrError');
    if (loading) loading.classList.add('hidden');

    var qr = data && data.qrcode;
    if (qr) {
      if (img) {
        img.src = qr.indexOf('data:') === 0 ? qr : 'data:image/png;base64,' + qr;
      }
      if (wrap) wrap.classList.remove('hidden');
      if (pairing && data.pairingCode) {
        pairing.textContent = 'رمز الاقتران: ' + data.pairingCode;
        pairing.classList.remove('hidden');
      }
      if (err) err.classList.add('hidden');
      return true;
    }
    return false;
  }

  async function pollQrOnce() {
    var data = await apiCall('/api/whatsapp/qr');
    if (!data || !data.success) {
      stopQrPoll();
      var loading = document.getElementById('waQrLoading');
      var err = document.getElementById('waQrError');
      if (loading) loading.classList.add('hidden');
      if (err) {
        err.textContent = (data && data.message) || 'فشل جلب رمز QR';
        err.classList.remove('hidden');
      }
      return;
    }
    if (showQrResult(data)) {
      stopQrPoll();
      return;
    }
    if (Date.now() >= qrPollDeadline) {
      stopQrPoll();
      var loading2 = document.getElementById('waQrLoading');
      var err2 = document.getElementById('waQrError');
      if (loading2) loading2.classList.add('hidden');
      if (err2) {
        err2.textContent = 'انتهت مهلة انتظار الرمز. أعد المحاولة.';
        err2.classList.remove('hidden');
      }
    }
  }

  function showQr() {
    stopQrPoll();
    resetQrModalUi();
    openModal('waQrModal');
    qrPollDeadline = Date.now() + 60000;
    pollQrOnce();
    qrPollTimer = setInterval(pollQrOnce, 2000);
  }

  async function logoutInstance() {
    if (!window.confirm('قطع اتصال واتساب لهذه النسخة؟')) return;
    var res = await apiCall('/api/whatsapp/logout', { method: 'POST', body: '{}' });
    toast(res.message || (res.success ? 'تم قطع الاتصال' : 'فشل'), res.success ? 'success' : 'error');
    if (res.success) loadStatus();
  }

  function statusBadgeHtml(status) {
    var map = {
      pending: 'bg-amber-50 text-amber-700',
      verified: 'bg-emerald-50 text-emerald-700',
      blocked: 'bg-rose-50 text-rose-700',
    };
    var labels = { pending: 'بانتظار التفعيل', verified: 'مُفعّل', blocked: 'محظور' };
    var cls = map[status] || 'bg-slate-100 text-slate-600';
    return (
      '<span class="inline-flex rounded-lg px-2 py-0.5 text-xs font-semibold ' +
      cls +
      '">' +
      esc(labels[status] || status || '—') +
      '</span>'
    );
  }

  function subjectLabel(type) {
    return type === 'operator' ? 'مشغّل' : 'عضو';
  }

  function subjectRef(row) {
    if (row.subject_type === 'operator') return row.operator_user_id != null ? String(row.operator_user_id) : '—';
    return row.member_user_id || '—';
  }

  async function loadIdentities() {
    var tbody = document.getElementById('waIdentitiesBody');
    if (!tbody) return;
    var data = await apiCall('/api/whatsapp/identities');
    if (!data || !data.success) {
      tbody.innerHTML =
        '<tr><td colspan="7" class="px-3 py-10 text-center text-sm text-rose-600">' +
        esc((data && data.message) || 'فشل التحميل') +
        '</td></tr>';
      return;
    }
    var rows = data.identities || [];
    if (!rows.length) {
      tbody.innerHTML =
        '<tr><td colspan="7" class="px-3 py-12 text-center text-sm text-slate-500">لا توجد هويات مربوطة بعد</td></tr>';
      return;
    }
    tbody.innerHTML = rows
      .map(function (r) {
        var id = r.id;
        var blockBtn =
          r.status === 'blocked'
            ? '<button type="button" data-wa-act="status" data-id="' +
              id +
              '" data-status="verified" class="rounded-lg px-2 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-50">تفعيل</button>'
            : '<button type="button" data-wa-act="status" data-id="' +
              id +
              '" data-status="blocked" class="rounded-lg px-2 py-1 text-xs font-semibold text-rose-700 hover:bg-rose-50">حظر</button>';
        return (
          '<tr class="transition-colors hover:bg-emerald-50/30">' +
          '<td class="py-3.5 pl-2 pr-2 font-mono text-xs tabular-nums text-slate-900 sm:pl-4" dir="ltr">' +
          esc(phoneFromJid(r.phone_jid)) +
          '</td>' +
          '<td class="px-2 py-3.5 sm:px-4">' +
          esc(r.display_name || '—') +
          '</td>' +
          '<td class="px-2 py-3.5 sm:px-4">' +
          esc(subjectLabel(r.subject_type)) +
          '</td>' +
          '<td class="px-2 py-3.5 font-mono text-xs sm:px-4" dir="ltr">' +
          esc(subjectRef(r)) +
          '</td>' +
          '<td class="px-2 py-3.5 sm:px-4">' +
          statusBadgeHtml(r.status) +
          '</td>' +
          '<td class="px-2 py-3.5 text-xs text-slate-500 sm:px-4">' +
          esc(fmtDate(r.last_seen_at)) +
          '</td>' +
          '<td class="py-3.5 pl-4 pr-2">' +
          '<div class="flex flex-wrap items-center gap-1">' +
          '<button type="button" data-wa-act="otp" data-id="' +
          id +
          '" class="rounded-lg px-2 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-50">إصدار رمز</button>' +
          blockBtn +
          '<button type="button" data-wa-act="delete" data-id="' +
          id +
          '" class="rounded-lg px-2 py-1 text-xs font-semibold text-slate-600 hover:bg-slate-100">حذف</button>' +
          '</div></td></tr>'
        );
      })
      .join('');
  }

  function toggleSubjectFields() {
    var type = (document.getElementById('waSubjectType') || {}).value || 'member';
    var memberField = document.getElementById('waMemberField');
    var operatorField = document.getElementById('waOperatorField');
    if (type === 'operator') {
      if (memberField) memberField.classList.add('hidden');
      if (operatorField) operatorField.classList.remove('hidden');
    } else {
      if (memberField) memberField.classList.remove('hidden');
      if (operatorField) operatorField.classList.add('hidden');
    }
  }

  async function loadOperators() {
    var select = document.getElementById('waOperatorSelect');
    if (!select) return;
    var data = await apiCall('/api/whatsapp/operators');
    var rows = (data && data.operators) || [];
    select.innerHTML = '<option value="">— اختر مستخدماً —</option>';
    rows.forEach(function (u) {
      var label = (u.display_name || u.username || 'مستخدم') + ' (#' + u.id + ')';
      select.innerHTML +=
        '<option value="' +
        esc(String(u.id)) +
        '">' +
        esc(label) +
        (u.role === 'admin' ? ' — مدير' : '') +
        '</option>';
    });
  }

  async function searchMembers(q) {
    var box = document.getElementById('waMemberResults');
    if (!box) return;
    var params = new URLSearchParams({ page: '1', pageSize: '20', q: q || '' });
    var res = await fetch('/api/member-directory/list?' + params.toString(), { credentials: 'same-origin' });
    if (res.status === 401) {
      box.innerHTML = '<p class="px-3 py-2 text-rose-600">انتهت الجلسة</p>';
      box.classList.remove('hidden');
      return;
    }
    var data = await res.json();
    var rows = (data && data.rows) || [];
    if (!rows.length) {
      box.innerHTML = '<p class="px-3 py-2 text-slate-500">لا نتائج</p>';
      box.classList.remove('hidden');
      return;
    }
    box.innerHTML = rows
      .map(function (r) {
        var name = r.display_name || r.last_seen_name || '—';
        return (
          '<button type="button" data-member-id="' +
          esc(String(r.member_user_id)) +
          '" data-member-name="' +
          esc(name) +
          '" class="wa-member-pick block w-full px-3 py-2 text-right transition hover:bg-emerald-50">' +
          '<span class="font-mono text-xs text-slate-500" dir="ltr">' +
          esc(r.member_user_id) +
          '</span> — ' +
          esc(name) +
          '</button>'
        );
      })
      .join('');
    box.classList.remove('hidden');
  }

  function selectMember(id, name) {
    var hidden = document.getElementById('waMemberUserId');
    var selected = document.getElementById('waMemberSelected');
    var box = document.getElementById('waMemberResults');
    var search = document.getElementById('waMemberSearch');
    if (hidden) hidden.value = id || '';
    if (selected) {
      selected.textContent = id ? 'المحدد: ' + name + ' (#' + id + ')' : '';
      selected.classList.toggle('hidden', !id);
    }
    if (search && name) search.value = id + ' — ' + name;
    if (box) box.classList.add('hidden');
  }

  async function submitLink(e) {
    e.preventDefault();
    var phone = ((document.getElementById('waPhoneInput') || {}).value || '').trim();
    var displayName = ((document.getElementById('waDisplayNameInput') || {}).value || '').trim();
    var subjectType = (document.getElementById('waSubjectType') || {}).value || 'member';
    var body = { phone: phone, subjectType: subjectType, displayName: displayName || undefined };

    if (subjectType === 'member') {
      var mid = ((document.getElementById('waMemberUserId') || {}).value || '').trim();
      if (!mid) {
        toast('اختر عضواً من نتائج البحث', 'error');
        return;
      }
      body.memberUserId = mid;
    } else {
      var oid = ((document.getElementById('waOperatorSelect') || {}).value || '').trim();
      if (!oid) {
        toast('اختر مشغّلاً', 'error');
        return;
      }
      body.operatorUserId = parseInt(oid, 10);
    }

    var btn = document.getElementById('waLinkSubmitBtn');
    if (btn) btn.disabled = true;
    var res = await apiCall('/api/whatsapp/identities', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    if (btn) btn.disabled = false;
    toast(res.message || (res.success ? (res.relinked ? 'تم إعادة الربط — بانتظار التفعيل' : 'تم الربط — بانتظار التفعيل') : 'فشل'), res.success ? 'success' : 'error');
    if (res.success) {
      document.getElementById('waLinkForm').reset();
      selectMember('', '');
      toggleSubjectFields();
      loadIdentities();
    }
  }

  async function issueCode(id) {
    var res = await apiCall('/api/whatsapp/identities/' + encodeURIComponent(id) + '/issue-code', {
      method: 'POST',
      body: '{}',
    });
    if (!res || !res.success) {
      toast((res && res.message) || 'فشل إصدار الرمز', 'error');
      return;
    }
    lastOtpCode = String(res.code || '');
    var codeEl = document.getElementById('waOtpCode');
    var ttlEl = document.getElementById('waOtpTtl');
    if (codeEl) codeEl.textContent = lastOtpCode;
    if (ttlEl) ttlEl.textContent = 'صالح لمدة ' + (res.ttlMinutes || 10) + ' دقيقة';
    openModal('waOtpModal');
  }

  async function setStatus(id, status) {
    var label = status === 'blocked' ? 'حظر' : 'تفعيل';
    if (!window.confirm(label + ' هذه الهوية؟')) return;
    var res = await apiCall('/api/whatsapp/identities/' + encodeURIComponent(id) + '/status', {
      method: 'PATCH',
      body: JSON.stringify({ status: status }),
    });
    toast(res.message || (res.success ? 'تم التحديث' : 'فشل'), res.success ? 'success' : 'error');
    if (res.success) loadIdentities();
  }

  async function deleteIdentity(id) {
    if (!window.confirm('حذف هذه الهوية نهائياً؟')) return;
    var res = await apiCall('/api/whatsapp/identities/' + encodeURIComponent(id), { method: 'DELETE' });
    toast(res.message || (res.success ? 'تم الحذف' : 'فشل'), res.success ? 'success' : 'error');
    if (res.success) loadIdentities();
  }

  function fillActionFilter(entries) {
    var select = document.getElementById('waLogActionFilter');
    if (!select) return;
    var current = select.value;
    var actions = {};
    entries.forEach(function (e) {
      if (e.action) actions[e.action] = true;
    });
    var keys = Object.keys(actions).sort();
    select.innerHTML = '<option value="">الكل</option>';
    keys.forEach(function (a) {
      select.innerHTML += '<option value="' + esc(a) + '">' + esc(a) + '</option>';
    });
    if (current && actions[current]) select.value = current;
  }

  function renderLog() {
    var tbody = document.getElementById('waLogBody');
    var filter = ((document.getElementById('waLogActionFilter') || {}).value || '').trim();
    if (!tbody) return;
    var rows = allLogEntries.filter(function (e) {
      return !filter || e.action === filter;
    });
    if (!rows.length) {
      tbody.innerHTML =
        '<tr><td colspan="6" class="px-3 py-12 text-center text-sm text-slate-500">لا سجلات</td></tr>';
      return;
    }
    tbody.innerHTML = rows
      .map(function (e) {
        var denied = e.allowed === false;
        var rowCls = denied ? 'bg-rose-50/80 text-rose-900' : 'hover:bg-slate-50';
        return (
          '<tr class="' +
          rowCls +
          '">' +
          '<td class="py-3 pl-2 pr-2 text-xs sm:pl-4">' +
          esc(fmtDate(e.created_at)) +
          '</td>' +
          '<td class="px-2 py-3 font-mono text-xs" dir="ltr">' +
          esc(phoneFromJid(e.phone_jid)) +
          '</td>' +
          '<td class="px-2 py-3 text-xs font-medium">' +
          esc(e.action || '—') +
          '</td>' +
          '<td class="px-2 py-3 font-mono text-xs" dir="ltr">' +
          esc(e.tool || '—') +
          '</td>' +
          '<td class="max-w-[18rem] truncate px-2 py-3 text-xs" title="' +
          esc(e.detail || '') +
          '">' +
          esc(e.detail || '—') +
          '</td>' +
          '<td class="py-3 pl-4 pr-2 text-xs font-semibold">' +
          (denied ? 'مرفوض' : 'مسموح') +
          '</td></tr>'
        );
      })
      .join('');
  }

  async function loadLog() {
    var data = await apiCall('/api/whatsapp/access-log?limit=100');
    if (!data || !data.success) {
      allLogEntries = [];
      var tbody = document.getElementById('waLogBody');
      if (tbody) {
        tbody.innerHTML =
          '<tr><td colspan="6" class="px-3 py-10 text-center text-sm text-rose-600">' +
          esc((data && data.message) || 'فشل التحميل') +
          '</td></tr>';
      }
      return;
    }
    allLogEntries = data.entries || [];
    fillActionFilter(allLogEntries);
    renderLog();
  }

  async function copyOtp() {
    if (!lastOtpCode) return;
    try {
      await navigator.clipboard.writeText(lastOtpCode);
      toast('تم نسخ الرمز', 'success');
    } catch (_) {
      toast('تعذّر النسخ — انسخه يدوياً', 'error');
    }
  }

  function bind() {
    var refreshStatus = document.getElementById('waRefreshStatusBtn');
    if (refreshStatus) refreshStatus.addEventListener('click', loadStatus);

    var createBtn = document.getElementById('waCreateInstanceBtn');
    if (createBtn) createBtn.addEventListener('click', createInstance);

    var qrBtn = document.getElementById('waShowQrBtn');
    if (qrBtn) qrBtn.addEventListener('click', showQr);

    var logoutBtn = document.getElementById('waLogoutBtn');
    if (logoutBtn) logoutBtn.addEventListener('click', logoutInstance);

    var qrClose = document.getElementById('waQrCloseBtn');
    if (qrClose) {
      qrClose.addEventListener('click', function () {
        stopQrPoll();
        closeModal('waQrModal');
      });
    }
    var qrModal = document.getElementById('waQrModal');
    if (qrModal) {
      qrModal.addEventListener('click', function (ev) {
        if (ev.target === qrModal) {
          stopQrPoll();
          closeModal('waQrModal');
        }
      });
    }

    var otpClose = document.getElementById('waOtpCloseBtn');
    if (otpClose) otpClose.addEventListener('click', function () { closeModal('waOtpModal'); });
    var otpModal = document.getElementById('waOtpModal');
    if (otpModal) {
      otpModal.addEventListener('click', function (ev) {
        if (ev.target === otpModal) closeModal('waOtpModal');
      });
    }
    var otpCopy = document.getElementById('waOtpCopyBtn');
    if (otpCopy) otpCopy.addEventListener('click', copyOtp);

    var refreshIdent = document.getElementById('waRefreshIdentitiesBtn');
    if (refreshIdent) refreshIdent.addEventListener('click', loadIdentities);

    var subject = document.getElementById('waSubjectType');
    if (subject) subject.addEventListener('change', toggleSubjectFields);

    var memberSearch = document.getElementById('waMemberSearch');
    if (memberSearch) {
      memberSearch.addEventListener('input', function () {
        clearTimeout(memberSearchTimer);
        var q = (memberSearch.value || '').trim();
        document.getElementById('waMemberUserId').value = '';
        document.getElementById('waMemberSelected').classList.add('hidden');
        if (q.length < 1) {
          document.getElementById('waMemberResults').classList.add('hidden');
          return;
        }
        memberSearchTimer = setTimeout(function () { searchMembers(q); }, 320);
      });
    }

    var memberResults = document.getElementById('waMemberResults');
    if (memberResults) {
      memberResults.addEventListener('click', function (ev) {
        var btn = ev.target.closest('[data-member-id]');
        if (!btn) return;
        selectMember(btn.getAttribute('data-member-id'), btn.getAttribute('data-member-name'));
      });
    }

    var linkForm = document.getElementById('waLinkForm');
    if (linkForm) linkForm.addEventListener('submit', submitLink);

    var identBody = document.getElementById('waIdentitiesBody');
    if (identBody) {
      identBody.addEventListener('click', function (ev) {
        var btn = ev.target.closest('[data-wa-act]');
        if (!btn) return;
        var act = btn.getAttribute('data-wa-act');
        var id = btn.getAttribute('data-id');
        if (act === 'otp') issueCode(id);
        else if (act === 'status') setStatus(id, btn.getAttribute('data-status'));
        else if (act === 'delete') deleteIdentity(id);
      });
    }

    var refreshLog = document.getElementById('waRefreshLogBtn');
    if (refreshLog) refreshLog.addEventListener('click', loadLog);
    var logFilter = document.getElementById('waLogActionFilter');
    if (logFilter) logFilter.addEventListener('change', renderLog);
  }

  document.addEventListener('DOMContentLoaded', function () {
    bind();
    toggleSubjectFields();
    loadStatus();
    loadOperators();
    loadIdentities();
    loadLog();
  });
})();
