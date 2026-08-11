# LorkERP — Complete Accounting System Specification

> **Document type:** Reverse-engineered product & technical specification  
> **System name:** LorkERP (لورك إي آر بي) — نظام المحاسبة المتكامل  
> **Version:** 1.0.0 (from package.json)  
> **Analysis mode:** READ-ONLY reverse engineering of the working codebase  
> **Language / UI:** Arabic RTL  
> **Default port:** 3020  

**Evidence discipline:** Only behaviors confirmed from source code, routes, schema, services, views, and tests are documented. Gaps are marked `UNKNOWN`, `PARTIALLY IMPLEMENTED`, `BACKEND ONLY`, or `UI ONLY / UNCONNECTED`.

---

## 1. Product Overview

LorkERP is a **single-tenant, session-authenticated, Arabic RTL web ERP** focused on:

1. **Financial cycles** synchronized from Google Sheets (management + agent payroll tables).
2. **Payroll audit** against those sheets (color/write to Google or server-only cache).
3. **Cash funds** (main fund + secondary funds + hidden profit pool fund).
4. **Accreditations** (معتمدون) with payable/receivable buckets and transfers.
5. **Transfer companies**, **sub-agencies**, **shipping inventory** (gold/crystal).
6. **Debts / payables / receivables**, **expenses**, **FX spread**, **returns**.
7. **PDF accounting reports** (Puppeteer HTML→PDF).
8. **AI message sorting** (OpenAI / Gemini / OpenRouter) with Socket.IO progress.
9. **WhatsApp agent** via embedded Evolution API (optional), with OTP identity isolation.

It is **not** a classic Chart-of-Accounts double-entry general ledger. Accounting is **fund-centric + entity ledgers + analytical `ledger_entries` buckets** (`net_profit`, `main_cash`, `expense`).

### What the product is NOT

- No classic CoA / journal balanced pairs across GL accounts.
- No multi-company tenancy beyond `user_id` scoping on most tables.
- No public self-registration UI.
- No MFA / email verification / password reset flow.
- Clients page and Client Portal are **UI stubs only**.

---

## 2. Technology Stack

| Layer | Technology | Notes |
|-------|------------|-------|
| Runtime | Node.js 18+ | Express 4 |
| Frontend | EJS server-rendered + vanilla JS | Tailwind CSS 4 built from `src/input.css` |
| UI fonts/icons | Tajawal (Google Fonts), Font Awesome 6 CDN | RTL `dir="rtl"` |
| Database | PostgreSQL 14+ | `pg` pool; schema in `db/schema.pg.sql` + runtime ALTERs in `db/database.js` |
| Auth | express-session | File store (`sessions/`) in prod; MemoryStore in dev |
| Password hashing | bcryptjs | cost factor 10 |
| API key encryption | AES-256-GCM | key derived from `SESSION_SECRET` via SHA-256 |
| Google | googleapis | Sheets OAuth + Drive list |
| PDF | Puppeteer | HTML accounting PDFs |
| Realtime | Socket.IO | AI analysis job progress rooms `analysis:{jobId}` |
| Excel import | xlsx, multer | Cycle upload + accreditation bulk |
| WhatsApp | Evolution API (embedded optional) | Mount path `/whatsapp-api` |
| Process manager | PM2 (`ecosystem.config.cjs`) | Optional |
| Tests | Node scripts | WhatsApp isolation / memory / conversation only |

---

## 3. Architecture

```
Browser (RTL EJS pages + public/js/*)
        │  session cookie
        ▼
Express (server.js)
  ├── Helmet CSP + compression
  ├── Session middleware
  ├── Static /public
  ├── Page routes (EJS dashboard shell)
  ├── JSON API routes (/api/*, /ai, /settings, /dashboard/*, /sheets)
  ├── Socket.IO (AI jobs)
  ├── Background: cycleSyncWorker (Google Sheets poll)
  └── Optional: embed Evolution WhatsApp API
        │
        ▼
PostgreSQL
  ├── Operational tables (funds, accreditations, shipping, …)
  ├── Payroll cycle caches
  └── WhatsApp identity / OTP / memory (isolated)
```

**Deployment model:** Single Node process listening on `PORT` (default 3020), lock file `.server.lock` to prevent duplicate instances (skipped under PM2). Database required at boot (`initDatabase()`).

---

## 4. Application Structure (repository layout)

```
LorkERP/
├── server.js                 # HTTP + Socket.IO bootstrap, route mounts
├── package.json              # name: lorkerp, version 1.0.0
├── .env.example
├── ecosystem.config.cjs
├── db/
│   ├── database.js           # pool, schema apply, migrations, admin seed
│   ├── schema.pg.sql         # canonical CREATE TABLEs
│   ├── agentRls.js           # WhatsApp agent RLS (optional)
│   └── agentViews.js         # agent_v_* views
├── middleware/auth.js        # requireAuth, requireRole
├── routes/                   # ~27 route modules, ~198 endpoints
├── services/                 # business logic + accounting
│   ├── pdf/htmlAccountingPdf.js
│   └── whatsapp/*
├── views/                    # login, dashboard shell, 404, error
│   └── partials/             # page bodies
├── public/js/                # page scripts
├── public/css/tailwind.css   # built CSS
├── src/input.css             # Tailwind source
├── utils/crypto.js, numbers.js
├── tests/                    # WhatsApp-focused
├── docs/                     # existing Arabic docs
└── integrations/evolution-api/
```

---

## 5. Navigation Map (actual)

```
Application
├── تسجيل الدخول (/login) — public
├── لوحة التحكم (/dashboard) — home
├── عمليات وإدخال
│   ├── Sheet (/sheet)
│   ├── تدقيق الرواتب (/payroll)
│   ├── البحث (/search)
│   ├── الوكالات الفرعية (/sub-agencies, /sub-agencies/:id)
│   ├── بيانات المستخدمين (/member-directory, …/member/:memberUserId)
│   ├── إضافات وخصومات (/member-adjustments)
│   ├── وساطة إدارية (/admin-brokerage)
│   └── وكيل واتساب (/whatsapp) — admin role for APIs
├── ترتيب الرسائل (/messages)
├── فرق التصريف (/fx-spread)
├── شركات التحويل (/transfer-companies, /:id)
├── الصناديق (/funds, /funds/:id)
├── الاعتمادات (/approvals, /approvals/:id)
├── الشحن (/shipping)
├── المالية والالتزامات
│   ├── الديون (/debts, /debts/company/:id, /debts/fund/:id)
│   ├── دين علينا (/payables-us)
│   ├── دين لنا (/receivables-to-us)
│   ├── مصادر الربح (/profit-sources, /:sourceType/detail)
│   ├── مطلوب دفع (/payment-due)
│   ├── سجل الحركات (/financial-movements)
│   └── المصاريف (/expenses-manual)
├── الإعدادات (/settings)
├── تسجيل الخروج (/logout)
└── Routed but NOT in sidebar
    ├── رصيد المؤجل (/deferred-balance) — linked from dashboard card
    ├── بيانات العملاء (/clients) — UI ONLY stub
    └── واجهة العملاء (/client-portal) — UI ONLY stub
```

Mobile bottom nav (5 items): الرئيسية · تدقيق راتب · FAB (+) · المستخدمين · إضافات.

FAB cascade: **صادر** / **وارد** quick actions (see UI spec).

---

## 6. Modules Inventory

| Module | Purpose | Pages | Primary APIs | Key tables |
|--------|---------|-------|--------------|------------|
| Auth | Login/logout session | login | POST /login, GET /logout | users |
| Dashboard | KPIs, PDF download, fund modal | home | /dashboard/stats, fund-sources, reports | many aggregates |
| Financial cycles (Sheet) | Create/sync cycles from Google/upload | sheet | /api/sheet/cycles* | financial_cycles, payroll_* |
| Payroll audit | Audit members vs sheets | payroll-google | /api/sheet/payroll-* , /api/search/* | payroll caches, deferred_salary_lines |
| Search | Per-member audit UI | search | /api/search/* | same |
| Accreditations | معتمدون balances & transfers | approvals* | /api/accreditations/* | accreditation_entities, accreditation_ledger |
| Funds | Cash boxes | funds* | /api/funds/* | funds, fund_balances, fund_ledger |
| Transfer companies | External remittance cos | transfer-companies* | /api/transfer-companies/* | transfer_companies, transfer_company_ledger |
| Sub-agencies | Commission agencies | sub-agencies* | /api/sub-agencies/* | shipping_sub_agencies, sub_agency_transactions |
| Shipping | Buy/sell gold/crystal | shipping | /api/shipping/* | shipping_transactions, shipping_inventory |
| Debts | Obligation register + overview | debts* | /api/debts/* | entity_payables + aggregates |
| Payables / Receivables | Views of obligations | payables-overview, receivables-to-us | /api/payables, /dashboard/receivables-to-us | computed |
| Payment due | Delivery settle | payment-due | /api/payment-due/settle | accreditation + sub-agency balances |
| Expenses | Manual + unified ledger | expenses-page | /api/expenses/* | expense_entries, ledger_entries |
| Profit sources | net_profit by source_type | profit-sources* | /api/expenses/net-profit-by-source* | ledger_entries, shipping |
| FX spread | Rate arb profit | fx-spread | /api/fx-spread/* | fx_spread_entries |
| Returns | Financial returns | modals on fund/TC | /api/returns/* | financial_returns |
| Admin brokerage | Brokerage split | admin-brokerage | /api/admin-brokerage/* | admin_brokerage_entries |
| Member directory | Member profiles | member-directory* | /api/member-directory/* | member_profiles, events |
| Member adjustments | add/deduct/reward | member-adjustments | /api/member-adjustments/apply | member_adjustments, deferred |
| Messages / AI | Parse messages to tables | messages | /ai/*, /sheets/export | analysis_jobs, message_analyses, ai_config |
| Settings | Profile, currency, AI keys, Sheets, reset | settings | /settings/*, /sheets/* | settings, users, google_sheets_config |
| Reports | PDF catalog | home toolbar + detail pages | /api/reports/* | accountingReportData |
| WhatsApp | Agent + identities | whatsapp | /api/whatsapp/* | whatsapp_* |
| Clients / Portal | Placeholders | clients, client-portal | none | none |
| Financial movements | Unified feed | financial-movements | /api/financial-movements/feed | multi-source feed |
| Cycle accounting | Apply audit profits / rebuild deferred | API only + hooks | /api/cycle-accounting/* | ledger + deferred |
| Quick action | FAB context | global | /api/quick-action/context | reads |

---

## 7–8. Sections & Pages (summary)

Full page-by-page UI detail is in `ACCOUNTING_SYSTEM_UI_SPEC.md`. Backend flows in `ACCOUNTING_SYSTEM_BACKEND_SPEC.md`. Feature status matrix in `ACCOUNTING_SYSTEM_FEATURE_MATRIX.md`.

### Access model (global)

- Almost all pages/APIs: `requireAuth` (session `userId`).
- WhatsApp management APIs: `requireAuth` + `requireRole('admin')`.
- WhatsApp webhook: shared secret header `x-lorkerp-webhook-secret`.
- `users.role` defaults to `admin`; seeded user is `admin`. **Fine-grained permission matrix does not exist** — no per-feature ACL beyond WhatsApp admin gate. Marked as such in Permissions section.

---

## 9. Authentication

### Login

1. GET `/login` — if session exists → redirect `/dashboard`.
2. POST `/login` with `username`, `password`.
3. Lookup `users` by username; `bcrypt.compareSync`.
4. On success set `req.session.userId` and `req.session.user` `{ id, username, displayName, role, useSimpleFinancialTerms }`.
5. Redirect `/dashboard`.
6. On failure re-render login with Arabic error.

### Logout

GET `/logout` → `session.destroy` → `/login`.

### Session

- Cookie: `httpOnly`, `sameSite=lax`, `secure=false`, maxAge **7 days**.
- Store: file store under `sessions/` (prod) or MemoryStore (dev / `USE_MEMORY_SESSION=1`).
- Secret: `SESSION_SECRET` (default `'lorkerp-secret'` if unset — insecure default exists in code).

### Not implemented

- Registration UI, password reset, MFA, email verification, account lockout (except WhatsApp OTP lockout on identities), refresh tokens, JWT.

### Admin seed

On DB init, if no user with `ADMIN_USERNAME` (default `admin`), insert with bcrypt-hashed `ADMIN_PASSWORD` (default `admin123`), display name `مدير النظام`, role `admin`.

---

## 10. Authorization / Roles / Permissions

| Mechanism | Behavior |
|-----------|----------|
| `requireAuth` | Session required; JSON 401 or redirect `/login` |
| `requireRole(...roles)` | Compares `req.session.user.role`; 403 if mismatch |
| Applied roles | Only WhatsApp admin APIs use `requireRole('admin')` |
| `users.role` column | Exists; default `'admin'`; **no multi-role UI** |
| Permission strings | **Do not exist** (no `invoice:create` style ACL) |
| UI guards | Pages rely on auth cookie only; WhatsApp page loads for any logged-in user but APIs return 403 for non-admin |

**Permission matrix (actual):**

| Capability | Any authenticated user | role=`admin` |
|------------|------------------------|--------------|
| All ERP pages & financial APIs | ✓ | ✓ |
| WhatsApp config/identities/persona/log APIs | ✗ (403) | ✓ |
| WhatsApp webhook | secret header | secret header |

---

## 11. Accounting Engine (critical)

### 11.1 Model

**Fund-centric cash books** + **entity balances** + **analytical ledger buckets**.

| Concept | Storage | Semantics |
|---------|---------|-----------|
| Fund cash | `fund_balances.amount` + `fund_ledger` signed `amount` | Positive credit increases cash |
| Profit pool | Fund named `صندوق الربح`, `exclude_from_dashboard=1` | Mirror of `net_profit` (with skips) |
| Main fund | `funds.is_main=1` (auto-created `الصندوق الرئيسي`) | Primary cash |
| Analytical P&L | `ledger_entries` with `bucket` + `direction` (±1) + absolute `amount` | Reporting |
| Accreditation | `balance_payable` (علينا), `balance_receivable` (لنا), `balance_amount = payable − receivable` | Party books |
| Explicit debts | `entity_payables` FIFO settle | Obligations |
| Shipping inventory | weighted average cost per `(user_id, item_type)` | COGS on sell |

**Classic double-entry CoA:** NOT PRESENT.

### 11.2 Fund adjust primitive

`adjustFundBalance(fundId, currency, delta, type, notes, refTable, refId, movementGroupId)`:

1. UPSERT `fund_balances` += `delta`.
2. INSERT `fund_ledger` with `amount = delta` (signed).

Wrappers: `creditFundBalance` (positive), `debitFundBalance` (−abs).

### 11.3 Analytical ledger

`insertLedgerEntry`: stores absolute amount + direction sign.  
`insertNetProfitLedgerAndMirrorFund`: inserts `net_profit` then mirrors to profit fund as `net_profit_mirror` unless `source_type` in skip set (`sub_agency_company_profit`).

`sumLedgerBucket(net_profit)` excludes `cycle_creation_discount_profit` when same cycle already has `transfer_discount_profit` (dedupe).

### 11.4 Dashboard net profit formula (verified)

```
shippingProfit = SUM(shipping_transactions.profit_amount) WHERE type='sell'
ledgerNetProfit = sumLedgerBucket(user, 'net_profit', 'USD')
netProfit = shippingProfit + ledgerNetProfit
totalExpensesLedger = sumLedgerBucket(user, 'expense', 'USD')
```

Report summary variant:

```
netProfitDisplayed = shippingProfit + ledgerNetProfit − totalExpensesLedger
```
(see `accountingReportData.getSummarySnapshot` — expenses subtracted there).

**Dashboard `/dashboard/stats`:** uses `netProfit = shippingProfit + ledgerNetProfit` **without** subtracting expenses in that field; expenses exposed separately as `totalExpensesLedger`.

### 11.5 Debt total formula

```
totalDebts =
  SUM(shipping sell where status='debt') +
  SUM(accreditation.balance_payable where > 0) +
  SUM(entity_payables USD) +
  SUM(|negative transfer_company USD balances|) +
  SUM(|negative fund USD balances|)
```

`fxSpreadSumUsd` is computed but **not** included in `totalDebts`.

### 11.6 Receivables to us

Sum of: negative sub-agency balances; accreditations with `balance_amount < 0`; `member_profiles.debt_to_company_usd > 0`; fund returns disposition `remain_at_entity` (not cancelled); positive transfer company USD balances.

### 11.7 Payment due

Accreditation `balance_payable > 0` + positive sub-agency balances.

### 11.8 Shipping inventory

- Buy: `quantity_on_hand += q`, `total_cost_basis += lineTotal`.
- Sell: require qty; `avgCost = total_cost_basis / qty`; `costAllocated = q * avgCost`; `profit = revenue − costAllocated`; reduce inventory.
- Cash sell credits main fund full line total; cash buy debits main fund.

### 11.9 Cycle create financial cascade

On financial cycle create:

1. `ensurePrimaryAccreditationAfterCycleCreate` — seed primary accreditation payable from agent salaries after transfer discount; credit main fund seed; `main_cash` ledger.
2. `rebuildDeferredFromLocalAgentData` — rebuild deferred lines; post transfer discount profit once; sync primary accreditation vs deferred.
3. `onFinancialCycleCreated` — opening snapshot only.

### 11.10 Audit profits

`applyCycleAuditProfitsToLedger`:

- Management Y+Z → `net_profit` `audit_management_yz` (once).
- Main fund credit notes cycle: amount = raw W + YZ (once per cycle via note guard).

Agency company share: debit main / credit profit fund; agency share via `sub_agency_transactions` type `profit`.

### 11.11 FX spread

```
spreadUsd = |amountForeign/internalRate − amountForeign/settlementRate|
```

Posts `net_profit` + fund moves when entity is fund; company entity debits main only. **DELETE entry does not reverse ledger/funds** (known inconsistency).

### 11.12 Cancellation

`ledgerCancelService`: reverse eligible fund/company ledger rows via opposite balance delta + `movement_cancel` type; group cancel by `movement_group_id`; restore payables from `movement_side_effects`. Cannot cancel `net_profit_mirror` / `opening_reference`. Accreditation ledger / most cycle posts not cancelled by this path.

### 11.13 Transactional integrity

`runTransaction` used for: returns create, fund receive/transfer, company payout/receivable, cancel paths.  
**Often NOT wrapped:** accreditation add-amount, expenses, FX add, cycle seed/rebuild, shipping buy/sell, admin brokerage — partial failure risk.

---

## 12. Major Workflows (user journeys)

### A. Login

User opens `/` → redirect `/login` → submit credentials → session → `/dashboard`.

### B. Create financial cycle (Sheet)

1. Open `/sheet` → «إنشاء دورة جديدة».
2. Choose Google Sheets tabs or upload Excel/CSV pair.
3. Save → POST `/api/sheet/cycles`.
4. Backend creates cycle + primary accreditation seed + deferred rebuild + opening snapshot.
5. UI refreshes cycle cards.

### C. Payroll audit

1. `/payroll` → select cycle → configure columns/colors/discount → «تدقيق».
2. POST `/api/sheet/payroll-execute` (writes Google) or local audit endpoint.
3. Results shown; session can be closed (`close-audit`) / reopened.
4. Deferred + audit profits applied via cycle accounting services (also background sync).

### D. Accreditation add amount

1. `/approvals` or detail → «إضافة مبلغ» / «تسجيل».
2. Choose kind (salary / debt_*); if receivable exists, offset modal required.
3. POST `/api/accreditations/:id/add-amount`.
4. Updates payable/receivable buckets; may credit main fund; may post `net_profit` (brokerage/discount); mirrors profit fund.

### E. Fund transfer

1. Fund detail → «ترحيل» → toFundId, amount → POST `/api/funds/:id/transfer` (transactional debit/credit).

### F. Shipping sell

1. `/shipping` → بيع → buyer type, item, qty, total, payment method.
2. POST `/api/shipping/sell` → inventory applySell → optional main fund credit / debt status / salary deduction paths.

### G. Delivery settle (payment due)

1. `/payment-due` → select accreditations & sub-agencies → «تسليم المحدد».
2. POST `/api/payment-due/settle` → zeros payables / agency balances **without cash debit**.

### H. Manual expense

1. `/expenses-manual` or FAB → amount → POST `/api/expenses/add` → expense_entries + expense ledger; optional debit main.

### I. WhatsApp identity link (admin)

1. `/whatsapp` → هويات → bind phone → issue OTP → user verifies in WhatsApp chat → scoped queries only.

---

## 13. APIs

Complete endpoint catalog (~198 routes) is summarized in Backend Spec. Mount prefixes from `server.js`:

`/`, `/dashboard`, `/sheets`, `/api/sheet`, `/settings`, `/api/search`, `/api/shipping`, `/api/sub-agencies`, `/api/funds`, `/api/transfer-companies`, `/api/accreditations`, `/api/returns`, `/api/debts`, `/api/fx-spread`, `/api/cycle-accounting`, `/api/expenses`, `/api/admin-brokerage`, `/api/payables`, `/api/financial-movements`, `/api/quick-action`, `/api/payment-due`, `/api/reports`, `/api/member-directory`, `/api/member-adjustments`, `/api/whatsapp`, `/ai`.

Typical JSON shape: `{ success: boolean, message?, ... }`. Auth failures: `{ error: 'غير مصرح' }` 401.

---

## 14. Database Map

### Core tables (from schema + runtime)

**Identity / config:** `users`, `settings`, `ai_config`, `google_sheets_config`

**AI:** `message_analyses`, `analysis_jobs`

**Cycles / payroll:** `financial_cycles`, `payroll_settings`, `payroll_cycle_columns`, `payroll_cycle_cache`, `payroll_user_audit_cache`, `payroll_native_*` (native upload cycles — present in schema), `deferred_balance_users`, `deferred_salary_lines`, `financial_cycle_opening_snapshots`, `cash_box_snapshot`

**Shipping / agencies:** `shipping_approved`, `shipping_sub_agencies`, `sub_agency_transactions`, `shipping_companies`, `shipping_transactions`, `agency_sheet_mapping`, `user_agency_link`, `agency_cycle_users`, `agency_sync_log`, `sub_agency_cycle_settings`, `shipping_carrier_agencies`, `shipping_carrier_transactions`, `shipping_inventory`

**Funds / companies:** `transfer_companies`, `transfer_company_ledger`, `funds`, `fund_balances`, `fund_ledger`, `fund_transfers`, `profit_transfer_batches`

**Accreditations / debts:** `accreditation_entities`, `accreditation_ledger`, `financial_returns`, `entity_payables`, `fx_spread_entries`, `ledger_entries`, `expense_entries`, `admin_brokerage_entries`, `salary_swap_entries`, `movement_side_effects`

**Members:** `member_profiles`, `member_profile_events`, `member_adjustments`

**WhatsApp:** `whatsapp_identities`, `whatsapp_otp`, `whatsapp_sessions`, `whatsapp_access_log`, `whatsapp_rate_limit`, `whatsapp_config`, `whatsapp_agent_persona`, `whatsapp_agent_memory`

### Relationship sketch

```
users
 ├─ financial_cycles ─┬─ payroll_* caches
 │                    ├─ deferred_salary_lines
 │                    └─ opening_snapshots
 ├─ funds ─┬─ fund_balances
 │         ├─ fund_ledger
 │         └─ fund_transfers
 ├─ accreditation_entities ─ accreditation_ledger
 ├─ transfer_companies ─ transfer_company_ledger
 ├─ shipping_sub_agencies ─ sub_agency_transactions
 ├─ shipping_inventory / shipping_transactions
 ├─ ledger_entries / expense_entries
 ├─ entity_payables / financial_returns
 └─ whatsapp_identities ─ otp / memory / sessions
```

Indexes: ledger by user+bucket; member profiles; WhatsApp identity uniqueness constraints; deferred unique `(user_id, cycle_id, member_user_id)`.

Views/triggers/stored procedures for main ERP: **none** in schema (agent views optional via `agentViews.js`).

---

## 15. Reports & Exports

| Report | Endpoint | Format |
|--------|----------|--------|
| Sub-agency PDF | GET `/api/reports/pdf/sub-agency?subAgencyId&cycleId?` | PDF |
| All sub-agencies | GET `/api/reports/pdf/all-sub-agencies` | PDF |
| Accreditations | GET `/api/reports/pdf/accreditations` | PDF |
| Accreditations net | GET `/api/reports/pdf/accreditations-net` | PDF |
| Transfer companies | GET `/api/reports/pdf/transfer-companies` | PDF |
| TC ledger | GET `/api/reports/pdf/transfer-company-ledger?companyId` | PDF |
| Fund ledger | GET `/api/reports/pdf/fund-ledger?fundId` | PDF |
| All funds | GET `/api/reports/pdf/all-funds` | PDF |
| Movements | GET `/api/reports/pdf/movements` | PDF |
| Comprehensive | GET `/api/reports/pdf/comprehensive` | PDF |
| Reconciliation | GET `/api/reports/pdf/reconciliation` | PDF |
| Cycle unified | POST `/api/reports/cycle-unified` body `{cycleId}` | PDF |
| Summary JSON | GET `/api/reports/summary` | JSON |

Dashboard home selects report type then downloads via fetch blob. Message sorting exports CSV copy + Google Sheets export. Search can download card as image (html2canvas).

**Print:** CSS `@media print` rules for debts page; PDF is primary formal export.

---

## 16. Background Jobs / Queues / Cron

| Job | Trigger | Behavior |
|-----|---------|----------|
| `cycleSyncWorker.startBackgroundSync` | Server listen; interval env `CYCLE_SYNC_INTERVAL_MS` or ~120s; skip if `DISABLE_BACKGROUND_SYNC=1` | Poll Google Sheets for cycles with credentials; refresh `payroll_cycle_cache`; sync agencies |
| AI analysis job | POST `/ai/analyze` | Async job rows + Socket.IO progress (in-process, not Redis queue) |
| WhatsApp webhook handling | POST webhook → ack → async process | In-process |
| Session file reap | session-file-store `reapInterval: 3600` | Delete expired sessions |

**No Redis / Bull / external queue.** No classic cron crontab — only setInterval worker.

---

## 17. Integrations

| Integration | Status | Notes |
|-------------|--------|-------|
| Google Sheets OAuth + read/write | VERIFIED | cycles, payroll execute, export analysis, agency sync |
| OpenAI / Gemini / OpenRouter | VERIFIED | AI keys in `ai_config` encrypted; message analyze + WhatsApp brain |
| Evolution WhatsApp API | OPTIONAL | `EVOLUTION_EMBEDDED=1`; mount `/whatsapp-api` |
| Puppeteer PDF | VERIFIED | local Chromium |

Webhooks inbound: WhatsApp Evolution → `/api/whatsapp/webhook` with secret. Outbound webhooks for business events: **not found**.

---

## 18. Notifications

| Channel | Implementation |
|---------|----------------|
| Toast | Client `showToast` in `app.js` — success/error Arabic |
| Socket.IO | AI job progress only |
| Email / SMS / Push | **NOT IMPLEMENTED** |
| WhatsApp messages | Agent replies via Evolution |

---

## 19. File Storage

| Use | Mechanism |
|-----|-----------|
| Cycle Excel upload | multer memory/disk → parsed by xlsx (not long-term stored as product files — UNKNOWN persistence path beyond parse) |
| Accreditation bulk file | multer upload parse |
| Sessions | `sessions/` directory |
| Uploads folder | ignored by nodemon; used if present |
| AI keys | DB encrypted, not files |

File upload security: size limits via Express 25mb JSON (Evolution skipped); multer used — MIME whitelist **UNKNOWN / partial**.

---

## 20. Security (verified)

| Control | Status |
|---------|--------|
| Password bcrypt | Yes |
| Session httpOnly cookie | Yes |
| Helmet CSP | Yes (allows unsafe-inline scripts/styles) |
| AES-GCM for AI keys | Yes |
| WhatsApp OTP hashed | Yes (`code_hash`) |
| WhatsApp rate limit table | Yes |
| Agent query RLS / views | Optional when `AGENT_DB_PASSWORD` set |
| CSRF tokens | **Not found** |
| Rate limit on login | **Not found** |
| SQL | Parameterized `$1` queries via `pg` |
| XSS | Mostly server templates; inline scripts present; CSP partially mitigates |
| secure cookie | `secure: false` always |
| Default SESSION_SECRET / admin password | Present in code/env example — must change in production |

---

## 21. State Management (frontend)

- **No Redux/Vuex.** Vanilla JS globals + DOM.
- Server state via `fetch`/`apiCall` per page.
- `localStorage`: sidebar collapse `lorkerp_sidebar_desktop_collapsed`; payroll draft badge `payrollAuditDraftV1`; accreditation card order.
- Session cookie for auth.
- Socket.IO for AI jobs only.

---

## 22. Caching

| Cache | Behavior |
|-------|----------|
| `payroll_cycle_cache` | Sheet rows + audited id sets; `stale_after` |
| `payroll_user_audit_cache` | Per-member audit status |
| AI `models_cache` in `ai_config` | Provider model lists |
| Browser | Static assets; no Service Worker found |
| Redis | **Not used** |

Invalidation: sync endpoints, force reaudit flags, background worker overwrite.

---

## 23. Error Handling

- Express error middleware: API → 500 JSON `{success:false,message}`; pages → `error.ejs`.
- 404 → `404.ejs`.
- uncaughtException / unhandledRejection logged, process may continue.
- Frontend: toast on failed `apiCall`; forms show Arabic validation messages from API `message`.

---

## 24. Loading / Empty / Confirmation

- Common loading strings: «جاري التحميل...», «جاري التحقق…».
- Empty: e.g. «لا توجد دورات محفوظة», «لا توجد حركات ضمن هذا التصفية.», stub «سيتم إضافة… قريباً».
- Confirmations: delete cycle `#deleteConfirmModal`; accreditation `#accConfirmModal`; reset data requires phrase `حذف نهائي` or `DELETE`.

---

## 25. Internationalization

- UI Arabic-only (hardcoded strings).
- Optional `use_simple_financial_terms` user flag + terminology glossary JSON for simpler Arabic labels.
- Currency setting in `settings` (symbol via API).
- Number formatting helpers in `utils/numbers.js` / `formatMoney`.
- Timezone: **UNKNOWN** (uses DB `CURRENT_TIMESTAMP` / server local).

---

## 26. Multi-currency

- Fund balances keyed by currency; transfers specify currency (default USD).
- Debt register allows USD/TRY/SYP.
- FX spread compares foreign amount at two rates → USD profit.
- Many aggregations **filter USD only** for totals — non-USD may be stored but excluded from some KPIs.

---

## 27. Settings Catalog

| Setting | Storage | Effect |
|---------|---------|--------|
| Currency | `settings` key | Display symbol |
| Simple financial terms | `users.use_simple_financial_terms` | Label glossary |
| Display name | `users.display_name` | Sidebar |
| Password | `users.password` bcrypt | Auth |
| AI provider keys/models | `ai_config` encrypted | Messages + WhatsApp |
| Google Sheets config | `google_sheets_config` | OAuth token, spreadsheet, sync flag |
| Payroll discount/colors | `payroll_settings` | Audit |
| Cycle column maps | `payroll_cycle_columns` | Audit |
| WhatsApp persona/config | whatsapp_* tables | Agent |
| Reset data categories | POST wipe | Destructive |

---

## 28. Audit Logging

| Area | What is logged |
|------|----------------|
| WhatsApp | `whatsapp_access_log` (phone, identity, action, tool, allowed) |
| Fund/company ledgers | Append-only rows with types/notes/refs; cancel marks `cancelled_at` |
| Accreditation ledger | Append-only entry types |
| Member profile events | `member_profile_events` |
| Generic ERP actor/IP audit table | **Not found** |

---

## 29. Testing

| Suite | Coverage |
|-------|----------|
| `tests/whatsapp-isolation.test.js` | Identity isolation |
| `tests/agent-query-isolation.test.js` | Agent query scoping |
| `tests/whatsapp-conversation.test.js` | Conversation flow |
| `tests/agent-memory.test.js` | Memory |
| Accounting / funds / payroll / API e2e | **Not found** |

Important untested: fund transfers, accreditation math, cycle seed, inventory COGS, FX delete inconsistency.

---

## 30. Known Limitations & Inconsistencies

1. FX spread DELETE does not reverse profit/fund effects.
2. Dashboard `netProfit` does not subtract expenses; report summary does.
3. `fxSpreadSumUsd` not in `totalDebts`.
4. Clients & client-portal are stubs; shipping «الباقات» tab stub.
5. `requireRole` unused for financial modules.
6. Cookie `secure:false`; default secrets in example.
7. Non-transactional multi-step money ops can partially apply.
8. Reconciliation report uses gross `admin_brokerage_entries.amount` vs profit portion elsewhere.
9. Native payroll cycle tables exist in schema — UI path primarily Google cycles (`PARTIALLY` / usage depth UNKNOWN without runtime).
10. Runtime UI verification in this analysis pass: **code-verified**; live browser screenshots **not captured** (environment may lack DB/server). Mark UI visual details as code-verified.

---

## 31. Unknown / Unverified

- Exact production deployment topology beyond PM2 docs.
- Whether `payroll_native_*` flows are reachable from current UI (schema present; primary UX is Google payroll page).
- Full enumeration of every `fund_ledger.type` string used across all call sites (many string literals).
- Complete WhatsApp tool list semantics beyond isolation docs (see `docs/تكامل-واتساب.md`).
- Live data race behaviors under concurrent users (single-user assumption strong).

---

## 32. Traceability Indexes

### Page → primary APIs (sample)

| Page | APIs |
|------|------|
| home | GET /dashboard/stats, /dashboard/fund-sources, /sheets/status, /api/reports/* |
| sheet | /api/sheet/cycles* |
| payroll | /api/sheet/payroll-*, cycles, audit-session |
| approvals | /api/accreditations/* |
| funds | /api/funds/* |
| shipping | /api/shipping/* |
| debts | /api/debts/* |
| settings | /settings/*, /sheets/*, /ai/* |
| whatsapp | /api/whatsapp/* |

### Button → backend (examples)

| UI Action | API | Service effects |
|-----------|-----|-----------------|
| تسجيل الدخول | POST /login | session |
| إنشاء دورة | POST /api/sheet/cycles | cycle + primary acc seed + deferred |
| إضافة مبلغ معتمد | POST /api/accreditations/:id/add-amount | buckets ± fund ± net_profit |
| ترحيل صندوق | POST /api/funds/:id/transfer | two fund ledgers |
| بيع شحن | POST /api/shipping/sell | inventory + optional fund |
| تسجيل مصروف | POST /api/expenses/add | expense + ledger ± fund |
| فرق تصريف حفظ | POST /api/fx-spread/add | fx row + net_profit ± fund |
| تسليم مطلوب دفع | POST /api/payment-due/settle | zero payables/agency |

### Page → tables (examples)

| Page | Read | Write |
|------|------|-------|
| home | funds, ledger, shipping, deferred, debts aggregates | refresh-cash / deferred optional |
| approvals | accreditation_* | accreditation_*, funds, ledger, entity_payables |
| shipping | inventory, txs | shipping_*, funds, inventory |
| payroll | cycles, caches, sheets | audit cache, Google, deferred, ledger |

---

## 33. Business Rules Catalog (selected verified)

1. Main fund required for many accreditation cash paths.
2. Receivable offset mandatory when `balance_receivable > 0.0001` on certain add-amount kinds.
3. Transfer discount profit posted at most once per cycle.
4. Audit YZ profit posted at most once per cycle.
5. Delivery settle clears payable without cash movement.
6. Returns settle `entity_payables` FIFO before net cash/receivable move.
7. Insufficient inventory blocks sell (`INSUFFICIENT_QTY`).
8. Cannot cancel `net_profit_mirror` / `opening_reference` fund rows.
9. Profit fund excluded from dashboard fund totals.
10. Reset data requires exact confirmation phrase.

---

## 34. Delete vs Archive

| Entity | Behavior |
|--------|----------|
| Cycle delete | Hard DELETE API |
| Fund ledger cancel | Soft cancel flags + reverse row |
| Financial returns cancel | `cancelled_at` |
| FX delete | Hard delete row only |
| Accreditation | No delete API found in catalog |
| WhatsApp identity | Hard DELETE |
| Reset data | Hard wipe selected categories |

---

## 35. Performance Notes

- Pagination: member-directory `page`/`pageSize`; financial-movements client page size ~15; expenses `limit` query.
- Compression middleware on.
- Background sheet sync batched helpers.
- Indexes on ledger/members/WhatsApp.
- No Redis cache layer.

---

## 36. Companion Documents

1. `ACCOUNTING_SYSTEM_UI_SPEC.md` — every page UI elements.
2. `ACCOUNTING_SYSTEM_BACKEND_SPEC.md` — services, APIs, data flows.
3. `ACCOUNTING_SYSTEM_FEATURE_MATRIX.md` — feature status matrix.
4. `ACCOUNTING_SYSTEM_RECREATION_PROMPT.md` — standalone rebuild prompt (no source references).

---

*End of complete specification.*
