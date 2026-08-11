# LorkERP Recreation Prompt — Standalone Product Specification for an AI Coding Agent

You are to **build a complete product from scratch** that matches the behavior described in this document. Do **not** assume standard accounting-software features unless stated here. Do **not** invent CRM, invoices, warehouses, or classic Chart-of-Accounts double-entry unless explicitly required below.

This prompt is self-contained. Implement exactly what is specified. Where something is marked **OUT OF SCOPE / STUB**, ship the stub UI only. Where marked **MUST**, implement fully.

---

## 0. Product identity

**Name:** LorkERP  
**Tagline (AR):** نظام المحاسبة المتكامل  
**Locale:** Arabic UI, `dir="rtl"`, font Tajawal, Font Awesome icons  
**Version label in sidebar footer:** LorkERP v1.0  
**Default HTTP port:** 3020  
**Default admin:** username `admin`, password `admin123` (force change via settings; seed on empty DB)

**Product nature:** Operator ERP for a remittance / payroll-agency style business that:

- Imports **financial cycles** from Google Sheets (management table + agent salary table)
- **Audits payroll** against sheets
- Tracks **cash funds**, **accreditations (معتمدون)**, **transfer companies**, **sub-agencies**, **shipping inventory (gold/crystal)**
- Aggregates **debts / receivables / payment due / expenses / FX spread / profit sources**
- Exports **PDF reports**
- Optional **WhatsApp AI agent** with OTP identity isolation
- AI **message sorting** into tables

**Accounting model (MUST):** Fund-centric cash ledgers + entity payable/receivable buckets + analytical ledger buckets (`net_profit`, `main_cash`, `expense`). **Do NOT** implement a classic Chart of Accounts with balanced journal lines across GL accounts.

---

## 1. Technology requirements (recommended stack matching the product)

- Node.js 18+, Express 4
- PostgreSQL 14+
- EJS server-rendered pages + vanilla JS front-end
- Tailwind CSS 4 built from a single input CSS file
- express-session (7-day cookie, httpOnly, sameSite=lax; file session store in production, memory in dev)
- bcryptjs password hashing
- AES-256-GCM for storing AI API keys (key material derived from SESSION_SECRET)
- googleapis for Google Sheets
- Puppeteer for PDF
- Socket.IO for AI job progress
- multer + xlsx for uploads
- Optional embedded WhatsApp Evolution-compatible API mount
- Helmet with CSP allowing required CDNs; compression middleware

 monorepo-style optional WhatsApp integration folder is acceptable.

---

## 2. Information architecture / navigation (MUST match)

Authenticated shell with dark sidebar (`#1a1a2e`), mobile header, mobile bottom nav, floating action button (FAB).

### Sidebar

1. **لوحة التحكم** → `/dashboard`
2. Group **عمليات وإدخال**
   - Sheet → `/sheet`
   - تدقيق الرواتب → `/payroll` (badge مسودة when local draft exists)
   - البحث → `/search`
   - الوكالات الفرعية → `/sub-agencies`
   - بيانات المستخدمين → `/member-directory`
   - إضافات وخصومات → `/member-adjustments`
   - وساطة إدارية → `/admin-brokerage`
   - وكيل واتساب → `/whatsapp`
3. ترتيب الرسائل → `/messages`
4. فرق التصريف → `/fx-spread`
5. شركات التحويل → `/transfer-companies`
6. الصناديق → `/funds`
7. الاعتمادات → `/approvals`
8. الشحن → `/shipping`
9. Group **المالية والالتزامات**
   - الديون → `/debts`
   - دين علينا → `/payables-us`
   - دين لنا → `/receivables-to-us`
   - مصادر الربح → `/profit-sources`
   - مطلوب دفع → `/payment-due`
   - سجل الحركات → `/financial-movements`
   - المصاريف → `/expenses-manual`
10. الإعدادات → `/settings`
11. تسجيل الخروج → `/logout`

### Also implement routes (not necessarily sidebar)

- `/deferred-balance` (linked from dashboard deferred card)
- Detail routes: `/funds/:id`, `/approvals/:id`, `/sub-agencies/:id`, `/transfer-companies/:id`, `/member-directory/member/:memberUserId`, `/profit-sources/:sourceType/detail`, `/debts/company/:id`, `/debts/fund/:id`
- Stub pages: `/clients`, `/client-portal` (placeholder tabs only)
- Legacy redirects: `/payroll-google`→`/payroll`, `/sub_agencies`→`/sub-agencies`, `/main-agency`→`/dashboard`, `/local-audit`→`/dashboard`

### Mobile bottom nav

الرئيسية · تدقيق راتب · FAB(+) · المستخدمين · إضافات

### FAB menus

**صادر:** شحن(بيع) · مكافأة وكالة · صرف شركة تحويل · تمويل صندوق من الرئيسي · مصروف · مطلوب دفع · رابط مرتجع  
**وارد:** شحن(شراء/تبديل راتب) · دين · اعتماد · فرق تصريف · دين لنا · خصم وكالة  

FAB should load liquidity context from `GET /api/quick-action/context`.

---

## 3. Authentication & authorization

### MUST

- Pages: GET `/login`, POST `/login`, GET `/logout`
- Session fields: `userId`, `user{id,username,displayName,role,useSimpleFinancialTerms}`
- Seed admin from env `ADMIN_USERNAME`/`ADMIN_PASSWORD`
- `requireAuth` on all app pages/APIs except login and WhatsApp webhook
- `requireRole('admin')` on WhatsApp management APIs only
- Webhook auth via header `x-lorkerp-webhook-secret`

### MUST NOT (unless later asked)

- Public registration, MFA, email verification, password reset emails, JWT refresh tokens, fine-grained permission strings

`users.role` exists (default `admin`) but financial modules are available to any authenticated user.

---

## 4. Database (MUST)

Implement PostgreSQL tables covering at least:

**users, settings, ai_config, message_analyses, analysis_jobs, google_sheets_config**

**financial_cycles** (incl. spreadsheet refs, sheet JSON/data fields, `transfer_discount_pct`, `payroll_audit_closed_at`, user_info fields)

**payroll_settings, payroll_cycle_columns, payroll_cycle_cache, payroll_user_audit_cache**

**deferred_salary_lines** unique (user_id, cycle_id, member_user_id); optional deferred_balance_users

**shipping_*** : approved, sub_agencies, transactions, companies, inventory(user_id,item_type PK), carriers + carrier txs, agency mappings/links/cycle users/sync log, sub_agency_cycle_settings, sub_agency_transactions

**transfer_companies, transfer_company_ledger** (with cancel + movement_group columns)

**funds, fund_balances(PK fund_id+currency), fund_ledger, fund_transfers, profit_transfer_batches**

Auto-create on first use:

- Main fund `الصندوق الرئيسي` `is_main=1`
- Profit fund `صندوق الربح` `exclude_from_dashboard=1`

**accreditation_entities** with `balance_amount`, `balance_payable`, `balance_receivable`, `pinned`, `is_primary`, `code`

**accreditation_ledger**

**financial_returns** (disposition, target_fund, cancelled_at, payables_settled, net_amount, movement_group_id)

**entity_payables** (settlement_mode)

**fx_spread_entries**

**ledger_entries** (bucket, source_type, amount abs, direction ±1, cycle_id, refs, meta_json)

**expense_entries, admin_brokerage_entries, salary_swap_entries**

**movement_side_effects**

**member_profiles, member_profile_events, member_adjustments** (kind in deduct|add|reward)

**financial_cycle_opening_snapshots, cash_box_snapshot**

**WhatsApp:** identities, otp, sessions, access_log, rate_limit, config, agent_persona, agent_memory

Indexes as needed for ledger(user,bucket), members, WhatsApp uniqueness.

On boot: migrate/ensure columns; seed admin.

---

## 5. Accounting rules (MUST implement exactly)

### 5.1 Fund adjust

Upsert balance by delta; append fund_ledger with **signed** amount; type string identifies reason; optional ref_table/ref_id/movement_group_id.

### 5.2 Analytical ledger

Store absolute amount + direction.  
`sum(net_profit)` = sum(amount*direction) with dedupe: ignore `cycle_creation_discount_profit` when same cycle has `transfer_discount_profit`.

When inserting `net_profit`, also mirror into profit fund as `net_profit_mirror` unless source_type is `sub_agency_company_profit` (that path moves cash main→profit separately).

### 5.3 Dashboard stats formulas

```
shippingProfit = SUM(profit_amount) FROM shipping_transactions WHERE type='sell'
ledgerNetProfit = sumLedgerBucket(net_profit)
netProfit (dashboard field) = shippingProfit + ledgerNetProfit
totalExpensesLedger = sumLedgerBucket(expense)
shippingBalanceQty = gold_on_hand_calc + crystal_on_hand_calc
  (from buy-sell qty aggregates OR inventory table — keep consistent)
```

Debt total:

```
totalDebts = shippingDebtSells
  + SUM(accreditation.balance_payable > 0)
  + SUM(entity_payables USD)
  + SUM(|negative TC USD balances|)
  + SUM(|negative fund USD balances|)
```

Do **not** add FX spread sum into totalDebts (compute separately if needed).

Receivables to us: negative sub-agency balances + accreditations with balance_amount<0 + member debt_to_company>0 + non-cancelled fund returns remain_at_entity + positive TC USD balances.

Payment due: accreditation payables>0 + positive sub-agency balances.

### 5.4 Inventory (gold/crystal)

Weighted average cost per (owner_user_id, item_type):

- Buy: qty+=q; cost_basis+=lineTotal
- Sell: reject if insufficient qty; profit=revenue−q*avgCost; capital=costAllocated; reduce qty/basis
- Cash sell: credit main fund **full line total**
- Cash buy: debit main fund full line total

### 5.5 Cycle create cascade

1. Insert cycle  
2. Ensure primary accreditation: sum agent salaries, apply transfer_discount_pct → net payable on primary entity; credit main fund seed; main_cash ledger  
3. Rebuild deferred from agent data; post transfer_discount_profit **once** per cycle; sync primary accreditation vs deferred total  
4. Opening snapshot of receivables/payment-due JSON  

### 5.6 Audit profits apply

Once per cycle:

- Management Y+Z columns sum → net_profit `audit_management_yz`
- Credit main fund amount = raw W sum + YZ (guard against duplicates)

Agency company share: debit main / credit profit fund; agency share via sub_agency_transactions type `profit`.

### 5.7 Accreditation add-amount kinds

Support salary directions and debt kinds including receivable/payable/payable_no_fund; brokerage % → net_profit; discount splits; optional deposit to main fund; **receivable offset modal required** when receivable>0.0001 (modes defer/full/custom + acknowledgment).

Transfer to fund/company with modes: payable (create entity_payables, may zero-amount ledger), main_fund (debit main credit dest with balance check), legacy.

Delivery settle: zero balance_payable **without** cash out.

Manual settle receivable↔payable: reduce both by same amount.

### 5.8 Returns

In a DB transaction: settle entity_payables FIFO first; net remainder follows disposition `transfer_to_fund` or `remain_at_entity` for fund or transfer_company; store side effects for cancel.

### 5.9 Expenses

Always insert expense_entries + expense ledger; optional debit main.

### 5.10 Admin brokerage

```
profit = amount * pct/100
mainPart = amount - profit
```

Post profit to net_profit(+mirror); credit main with mainPart + main_cash ledger.

### 5.11 FX spread

```
spreadUsd = abs(amt/internalRate - amt/settlementRate)
```

Insert entry; post net_profit; if fund entity move main↔dest at amt/internalRate; if company debit main only.  
**Note known product quirk:** deleting FX entry removes the row but historically did not reverse profit/funds — either recreate that quirk OR document and reverse properly; prefer **reversing on delete** only if you also update the Complete Spec conflict note — for faithful clone, keep non-reversing delete.

### 5.12 Cancel movements

Allow cancel of fund/company ledger rows that are part of movement groups / returns / transfers; write opposite balance delta + `movement_cancel`; set cancelled_at; restore payables from side effects. Block cancel of `net_profit_mirror` and `opening_reference`.

### 5.13 Transactions

Use DB transactions for: returns, fund transfers, receive-from-main, company payout/receivable, cancels.  
Other money ops may be multi-step without transaction (faithful) but prefer wrapping if you improve safety **without** changing business math.

---

## 6. Page & UI requirements (high level; match Arabic labels)

### Login

Brand LorkERP hero; username/password; eye toggle; error banners in Arabic.

### Dashboard home

Cycle select; report type select; تحديث; تنزيل PDF; KPI cards listed in Complete Spec; Sheets status pill; fund sources modal.

### Sheet

Cycle cards; create modal with Google Sheets tab OR file upload; sync/delete confirm.

### Payroll

Cycle load; column mapping; discount; colors; تدقيق; close/reopen audit session; results panel.

### Search

User id, cycle or all-cycles, selective audit to sheet, copy card / download image.

### Approvals

Searchable cards; bulk upload (file/paste/sheet URL) with preview/commit; delivery; add; list add-amount/transfer modals; detail page with balances لنا/علينا/صافي, settlement, ledger, pin, PDF.

### Funds / Transfer companies / Sub-agencies

Card lists + detail profiles with ledgers, PDF, returns/receivables/reward/deduct as specified.

### Shipping

Tabs: carriers | shipping ops | salary swap | **packages stub**. Sell/buy modals with buyer types and payment methods listed in UI spec.

### Debts / payables / receivables / payment-due / movements / expenses / fx / brokerage / profit-sources

Implement forms, filters, tables, empty/loading Arabic strings from UI spec.

### Member directory & adjustments

Paginated search table; detail sections; adjustments kinds خصم/إضافة/مكافأة with optional Google user-info sheet sync.

### Messages

Textarea → AI analyze with Socket.IO progress → table → copy CSV → export Google Sheets modal.

### Settings

Tabs: عام (currency, simple terms), ملف شخصي, AI providers (openai/gemini/openrouter), Google Sheets OAuth, destructive reset with confirm phrase `حذف نهائي` or `DELETE`.

### WhatsApp (admin APIs)

Tabs: اتصال, هويات, شخصية, سجل تدقيق; QR modal; OTP show-once; memory clear; persona preview.

### Stubs

clients & client-portal: tabs with «سيتم إضافة… قريباً» only.

### Design constraints

- RTL everywhere for app chrome
- Prefer solid button colors; member-directory style tables without nested white cards where documented
- Toast notifications
- Modal shell pattern with backdrop/Escape
- Touch-friendly controls

---

## 7. API surface (MUST)

Implement JSON APIs under the same path prefixes and verbs described in the Backend Spec condensed catalog, including approximately:

- `/dashboard/*` stats and deferred helpers  
- `/sheets/*` OAuth + export  
- `/api/sheet/*` cycles + payroll  
- `/api/search/*`  
- `/api/shipping/*`  
- `/api/sub-agencies/*`  
- `/api/funds/*`  
- `/api/transfer-companies/*`  
- `/api/accreditations/*` including bulk balance pipelines  
- `/api/returns`, `/api/debts`, `/api/fx-spread`, `/api/cycle-accounting`, `/api/expenses`, `/api/admin-brokerage`, `/api/payables`, `/api/financial-movements`, `/api/quick-action`, `/api/payment-due`  
- `/api/reports` PDF + summary + cycle-unified  
- `/api/member-directory`, `/api/member-adjustments`  
- `/settings/*`  
- `/ai/*` + Socket.IO rooms `analysis:{jobId}`  
- `/api/whatsapp/*`  

Responses generally `{ success, message?, ... }`. Auth failures 401; role failures 403.

---

## 8. Background work

- Interval worker syncing Google cycle caches (env interval; disable flag)
- In-process AI jobs
- Optional Evolution WhatsApp embed on same port under `/whatsapp-api` when enabled by env

No Redis required.

---

## 9. Reports

HTML→PDF reports: sub-agency, all sub-agencies, accreditations (±net), transfer companies, company ledger, fund ledger, all funds, movements, comprehensive, reconciliation, cycle unified. JSON summary endpoint. Dashboard drives downloads.

Reconciliation / summary math must follow Backend Spec (shipping profit + ledger sources − expenses − obligations as defined).

---

## 10. Security requirements

- bcrypt passwords  
- httpOnly sessions  
- Helmet CSP  
- Parameterized SQL  
- Encrypt AI keys at rest  
- WhatsApp OTP hashed, attempt limits, block windows, rate limit buckets  
- Optional restricted DB role + views for agent queries  
- Do not enable `secure` cookie unless TLS termination is configured (product historically used secure:false)

---

## 11. Testing requirements

At minimum, automate:

- WhatsApp identity isolation (member A cannot see member B)  
- Agent query scoping  
- Conversation OTP happy path  
- Agent memory store/recall  

Strongly recommended additions (not historically present): fund transfer atomicity, accreditation receivable offset enforcement, inventory insufficient qty, cycle discount profit idempotency, FX delete behavior.

---

## 12. Environment variables (implement)

PORT, SESSION_SECRET, DATABASE_URL, ADMIN_USERNAME, ADMIN_PASSWORD,  
GOOGLE_CLIENT_ID/SECRET/REDIRECT_URI/SPREADSHEET_ID,  
DISABLE_BACKGROUND_SYNC, CYCLE_SYNC_INTERVAL_MS,  
EVOLUTION_EMBEDDED, EVOLUTION_MOUNT_PATH, EVOLUTION_API_KEY, WHATSAPP_WEBHOOK_SECRET, PUBLIC_BASE_URL,  
WhatsApp OTP/session/rate/humanize/STT/OpenRouter/memory knobs,  
AGENT_DB_USER/PASSWORD/URL/query limits.

Provide `.env.example` documenting each.

---

## 13. Non-goals / stubs

- Classic GL CoA / trial balance module  
- Full CRM clients & client portal (stub only)  
- Shipping packages tab content  
- Email/SMS/push notifications  
- Multi-company SaaS tenancy beyond per-user_id rows  
- Fine-grained RBAC matrix beyond WhatsApp admin  

---

## 14. Acceptance checklist

A recreation is complete when:

1. Admin can log in and see dashboard KPIs matching formulas.  
2. Cycle can be created from Google or upload and seeds primary accreditation + deferred.  
3. Payroll audit updates member status and can close session.  
4. Funds show main + profit pool; transfers update both ledgers and balances.  
5. Accreditation add-amount respects receivable offset rules and posts profit/fund correctly.  
6. Shipping buy/sell updates weighted-average inventory and main fund on cash.  
7. Debts/receivables/payment-due aggregates match formulas.  
8. Expenses and brokerage post to ledger buckets.  
9. PDFs generate for fund/accreditation/sub-agency.  
10. AI message analyze streams progress over Socket.IO.  
11. WhatsApp admin can link identity, issue OTP; webhook rejects bad secret.  
12. Clients/portal remain stubs; packages tab stub.  
13. Reset data requires confirmation phrase.  
14. Entire UI is Arabic RTL.

---

## 15. Implementation order (suggested)

1. DB schema + auth + shell/nav  
2. Funds + ledger primitives + dashboard stats  
3. Cycles + Google read + primary accreditation + deferred  
4. Payroll audit + search  
5. Accreditations + bulk + delivery  
6. Transfer companies + returns + debts  
7. Sub-agencies + agency sync profits  
8. Shipping inventory + salary swap  
9. Expenses, FX, brokerage, profit sources, movements, payment due  
10. Reports PDF  
11. Members + adjustments  
12. Settings + reset  
13. Messages AI + Sheets export  
14. WhatsApp agent stack  
15. FAB quick actions polish + tests  

---

Build the system so another team can operate it using only this prompt and the companion human-readable specs if available. Prefer explicit Arabic copy and exact route paths over redesign.
