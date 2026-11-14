import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/application/audit_plan_review_service.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

GoogleCell _text(String text) =>
    GoogleCell(entered: {'stringValue': text}, formatted: text);

GoogleSheetData _sheet(
  String spreadsheet,
  int id,
  String title,
  List<List<GoogleCell>> rows, {
  String account = 'account',
}) => GoogleSheetData(
  originAccountId: account,
  originSpreadsheetId: spreadsheet,
  ref: SheetRef(id: id, title: title),
  rows: [for (var i = 0; i < rows.length; i++) GoogleRow(i, rows[i])],
  merges: const [],
  protectedRanges: const [],
);

Map<String, Object?> _metadata(GoogleSheetData data) => {
  'kind': 'google',
  'accountId': data.originAccountId,
  'spreadsheetId': data.originSpreadsheetId,
  'sheetId': data.ref.id,
  'sheetTitle': data.ref.title,
  'googleReviewProof': AuditPlanReviewService.captureProof(data),
};

List<List<String>> _visibleRows(GoogleSheetData data) =>
    data.rows
        .map(
          (row) =>
              row.cells
                  .map(
                    (cell) =>
                        cell.entered['stringValue']?.toString() ??
                        cell.formatted,
                  )
                  .toList(),
        )
        .toList();

class _FakeGateway implements SheetsGateway {
  _FakeGateway(this.sheets);
  final Map<String, GoogleSheetData> sheets;
  int reads = 0;
  int writes = 0;
  Future<void> Function()? onRead;

  @override
  Future<GoogleSheetData> readSheet(
    String accountId,
    String spreadsheetId,
    int sheetId,
  ) async {
    reads++;
    await onRead?.call();
    final sheet = sheets[spreadsheetId];
    if (sheet == null || sheet.ref.id != sheetId) {
      throw StateError('missing test sheet');
    }
    return sheet;
  }

  Never _unexpectedWrite() {
    writes++;
    throw StateError('unexpected write');
  }

  @override
  Future<void> createSheet(
    String accountId,
    String spreadsheetId,
    String title,
  ) async => _unexpectedWrite();
  @override
  Future<void> writeRow(
    String accountId,
    String spreadsheetId,
    int sheetId,
    int rowIndex,
    List<Map<String, dynamic>> values,
  ) async => _unexpectedWrite();
  @override
  Future<void> colorRow(
    String accountId,
    String spreadsheetId,
    int sheetId,
    int rowIndex,
    int endColumn,
    Map<String, double> color,
  ) async => _unexpectedWrite();
  @override
  Future<void> writeStatus(
    String accountId,
    String spreadsheetId,
    int sheetId,
    int rowIndex,
    int columnIndex,
    String value,
  ) async => _unexpectedWrite();
  @override
  Future<List<SpreadsheetRef>> listSpreadsheets(String accountId) async =>
      throw UnimplementedError();
  @override
  Future<List<SheetRef>> listSheets(
    String accountId,
    String spreadsheetId,
  ) async => throw UnimplementedError();
}

void main() {
  sqfliteFfiInit();
  late LocalAuditDatabase db;
  late _FakeGateway gateway;
  late CycleRecord cycle;

  setUp(() async {
    db = await LocalAuditDatabase.open(
      databasePath: inMemoryDatabasePath,
      factory: databaseFactoryFfi,
    );
    final management = _sheet('management', 1, 'management', [
      [_text('الرقم'), _text('القيمة')],
      [
        _text('001'),
        const GoogleCell(entered: {'numberValue': 100}, formatted: '100'),
      ],
    ]);
    final agent = _sheet('agent', 2, 'agent', [
      [_text('الرقم')],
      [_text('001')],
    ]);
    final info = _sheet('info', 3, 'info', [
      [_text('الرقم'), _text('الوجهة')],
      [_text('001'), _text('وكالة')],
    ]);
    gateway = _FakeGateway({
      'management': management,
      'agent': agent,
      'info': info,
    });
    cycle = CycleRecord(
      id: 'cycle',
      name: 'دورة',
      createdAt: DateTime.utc(2026),
      managementSource: _metadata(management),
      agentSource: _metadata(agent),
      columnMappings: const {
        'managementId': 'A',
        'agentId': 'A',
        'userInfoId': 'A',
        'userInfoTitle': 'B',
        'userInfoStatus': '',
        'managementHeader': true,
        'agentHeader': true,
        'userInfoHeader': true,
      },
      snapshots: {
        'management': _visibleRows(management),
        'agent': _visibleRows(agent),
        'userInfo': _visibleRows(info),
        'userInfoSource': _metadata(info),
        'auditTarget': {'id': 'target', 'accountId': 'account'},
      },
    );
    await db.saveCycle(cycle);
  });
  tearDown(() => db.close());

  Future<dynamic> prepare() => AuditPlanReviewService(db, gateway).prepare(
    cycleId: 'cycle',
    accountId: 'account',
    agentColor: '#112233',
    managementColor: '#445566',
  );

  test(
    'review reads typed sources without saving operations or writing',
    () async {
      final preview = await prepare();
      expect(preview.plannedMemberCount, 1);
      expect(preview.operationCount, 5);
      expect(gateway.reads, 3);
      expect(gateway.writes, 0);
      expect(await db.listOperations('cycle'), isEmpty);
    },
  );

  test(
    'changed formula is detected even if its displayed value is unchanged',
    () async {
      gateway.sheets['management'] = _sheet('management', 1, 'management', [
        [_text('الرقم'), _text('القيمة')],
        [
          _text('001'),
          const GoogleCell(
            entered: {'formulaValue': '=50+50'},
            formatted: '100',
          ),
        ],
      ]);
      await expectLater(prepare(), throwsStateError);
      expect(gateway.writes, 0);
      expect(await db.listOperations('cycle'), isEmpty);
    },
  );

  test(
    'legacy text-only snapshot without proof requires a fresh download',
    () async {
      await db.saveCycle(
        CycleRecord(
          id: cycle.id,
          name: cycle.name,
          createdAt: cycle.createdAt,
          managementSource: {...cycle.managementSource}
            ..remove('googleReviewProof'),
          agentSource: cycle.agentSource,
          columnMappings: cycle.columnMappings,
          snapshots: cycle.snapshots,
        ),
      );
      await expectLater(prepare(), throwsStateError);
      expect(gateway.reads, 0);
    },
  );

  test(
    'modified locally saved rows are not accepted as the approved snapshot',
    () async {
      await db.saveCycle(
        CycleRecord(
          id: cycle.id,
          name: cycle.name,
          createdAt: cycle.createdAt,
          managementSource: cycle.managementSource,
          agentSource: cycle.agentSource,
          columnMappings: cycle.columnMappings,
          snapshots: {
            ...cycle.snapshots,
            'management': [
              ['غير مطابق'],
            ],
          },
        ),
      );
      await expectLater(prepare(), throwsStateError);
      expect(gateway.writes, 0);
    },
  );

  test('switching accounts is rejected before any Google reads', () async {
    await expectLater(
      AuditPlanReviewService(db, gateway).prepare(
        cycleId: 'cycle',
        accountId: 'different',
        agentColor: '#112233',
        managementColor: '#445566',
      ),
      throwsStateError,
    );
    expect(gateway.reads, 0);
  });
}
