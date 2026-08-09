-- PostgreSQL schema for LorkERP
-- Run: psql postgresql://lork:123456@localhost:5432/lorkerp -f db/schema.pg.sql

CREATE TABLE IF NOT EXISTS users (
  id SERIAL PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  display_name TEXT NOT NULL,
  role TEXT DEFAULT 'admin',
  avatar TEXT,
  use_simple_financial_terms BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_config (
  id SERIAL PRIMARY KEY,
  provider TEXT NOT NULL,
  api_key_encrypted TEXT NOT NULL,
  selected_model TEXT,
  models_cache TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_analyses (
  id SERIAL PRIMARY KEY,
  input_text TEXT,
  output_table TEXT,
  provider TEXT,
  model TEXT,
  chunks_count INTEGER DEFAULT 1,
  status TEXT DEFAULT 'completed',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analysis_jobs (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  status TEXT DEFAULT 'pending',
  progress_current INTEGER DEFAULT 0,
  progress_total INTEGER DEFAULT 0,
  output_table TEXT,
  provider TEXT,
  model TEXT,
  chunks_count INTEGER DEFAULT 0,
  error_message TEXT,
  exported_to_sheets INTEGER DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS google_sheets_config (
  id SERIAL PRIMARY KEY,
  spreadsheet_id TEXT,
  credentials TEXT,
  token TEXT,
  sync_enabled INTEGER DEFAULT 0,
  last_sync TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS financial_cycles (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  management_data TEXT,
  agent_data TEXT,
  management_spreadsheet_id TEXT,
  management_sheet_name TEXT,
  agent_spreadsheet_id TEXT,
  agent_sheet_name TEXT,
  user_info_data TEXT,
  user_info_sheet_name TEXT,
  user_info_spreadsheet_id TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payroll_settings (
  user_id INTEGER PRIMARY KEY,
  discount_rate REAL DEFAULT 0,
  agent_color TEXT DEFAULT '#8b5cf6',
  management_color TEXT DEFAULT '#facc15',
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payroll_cycle_columns (
  user_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL,
  mgmt_user_id_col TEXT DEFAULT 'A',
  agent_user_id_col TEXT DEFAULT 'A',
  agent_salary_col TEXT DEFAULT 'D',
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, cycle_id)
);

CREATE TABLE IF NOT EXISTS payroll_cycle_cache (
  user_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL,
  management_data TEXT,
  agent_data TEXT,
  management_sheet_name TEXT,
  agent_sheet_name TEXT,
  audited_agent_ids TEXT,
  audited_mgmt_ids TEXT,
  found_in_target_sheet_ids TEXT,
  synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  stale_after TIMESTAMP,
  PRIMARY KEY (user_id, cycle_id)
);

CREATE TABLE IF NOT EXISTS payroll_user_audit_cache (
  user_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  audit_status TEXT DEFAULT 'غير مدقق',
  audit_source TEXT,
  details_json TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, cycle_id, member_user_id)
);

/** تدقيق الرواتب المحلي — دورات مرفوعة كملفات، جدولان (إدارة / وكيل) + معلومات المستخدمين */
CREATE TABLE IF NOT EXISTS payroll_native_cycles (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payroll_native_management_workbook (
  cycle_id INTEGER PRIMARY KEY REFERENCES payroll_native_cycles(id) ON DELETE CASCADE,
  sheets_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_native_agent_workbook (
  cycle_id INTEGER PRIMARY KEY REFERENCES payroll_native_cycles(id) ON DELETE CASCADE,
  sheets_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_native_userinfo_workbook (
  cycle_id INTEGER PRIMARY KEY REFERENCES payroll_native_cycles(id) ON DELETE CASCADE,
  sheets_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_native_settings (
  user_id INTEGER NOT NULL,
  native_cycle_id INTEGER NOT NULL,
  mgmt_user_id_col TEXT DEFAULT 'A',
  agent_user_id_col TEXT DEFAULT 'A',
  agent_salary_col TEXT DEFAULT 'D',
  user_info_user_id_col TEXT DEFAULT 'C',
  user_info_title_col TEXT DEFAULT 'D',
  user_info_salary_col TEXT DEFAULT 'L',
  user_info_sheet_index INTEGER DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, native_cycle_id),
  FOREIGN KEY (native_cycle_id) REFERENCES payroll_native_cycles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS payroll_native_user_audit (
  user_id INTEGER NOT NULL,
  native_cycle_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  audit_status TEXT DEFAULT 'غير مدقق',
  audit_source TEXT,
  details_json TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, native_cycle_id, member_user_id),
  FOREIGN KEY (native_cycle_id) REFERENCES payroll_native_cycles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS shipping_approved (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shipping_sub_agencies (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  commission_percent REAL DEFAULT 0,
  company_percent REAL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sub_agency_transactions (
  id SERIAL PRIMARY KEY,
  sub_agency_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  amount REAL NOT NULL,
  notes TEXT,
  cycle_id INTEGER,
  member_user_id TEXT,
  shipping_transaction_id INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (sub_agency_id) REFERENCES shipping_sub_agencies(id)
);

CREATE TABLE IF NOT EXISTS shipping_companies (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shipping_transactions (
  id SERIAL PRIMARY KEY,
  type TEXT NOT NULL,
  item_type TEXT NOT NULL,
  quantity REAL NOT NULL,
  unit_price REAL NOT NULL,
  total REAL NOT NULL,
  payment_method TEXT NOT NULL,
  status TEXT DEFAULT 'completed',
  buyer_type TEXT,
  buyer_user_id TEXT,
  buyer_approved_id INTEGER,
  buyer_sub_agency_id INTEGER,
  salary_deduction_user_id TEXT,
  purchase_source TEXT,
  purchase_company_id INTEGER,
  purchase_company_name TEXT,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agency_sheet_mapping (
  id SERIAL PRIMARY KEY,
  sub_agency_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL,
  sheet_name TEXT NOT NULL,
  spreadsheet_id TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(sub_agency_id, cycle_id),
  FOREIGN KEY (sub_agency_id) REFERENCES shipping_sub_agencies(id)
);

CREATE TABLE IF NOT EXISTS user_agency_link (
  id SERIAL PRIMARY KEY,
  member_user_id TEXT NOT NULL UNIQUE,
  sub_agency_id INTEGER NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (sub_agency_id) REFERENCES shipping_sub_agencies(id)
);

CREATE TABLE IF NOT EXISTS agency_cycle_users (
  id SERIAL PRIMARY KEY,
  cycle_id INTEGER NOT NULL,
  sub_agency_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  user_name TEXT,
  base_profit_w REAL DEFAULT 0,
  synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(cycle_id, sub_agency_id, member_user_id),
  FOREIGN KEY (sub_agency_id) REFERENCES shipping_sub_agencies(id)
);

CREATE TABLE IF NOT EXISTS agency_sync_log (
  id SERIAL PRIMARY KEY,
  cycle_id INTEGER NOT NULL,
  synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  users_count INTEGER DEFAULT 0,
  agencies_count INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cash_box_snapshot (
  id SERIAL PRIMARY KEY,
  cycle_id INTEGER NOT NULL,
  snapshot_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  cash_balance REAL DEFAULT 0,
  source_first_sheet_w REAL DEFAULT 0,
  source_y_z REAL DEFAULT 0,
  company_profit REAL DEFAULT 0,
  details_json TEXT
);

CREATE TABLE IF NOT EXISTS deferred_balance_users (
  id SERIAL PRIMARY KEY,
  cycle_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  extra_id_c TEXT,
  balance_d REAL DEFAULT 0,
  sheet_source TEXT,
  synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

/** أرصدة مؤجلة لكل دورة مع ترحيل عبر الدورات (لا تُحذف عند دورة جديدة) */
CREATE TABLE IF NOT EXISTS deferred_salary_lines (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  extra_id_c TEXT,
  balance_d REAL NOT NULL DEFAULT 0,
  salary_before_discount REAL,
  sheet_source TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, cycle_id, member_user_id)
);
CREATE INDEX IF NOT EXISTS idx_deferred_salary_lines_uid ON deferred_salary_lines(user_id);
CREATE INDEX IF NOT EXISTS idx_deferred_salary_lines_member ON deferred_salary_lines(user_id, member_user_id);

-- مخزون الشحن: متوسط التكلفة لكل مستخدم ونوع سلعة
CREATE TABLE IF NOT EXISTS shipping_inventory (
  user_id INTEGER NOT NULL,
  item_type TEXT NOT NULL,
  quantity_on_hand REAL NOT NULL DEFAULT 0,
  total_cost_basis REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, item_type)
);

-- نسبة الوكالة الفرعية المحفوظة لكل دورة مالية
CREATE TABLE IF NOT EXISTS sub_agency_cycle_settings (
  cycle_id INTEGER NOT NULL,
  sub_agency_id INTEGER NOT NULL,
  commission_percent REAL DEFAULT 0,
  company_percent REAL DEFAULT 0,
  saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (cycle_id, sub_agency_id),
  FOREIGN KEY (sub_agency_id) REFERENCES shipping_sub_agencies(id) ON DELETE CASCADE
);

-- وكالات الشحن (ناقلون)
CREATE TABLE IF NOT EXISTS shipping_carrier_agencies (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shipping_carrier_transactions (
  id SERIAL PRIMARY KEY,
  carrier_id INTEGER NOT NULL REFERENCES shipping_carrier_agencies(id) ON DELETE CASCADE,
  direction TEXT NOT NULL,
  amount REAL DEFAULT 0,
  quantity REAL DEFAULT 0,
  notes TEXT,
  shipping_transaction_id INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- شركات التحويل (موسّعة)
CREATE TABLE IF NOT EXISTS transfer_companies (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  country TEXT,
  region_syria TEXT,
  balance_amount REAL DEFAULT 0,
  balance_currency TEXT DEFAULT 'USD',
  transfer_types TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transfer_company_ledger (
  id SERIAL PRIMARY KEY,
  company_id INTEGER NOT NULL REFERENCES transfer_companies(id) ON DELETE CASCADE,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- الصناديع
CREATE TABLE IF NOT EXISTS funds (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  fund_number TEXT,
  transfer_company_id INTEGER REFERENCES transfer_companies(id) ON DELETE SET NULL,
  country TEXT,
  region_syria TEXT,
  is_main INTEGER DEFAULT 0,
  exclude_from_dashboard INTEGER DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fund_balances (
  fund_id INTEGER NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
  currency TEXT NOT NULL DEFAULT 'USD',
  amount REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (fund_id, currency)
);

CREATE TABLE IF NOT EXISTS fund_ledger (
  id SERIAL PRIMARY KEY,
  fund_id INTEGER NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  amount REAL NOT NULL,
  currency TEXT NOT NULL DEFAULT 'USD',
  notes TEXT,
  ref_table TEXT,
  ref_id INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fund_transfers (
  id SERIAL PRIMARY KEY,
  from_fund_id INTEGER NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
  to_fund_id INTEGER NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS profit_transfer_batches (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  fund_id INTEGER NOT NULL REFERENCES funds(id) ON DELETE CASCADE,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  cycle_id INTEGER,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- اعتمادات (معتمدون بكود)
CREATE TABLE IF NOT EXISTS accreditation_entities (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  code TEXT,
  balance_amount REAL DEFAULT 0,
  balance_payable REAL DEFAULT 0,
  balance_receivable REAL DEFAULT 0,
  pinned INTEGER DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ترقية قواعد قديمة: فصل «علينا» عن «لنا»
ALTER TABLE accreditation_entities ADD COLUMN IF NOT EXISTS balance_payable REAL DEFAULT 0;
ALTER TABLE accreditation_entities ADD COLUMN IF NOT EXISTS balance_receivable REAL DEFAULT 0;

CREATE TABLE IF NOT EXISTS accreditation_ledger (
  id SERIAL PRIMARY KEY,
  accreditation_id INTEGER NOT NULL REFERENCES accreditation_entities(id) ON DELETE CASCADE,
  entry_type TEXT NOT NULL,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  direction TEXT,
  brokerage_pct REAL,
  brokerage_amount REAL,
  cycle_id INTEGER,
  notes TEXT,
  meta_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE shipping_transactions ADD COLUMN IF NOT EXISTS cost_allocated REAL;
ALTER TABLE shipping_transactions ADD COLUMN IF NOT EXISTS profit_amount REAL;
ALTER TABLE shipping_transactions ADD COLUMN IF NOT EXISTS capital_amount REAL;
ALTER TABLE shipping_transactions ADD COLUMN IF NOT EXISTS buyer_carrier_id INTEGER;
ALTER TABLE shipping_transactions ADD COLUMN IF NOT EXISTS purchase_transfer_company_id INTEGER;

-- مرتجع مالي (شركة تحويل / صندوق)
CREATE TABLE IF NOT EXISTS financial_returns (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id INTEGER NOT NULL,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  sent_amount REAL,
  utilized_amount REAL,
  disposition TEXT NOT NULL,
  target_fund_id INTEGER REFERENCES funds(id) ON DELETE SET NULL,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- تسجيل ديون صريحة (مديونية على حسابنا تجاه صندوق أو شركة)
CREATE TABLE IF NOT EXISTS entity_payables (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id INTEGER NOT NULL,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE transfer_company_ledger ADD COLUMN IF NOT EXISTS ref_table TEXT;
ALTER TABLE transfer_company_ledger ADD COLUMN IF NOT EXISTS ref_id INTEGER;

-- فرق التصريف: مقارنة سعر التصريف الداخلي (مثلاً للرواتب) بسعر التسليم لشركة التحويل
CREATE TABLE IF NOT EXISTS fx_spread_entries (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  cycle_id INTEGER REFERENCES financial_cycles(id) ON DELETE SET NULL,
  currency TEXT NOT NULL,
  amount_foreign REAL NOT NULL,
  internal_rate REAL NOT NULL,
  settlement_rate REAL NOT NULL,
  spread_usd REAL NOT NULL,
  entity_type TEXT,
  entity_id INTEGER,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- دفتر محاسبي موحّد للوحة والتقارير
CREATE TABLE IF NOT EXISTS ledger_entries (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  bucket TEXT NOT NULL,
  source_type TEXT NOT NULL,
  amount REAL NOT NULL,
  currency TEXT NOT NULL DEFAULT 'USD',
  direction SMALLINT DEFAULT 1,
  cycle_id INTEGER REFERENCES financial_cycles(id) ON DELETE SET NULL,
  ref_table TEXT,
  ref_id INTEGER,
  notes TEXT,
  meta_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_user_bucket ON ledger_entries(user_id, bucket);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_cycle ON ledger_entries(user_id, cycle_id);

CREATE TABLE IF NOT EXISTS expense_entries (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  amount REAL NOT NULL,
  currency TEXT DEFAULT 'USD',
  category TEXT,
  notes TEXT,
  ref_table TEXT,
  ref_id INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_brokerage_entries (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  cycle_id INTEGER REFERENCES financial_cycles(id) ON DELETE SET NULL,
  amount REAL NOT NULL,
  brokerage_pct REAL NOT NULL,
  profit_amount REAL NOT NULL,
  main_fund_amount REAL NOT NULL,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS salary_swap_entries (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  company_id INTEGER REFERENCES transfer_companies(id) ON DELETE SET NULL,
  gross_amount REAL NOT NULL,
  discount_pct REAL DEFAULT 0,
  payment_mode TEXT NOT NULL,
  net_after_discount REAL NOT NULL,
  first_installment REAL DEFAULT 0,
  debt_amount REAL DEFAULT 0,
  main_fund_credit REAL DEFAULT 0,
  expense_discount REAL DEFAULT 0,
  notes TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS member_profiles (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  display_name TEXT,
  last_seen_name TEXT,
  total_salary_audited_usd REAL DEFAULT 0,
  deferred_balance_usd REAL DEFAULT 0,
  debt_to_company_usd REAL DEFAULT 0,
  meta_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, member_user_id)
);
CREATE INDEX IF NOT EXISTS idx_member_profiles_uid ON member_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_member_profiles_member ON member_profiles(user_id, member_user_id);

CREATE TABLE IF NOT EXISTS member_profile_events (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  amount REAL,
  cycle_id INTEGER,
  notes TEXT,
  status TEXT DEFAULT 'done',
  meta_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_member_profile_events_member ON member_profile_events(user_id, member_user_id);
CREATE INDEX IF NOT EXISTS idx_member_profile_events_created ON member_profile_events(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS member_adjustments (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  member_user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  amount REAL NOT NULL,
  status TEXT DEFAULT 'pending',
  notes TEXT,
  cycle_id INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP,
  CONSTRAINT member_adjustments_kind_chk CHECK (kind IN ('deduct', 'add', 'reward'))
);

ALTER TABLE financial_cycles ADD COLUMN IF NOT EXISTS transfer_discount_pct REAL DEFAULT 0;
ALTER TABLE financial_cycles ADD COLUMN IF NOT EXISTS payroll_audit_user_info_hash TEXT;
ALTER TABLE financial_cycles ADD COLUMN IF NOT EXISTS payroll_audit_closed_at TIMESTAMP NULL;
ALTER TABLE payroll_settings ADD COLUMN IF NOT EXISTS last_deferred_offset_applied REAL DEFAULT 0;
ALTER TABLE payroll_settings ADD COLUMN IF NOT EXISTS deferred_sync_baseline_done INTEGER DEFAULT 0;
ALTER TABLE accreditation_entities ADD COLUMN IF NOT EXISTS is_primary INTEGER DEFAULT 0;
ALTER TABLE entity_payables ADD COLUMN IF NOT EXISTS settlement_mode TEXT DEFAULT 'payable';

CREATE TABLE IF NOT EXISTS financial_cycle_opening_snapshots (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  cycle_id INTEGER NOT NULL REFERENCES financial_cycles(id) ON DELETE CASCADE,
  receivables_json TEXT,
  payment_due_json TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cycle_open_snap_cycle ON financial_cycle_opening_snapshots(cycle_id);

-- ============================================================
-- طبقة الهوية والتحقق لوكيل واتساب (Evolution API)
-- كل جدول هنا مفصول تماماً عن جداول النظام الحالية،
-- ولا يعدّل أي جدول قائم — الإضافة آمنة ولا تعطّل أي وظيفة.
-- ============================================================

-- هوية مُتحقَّق منها تربط رقم واتساب بشخص واحد فقط داخل النظام
CREATE TABLE IF NOT EXISTS whatsapp_identities (
  id SERIAL PRIMARY KEY,
  owner_user_id INTEGER NOT NULL,
  subject_type TEXT NOT NULL DEFAULT 'member',
  member_user_id TEXT,
  operator_user_id INTEGER,
  phone_jid TEXT NOT NULL UNIQUE,
  display_name TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  verified_at TIMESTAMP,
  last_seen_at TIMESTAMP,
  failed_attempts INTEGER DEFAULT 0,
  blocked_until TIMESTAMP,
  created_by INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT wa_identities_subject_chk CHECK (subject_type IN ('member', 'operator')),
  CONSTRAINT wa_identities_status_chk CHECK (status IN ('pending', 'verified', 'blocked'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wa_ident_member ON whatsapp_identities(owner_user_id, member_user_id) WHERE member_user_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_wa_ident_operator ON whatsapp_identities(operator_user_id) WHERE operator_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wa_ident_owner ON whatsapp_identities(owner_user_id);

-- رموز التحقق المؤقتة (تُخزَّن مُجزَّأة، لا تُخزَّن نصاً صريحاً)
CREATE TABLE IF NOT EXISTS whatsapp_otp (
  id SERIAL PRIMARY KEY,
  identity_id INTEGER NOT NULL REFERENCES whatsapp_identities(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  attempts INTEGER DEFAULT 0,
  max_attempts INTEGER DEFAULT 5,
  consumed_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wa_otp_identity ON whatsapp_otp(identity_id, created_at DESC);

-- حالة المحادثة لكل رقم (سياق قصير + الهوية المرتبطة بالجلسة)
CREATE TABLE IF NOT EXISTS whatsapp_sessions (
  phone_jid TEXT PRIMARY KEY,
  identity_id INTEGER REFERENCES whatsapp_identities(id) ON DELETE CASCADE,
  state TEXT NOT NULL DEFAULT 'unverified',
  history_json TEXT,
  expires_at TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- سجل تدقيق: من سأل عن ماذا، وأي أداة نُفِّذت، وهل سُمح بها
CREATE TABLE IF NOT EXISTS whatsapp_access_log (
  id SERIAL PRIMARY KEY,
  phone_jid TEXT,
  identity_id INTEGER,
  owner_user_id INTEGER,
  subject_ref TEXT,
  action TEXT NOT NULL,
  tool TEXT,
  detail TEXT,
  allowed BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wa_log_created ON whatsapp_access_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wa_log_identity ON whatsapp_access_log(identity_id, created_at DESC);

-- تحديد المعدّل لمنع التخمين والإغراق
CREATE TABLE IF NOT EXISTS whatsapp_rate_limit (
  phone_jid TEXT NOT NULL,
  bucket TEXT NOT NULL,
  window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  count INTEGER DEFAULT 0,
  PRIMARY KEY (phone_jid, bucket)
);

-- إعدادات الوكيل لكل مالك نظام
CREATE TABLE IF NOT EXISTS whatsapp_config (
  user_id INTEGER PRIMARY KEY,
  instance_name TEXT,
  enabled BOOLEAN DEFAULT FALSE,
  agent_enabled BOOLEAN DEFAULT TRUE,
  greeting TEXT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
