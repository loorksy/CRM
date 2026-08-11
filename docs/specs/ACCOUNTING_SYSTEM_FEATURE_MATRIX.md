# LorkERP — Complete Feature Matrix

Status values: **VERIFIED** | **PARTIALLY IMPLEMENTED** | **BACKEND ONLY** | **UI ONLY** | **UNKNOWN**

| Section | Page / Area | Feature | UI Action | API | Database | Permission | Status |
|---------|-------------|---------|-----------|-----|----------|------------|--------|
| Auth | Login | Login | Submit تسجيل الدخول | POST /login | users read | Public | VERIFIED |
| Auth | Logout | Logout | Click تسجيل الخروج | GET /logout | session destroy | Auth | VERIFIED |
| Auth | Registration | Self-register | — | — | — | — | UNKNOWN / not present |
| Auth | Password reset | Reset flow | — | — | — | — | UNKNOWN / not present |
| Dashboard | Home | KPI stats | Load / تحديث | GET /dashboard/stats | aggregates | Auth | VERIFIED |
| Dashboard | Home | Fund sources modal | Click main fund card | GET /dashboard/fund-sources | funds, balances | Auth | VERIFIED |
| Dashboard | Home | PDF download | تنزيل | /api/reports/pdf/* , cycle-unified | report builders | Auth | VERIFIED |
| Dashboard | Home | Sheets status | Load | GET /sheets/status | google_sheets_config | Auth | VERIFIED |
| Dashboard | Home | Transfer profit | (API available) | POST /dashboard/transfer-profit | profit_transfer_batches, funds | Auth | VERIFIED (API; UI surface UNKNOWN depth) |
| Cycles | Sheet | List cycles | Load | GET /api/sheet/cycles | financial_cycles | Auth | VERIFIED |
| Cycles | Sheet | Create cycle | إنشاء دورة | POST /api/sheet/cycles | cycles + acc seed + deferred | Auth | VERIFIED |
| Cycles | Sheet | Sync Google | Sync action | POST /api/sheet/cycles/:id/sync | cycle data + agency sync | Auth | VERIFIED |
| Cycles | Sheet | Upload files | Upload tab | POST /api/sheet/upload | parse only | Auth | VERIFIED |
| Cycles | Sheet | Delete cycle | حذف نهائياً | DELETE /api/sheet/cycles/:id | financial_cycles | Auth | VERIFIED |
| Payroll | Payroll page | Execute audit (Google) | تدقيق | POST /api/sheet/payroll-execute | cache + Google write | Auth | VERIFIED |
| Payroll | Payroll page | Local audit | (endpoint) | POST /api/sheet/payroll-audit-local | cache | Auth | VERIFIED |
| Payroll | Payroll page | Close/reopen session | إغلاق/إعادة فتح | close-audit / reopen-audit | financial_cycles.payroll_audit_closed_at | Auth | VERIFIED |
| Payroll | Settings | Discount/colors | حفظ الإعدادات | POST /api/sheet/payroll-settings | payroll_settings | Auth | VERIFIED |
| Payroll | Native cycles tables | Native workbook cycles | — | — | payroll_native_* | — | UNKNOWN / schema present |
| Search | Search | Search user | بحث | GET /api/search/search-user | caches/cycles | Auth | VERIFIED |
| Search | Search | Across cycles | شامل | GET /api/search/user-across-cycles | caches | Auth | VERIFIED |
| Search | Search | Selective audit | تنفيذ التدقيق الانتقائي | POST execute-audit-advanced | cache + Google | Auth | VERIFIED |
| Deferred | deferred-balance | List deferred | Load | GET /dashboard/deferred-users | deferred_salary_lines | Auth | VERIFIED |
| Deferred | deferred-balance | Refresh Google | تحديث من Google | POST /dashboard/refresh-deferred | deferred_* | Auth | VERIFIED |
| Deferred | deferred-balance | Merge | دمج وتدقيق | POST /dashboard/deferred-merge | deferred_salary_lines | Auth | VERIFIED |
| Cycle Acct | API | Apply audit profits | API/hooks | POST /api/cycle-accounting/apply-audit-profits/:id | ledger_entries, funds | Auth | VERIFIED |
| Cycle Acct | API | Rebuild deferred | API | POST rebuild-deferred/:id | deferred + primary sync | Auth | VERIFIED |
| Background | Worker | Cycle sheet sync | Auto interval | — | payroll_cycle_cache | Server | VERIFIED |
| Accreditations | approvals | List cards | Load | GET /api/accreditations/list | accreditation_entities | Auth | VERIFIED |
| Accreditations | approvals | Add entity | إضافة معتمد | POST /api/accreditations/add | accreditation_entities | Auth | VERIFIED |
| Accreditations | approvals | Bulk import | رفع أرصدة | bulk-balance-* | entities + ledger + funds | Auth | VERIFIED |
| Accreditations | approvals | Delivery settle | تسليم | POST delivery-settle | balance_payable | Auth | VERIFIED |
| Accreditations | detail | Add amount | إضافة مبلغ | POST :id/add-amount | buckets, funds, ledger | Auth | VERIFIED |
| Accreditations | detail | Transfer | تحويل | POST :id/transfer | buckets, funds/TC, payables | Auth | VERIFIED |
| Accreditations | detail | Settle rec↔pay | تنفيذ التسوية | POST settle-receivable-payable | buckets | Auth | VERIFIED |
| Accreditations | detail | Pin | تثبيت | POST :id/pin | pinned | Auth | VERIFIED |
| Funds | funds | List/add | إضافة صندوق | GET/POST /api/funds/* | funds, balances | Auth | VERIFIED |
| Funds | fund-detail | Transfer | ترحيل | POST :id/transfer | fund_ledger, fund_transfers | Auth | VERIFIED |
| Funds | fund-detail | Return | مرتجع | POST /api/returns | financial_returns, ledgers | Auth | VERIFIED |
| Funds | fund-detail | Cancel ledger | إلغاء التحويل | POST ledger/:id/cancel | cancel flags + reverse | Auth | VERIFIED |
| Funds | fund-detail | Receive from main | FAB/form | POST receive-from-main | funds / entity_payables | Auth | VERIFIED |
| Transfer Cos | list/detail | CRUD-ish + payout | إضافة / صرف | /api/transfer-companies/* | transfer_companies, ledger | Auth | VERIFIED |
| Transfer Cos | detail | Add receivable | إضافة دين لنا | POST add-receivable | company balance + payables settle | Auth | VERIFIED |
| Sub-agencies | list/detail | Sync Google | مزامنة | POST sync-from-management | agency_cycle_users, txs | Auth | VERIFIED |
| Sub-agencies | detail | Reward/deduct | مكافأة/خصم | POST reward/deduct | sub_agency_transactions ± funds/expense | Auth | VERIFIED |
| Sub-agencies | list | Delivery | تسليم | POST delivery-settle | txs due/zero | Auth | VERIFIED |
| Shipping | shipping | Sell | بيع | POST /api/shipping/sell | inventory, txs, funds | Auth | VERIFIED |
| Shipping | shipping | Buy | شراء | POST /api/shipping/buy | inventory, txs, funds | Auth | VERIFIED |
| Shipping | shipping | Salary swap | تبديل راتب | POST salary-swap | salary_swap_entries, expense, funds/debt | Auth | VERIFIED |
| Shipping | shipping | Carriers | إضافة وكالة شحن | carriers APIs | carrier tables | Auth | VERIFIED |
| Shipping | shipping | Packages tab | Tab الباقات | — | — | Auth | UI ONLY |
| Debts | debts | Overview | Load | GET /api/debts/overview | aggregates | Auth | VERIFIED |
| Debts | debts | Register | تسجيل الدين | POST /api/debts/register | entity_payables ± main fund | Auth | VERIFIED |
| Payables | payables-us | Overview | Load | GET /api/payables/overview | aggregates | Auth | VERIFIED |
| Receivables | receivables-to-us | Overview | Load | GET /dashboard/receivables-to-us | aggregates | Auth | VERIFIED |
| Payment due | payment-due | Settle | تسليم المحدد | POST /api/payment-due/settle | acc + agencies | Auth | VERIFIED |
| Expenses | expenses-manual | Add | تسجيل مصروف | POST /api/expenses/add | expense_entries, ledger, optional fund | Auth | VERIFIED |
| Expenses | expenses-manual | Unified ledger | تحديث/filter | GET ledger-unified | ledger + expenses | Auth | VERIFIED |
| Profit | profit-sources | By source | Load | GET net-profit-by-source | ledger + shipping override | Auth | VERIFIED |
| Profit | detail | Source detail | Open detail | GET …/detail | ledger or shipping rows | Auth | VERIFIED |
| FX | fx-spread | Add | حفظ | POST /api/fx-spread/add | fx_spread_entries + profit + funds | Auth | VERIFIED |
| FX | fx-spread | Delete | Delete | DELETE /api/fx-spread/:id | deletes row only | Auth | PARTIALLY IMPLEMENTED |
| Brokerage | admin-brokerage | Add | تسجيل | POST /api/admin-brokerage/add | admin_brokerage_entries + ledger/funds | Auth | VERIFIED |
| Movements | financial-movements | Feed | Filters/pager | GET /api/financial-movements/feed | multi-source | Auth | VERIFIED |
| Members | directory | List/search | بحث | GET /api/member-directory/list | member_profiles | Auth | VERIFIED |
| Members | detail | Profile | Open | GET …/member/:id | profiles, events, deferred | Auth | VERIFIED |
| Members | adjustments | Apply | تسجيل العملية | POST /api/member-adjustments/apply | member_adjustments ± sheet | Auth | VERIFIED |
| Messages | messages | AI analyze | تحليل واستخراج | POST /ai/analyze | analysis_jobs | Auth | VERIFIED |
| Messages | messages | Export Sheets | تصدير | POST /sheets/export | Google | Auth | VERIFIED |
| Settings | settings | Currency | حفظ | POST /settings/currency | settings | Auth | VERIFIED |
| Settings | settings | Profile/password | حفظ/تغيير | update-profile, change-password | users | Auth | VERIFIED |
| Settings | settings | AI keys | حفظ مفتاح | POST /ai/save-key | ai_config encrypted | Auth | VERIFIED |
| Settings | settings | Google connect | OAuth | /sheets/auth/callback | google_sheets_config | Auth | VERIFIED |
| Settings | settings | Reset data | حذف نهائي | POST /settings/reset-data | wipe categories | Auth | VERIFIED |
| Settings | settings | Simple terms | Checkbox | POST simple-financial-terms | users flag | Auth | VERIFIED |
| Clients | clients | Customer CRM | Tabs | — | — | Auth | UI ONLY |
| Portal | client-portal | Support portal | Tabs | — | — | Auth | UI ONLY |
| WhatsApp | whatsapp | Instance/QR | إنشاء/QR | /api/whatsapp/instance, qr | whatsapp_config | Admin | VERIFIED |
| WhatsApp | whatsapp | Identities/OTP | ربط/إصدار رمز | identities*, issue-code | whatsapp_identities, otp | Admin | VERIFIED |
| WhatsApp | whatsapp | Persona/memory | حفظ/مسح | persona, memory | persona, memory tables | Admin | VERIFIED |
| WhatsApp | webhook | Inbound messages | Evolution event | POST /api/whatsapp/webhook | sessions, logs | Secret | VERIFIED |
| WhatsApp | Agent RLS | Scoped SQL | Tools | agentQuery | agent views | Config | PARTIALLY IMPLEMENTED (needs AGENT_DB_PASSWORD) |
| Reports | various | PDF suite | تنزيل/PDF buttons | /api/reports/pdf/* | accountingReportData | Auth | VERIFIED |
| Quick Action | FAB | Out/In menus | صادر/وارد | multiple + /api/quick-action/context | reads | Auth | VERIFIED |
| Roles | system | Fine-grained ACL | — | — | users.role only | — | PARTIALLY IMPLEMENTED |
| CoA GL | accounting | Classic double-entry | — | — | — | — | UNKNOWN / not present |
| Email/SMS | notifications | Channels | — | — | — | — | UNKNOWN / not present |
| Multi-tenant | system | Org isolation | — | — | user_id scoping | Auth user | VERIFIED (single operator user model) |

---

## Counts (approximate)

| Metric | Count |
|--------|-------|
| Route modules | 27 |
| HTTP endpoints | ~198 |
| Page partials | ~35 |
| Sidebar destinations | ~20 (+ detail routes) |
| UI ONLY pages | 2 (+ shipping packages tab) |
| WhatsApp-focused tests | 4 files |
| Classic CoA | 0 |

---

*End of feature matrix.*
