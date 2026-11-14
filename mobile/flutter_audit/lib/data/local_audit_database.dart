import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// A locally stored cycle. JSON fields contain no credentials or tokens.
class CycleRecord {
  const CycleRecord({
    required this.id,
    required this.name,
    required this.createdAt,
    required this.managementSource,
    required this.agentSource,
    required this.columnMappings,
    required this.snapshots,
    this.auditClosed = false,
    this.updatedAt,
  });

  final String id;
  final String name;
  final DateTime createdAt;
  final Map<String, Object?> managementSource;
  final Map<String, Object?> agentSource;
  final Map<String, Object?> columnMappings;
  final Map<String, Object?> snapshots;
  final bool auditClosed;
  final DateTime? updatedAt;

  Map<String, Object?> _toMap() => {
        'id': id,
        'name': name,
        'created_at': createdAt.toUtc().toIso8601String(),
        'management_source': jsonEncode(managementSource),
        'agent_source': jsonEncode(agentSource),
        'column_mappings': jsonEncode(columnMappings),
        'snapshots': jsonEncode(snapshots),
        'audit_closed': auditClosed ? 1 : 0,
        'updated_at': (updatedAt ?? DateTime.now()).toUtc().toIso8601String(),
      };

  factory CycleRecord._fromMap(Map<String, Object?> row) => CycleRecord(
        id: row['id'] as String,
        name: row['name'] as String,
        createdAt: DateTime.parse(row['created_at'] as String),
        managementSource: _jsonMap(row['management_source']),
        agentSource: _jsonMap(row['agent_source']),
        columnMappings: _jsonMap(row['column_mappings']),
        snapshots: _jsonMap(row['snapshots']),
        auditClosed: row['audit_closed'] == 1,
        updatedAt: DateTime.parse(row['updated_at'] as String),
      );
}

enum OperationStatus {
  planned,
  pending,
  executing,
  verificationRequired,
  verified,
  conflict,
  failed,
}

/// A persistent, individually verifiable Google operation.
class OperationRecord {
  const OperationRecord({
    required this.id,
    required this.cycleId,
    required this.type,
    required this.source,
    required this.destination,
    required this.fingerprint,
    required this.status,
    required this.updatedAt,
    this.memberId,
    this.attempts = 0,
    this.lastError,
    this.evidence = const {},
  });

  final String id;
  final String cycleId;
  final String? memberId;
  final String type;
  final Map<String, Object?> source;
  final Map<String, Object?> destination;
  final String fingerprint;
  final OperationStatus status;
  final int attempts;
  final String? lastError;
  final DateTime updatedAt;
  final Map<String, Object?> evidence;

  Map<String, Object?> _toMap() => {
        'id': id,
        'cycle_id': cycleId,
        'member_id': memberId,
        'type': type,
        'source': jsonEncode(source),
        'destination': jsonEncode(destination),
        'fingerprint': fingerprint,
        'status': status.name,
        'attempts': attempts,
        'last_error': lastError,
        'updated_at': updatedAt.toUtc().toIso8601String(),
        'evidence': jsonEncode(evidence),
      };

  factory OperationRecord._fromMap(Map<String, Object?> row) => OperationRecord(
        id: row['id'] as String,
        cycleId: row['cycle_id'] as String,
        memberId: row['member_id'] as String?,
        type: row['type'] as String,
        source: _jsonMap(row['source']),
        destination: _jsonMap(row['destination']),
        fingerprint: row['fingerprint'] as String,
        status: OperationStatus.values.byName(row['status'] as String),
        attempts: row['attempts'] as int,
        lastError: row['last_error'] as String?,
        updatedAt: DateTime.parse(row['updated_at'] as String),
        evidence: _jsonMap(row['evidence']),
      );
}

Map<String, Object?> _jsonMap(Object? value) =>
    Map<String, Object?>.from(jsonDecode(value as String) as Map);

/// Identity of the stored settings used to prepare an audit plan. The planner
/// must compare this value in the same SQLite transaction that saves the plan;
/// otherwise another editor can change the cycle after the preview.
String cycleConfigurationFingerprint(CycleRecord cycle) => sha256
    .convert(utf8.encode(jsonEncode([
      cycle.managementSource,
      cycle.agentSource,
      cycle.columnMappings,
      cycle.snapshots['userInfoSource'],
      cycle.snapshots['auditTarget'],
    ])))
    .toString();

/// SQLite storage. Each open/close operation is transactional; no Google data
/// is modified by deleting a local cycle.
class LocalAuditDatabase {
  LocalAuditDatabase._(this._db);
  final Database _db;
  static const schemaVersion = 2;

  static Future<LocalAuditDatabase> open({
    String? databasePath,
    DatabaseFactory? factory,
  }) async {
    final f = factory ?? databaseFactory;
    final location = databasePath ?? p.join(await f.getDatabasesPath(), 'audit.db');
    final db = await f.openDatabase(location, options: OpenDatabaseOptions(
      version: schemaVersion,
      onConfigure: (db) => db.execute('PRAGMA foreign_keys = ON'),
      onCreate: (db, version) async {
        await _createV1(db);
        await _upgradeToV2(db);
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) await _upgradeToV2(db);
      },
    ));
    return LocalAuditDatabase._(db);
  }

  static Future<void> _createV1(DatabaseExecutor db) async {
    await db.execute('''CREATE TABLE cycles (
      id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL,
      created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
      management_source TEXT NOT NULL, agent_source TEXT NOT NULL,
      column_mappings TEXT NOT NULL, snapshots TEXT NOT NULL,
      audit_closed INTEGER NOT NULL DEFAULT 0 CHECK(audit_closed IN (0,1))
    )''');
  }

  static Future<void> _upgradeToV2(DatabaseExecutor db) async {
    await db.execute('''CREATE TABLE operations (
      id TEXT PRIMARY KEY NOT NULL, cycle_id TEXT NOT NULL,
      member_id TEXT, type TEXT NOT NULL, source TEXT NOT NULL,
      destination TEXT NOT NULL, fingerprint TEXT NOT NULL,
      status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
      last_error TEXT, updated_at TEXT NOT NULL, evidence TEXT NOT NULL,
      FOREIGN KEY(cycle_id) REFERENCES cycles(id) ON DELETE CASCADE
    )''');
    await db.execute('CREATE INDEX operations_cycle ON operations(cycle_id)');
    await db.execute('''CREATE TABLE cycle_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT, cycle_id TEXT NOT NULL,
      kind TEXT NOT NULL, created_at TEXT NOT NULL,
      FOREIGN KEY(cycle_id) REFERENCES cycles(id) ON DELETE CASCADE
    )''');
  }

  Future<void> saveCycle(CycleRecord cycle) async {
    if (cycle.id.trim().isEmpty || cycle.name.trim().isEmpty) {
      throw ArgumentError('Cycle ID and name are required.');
    }
    await _db.transaction((tx) async {
      final existing = await tx.query('cycles', where: 'id = ?', whereArgs: [cycle.id]);
      if (existing.isEmpty) {
        if (cycle.auditClosed) throw StateError('New cycles must be open.');
        await tx.insert('cycles', cycle._toMap());
      } else {
        if (existing.single['audit_closed'] == 1) {
          throw StateError('Reopen the cycle before editing.');
        }
        if (cycle.auditClosed) throw StateError('Use closeCycle to close a cycle.');
        final incomplete = await tx.query('operations', columns: ['id'],
            where: 'cycle_id = ? AND status != ?',
            whereArgs: [cycle.id, OperationStatus.verified.name], limit: 1);
        if (incomplete.isNotEmpty) {
          throw StateError('Resolve incomplete operations before editing the cycle.');
        }
        await tx.update('cycles', cycle._toMap(), where: 'id = ?', whereArgs: [cycle.id]);
      }
    });
  }

  Future<CycleRecord?> getCycle(String id) async {
    final rows = await _db.query('cycles', where: 'id = ?', whereArgs: [id]);
    return rows.isEmpty ? null : CycleRecord._fromMap(rows.single);
  }

  Future<List<CycleRecord>> listCycles() async => (await _db.query(
        'cycles', orderBy: 'created_at DESC',
      )).map(CycleRecord._fromMap).toList();

  Future<void> deleteCycle(String id) async {
    await _db.transaction((tx) async {
      final incomplete = await tx.query('operations', columns: ['id'],
          where: 'cycle_id = ? AND status != ?',
          whereArgs: [id, OperationStatus.verified.name], limit: 1);
      if (incomplete.isNotEmpty) {
        throw StateError('Resolve incomplete operations before deleting the cycle.');
      }
      await tx.delete('cycles', where: 'id = ?', whereArgs: [id]);
    });
  }

  /// Persists an entire approved plan atomically. Only one unfinished plan may
  /// exist for a cycle; all records must have new IDs and be initially planned.
  Future<void> createPlan(List<OperationRecord> records,
      {String? expectedCycleConfigFingerprint}) async {
    if (records.isEmpty) throw ArgumentError('An audit plan cannot be empty.');
    final cycleId = records.first.cycleId;
    final ids = <String>{};
    for (final op in records) {
      _validateOperation(op);
      if (op.cycleId != cycleId || op.status != OperationStatus.planned || !ids.add(op.id)) {
        throw ArgumentError('Plan operations must be new, planned, and in one cycle.');
      }
    }
    await _db.transaction((tx) async {
      final cycle = await tx.query('cycles', where: 'id = ?', whereArgs: [cycleId]);
      if (cycle.isEmpty || cycle.single['audit_closed'] == 1) throw StateError('Cycle is closed or missing.');
      if (expectedCycleConfigFingerprint != null &&
          cycleConfigurationFingerprint(CycleRecord._fromMap(cycle.single)) !=
              expectedCycleConfigFingerprint) {
        throw StateError('Cycle settings changed after the audit preview.');
      }
      final incomplete = await tx.query('operations', columns: ['id'],
          where: 'cycle_id = ? AND status != ?',
          whereArgs: [cycleId, OperationStatus.verified.name], limit: 1);
      if (incomplete.isNotEmpty) throw StateError('Cycle already has incomplete operations.');
      for (final op in records) {
        // Default conflict behavior is ABORT, rolling the entire plan back.
        await tx.insert('operations', op._toMap());
      }
    });
  }

  static void _validateOperation(OperationRecord operation) {
    if (operation.id.trim().isEmpty || operation.cycleId.trim().isEmpty ||
        operation.type.trim().isEmpty || operation.fingerprint.isEmpty || operation.attempts < 0) {
      throw ArgumentError('Invalid operation.');
    }
  }

  /// Updates the state of an operation previously inserted by [createPlan].
  /// Plan identity, payload and source fingerprint cannot be silently changed.
  Future<void> putOperation(OperationRecord operation) async {
    _validateOperation(operation);
    await _db.transaction((tx) async {
      final cycle = await tx.query('cycles', where: 'id = ?', whereArgs: [operation.cycleId]);
      if (cycle.isEmpty) throw StateError('Cycle not found.');
      final existing = await tx.query('operations', where: 'id = ?', whereArgs: [operation.id]);
      if (existing.isEmpty) {
        throw StateError('Create operations through createPlan.');
      } else {
        if (existing.single['cycle_id'] != operation.cycleId) throw StateError('Operation cycle cannot change.');
        if (cycle.single['audit_closed'] == 1) throw StateError('Cycle is closed.');
        final next = operation._toMap();
        for (final key in ['member_id', 'type', 'source', 'destination', 'fingerprint']) {
          if (existing.single[key] != next[key]) throw StateError('Operation plan cannot change.');
        }
        if (operation.attempts < (existing.single['attempts'] as int)) throw StateError('Attempts cannot decrease.');
        final previousStatus = OperationStatus.values.byName(existing.single['status'] as String);
        if ((previousStatus == OperationStatus.executing ||
                previousStatus == OperationStatus.verificationRequired) &&
            (operation.status == OperationStatus.planned ||
                operation.status == OperationStatus.pending ||
                operation.status == OperationStatus.failed)) {
          throw StateError('An uncertain operation requires verification or review.');
        }
        // A conflict may originate from an uncertain write. Without a durable,
        // explicitly reviewed resolution, routing it back to pending (or to
        // executing via failed) would discard that uncertainty.
        if (previousStatus == OperationStatus.conflict &&
            (operation.status == OperationStatus.planned ||
                operation.status == OperationStatus.pending ||
                operation.status == OperationStatus.executing ||
                operation.status == OperationStatus.failed)) {
          throw StateError('A conflict requires explicit review before retry.');
        }
        if (existing.single['status'] == OperationStatus.verified.name && operation.status != OperationStatus.verified) {
          throw StateError('Verified operations cannot be reverted.');
        }
        await tx.update('operations', next, where: 'id = ?', whereArgs: [operation.id]);
      }
    });
  }

  Future<OperationRecord?> getOperation(String id) async {
    final rows = await _db.query('operations', where: 'id = ?', whereArgs: [id]);
    return rows.isEmpty ? null : OperationRecord._fromMap(rows.single);
  }

  Future<List<OperationRecord>> listOperations(String cycleId) async =>
      (await _db.query('operations', where: 'cycle_id = ?', whereArgs: [cycleId],
              orderBy: 'updated_at, id'))
          .map(OperationRecord._fromMap).toList();

  Future<void> closeCycle(String id) => _changeClosed(id, true);
  Future<void> reopenCycle(String id) => _changeClosed(id, false);

  Future<void> _changeClosed(String id, bool closed) async {
    await _db.transaction((tx) async {
      final cycles = await tx.query('cycles', where: 'id = ?', whereArgs: [id]);
      if (cycles.isEmpty) throw StateError('Cycle not found.');
      if ((cycles.single['audit_closed'] == 1) == closed) return;
      if (closed) {
        final incomplete = await tx.query('operations', columns: ['id'],
            where: 'cycle_id = ? AND status != ?',
            whereArgs: [id, OperationStatus.verified.name], limit: 1);
        if (incomplete.isNotEmpty) throw StateError('Cycle has incomplete operations.');
      }
      final now = DateTime.now().toUtc().toIso8601String();
      await tx.update('cycles', {'audit_closed': closed ? 1 : 0, 'updated_at': now},
          where: 'id = ?', whereArgs: [id]);
      await tx.insert('cycle_events', {'cycle_id': id, 'kind': closed ? 'closed' : 'reopened', 'created_at': now});
    });
  }

  Future<List<Map<String, Object?>>> listEvents(String cycleId) => _db.query(
        'cycle_events', where: 'cycle_id = ?', whereArgs: [cycleId], orderBy: 'id',
      );

  Future<void> close() => _db.close();
}
