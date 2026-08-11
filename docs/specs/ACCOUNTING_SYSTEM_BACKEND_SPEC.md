# LorkERP — Backend & Data Flow Specification

> Reverse-engineered from Express routes, services, and PostgreSQL schema.  
> No invented endpoints. Incomplete areas marked UNKNOWN / PARTIALLY IMPLEMENTED.

---

## 1. Architecture

- **Style:** Monolithic Express app, server-rendered EJS + JSON APIs.
- **Entry:** `server.js` creates HTTP server + Socket.IO, mounts routers, starts DB init then listen.
- **Layers:** Route handlers → Service modules → `getDb()` (`pg` Pool) → PostgreSQL.
- **Transactions:** `runTransaction(fn)` BEGIN/COMMIT/ROLLBACK helper in `db/database.js`.
- **Auth middleware:** `requireAuth`, `requireRole` in `middleware/auth.js`.

### Boot sequence

1. Load `.env`
2. Register process error handlers
3. Configure Helmet CSP, compression, JSON/urlencoded (skip Evolution paths), static, session
4. Mount all routers
5. `initDatabase()` — apply `schema.pg.sql`, ALTERs, seed admin, optional agent RLS
6. Single-instance lock (non-PM2)
7. Listen PORT
8. `startBackgroundSync` (cycle sheets)
9. `mountEvolution` optional

---

## 2. Modules & Services Map

| Domain | Route file | Key services |
|--------|------------|--------------|
| Auth | auth.js | bcrypt + users |
| Dashboard | dashboard.js | fundService, debtAggregation, paymentDue, deferredSalary, agencySync, ledgerService |
| Sheets OAuth | sheets.js | googleapis OAuth |
| Cycles/Payroll API | sheet.js | payrollAuditEngine, agencySync, cycleAccounting, accreditationCycle, deferred |
| Search | search.js | payrollSearchService |
| Shipping | shipping.js | shippingInventoryService, fundService, entityPayables |
| Sub-agencies | subAgencies.js | agencySyncService |
| Funds | funds.js | fundService, ledgerCancelService, returns |
| Transfer cos | transferCompanies.js | fundService, entityPayables, ledgerCancel |
| Accreditations | accreditations.js | accreditationBalance, DebtAmounts, BulkImport, CycleService, deferred sync |
| Returns | returns.js | returnsService |
| Debts | debts.js | debtAggregation, entityPayables, fundService |
| FX | fxSpread.js | ledgerService, fundService |
| Cycle accounting | cycleAccounting.js | cycleAccountingService |
| Expenses | expenses.js | ledgerService, fundService |
| Admin brokerage | adminBrokerage.js | ledgerService, fundService |
| Payables | payables.js | debtAggregation, entityPayablesService |
| Movements | financialMovements.js | financialMovementFeedService |
| Quick action | quickAction.js | fund/shipping reads |
| Payment due | paymentDue.js | paymentDueService + delivery settles |
| Reports | reports.js | accountingReportData, htmlAccountingPdf |
| Members | member-directory.js | memberDirectoryService |
| Adjustments | member-adjustments.js | memberAdjustmentsService, sheets sync |
| WhatsApp | whatsapp.js | services/whatsapp/* |
| AI | ai.js | aiService + Socket.IO |
| Settings | settings.js | resetDataService, crypto |
| Pages | pages.js | EJS only |
| Labels | — | accountingLabelsAr, financialTerminology |

---

## 3. Controllers / Route Patterns

- Handlers are inline async Express functions (no separate controller classes).
- Responses: `res.json({ success, ... })` or `res.render('dashboard', { page, title, user, ... })`.
- Errors: try/catch → JSON message or next(err).

---

## 4. Database Access

```
getDb() → { query, pool, runTransaction }
```

Parameterized SQL only (`$1`, `$2`, …). Schema applied on boot from `db/schema.pg.sql` plus imperative migrations in `database.js` (movement cancel columns, WhatsApp tables, member tables, etc.).

---

## 5. Accounting Engine (backend)

### Fund primitive

```
adjustFundBalance:
  UPSERT fund_balances.amount += delta
  INSERT fund_ledger(type, amount=delta, currency, notes, ref_*, movement_group_id)
```

### Analytical ledger

```
insertLedgerEntry(bucket, sourceType, amountSigned):
  amount = abs(amountSigned)
  direction = sign
  INSERT ledger_entries

insertNetProfitLedgerAndMirrorFund:
  insert net_profit
  mirror to صندوق الربح as net_profit_mirror (skip sub_agency_company_profit)
```

### Accreditation buckets

```
balance_amount = balance_payable - balance_receivable
applySalaryToThem / applyTransferOut / netToBuckets helpers
```

### Inventory

```
applyBuy: qty += q; cost_basis += lineTotal
applySell: profit = revenue - qty*avgCost; reduce qty/basis
```

### Entity payables FIFO

`settleOpenPayablesFifo` — oldest first, matching currency; delete or reduce rows.

### Cancel

`ledgerCancelService` reverses signed fund/company rows; restores payables from `movement_side_effects`; marks `cancelled_at`.

---

## 6. Important User Action Flows

### 6.1 Login

```
UI login form
 → POST /login
 → SELECT users WHERE username
 → bcrypt.compareSync
 → session.userId + session.user
 → redirect /dashboard
```

### 6.2 Create cycle

```
UI Sheet modal Save
 → POST /api/sheet/cycles
 → INSERT financial_cycles
 → ensurePrimaryAccreditationAfterCycleCreate
      (agent salaries × (1 - transfer_discount_pct)
       → primary accreditation payable
       → main fund primary_agent_seed
       → main_cash agent_table_primary_seed)
 → rebuildDeferredFromLocalAgentData
 → onFinancialCycleCreated (opening snapshot)
 → JSON { id }
 → UI refresh cycles
```

### 6.3 Accreditation add-amount (salary / debt)

```
UI modal fields + optional receivable offset
 → POST /api/accreditations/:id/add-amount
 → validate main fund when needed
 → if receivable>ε and offset missing → 400 RECEIVABLE_OFFSET_REQUIRED
 → update balance_payable / balance_receivable
 → optional: insertNetProfit (brokerage / discount)
 → optional: credit/debit main fund
 → INSERT accreditation_ledger
 → return balances
 → UI toast + refresh cards/detail
```

### 6.4 Fund transfer

```
UI transfer modal
 → POST /api/funds/:id/transfer { toFundId, amount, currency, notes }
 → runTransaction:
      debit source fund_ledger
      credit dest fund_ledger
      INSERT fund_transfers
 → UI refresh ledger
```

### 6.5 Shipping sell

```
UI sell modal
 → POST /api/shipping/sell
 → applySell inventory (or INSUFFICIENT_QTY)
 → INSERT shipping_transactions (profit_amount, capital_amount, cost_allocated, status)
 → if cash: creditShippingCashSale (main fund full total)
 → if debt: status=debt (counts in debtBreakdown)
 → other payment methods: salary deduction / agency percent paths as coded
 → JSON { profit, capitalRecovered }
```

### 6.6 Shipping buy

```
 → POST /api/shipping/buy
 → applyBuy
 → INSERT tx type=buy
 → if cash: debitShippingCashBuy
 → if debt: may create entity_payables / company effects
```

### 6.7 Expense add

```
 → POST /api/expenses/add { amount, category, notes, debitMainFund? }
 → optional adjustFundBalance -amount type manual_expense
 → INSERT expense_entries
 → INSERT ledger_entries bucket=expense sourceType=manual_expense
```

### 6.8 Admin brokerage

```
profitAmount = amount * brokeragePct/100
mainFundAmount = amount - profitAmount
INSERT admin_brokerage_entries
if profit>0: insertNetProfitLedgerAndMirrorFund source admin_brokerage
if main>0: credit main + main_cash ledger
```

### 6.9 FX spread add

```
spreadUsd = |amt/ir - amt/sr|
INSERT fx_spread_entries
insertNetProfitLedgerAndMirrorFund fx_spread_profit
if fund entity: debit main amt/ir; credit dest amt/ir
if company entity: debit main amt/ir only
```

### 6.10 FX spread delete (incomplete)

```
DELETE fx_spread_entries WHERE id
# does NOT reverse ledger or funds
```

### 6.11 Returns create

```
 → POST /api/returns
 → runTransaction createReturn:
      settle payables FIFO
      net = amount - settled
      INSERT financial_returns
      apply disposition (transfer_to_fund | remain_at_entity) to fund/company ledgers
      store movement_side_effects
```

### 6.12 Payment due settle

```
 → POST /api/payment-due/settle { accreditationIds[], subAgencyIds[], cycleId? }
 → zero accreditation balance_payable (delivery) + sub-agency delivery settle
 → NO main fund debit
```

### 6.13 Apply audit profits

```
 → POST /api/cycle-accounting/apply-audit-profits/:cycleId
 → applyCycleAuditProfitsToLedger
      net_profit audit_management_yz (once)
      main fund credit W_raw + YZ (once)
```

### 6.14 AI analyze

```
 → POST /ai/analyze { text }
 → INSERT analysis_jobs pending
 → return jobId
 → background chunks via provider
 → Socket.IO emit to analysis:{jobId}
 → GET /ai/analysis/:jobId for status/table
```

### 6.15 WhatsApp webhook

```
 → POST /api/whatsapp/webhook
 → validate x-lorkerp-webhook-secret
 → ack { received:true }
 → async messageHandler → identity OTP/session → agentBrain tools → scopedData/agentQuery → Evolution reply
```

---

## 7. API Catalog (condensed by mount)

### Auth `/`
- GET/POST `/login`, GET `/logout`

### Dashboard `/dashboard`
- GET `/`, `/stats`, `/fund-sources`, `/receivables-to-us`, `/payment-due`, `/debts-detail`, `/deferred-users`
- POST `/refresh-cash`, `/refresh-deferred`, `/transfer-profit`, `/deferred-merge`

### Sheets `/sheets`
- status, configure, save-spreadsheet, toggle-sync, disconnect, auth, callback, sheets-list, export

### Sheet API `/api/sheet`
- spreadsheets-list, spreadsheet-sheets, spreadsheet-columns
- cycles CRUD, structure, sync, import-google, upload
- payroll-settings GET/POST, payroll-audit-local, payroll-execute
- cycle/:id/audit-session, close-audit, reopen-audit

### Search `/api/search`
- cycles, pending-cycles, cycle-sheets, user-across-cycles, search-user
- execute-audit, execute-audit-advanced, member-deferred-history

### Shipping `/api/shipping`
- balance, approved, sub-agencies, carriers(+balance), companies, users
- sell, buy, salary-swap, transactions

### Sub-agencies `/api/sub-agencies`
- list, add, :id, profit, transactions, users, cycles/list
- cycle-percent, update-percent, sync-from-management
- delivery-candidates, delivery-settle, reward, deduct

### Funds `/api/funds`
- list, add, update-main, transfer-companies/list
- :id, set-main, receive-from-main, transfer, ledger/:ledgerId/cancel

### Transfer companies `/api/transfer-companies`
- list, add, :id, payout-from-main, add-receivable, ledger cancel

### Accreditations `/api/accreditations`
- list, add, with-balance, delivery-settle
- bulk-balance-* (file/text/sheet parse+commit)
- :id, pin, add-amount, transfer, settle-receivable-payable

### Other money APIs
- `/api/returns` POST /, GET for-entity
- `/api/debts` overview, register
- `/api/fx-spread` list, add, DELETE :id
- `/api/cycle-accounting` apply-audit-profits/:cycleId, rebuild-deferred/:cycleId
- `/api/expenses` total, ledger-unified, net-profit-by-source(+detail), add
- `/api/admin-brokerage` ledger, add
- `/api/payables/overview`
- `/api/financial-movements/feed`
- `/api/quick-action/context`
- `/api/payment-due/settle`

### Reports `/api/reports`
- pdf/* variants, summary, cycle-unified

### Members
- `/api/member-directory/list`, `/member/:memberUserId`
- `/api/member-adjustments/apply`

### Settings `/settings`
- page, currency GET/POST, simple-financial-terms, change-password, update-profile
- reset-data-options, reset-data

### AI `/ai`
- status, save-key, select-model, refresh-models, analyze, analysis/:jobId, jobs, history

### WhatsApp `/api/whatsapp`
- webhook (secret)
- admin: status, instance, qr, logout, operators, identities CRUD-ish, issue-code, persona, memory, providers, access-log

---

## 8. Business Rules (backend-enforced)

1. Auth required on nearly all APIs.
2. WhatsApp admin role gate.
3. Main fund must exist for cash-affecting accreditation/brokerage paths.
4. Receivable offset acknowledgment required when receivable outstanding.
5. Discount % clamped 0–100.
6. Transfer discount / audit YZ profits idempotent per cycle.
7. Inventory sell blocked if qty insufficient.
8. Fund allocate from main blocked if insufficient unless mode `payable`.
9. Cancel blocked for mirror/opening types.
10. Reset requires phrase `حذف نهائي` or `DELETE`.
11. Webhook requires shared secret.
12. Agent queries capped by `AGENT_QUERY_MAX_ROWS` / timeout when agent DB enabled.

---

## 9. Jobs / Queues / Events

| Name | Type | Details |
|------|------|---------|
| cycleSyncWorker | setInterval | Sync Google cycle caches; agency sync |
| AI analysis | in-process async + Socket.IO | progress events |
| WhatsApp handler | async after webhook ack | |
| Session reap | file store hourly | |

No Redis queue. No outbound business webhooks.

---

## 10. Notifications

- API returns messages for UI toasts.
- Socket.IO for AI only.
- WhatsApp outbound via Evolution client.

---

## 11. Caching

- payroll_cycle_cache / audit cache tables
- ai_config.models_cache
- No HTTP cache headers strategy beyond static

---

## 12. Security Backend

- bcrypt passwords
- AES-256-GCM AI keys (`utils/crypto.js`)
- Helmet CSP
- Parameterized SQL
- WhatsApp OTP hashed; rate_limit table; block windows
- Optional agent DB role without base table grants + views
- SESSION_SECRET default weak if unset
- CSRF not implemented
- Login rate limit not implemented

---

## 13. Audit

- whatsapp_access_log
- Append-only financial ledgers
- member_profile_events
- No universal change-data-capture audit table

---

## 14. Integrations Backend

- Google OAuth token stored in `google_sheets_config`
- Provider SDKs: openai, @google/generative-ai, OpenRouter HTTP
- Puppeteer PDF generation
- Evolution embed mount

---

## 15. Error Patterns

| Case | Backend |
|------|---------|
| Unauthenticated | 401 JSON or redirect |
| Forbidden role | 403 |
| Validation | 200/400 with `{success:false,message}` (mixed; many money routes use 200 + success false) |
| Unhandled | 500 JSON / error.ejs |
| Inventory | Error code INSUFFICIENT_QTY |
| Receivable offset | RECEIVABLE_OFFSET_REQUIRED |

---

## 16. Concurrency

- No optimistic version columns found on funds.
- Unique constraints prevent duplicate deferred lines / agency cycle users.
- Single-instance lock reduces multi-process races on one host.
- Partial multi-step non-transactional updates remain race-sensitive.

---

## 17. Testing Backend

WhatsApp isolation/memory/conversation tests only. Accounting paths lack automated tests.

---

## 18. Feature Dependencies

```
Financial Cycle
 ├── Google Sheets / Upload
 ├── Primary Accreditation seed
 ├── Deferred salaries
 ├── Payroll audit
 ├── Agency sync / company profit share
 └── Audit profits → main fund + net_profit

Accreditation
 ├── Funds (main / dest)
 ├── Transfer companies
 ├── entity_payables
 ├── ledger_entries / profit fund
 └── Delivery settle ↔ Payment due

Shipping
 ├── Inventory WA cost
 ├── Main fund cash
 ├── Buyers: members / approved / sub-agency / carrier
 └── Debts / salary swap ↔ transfer companies

Reports
 └── accountingReportData aggregates across all above
```

---

*End of backend specification.*
