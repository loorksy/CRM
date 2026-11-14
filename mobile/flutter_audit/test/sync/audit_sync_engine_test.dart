import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';
import 'package:lork_audit_standalone/sync/audit_sync_engine.dart';

class FakeDatabase implements LocalAuditDatabase {
  FakeDatabase(this.cycle, this.operations);
  final CycleRecord cycle;
  final Map<String, OperationRecord> operations;
  @override
  Future<CycleRecord?> getCycle(String id) async => id == cycle.id ? cycle : null;
  @override
  Future<OperationRecord?> getOperation(String id) async => operations[id];
  @override
  Future<void> putOperation(OperationRecord op) async { operations[op.id] = op; }
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class FakeGateway implements SheetsGateway {
  FakeGateway(this.sheet, {this.sheets = const {}});
  GoogleSheetData sheet;
  final Map<int, GoogleSheetData> sheets;
  int writes = 0;
  @override
  Future<GoogleSheetData> readSheet(String accountId, String spreadsheetId, int sheetId) async => sheets[sheetId] ?? sheet;
  @override
  Future<List<SheetRef>> listSheets(String accountId, String spreadsheetId) async => [sheet.ref];
  @override
  Future<void> writeRow(String accountId, String spreadsheetId, int sheetId, int rowIndex, List<Map<String, dynamic>> values) async { writes++; }
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class ChangedBeforeLockDatabase extends FakeDatabase {
  ChangedBeforeLockDatabase(super.cycle, super.operations, this.stale);
  final OperationRecord stale;
  var reads = 0;

  @override
  Future<OperationRecord?> getOperation(String id) async {
    if (reads++ == 0) return stale;
    return super.getOperation(id);
  }
}

class BlockingGateway extends FakeGateway {
  BlockingGateway(super.sheet);
  final started = Completer<void>();
  final release = Completer<void>();

  @override
  Future<GoogleSheetData> readSheet(String accountId, String spreadsheetId, int sheetId) async {
    if (!started.isCompleted) started.complete();
    await release.future;
    return super.readSheet(accountId, spreadsheetId, sheetId);
  }
}

void main() {
  final cycle = CycleRecord(id: 'cycle', name: 'test', createdAt: DateTime.utc(2026),
      managementSource: const {}, agentSource: const {}, columnMappings: const {}, snapshots: const {});
  final fingerprint = sha256.convert(utf8.encode(jsonEncode([cycle.managementSource, cycle.agentSource, cycle.columnMappings, cycle.snapshots['userInfoSource'], cycle.snapshots['auditTarget']]))).toString();
  OperationRecord operation({OperationStatus status = OperationStatus.verificationRequired, Map<String, Object?>? destination}) =>
      OperationRecord(id: 'copy', cycleId: 'cycle', memberId: '001', type: 'copy',
          source: {'accountId': 'account', 'cycleConfigFingerprint': fingerprint},
          destination: destination ?? {'spreadsheetId': 'target', 'sheetId': 7, 'idColumn': 0,
            'values': [{'stringValue': '001'}, {'stringValue': 'name'}]},
          fingerprint: 'preview-hash', status: status, updatedAt: DateTime.utc(2026));
  GoogleSheetData sheet(Map<String, dynamic> identifier) => GoogleSheetData(
    ref: const SheetRef(id: 7, title: 'target'), merges: const [], protectedRanges: const [], rows: [
      GoogleRow(0, [GoogleCell(entered: identifier, formatted: '001'),
        const GoogleCell(entered: {'stringValue': 'name'}, formatted: 'name')]),
    ]);

  test('uncertain write is verified instead of copying a second time', () async {
    final op = operation();
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = FakeGateway(sheet({'stringValue': '001'}));
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.verified);
    expect(result.evidence['method'], 'google_readback');
    expect(gateway.writes, 0);
  });

  test('numeric identifiers fail closed after uncertain write', () async {
    final op = operation();
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = FakeGateway(sheet({'numberValue': 1}));
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.conflict);
    expect(gateway.writes, 0);
  });

  test('missing effect after an uncertain write remains uncertain', () async {
    final missing = GoogleSheetData(ref: const SheetRef(id: 7, title: 'target'),
        rows: const [], merges: const [], protectedRanges: const []);
    for (final status in [OperationStatus.executing, OperationStatus.verificationRequired]) {
      final op = operation(status: status);
      final db = FakeDatabase(cycle, {'copy': op});
      final gateway = FakeGateway(missing);
      final engine = AuditSyncEngine(db, gateway);
      for (var attempt = 0; attempt < 2; attempt++) {
        final result = await engine.execute('copy');
        expect(result.status, OperationStatus.verificationRequired);
        expect(result.attempts, 0);
        expect(gateway.writes, 0);
      }
    }
  });

  test('uncertain operation stays uncertain when a prerequisite is unavailable', () async {
    final op = operation(destination: {
      'spreadsheetId': 'target', 'sheetId': 7, 'idColumn': 0,
      'values': [{'stringValue': '001'}], 'requiresIds': ['earlier']
    });
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = FakeGateway(sheet({'stringValue': '001'}));
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.verificationRequired);
    expect(gateway.writes, 0);
  });

  test('a second executor in the same isolate cannot enter the same cycle', () async {
    final op = operation();
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = BlockingGateway(sheet({'stringValue': '001'}));
    final first = AuditSyncEngine(db, gateway).execute('copy');
    await gateway.started.future;
    try {
      await expectLater(AuditSyncEngine(db, gateway).execute('copy'), throwsStateError);
    } finally {
      gateway.release.complete();
    }
    expect((await first).status, OperationStatus.verified);
    expect(gateway.writes, 0);
  });

  test('operation is reread after taking the cycle lock', () async {
    final stale = operation(status: OperationStatus.planned);
    final verified = operation(status: OperationStatus.verified);
    final db = ChangedBeforeLockDatabase(cycle, {'copy': verified}, stale);
    final gateway = FakeGateway(sheet({'stringValue': '001'}));
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.verified);
    expect(db.reads, 2);
    expect(gateway.writes, 0);
  });

  test('incomplete prerequisite prevents writing', () async {
    final op = operation(status: OperationStatus.planned, destination: {
      'spreadsheetId': 'target', 'sheetId': 7, 'idColumn': 0,
      'values': [{'stringValue': '001'}], 'requiresIds': ['earlier']
    });
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = FakeGateway(sheet({'stringValue': '001'}));
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.pending);
    expect(gateway.writes, 0);
  });

  test('ready copy remains pending and never calls the write gateway', () async {
    GoogleCell textCell(String value) => GoogleCell(entered: {'stringValue': value}, formatted: value);
    GoogleSheetData sourceSheet(int id, String title, List<GoogleRow> rows) =>
        GoogleSheetData(ref: SheetRef(id: id, title: title), rows: rows,
            merges: const [], protectedRanges: const []);
    Map<String, Object?> check(String role, String file, int id,
        List<List<String>> values, List<List<Map<String, dynamic>>> entered,
        {int? titleColumn}) => {
      'role': role, 'spreadsheetId': file, 'sheetId': id, 'idColumn': 0,
      'hasHeader': false, 'expectedRows': values, 'expectedEnteredRows': entered,
      if (titleColumn != null) 'titleColumn': titleColumn,
    };
    final management = sourceSheet(1, 'management', [GoogleRow(0, [textCell('001'), textCell('name')])]);
    final agent = sourceSheet(2, 'agent', []);
    final info = sourceSheet(3, 'information', [GoogleRow(0, [textCell('001'), textCell('target')])]);
    final target = sourceSheet(7, 'target', []);
    final op = OperationRecord(id: 'copy', cycleId: 'cycle', memberId: '001', type: 'copy',
      source: {'accountId': 'account', 'cycleConfigFingerprint': fingerprint,
        'targetSpreadsheetId': 'target-file', 'checks': [
          check('management', 'management-file', 1, [['001', 'name']], [[{'stringValue': '001'}, {'stringValue': 'name'}]]),
          check('agent', 'agent-file', 2, [], []),
          check('userInfo', 'info-file', 3, [['001', 'target']], [[{'stringValue': '001'}, {'stringValue': 'target'}]], titleColumn: 1),
        ]},
      destination: {'spreadsheetId': 'target-file', 'sheetId': 7, 'idColumn': 0,
        'values': [{'stringValue': '001'}, {'stringValue': 'name'}]},
      fingerprint: 'approved-preview', status: OperationStatus.planned, updatedAt: DateTime.utc(2026));
    final db = FakeDatabase(cycle, {'copy': op});
    final gateway = FakeGateway(target, sheets: {1: management, 2: agent, 3: info, 7: target});
    final result = await AuditSyncEngine(db, gateway).execute('copy');
    expect(result.status, OperationStatus.pending);
    expect(result.lastError, contains('الكتابة إلى Google متوقفة'));
    expect(gateway.writes, 0);
    expect(result.attempts, 0);
  });

}
