import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

CycleRecord cycle({String name = 'دورة'}) => CycleRecord(
      id: 'cycle-1', name: name, createdAt: DateTime.utc(2026),
      managementSource: const {'type': 'file'}, agentSource: const {'type': 'file'},
      columnMappings: const {'managementId': 'AA'},
      snapshots: const {'management': [['001', 'قيمة']], 'agent': []},
    );

OperationRecord operation(OperationStatus status, {String id = 'op-1'}) => OperationRecord(
      id: id, cycleId: 'cycle-1', memberId: '001', type: 'copy',
      source: const {'sheetId': 123}, destination: const {'sheetId': 456},
      fingerprint: 'abc', status: status, updatedAt: DateTime.utc(2026),
      evidence: status == OperationStatus.verified ? const {'verified': true} : const {},
    );

void main() {
  sqfliteFfiInit();
  late Directory dir;
  late String path;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('audit-db-test-');
    path = p.join(dir.path, 'audit.db');
  });
  tearDown(() async => dir.delete(recursive: true));

  test('persists cycles, snapshots, operation recovery and local deletion', () async {
    var store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    await store.saveCycle(cycle());
    await store.saveCycle(cycle(name: 'معدلة'));
    await store.createPlan([operation(OperationStatus.planned)]);
    await store.putOperation(operation(OperationStatus.verificationRequired));
    await store.close();

    store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    expect((await store.getCycle('cycle-1'))!.name, 'معدلة');
    expect((await store.getCycle('cycle-1'))!.snapshots['management'], [['001', 'قيمة']]);
    expect((await store.getOperation('op-1'))!.status, OperationStatus.verificationRequired);
    await expectLater(store.deleteCycle('cycle-1'), throwsStateError);
    await expectLater(store.saveCycle(cycle(name: 'invalid')), throwsStateError);
    await store.putOperation(operation(OperationStatus.verified));
    await store.deleteCycle('cycle-1');
    expect(await store.getCycle('cycle-1'), isNull);
    expect(await store.getOperation('op-1'), isNull);
    await store.close();
  });

  test('close rejects incomplete operations and persists close/reopen history', () async {
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    await store.saveCycle(cycle());
    await store.createPlan([operation(OperationStatus.planned)]);
    await store.putOperation(operation(OperationStatus.pending));
    await expectLater(store.closeCycle('cycle-1'), throwsStateError);
    expect((await store.getCycle('cycle-1'))!.auditClosed, isFalse);
    await store.putOperation(operation(OperationStatus.verified));
    await store.closeCycle('cycle-1');
    await expectLater(store.putOperation(operation(OperationStatus.pending)), throwsStateError);
    await expectLater(store.saveCycle(cycle(name: 'invalid')), throwsStateError);
    await store.reopenCycle('cycle-1');
    expect((await store.getCycle('cycle-1'))!.auditClosed, isFalse);
    expect((await store.listEvents('cycle-1')).map((e) => e['kind']), ['closed', 'reopened']);
    expect((await store.getOperation('op-1'))!.status, OperationStatus.verified);
    await store.close();
  });

  test('plan is atomic and rejects overlap and reused IDs', () async {
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    await store.saveCycle(cycle());
    await store.createPlan([operation(OperationStatus.planned), operation(OperationStatus.planned, id: 'op-2')]);
    await expectLater(store.createPlan([operation(OperationStatus.planned, id: 'op-3')]), throwsStateError);
    expect(await store.listOperations('cycle-1'), hasLength(2));
    await store.putOperation(operation(OperationStatus.verified));
    await store.putOperation(operation(OperationStatus.verified, id: 'op-2'));
    await expectLater(store.createPlan([operation(OperationStatus.planned, id: 'op-3'), operation(OperationStatus.planned)]), throwsA(anything));
    expect(await store.getOperation('op-3'), isNull);
    await store.close();
  });

  test('plan rejects settings changed since the preview in the insert transaction', () async {
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    final original = cycle();
    await store.saveCycle(original);
    final fingerprint = cycleConfigurationFingerprint(original);
    await store.saveCycle(CycleRecord(
      id: original.id,
      name: original.name,
      createdAt: original.createdAt,
      managementSource: original.managementSource,
      agentSource: original.agentSource,
      columnMappings: const {'managementId': 'AB'},
      snapshots: original.snapshots,
    ));
    await expectLater(
      store.createPlan([operation(OperationStatus.planned)],
          expectedCycleConfigFingerprint: fingerprint),
      throwsStateError,
    );
    expect(await store.listOperations('cycle-1'), isEmpty);
    final updated = (await store.getCycle('cycle-1'))!;
    await store.createPlan([operation(OperationStatus.planned)],
        expectedCycleConfigFingerprint: cycleConfigurationFingerprint(updated));
    expect(await store.listOperations('cycle-1'), hasLength(1));
    await store.close();
  });

  test('an uncertain operation cannot be reset to pending or failed', () async {
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    await store.saveCycle(cycle());
    await store.createPlan([operation(OperationStatus.planned)]);
    await store.putOperation(operation(OperationStatus.executing));
    await expectLater(store.putOperation(operation(OperationStatus.pending)), throwsStateError);
    await expectLater(store.putOperation(operation(OperationStatus.failed)), throwsStateError);
    expect((await store.getOperation('op-1'))!.status, OperationStatus.executing);
    await store.putOperation(operation(OperationStatus.verificationRequired));
    await expectLater(store.putOperation(operation(OperationStatus.planned)), throwsStateError);
    await expectLater(store.putOperation(operation(OperationStatus.pending)), throwsStateError);
    await expectLater(store.putOperation(operation(OperationStatus.failed)), throwsStateError);
    expect((await store.getOperation('op-1'))!.status, OperationStatus.verificationRequired);
    await store.putOperation(operation(OperationStatus.verified));
    await store.close();
  });

  test('a conflict cannot hide uncertainty by routing through failed', () async {
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    await store.saveCycle(cycle());
    await store.createPlan([operation(OperationStatus.planned)]);
    await store.putOperation(operation(OperationStatus.executing));
    await store.putOperation(operation(OperationStatus.conflict));
    await expectLater(store.putOperation(operation(OperationStatus.pending)), throwsStateError);
    await expectLater(store.putOperation(operation(OperationStatus.failed)), throwsStateError);
    await expectLater(store.putOperation(operation(OperationStatus.executing)), throwsStateError);
    expect((await store.getOperation('op-1'))!.status, OperationStatus.conflict);
    await store.close();
  });

  test('upgrades version 1 data without losing its snapshots', () async {
    final old = await databaseFactoryFfi.openDatabase(path, options: OpenDatabaseOptions(
      version: 1, onCreate: (db, version) async {
        await db.execute('''CREATE TABLE cycles (
          id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          management_source TEXT NOT NULL, agent_source TEXT NOT NULL,
          column_mappings TEXT NOT NULL, snapshots TEXT NOT NULL,
          audit_closed INTEGER NOT NULL DEFAULT 0
        )''');
        await db.insert('cycles', {
          'id': 'old', 'name': 'قديمة', 'created_at': '2026-01-01T00:00:00.000Z',
          'updated_at': '2026-01-01T00:00:00.000Z', 'management_source': '{}',
          'agent_source': '{}', 'column_mappings': '{}', 'snapshots': '{"management":[["001"]]}', 'audit_closed': 0,
        });
      },
    ));
    await old.close();
    final store = await LocalAuditDatabase.open(databasePath: path, factory: databaseFactoryFfi);
    expect((await store.getCycle('old'))!.snapshots['management'], [['001']]);
    expect(await store.listOperations('old'), isEmpty);
    await store.close();
  });
}
