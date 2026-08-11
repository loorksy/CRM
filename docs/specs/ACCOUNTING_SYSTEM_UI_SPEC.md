# LorkERP — Ultra-Detailed UI Specification

> Reverse-engineered from EJS partials + `public/js/*` + dashboard shell.  
> All copy below is Arabic as in the product. Status tags: VERIFIED | PARTIALLY IMPLEMENTED | UI ONLY.

---

## Global Shell

### PAGE: Application Shell
- **ROUTE:** All authenticated pages render `views/dashboard.ejs` with a `page` key selecting a partial.
- **PURPOSE:** Provide RTL app chrome: sidebar, mobile header, bottom nav, FAB, toast.
- **LAYOUT:**
  - `html lang="ar" dir="rtl"`
  - Mobile top bar 60px: menu toggle, title, user avatar button
  - Sidebar `#sidebar` width 260px, background `#1a1a2e`, brand **LorkERP**, user display name, `sidebar-nav`, footer «LorkERP v1.0»
  - Main `#mainContent` loads page partial
  - Toast container `#toastContainer` z-index 10070
  - Mobile bottom nav 5 columns
  - Desktop FAB + mobile FAB radial
  - Quick action backdrop + cascade overlay
- **SIDEBAR GROUPS:**
  1. لوحة التحكم
  2. عمليات وإدخال (details): Sheet, تدقيق الرواتب (+ مسودة badge), البحث, الوكالات الفرعية, بيانات المستخدمين, إضافات وخصومات, وساطة إدارية, وكيل واتساب
  3. ترتيب الرسائل, فرق التصريف, شركات التحويل, الصناديق, الاعتمادات, الشحن
  4. المالية والالتزامات (details): الديون, دين علينا, دين لنا, مصادر الربح, مطلوب دفع, سجل الحركات, المصاريف
  5. الإعدادات, تسجيل الخروج
- **MOBILE BOTTOM NAV:** الرئيسية `/dashboard` · تدقيق راتب `/payroll` · FAB · المستخدمين `/member-directory` · إضافات `/member-adjustments`
- **FAB:** Opens radial with **صادر** (`qa-out`) and **وارد** (`qa-in`). Cascade menus from `quick-actions.js`.
- **LOADING/ERROR:** Toasts via `showToast(message, type)`.
- **PERMISSIONS:** Any authenticated user; WhatsApp API admin-only.
- **RESPONSIVE:** Sidebar overlay on mobile; collapse toggle on `lg`; FAB centered on large screens.

### FAB Quick Actions (not a page)

**صادر menu items:**
| Label | Result |
|-------|--------|
| شحن | Navigate `/shipping?fab=out` |
| وكالة فرعية (مكافأة) | Inline form → POST `/api/sub-agencies/:id/reward` |
| شركة تحويل | Form → POST `/api/transfer-companies/:id/payout-from-main` |
| صندوق | Form → POST `/api/funds/:id/receive-from-main` |
| مصروف | Form → POST `/api/expenses/add` |
| مطلوب دفع (تسليم) | Navigate `/payment-due` |
| رابط مرتجع | Navigate `/transfer-companies` |

**وارد menu items:**
| Label | Result |
|-------|--------|
| شحن (شراء + تبديل راتب) | `/shipping?fab=in&qaFocus=swap` |
| دين | `/debts` |
| اعتماد | Form → accreditations APIs |
| فرق تصريف | Form → POST `/api/fx-spread/add` |
| دين لنا | `/receivables-to-us` |
| خصم وكالة | `/sub-agencies` |

Context fetch: GET `/api/quick-action/context` (may warn cash blocked).

---

## PAGE: Login
- **ROUTE:** `/login`
- **PURPOSE:** Authenticate operator.
- **LAYOUT:** Centered card on gradient navy background; logo icon; brand **LorkERP**; subtitle «نظام المحاسبة المتكامل».
- **FORM:**
  | Field | Type | Required | Placeholder |
  |-------|------|----------|-------------|
  | username | text | yes | اسم المستخدم |
  | password | password | yes | كلمة المرور |
- **BUTTONS:**
  | Text | Action |
  |------|--------|
  | تسجيل الدخول | POST `/login` |
  | Eye icon | Toggle password visibility |
- **ERROR STATES:** Banner «اسم المستخدم أو كلمة المرور غير صحيحة» or «حدث خطأ. حاول مرة أخرى.»
- **SUCCESS:** Redirect `/dashboard`
- **STATUS:** VERIFIED

---

## PAGE: Home (لوحة التحكم)
- **ROUTE:** `/dashboard`
- **PAGE KEY:** `home`
- **PURPOSE:** KPI overview, cycle filter, PDF report download, fund sources modal.
- **LAYOUT:** Toolbar (cycle select + report type + تحديث + تنزيل) → KPI card grid → Sheets status pill → optional fund modal.
- **FILTERS:** `#homeCycleSelect` cycle list; `#homeReportType` report kinds (شامل, مطابقة, موحّد, حركات, اعتمادات, شركات تحويل, …).
- **CARDS / WIDGETS:**
  | Widget | Data source | Notes |
  |--------|-------------|-------|
  | الربح الصافي | `/dashboard/stats` `netProfit` | shippingProfit + ledgerNetProfit |
  | المصاريف | `totalExpensesLedger` | expense bucket |
  | إجمالي التزامات | `totalDebts` | debt breakdown |
  | دين لنا | `receivablesToUsTotal` | |
  | مطلوب دفع | `paymentDueTotal` | |
  | رصيد الصندوق الرئيسي | main fund USD | opens `#homeFundModal` |
  | رصيد المؤجل | deferred | links `/deferred-balance` |
  | رصيد الشحن | gold+crystal qty | |
- **BUTTONS:** تحديث (reload stats); تنزيل (PDF fetch); modal إغلاق.
- **MODAL:** `#homeFundModal` — مصادر الأموال والصناديق from `/dashboard/fund-sources`.
- **EMPTY/LOADING:** Sheets «جاري التحقق…» → متصل/غير متصل; metrics default 0.00.
- **STATUS:** VERIFIED

---

## PAGE: Sheet (الدورات المالية)
- **ROUTE:** `/sheet`
- **PAGE KEY:** `sheet`
- **PURPOSE:** Create/edit/delete/sync financial cycles.
- **LAYOUT:** Header + `#cyclesList` card grid + `#cyclesEmpty`.
- **BUTTONS:** إنشاء دورة جديدة; إنشاء أول دورة; per-card edit/sync/delete; modal حفظ / جلب البيانات / إلغاء; حذف نهائياً.
- **MODALS:** `#cycleModal` (tabs Google Sheets / رفع ملفات); `#deleteConfirmModal`.
- **FIELDS:** Cycle name; management/agent spreadsheet IDs & sheet names; file uploads; transfer discount % (if present in form panels).
- **EMPTY:** «لا توجد دورات محفوظة»
- **APIs:** `/api/sheet/cycles` CRUD, import-google, upload, spreadsheets-list.
- **STATUS:** VERIFIED

---

## PAGE: Payroll Audit (تدقيق الرواتب)
- **ROUTE:** `/payroll` (legacy `/payroll-google` → 301)
- **PAGE KEY:** `payroll-google`
- **PURPOSE:** Configure columns/colors/discount; run payroll audit; close/reopen session.
- **BUTTONS:** تحميل الدورة; تدقيق; إغلاق التدقيق; إعادة فتح الجلسة; حفظ الإعدادات.
- **FIELDS:** Cycle select; spreadsheet/sheet; column letters (رقم المستخدم, الراتب, …); خصم %; agent/management colors.
- **EMPTY:** «لا توجد دورات…»; results placeholder «سيظهر هنا نتيجة التدقيق…»
- **LOADING:** Select options «— جاري التحميل... —»
- **APIs:** payroll-execute, payroll-settings, cycles sync/structure, audit-session close/reopen.
- **NAV BADGE:** مسودة from localStorage draft flag.
- **STATUS:** VERIFIED

---

## PAGE: Deferred Balance (رصيد المؤجل)
- **ROUTE:** `/deferred-balance`
- **PAGE KEY:** `deferred-balance`
- **PURPOSE:** List deferred salary lines; search member; refresh from Google; merge into cycle.
- **LAYOUT:** 3 action tiles + deferred list.
- **BUTTONS:** بحث عن مستخدم; الدورة والمزامنة; دمج وتدقيق; نسخ أرقام المستخدمين; modal عرض السجل / تحديث من Google / دمج وتدقيق.
- **MODALS:** `#deferredSearchModal`, `#deferredCycleModal`, `#deferredMergeModal`.
- **EMPTY:** «لا توجد أسطر مؤجلة…»; loading «جاري التحميل...»
- **APIs:** `/dashboard/deferred-users`, refresh-deferred, deferred-merge, `/api/search/member-deferred-history`.
- **STATUS:** VERIFIED (not in sidebar; linked from home)

---

## PAGE: Search (البحث)
- **ROUTE:** `/search`
- **PAGE KEY:** `search`
- **PURPOSE:** Find member status in one or all cycles; selective audit; export card image/CSV.
- **BUTTONS:** لصق; نسخ كبطاقة; تنزيل كصورة; تدقيق هذه الدورة; تنفيذ التدقيق الانتقائي.
- **FIELDS:** cycle; user id; خصم %; toggle البحث الشامل; target sheet / new sheet title.
- **APIs:** `/api/search/*` including execute-audit-advanced.
- **STATUS:** VERIFIED

---

## PAGE: Clients (بيانات العملاء) — UI ONLY
- **ROUTE:** `/clients`
- **TABS:** العملاء | خصومات وإضافات
- **CONTENT:** Placeholder «سيتم إضافة… قريباً»
- **API:** none
- **STATUS:** UI ONLY / UNCONNECTED

---

## PAGE: Client Portal (واجهة العملاء) — UI ONLY
- **ROUTE:** `/client-portal`
- **TABS:** تخصيص | الدعم | الرسائل | المشاكل — all placeholders
- **STATUS:** UI ONLY / UNCONNECTED

---

## PAGE: Messages (ترتيب الرسائل)
- **ROUTE:** `/messages`
- **PAGE KEY:** `messages`
- **PURPOSE:** Paste messages → AI extract table → export Sheets/CSV.
- **BUTTONS:** تحليل واستخراج; مسح; نسخ الجدول; نسخ CSV; تصدير إلى Google Sheets; modal تصدير/إلغاء.
- **MODAL:** `#exportSheetsModal`
- **LOADING:** Progress via Socket.IO job subscription.
- **APIs:** `/ai/analyze`, `/ai/analysis/:jobId`, `/ai/jobs`, `/sheets/export`
- **STATUS:** VERIFIED

---

## PAGE: Approvals List (ملف الاعتمادات)
- **ROUTE:** `/approvals`
- **PAGE KEY:** `approvals`
- **PURPOSE:** Manage accreditation entities cards; bulk balance import; delivery settle.
- **LAYOUT:** Toolbar → search `#accCardsSearch` → `#accCards` grid (drag reorder localStorage).
- **BUTTONS:** رفع أرصدة; تسليم; إضافة معتمد; bulk file/paste/sheet preview & حفظ واعتماد; confirm نعم/لا.
- **MODALS:** `#accBulkModal`, `#accDeliveryModal`, `#accConfirmModal`, `#accAddModal`, `#accListAddAmountModal`, `#accListTransferModal`, shared receivable offset modal.
- **FIELDS (add):** name, code.
- **FIELDS (amount/transfer):** amount kinds, brokerage %, discount %, cycle, fund/company, transfer mode, receivable offset modes (تأجيل / خصم كامل / مخصص).
- **LOADING:** «جاري التحميل...»
- **APIs:** `/api/accreditations/*`, funds list, transfer-companies list, cycles list, PDF.
- **STATUS:** VERIFIED

---

## PAGE: Approval Detail (ملف معتمد)
- **ROUTE:** `/approvals/:id`
- **PAGE KEY:** `approval-detail`
- **LAYOUT:** Prev/next → header with تثبيت → action buttons → balance cards (لنا / علينا / الصافي) → settlement panel → ledger table.
- **BUTTONS:** إضافة مبلغ; تحويل; PDF; تحديث; تنفيذ التسوية; متابعة اختيار خصم الدين; إزالة التذكير.
- **MODALS:** detail add-amount, transfer, receivable offset.
- **APIs:** GET/POST accreditation detail endpoints.
- **STATUS:** VERIFIED

---

## PAGE: Funds List
- **ROUTE:** `/funds`
- **BUTTONS:** تعديل الرئيسي; إضافة صندوق; حفظ/إلغاء in modals.
- **MODALS:** `#fundEditMainModal`, `#fundAddModal`
- **FIELDS:** name, fund number, transfer company, country, Syria region, reference balances.
- **CARDS:** `#fundsCards`
- **STATUS:** VERIFIED

---

## PAGE: Fund Detail
- **ROUTE:** `/funds/:id`
- **BUTTONS:** ترحيل; مرتجع; PDF; تحديث; إلغاء التحويل (ledger cancel).
- **MODALS:** `#fundTransferModal`, `#fundReturnModal`, `#fundLedgerMoveModal`
- **SECTIONS:** balances; open payables; location meta; ledger with running balance.
- **APIs:** `/api/funds/:id`, transfer, `/api/returns`, ledger cancel, PDF.
- **STATUS:** VERIFIED

---

## PAGE: Shipping
- **ROUTE:** `/shipping`
- **TABS:** وكالات الشحن | الشحن | تبديل راتب | الباقات
- **BUTTONS:** إضافة وكالة; بيع; شراء; السجل; تسجيل البيع/الشراء; تطبيق filters.
- **SELL FORM FIELDS:** buyerType (مستخدم/معتمد/وكيل فرعي/وكالة شحن); itemType ذهب/كرستال; quantity; total (unitPrice field = line total); paymentMethod كاش/دين/خصم من راتب/خصم من نسبة; notes; buyer ids.
- **BUY FORM:** purchaseSource إدارة/شركة شحن/شركة تحويل; item; qty; total; كاش/دين.
- **SALARY SWAP:** company, gross, discount %, paymentMode cash|installment|debt, firstInstallment.
- **MODALS:** sell, buy, record, carrier add/detail.
- **FILTERS (record):** type, status, item.
- **STUB TAB:** الباقات — «سيتم إضافة محتوى الباقات هنا قريباً.»
- **STATUS:** PARTIALLY IMPLEMENTED

---

## PAGE: Debts
- **ROUTE:** `/debts`
- **LAYOUT:** Finance toolbar nav → KPI summary → register form → lists (negative companies/funds/payables).
- **FORM:** entityType شركة تحويل/صندوق; entity; amount; currency USD/TRY/SYP; settlementMode دين علينا/إيداع نقدي; notes.
- **BUTTON:** تسجيل الدين
- **LOADING:** «جاري تحميل الأرقام…»
- **STATUS:** VERIFIED

### Subpages debt-company / debt-fund
- Routes `/debts/company/:id`, `/debts/fund/:id`
- Back link العودة للديون; body filled by `debts.js` with entity ledger + returns.

---

## PAGE: Payables Overview (دين علينا)
- **ROUTE:** `/payables-us`
- **SECTIONS:** negative transfer companies; negative funds; payables table (النوع, المعرّف, المبلغ, ملاحظات)
- **API:** GET `/api/payables/overview`
- **STATUS:** VERIFIED

---

## PAGE: Receivables To Us (دين لنا)
- **ROUTE:** `/receivables-to-us`
- **SECTIONS:** sub-agencies; accreditations; members; fund returns; transfer companies
- **API:** GET `/dashboard/receivables-to-us`
- **STATUS:** VERIFIED

---

## PAGE: Profit Sources
- **ROUTE:** `/profit-sources`
- **LAYOUT:** Link to dashboard + `#profitSourcesBox` filled by JS
- **EMPTY:** «لا توجد قيود صافي ربح بعد»
- **API:** GET `/api/expenses/net-profit-by-source`
- Row click → `/profit-sources/:sourceType/detail`
- **STATUS:** VERIFIED

### Profit Source Detail
- API GET `/api/expenses/net-profit-by-source/:sourceType/detail`
- Shows shipping or ledger rows depending on kind.

---

## PAGE: Payment Due (مطلوب دفع)
- **ROUTE:** `/payment-due`
- **LAYOUT:** Total hero; cycle select; checklists for accreditations & sub-agencies; تسليم وتصفير / تسليم المحدد
- **API:** `/dashboard/payment-due`, POST `/api/payment-due/settle`
- **STATUS:** VERIFIED

---

## PAGE: Financial Movements
- **ROUTE:** `/financial-movements`
- **FILTERS (buttons):** الكل | دين علينا | دين لنا | التزامات | ترحيل نقدي
- **TABLE COLUMNS:** التصنيف, العنوان, متى, المبلغ
- **PAGINATION:** السابق/التالي (~15 rows client-side)
- **EMPTY:** «لا توجد حركات ضمن هذا التصفية.»
- **MODAL:** `#fmModal` detail
- **API:** GET `/api/financial-movements/feed?bucket&limit`
- **STATUS:** VERIFIED

---

## PAGE: Expenses
- **ROUTE:** `/expenses-manual`
- **FORM:** amount, category, notes → تسجيل مصروف
- **TABLE:** التاريخ, المبلغ, المصدر, النوع, ملاحظات; filter `#expUnifiedFilter`; تحديث
- **API:** `/api/expenses/ledger-unified`, POST `/api/expenses/add`
- **STATUS:** VERIFIED

---

## PAGE: FX Spread
- **ROUTE:** `/fx-spread`
- **FORM:** cycle, currency, amountForeign, internalRate, settlementRate, entityType/id, notes → حفظ
- **LIST:** `#fxList` with delete
- **API:** list/add/DELETE
- **STATUS:** VERIFIED (delete incomplete on backend — see complete spec)

---

## PAGE: Admin Brokerage
- **ROUTE:** `/admin-brokerage`
- **FORM:** cycle, amount, brokeragePct, notes → تسجيل
- **LEDGER BOX:** `#abLedgerBox`
- **API:** `/api/admin-brokerage/ledger|add`
- **STATUS:** VERIFIED

---

## PAGE: Sub-Agencies
- **ROUTE:** `/sub-agencies`
- **BUTTONS:** تسليم; إضافة وكالة; مزامنة من Google; cycle select for sync
- **MODALS:** add, reward, deduct, delivery, users (`sub-agency-modals.ejs`)
- **STATUS:** VERIFIED

### Sub-Agency Detail `/sub-agencies/:id`
- Balance, percent save, مكافأة, خصم, PDF, المستخدمون, ledger filters تطبيق
- **STATUS:** VERIFIED

---

## PAGE: Transfer Companies
- **ROUTE:** `/transfer-companies`
- **SEARCH:** `#tcCardsSearch`
- **BUTTON:** إضافة شركة → `#tcAddModal`
- **STATUS:** VERIFIED

### Transfer Company Detail `/transfer-companies/:id`
- **BUTTONS:** إضافة دين لنا; تسجيل مرتجع; PDF; تحديث; cancel ledger
- **MODALS:** receivable, return, ledger
- **STATUS:** VERIFIED

---

## PAGE: Member Directory
- **ROUTE:** `/member-directory`
- **SEARCH:** paste + بحث; table columns: رقم المستخدم, الاسم, راتب (تدقيق), مؤجل, دين, آخر تحديث, إجراء
- **PAGINATION:** السابق/التالي (`md-pager-btn`)
- **DESIGN:** No nested white cards; divide-y table (see UI reference)
- **STATUS:** VERIFIED

### Member Detail `/member-directory/member/:memberUserId`
- Sections: نظرة سريعة; المؤجل; سجل التدقيق; أحداث الملف; إضافات وخصومات; الشحن
- Prev/next profile nav
- **STATUS:** VERIFIED

---

## PAGE: Member Adjustments
- **ROUTE:** `/member-adjustments`
- **KIND CHIPS:** خصم | إضافة | مكافأة
- **FIELDS:** memberUserId (لصق), amount, cycle, sync sheet checkbox + column letters, notes
- **BUTTON:** تسجيل العملية
- **API:** POST `/api/member-adjustments/apply`
- **STATUS:** VERIFIED

---

## PAGE: Settings
- **ROUTE:** `/settings`
- **TABS:** عام | الملف الشخصي | الذكاء الاصطناعي | Google Sheets
- **GENERAL:** currency select; simple financial terms checkbox; حفظ
- **PROFILE:** displayName; change password (current/new/confirm)
- **AI:** provider openai|gemini|openrouter; api key; model select; refresh models (`ai-settings.js`)
- **SHEETS:** status; OAuth connect `/sheets/auth`; save spreadsheet; disconnect; sync toggle (via sheets.js)
- **RESET:** modal `#resetDataModal` with categories + confirm phrase → POST `/settings/reset-data`
- **STATUS:** VERIFIED

---

## PAGE: WhatsApp Agent
- **ROUTE:** `/whatsapp`
- **TABS:** الاتصال | الهويات | شخصية الوكيل | سجل التدقيق
- **CONNECTION:** status badges; instance name; إنشاء نسخة; عرض رمز QR; قطع الاتصال; providers list
- **IDENTITIES TABLE:** الرقم, الاسم, النوع, الآيدي, الحالة, آخر ظهور, إجراءات; form ربط رقم جديد
- **PERSONA:** skills/prompt fields; حفظ الشخصية; معاينة
- **AUDIT:** access log table
- **MODALS:** `#waQrModal`, `#waMemoryModal`, `#waOtpModal`
- **PERMISSIONS:** Page visible to any auth user; APIs require role admin
- **STATUS:** VERIFIED

---

## Shared Modals

### Receivable Offset (`acc-receivable-offset-modal.ejs`)
- Modes: تأجيل / خصم كامل / مخصص / ignore paths as implemented
- Required acknowledgment when receivable > epsilon

### Modal UX pattern
- Shell `tc-modal-shell`, z-[10050], backdrop `bg-slate-900/60`
- Close: backdrop click / Escape where wired
- Mobile inset rules in `input.css`

---

## Design Tokens (observed)

- Sidebar dark: `#1a1a2e`
- Active nav: indigo accents
- Font: Tajawal
- Money: `font-mono tabular-nums`, often `dir="ltr"` on amounts
- Touch targets ≥ 44px guidance in docs
- CSS build: `src/input.css` → `npm run css:build` → `public/css/tailwind.css`

---

## Error / Success Patterns (UI)

- Toast success/error after API
- Inline API `message` Arabic strings
- Disabled buttons during submit (page-dependent)
- Confirm modals for destructive deletes/settles

---

*End of UI specification.*
