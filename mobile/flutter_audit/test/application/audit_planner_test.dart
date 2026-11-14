import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/application/audit_planner.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

GoogleCell textCell(String value) =>
    GoogleCell(entered: {'stringValue': value}, formatted: value);
GoogleCell numberCell(num value) =>
    GoogleCell(entered: {'numberValue': value}, formatted: value.toString());
GoogleCell formulaCell(String value) =>
    GoogleCell(entered: {'formulaValue': value}, formatted: '100');

GoogleSheetData sheet(
  String spreadsheetId,
  int id,
  String title,
  List<List<GoogleCell>> rows, {
  String account = 'account',
}) => GoogleSheetData(
  ref: SheetRef(id: id, title: title),
  rows: [for (var i = 0; i < rows.length; i++) GoogleRow(i, rows[i])],
  merges: const [],
  protectedRanges: const [],
  originAccountId: account,
  originSpreadsheetId: spreadsheetId,
);

Map<String, Object?> savedSource(String book, int id, String title) => {
  'kind': 'google',
  'accountId': 'account',
  'spreadsheetId': book,
  'sheetId': id,
  'sheetTitle': title,
};

PlannerRequest request({
  GoogleCell? managementId,
  bool duplicateManagement = false,
  GoogleCell? amount,
  String destination = 'target',
  String managementOrigin = 'm',
  String managementAccount = 'account',
  Set<String>? onlyMemberIds,
  String statusHeader = 'حالة التدقيق',
}) {
  final managementRows = <List<GoogleCell>>[
    [textCell('الرقم'), textCell('قيمة أصلية')],
    [managementId ?? textCell('001'), amount ?? numberCell(100)],
    if (duplicateManagement) [textCell('001'), numberCell(200)],
  ];
  return PlannerRequest(
    cycleId: 'cycle',
    accountId: 'account',
    management: PlannerSource(
      spreadsheetId: 'm',
      idColumn: 'A',
      hasHeader: true,
      snapshot: sheet(
        managementOrigin,
        1,
        'management',
        managementRows,
        account: managementAccount,
      ),
    ),
    agent: PlannerSource(
      spreadsheetId: 'a',
      idColumn: 'A',
      hasHeader: true,
      snapshot: sheet('a', 2, 'agent', [
        [textCell('الرقم'), textCell('بيانات')],
        [textCell('001'), textCell('أ')],
        [textCell('001'), textCell('ب')],
      ]),
    ),
    userInfo: PlannerSource(
      spreadsheetId: 'i',
      idColumn: 'A',
      hasHeader: true,
      snapshot: sheet('i', 3, 'info', [
        [textCell('الرقم'), textCell('الوجهة'), textCell(statusHeader)],
        [textCell('001'), textCell('وكالة'), const GoogleCell(entered: {})],
      ]),
    ),
    titleColumn: 'B',
    targetSpreadsheetId: destination,
    agentColor: '#112233',
    managementColor: '#445566',
    statusColumn: 'C',
    statusColumnIsVerifiedNonFinancial: true,
    onlyMemberIds: onlyMemberIds,
  );
}

void main() {
  sqfliteFfiInit();
  late LocalAuditDatabase database;
  late AuditPlanner planner;

  setUp(() async {
    database = await LocalAuditDatabase.open(
      databasePath: inMemoryDatabasePath,
      factory: databaseFactoryFfi,
    );
    await database.saveCycle(
      CycleRecord(
        id: 'cycle',
        name: 'دورة',
        createdAt: DateTime.utc(2026),
        managementSource: savedSource('m', 1, 'management'),
        agentSource: savedSource('a', 2, 'agent'),
        columnMappings: const {
          'managementId': 'A',
          'agentId': 'A',
          'userInfoId': 'A',
          'userInfoTitle': 'B',
          'userInfoStatus': 'C',
          'managementHeader': true,
          'agentHeader': true,
          'userInfoHeader': true,
          'userInfoStatusConfirmed': true,
        },
        snapshots: {
          'userInfoSource': savedSource('i', 3, 'info'),
          'auditTarget': {'id': 'target', 'accountId': 'account'},
        },
      ),
    );
    planner = AuditPlanner(database);
  });
  tearDown(() async => database.close());

  test(
    'preview is read only; approved plan is durable, ordered, and preserves typed cells',
    () async {
      final preview = await planner.preview(request());
      expect(preview.plannedMemberCount, 1);
      expect(
        preview.operationCount,
        7,
      ); // create, copy, target, management, two agent rows, status
      expect(preview.issues, isEmpty);
      expect(await database.listOperations('cycle'), isEmpty);
      await planner.saveApproved(preview);
      final operations = await database.listOperations('cycle');
      expect(operations, hasLength(7));
      final copy = operations.singleWhere((op) => op.type == 'copy');
      expect(copy.destination['spreadsheetId'], 'target');
      expect(copy.destination['sheetTitle'], 'وكالة');
      expect(copy.destination['values'], [
        {'stringValue': '001'},
        {'numberValue': 100},
      ]);
      expect(copy.source['targetSpreadsheetId'], 'target');
      expect(
        (copy.source['checks'] as List).every(
          (check) => (check as Map)['expectedEnteredRows'] is List,
        ),
        isTrue,
      );
      final ids = operations.map((op) => op.id).toSet();
      for (final op in operations) {
        expect(op.status, OperationStatus.planned);
        for (final dependency in op.destination['requiresIds'] as List) {
          expect(ids, contains(dependency));
          expect(dependency, isNot(op.id));
        }
      }
      await expectLater(planner.saveApproved(preview), throwsA(anything));
      expect(await database.listOperations('cycle'), hasLength(7));
    },
  );

  test(
    'duplicate or numeric management identifiers cannot create a write plan',
    () async {
      final duplicate = await planner.preview(
        request(duplicateManagement: true),
      );
      expect(duplicate.operationCount, 0);
      expect(duplicate.issues, isNotEmpty);
      final numeric = await planner.preview(
        request(managementId: numberCell(1)),
      );
      expect(numeric.operationCount, 0);
      expect(numeric.audit.canExecute, isFalse);
      expect(await database.listOperations('cycle'), isEmpty);
    },
  );

  test('formula copy and mismatched target fail closed', () async {
    final formula = await planner.preview(
      request(amount: formulaCell('=50+50')),
    );
    expect(formula.operationCount, 0);
    expect(formula.issues, isNotEmpty);
    await expectLater(
      planner.preview(request(destination: 'another')),
      throwsStateError,
    );
    expect(await database.listOperations('cycle'), isEmpty);
  });

  test('approval is rejected if cycle settings change after preview', () async {
    final preview = await planner.preview(request());
    final cycle = (await database.getCycle('cycle'))!;
    await database.saveCycle(
      CycleRecord(
        id: cycle.id,
        name: cycle.name,
        createdAt: cycle.createdAt,
        managementSource: cycle.managementSource,
        agentSource: cycle.agentSource,
        columnMappings: {...cycle.columnMappings, 'managementId': 'B'},
        snapshots: cycle.snapshots,
      ),
    );

    await expectLater(planner.saveApproved(preview), throwsStateError);
    expect(await database.listOperations('cycle'), isEmpty);
  });

  test(
    'manual selection reports missing members without creating operations',
    () async {
      final preview = await planner.preview(request(onlyMemberIds: {'002'}));
      expect(preview.operationCount, 0);
      expect(preview.issues.single.memberId, '002');
      expect(await database.listOperations('cycle'), isEmpty);
    },
  );

  test(
    'financial status headers cannot be approved even if confirmed',
    () async {
      final preview = await planner.preview(
        request(statusHeader: 'راتب التدقيق'),
      );
      expect(preview.operationCount, 0);
      expect(preview.issues, isNotEmpty);
      expect(await database.listOperations('cycle'), isEmpty);
    },
  );

  test(
    'a snapshot read from a different spreadsheet cannot be relabelled',
    () async {
      await expectLater(
        planner.preview(request(managementOrigin: 'different')),
        throwsStateError,
      );
      await expectLater(
        planner.preview(request(managementAccount: 'different')),
        throwsStateError,
      );
      expect(await database.listOperations('cycle'), isEmpty);
    },
  );
}
