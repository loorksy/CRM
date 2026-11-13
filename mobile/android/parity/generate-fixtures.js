#!/usr/bin/env node
/**
 * يشغّل محرك التدقيق الأصلي ويكتب النتائج التي تقارنها اختبارات Kotlin.
 * التشغيل: NODE_PATH=/tmp/audit-fixtures/node_modules node mobile/android/parity/generate-fixtures.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const engine = require('../../../services/payrollAuditEngine');
const search = require('../../../services/payrollSearchService');

function hashUserInfoRows(rows) {
  const normalized = rows.map((row) =>
    (Array.isArray(row) ? row : []).map((cell) => (cell == null ? '' : String(cell)))
  );
  return crypto.createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
}

function slim(out) {
  return {
    summary: out.summary,
    dataStart: out.dataStart,
    results: out.results.map((r) => ({
      userId: r.userId,
      title: r.title,
      type: r.type,
      salaryValue: r.salaryValue,
      statusLabel: r.statusLabel,
      rowIndex: r.rowIndex,
      mgmtSheetRowIndex: r.mgmtSheetRowIndex,
      agentSheetRowIndices: r.agentSheetRowIndices,
    })),
    byTitle: Object.fromEntries(
      Object.entries(out.byTitle).map(([k, items]) => [
        k,
        items.map((it) => ({
          type: it.type,
          color: it.color,
          userId: search.normalizeUserId(it.managementRow && it.managementRow[0]),
        })),
      ])
    ),
  };
}

const cases = [];
function add(name, input) {
  const out = engine.runPayrollAuditCore(input);
  cases.push({ name, input, output: slim(out) });
}

const headerUser = ['ت', 'اسم', 'رقم', 'ورقة', '', '', '', '', '', '', '', 'راتب'];
const headerMgmt = ['رقم', 'اسم'];
const headerAgent = ['رقم', 'اسم', '', 'راتب'];

add('راتب واحد بعد خصم', {
  managementRows: [headerMgmt, ['1001', 'علي']],
  agentRows: [headerAgent, ['1001', 'علي', '', '100']],
  userInfoRows: [headerUser, ['', '', '1001', 'الرياض', '', '', '', '', '', '', '', '']],
  discountRatePct: 10,
  agentColor: '#8b5cf6',
  managementColor: '#facc15',
});

add('راتبان', {
  managementRows: [headerMgmt, ['2002', 'هند']],
  agentRows: [headerAgent, ['2002', 'هند', '', '40'], ['2002', 'هند', '', '10']],
  userInfoRows: [headerUser, ['', '', '2002', 'جدة', '', '', '', '', '', '', '', '']],
  discountRatePct: 0,
  agentColor: '#8b5cf6',
  managementColor: '#facc15',
});

add('سحب إدارة فقط', {
  managementRows: [headerMgmt, ['3003', 'سامي']],
  agentRows: [headerAgent],
  userInfoRows: [headerUser, ['', '', '3003', 'مكة', '', '', '', '', '', '', '', '']],
  discountRatePct: 5,
  agentColor: '#8b5cf6',
  managementColor: '#facc15',
});

add('قيم فارغة وغير صالحة وصفر وسالب', {
  managementRows: [headerMgmt, ['4004', 'أ'], ['4005', 'ب'], ['4006', 'ج'], ['4007', 'د']],
  agentRows: [
    headerAgent,
    ['4004', 'أ', '', ''],
    ['4005', 'ب', '', 'abc'],
    ['4006', 'ج', '', '0'],
    ['4007', 'د', '', '-20'],
  ],
  userInfoRows: [
    headerUser,
    ['', '', '4004', 'أ', '', '', '', '', '', '', '', ''],
    ['', '', '4005', 'ب', '', '', '', '', '', '', '', ''],
    ['', '', '4006', 'ج', '', '', '', '', '', '', '', ''],
    ['', '', '4007', 'د', '', '', '', '', '', '', '', ''],
  ],
  discountRatePct: 0,
});

add('معرفات لا تتطابق ووكيل بلا إدارة', {
  managementRows: [headerMgmt, ['5001', 'فقط إدارة']],
  agentRows: [headerAgent, ['5002', 'فقط وكيل', '', '15']],
  userInfoRows: [
    headerUser,
    ['', '', '5001', 'ورقة1', '', '', '', '', '', '', '', ''],
    ['', '', '5002', 'ورقة2', '', '', '', '', '', '', '', ''],
    ['', '', '5003', 'ورقة3', '', '', '', '', '', '', '', ''],
  ],
  discountRatePct: 0,
});

add('تكرار الإدارة آخر صف والوكيل كل الصفوف وأصفار بادئة', {
  managementRows: [headerMgmt, ['007', 'قديم'], ['7', 'جديد']],
  agentRows: [headerAgent, ['007', 'ق', '', '5'], ['7', 'ج', '', '6']],
  userInfoRows: [headerUser, ['', '', '٠٠٧', 'العنوان', '', '', '', '', '', '', '', '']],
  discountRatePct: 0,
});

add('بدون صف عناوين', {
  managementRows: [['8', 'نورة']],
  agentRows: [['8', 'نورة', '', '12.5']],
  userInfoRows: [['', '', '8', 'تبوك', '', '', '', '', '', '', '', '']],
  discountRatePct: 0,
});

add('خصم يجعل الناتج كسوراً', {
  managementRows: [headerMgmt, ['9', 'ل']],
  agentRows: [headerAgent, ['9', 'ل', '', '10.25']],
  userInfoRows: [headerUser, ['', '', '9', 'أبها', '', '', '', '', '', '', '', '']],
  discountRatePct: 2.5,
});

// Seeded cases stay reproducible and compare against the current JS source.
let seed = 0x10abcdef;
function pick(values) {
  seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
  return values[seed % values.length];
}
for (let n = 0; n < 256; n++) {
  const columns = n % 2 ? {
    userInfoUserIdCol: 'AA', userInfoTitleCol: 'AB',
    cycleMgmtUserIdCol: 'B', cycleAgentUserIdCol: 'C', cycleAgentSalaryCol: 'E',
  } : {};
  const uidCol = n % 2 ? 26 : 2, titleCol = n % 2 ? 27 : 3;
  const mgmtCol = n % 2 ? 1 : 0, agentCol = n % 2 ? 2 : 0, salaryCol = n % 2 ? 4 : 3;
  const row = (values) => {
    const cells = [];
    for (const [col, value] of values) cells[col] = value;
    return Array.from(cells, (v) => v == null ? '' : v);
  };
  const managementRows = [], agentRows = [], userInfoRows = [];
  if (n % 3) {
    managementRows.push(row([[mgmtCol, 'رقم']]));
    agentRows.push(row([[agentCol, 'رقم']]));
    userInfoRows.push(row([[uidCol, 'رقم'], [titleCol, 'ورقة']]));
  }
  for (let i = 0; i < 12; i++) {
    const id = pick(['001', '1', '٢', '۳', '4.9', '-2', '7abc', '', '1e2']);
    userInfoRows.push(row([[uidCol, id], [titleCol, pick(['', 'عنوان أ', 'عنوان ب', '  جدة  '])]]));
    if (pick([true, false])) managementRows.push(row([[mgmtCol, id], [3, 'اسم']]));
    if (pick([true, false, true])) agentRows.push(row([[agentCol, id], [salaryCol,
      pick(['100', '10.25', '-20.005', '0', '', 'abc', '١٬٢٣٤.٥', '1e2', '1.0000000000000001e2', '1e9999999999999999999'])]]));
  }
  add(`seeded-${n}`, {
    managementRows, agentRows, userInfoRows, columns,
    discountRatePct: pick([-10, 0, 2.5, 10, 100, 150]),
    agentColor: pick(['', '#8b5cf6']), managementColor: pick(['', '#facc15']),
  });
}

const duplicates = engine.findDuplicateUserInfoMemberIds(
  [headerUser, ['', '', '11', 'أ'], ['', '', '011', 'ب'], ['', '', '12', 'ج']],
  'C'
);

const manual = [
  { inMgmt: false, inAgent: false, mgmtColored: true, agentColored: true },
  { inMgmt: true, inAgent: true, mgmtColored: false, agentColored: false },
  { inMgmt: true, inAgent: false, mgmtColored: false, agentColored: true },
  { inMgmt: true, inAgent: false, mgmtColored: true, agentColored: false },
  { inMgmt: false, inAgent: true, mgmtColored: true, agentColored: true },
].map((c) => ({ input: c, output: search.classifyManualStatus(c) }));

const ids = ['007', '٠٠٧', '7.9', 'abc', '', '  15 ', '1e2', '1e9999999999999999999', '1e-9999999999999999999', '1.0000000000000001e2', 'Infinity'];
const normalized = ids.map((v) => ({ in: v, out: search.normalizeUserId(v) }));

const hashRows = [headerUser, ['', '', '1001', 'الرياض']];
const payload = {
  cases,
  duplicates,
  manual,
  normalized,
  hash: { rows: hashRows, hex: hashUserInfoRows(hashRows) },
  salary: search.computeSalaryWithDiscount(['100', '50', ''], 10),
};

const dest = path.join(__dirname, '../engine/src/test/resources/payroll-fixtures.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify({ ...payload, cases: cases.slice(0, 8) }, null, 2) + '\n');
const seededDest = path.join(path.dirname(dest), 'payroll-seeded-fixtures.json');
fs.writeFileSync(seededDest, '[\n' + cases.slice(8).map((c) => '  ' + JSON.stringify(c)).join(',\n') + '\n]\n');
console.log('wrote', dest, 'cases', cases.length);
