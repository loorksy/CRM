import 'package:uuid/uuid.dart';

import '../data/local_audit_database.dart';
import '../domain/audit_engine.dart';
import '../google/google_sheets_gateway.dart';

/// A Google snapshot read under the connected account. Cell types are retained;
/// formatted text alone cannot prove the original spelling of an identifier.
class PlannerSource {
  const PlannerSource({
    required this.spreadsheetId,
    required this.snapshot,
    required this.idColumn,
    required this.hasHeader,
  });

  final String spreadsheetId;
  final GoogleSheetData snapshot;
  final String idColumn;
  final bool hasHeader;
}

class PlannerRequest {
  const PlannerRequest({
    required this.cycleId,
    required this.accountId,
    required this.management,
    required this.agent,
    required this.userInfo,
    required this.titleColumn,
    required this.targetSpreadsheetId,
    required this.agentColor,
    required this.managementColor,
    this.statusColumn,
    this.statusColumnIsVerifiedNonFinancial = false,
    this.onlyMemberIds,
  });

  final String cycleId;
  final String accountId;
  final PlannerSource management;
  final PlannerSource agent;
  final PlannerSource userInfo;
  final String titleColumn;
  final String targetSpreadsheetId;

  /// RGB as #RRGGBB.
  final String agentColor;
  final String managementColor;
  final String? statusColumn;
  final bool statusColumnIsVerifiedNonFinancial;

  /// Null plans all eligible members; IDs are normalized and exact.
  final Set<String>? onlyMemberIds;
}

class PlanIssue {
  const PlanIssue(this.memberId, this.message);
  final String memberId;
  final String message;
}

/// A preview is entirely local and performs no writes. Only [saveApproved]
/// persists this particular plan after the user has reviewed it.
class AuditPlanPreview {
  const AuditPlanPreview._(
    this.audit,
    this.issues,
    this._operations,
    this._cycleConfigFingerprint,
  );
  final AuditPreview audit;
  final List<PlanIssue> issues;
  final List<OperationRecord> _operations;
  final String _cycleConfigFingerprint;
  int get operationCount => _operations.length;
  int get plannedMemberCount =>
      _operations.map((e) => e.memberId).toSet().length;
}

/// Builds a conservative, ordered plan. Current execution supports Google
/// snapshots only: local file sources deliberately have no implicit target.
class AuditPlanner {
  AuditPlanner(this.database, {Uuid? uuid}) : _uuid = uuid ?? const Uuid();
  final LocalAuditDatabase database;
  final Uuid _uuid;

  /// Preparing operations requires Google source snapshots and an explicit
  /// destination. For an offline classification without a destination use
  /// AuditEngine.preview directly; this method intentionally creates plans.
  Future<AuditPlanPreview> preview(PlannerRequest request) async {
    if (request.accountId.trim().isEmpty ||
        request.cycleId.trim().isEmpty ||
        request.targetSpreadsheetId.trim().isEmpty) {
      throw StateError('اختر الحساب والدورة وجدول الوجهة أولاً.');
    }
    final cycle = await database.getCycle(request.cycleId);
    if (cycle == null || cycle.auditClosed) {
      throw StateError('الدورة مغلقة أو غير موجودة.');
    }
    // A plan must be bound to the source identities and mappings saved for
    // this cycle, including the account that selected each Google document.
    _checkStoredSource(
      cycle.managementSource,
      request.management,
      request.accountId,
    );
    _checkStoredSource(cycle.agentSource, request.agent, request.accountId);
    final storedInfo = cycle.snapshots['userInfoSource'];
    final storedTarget = cycle.snapshots['auditTarget'];
    if (storedInfo is! Map || storedTarget is! Map) {
      throw StateError('احفظ إعدادات معلومات المستخدمين والوجهة قبل التخطيط.');
    }
    _checkStoredSource(storedInfo, request.userInfo, request.accountId);
    if (storedTarget['id'] != request.targetSpreadsheetId ||
        storedTarget['accountId'] != request.accountId) {
      throw StateError('جدول الوجهة أو الحساب مختلف عن الإعدادات المحفوظة.');
    }
    final maps = cycle.columnMappings;
    bool sameColumn(String name, String? value) =>
        (maps[name] as String? ?? '').trim().toUpperCase() ==
        (value ?? '').trim().toUpperCase();
    if (!sameColumn('managementId', request.management.idColumn) ||
        !sameColumn('agentId', request.agent.idColumn) ||
        !sameColumn('userInfoId', request.userInfo.idColumn) ||
        !sameColumn('userInfoTitle', request.titleColumn) ||
        !sameColumn('userInfoStatus', request.statusColumn) ||
        maps['managementHeader'] != request.management.hasHeader ||
        maps['agentHeader'] != request.agent.hasHeader ||
        maps['userInfoHeader'] != request.userInfo.hasHeader ||
        ((request.statusColumn?.trim().isNotEmpty ?? false) &&
            maps['userInfoStatusConfirmed'] !=
                request.statusColumnIsVerifiedNonFinancial)) {
      throw StateError('خرائط الأعمدة مختلفة عن إعدادات الدورة المحفوظة.');
    }
    final agentColor = _parseColor(request.agentColor);
    final managementColor = _parseColor(request.managementColor);
    final sources = <String, PlannerSource>{
      'management': request.management,
      'agent': request.agent,
      'userInfo': request.userInfo,
    };
    for (final source in sources.values) {
      if (source.spreadsheetId.trim().isEmpty ||
          source.snapshot.ref.title.isEmpty ||
          source.snapshot.originAccountId != request.accountId ||
          source.snapshot.originSpreadsheetId != source.spreadsheetId) {
        throw StateError('حدد كل مصادر Google بوضوح قبل إعداد الخطة.');
      }
    }
    final tables = <String, AuditTable>{};
    for (final entry in sources.entries) {
      tables[entry.key] = _table(entry.value);
    }
    final input = AuditInput(
      management: tables['management']!,
      agent: tables['agent']!,
      userInfo: tables['userInfo']!,
      titleColumn: request.titleColumn,
      targetSpreadsheetId: request.targetSpreadsheetId,
      agentColor: request.agentColor,
      managementColor: request.managementColor,
      statusColumn: request.statusColumn,
      statusColumnIsVerifiedNonFinancial:
          request.statusColumnIsVerifiedNonFinancial,
    );
    final audit = const AuditEngine().preview(input);
    final configHash = cycleConfigurationFingerprint(cycle);
    final requested = request.onlyMemberIds?.map(normalizeMemberId).toSet();
    if (requested != null && (requested.isEmpty || requested.contains(''))) {
      throw StateError('حدد رقم مستخدم صالحًا.');
    }
    final operations = <OperationRecord>[];
    final issues = <PlanIssue>[];
    for (final member in audit.members) {
      if (requested != null && !requested.contains(member.memberId)) continue;
      if (!member.canAudit) {
        issues.add(
          PlanIssue(
            member.memberId,
            member.conflicts.isNotEmpty
                ? member.conflicts.first.message
                : member.diagnostic ?? 'لا يمكن تدقيق هذا المستخدم.',
          ),
        );
        continue;
      }
      try {
        final selected = <String, List<GoogleRow>>{};
        for (final entry in sources.entries) {
          selected[entry.key] = _matches(entry.value, member.memberId);
        }
        final managementRows = selected['management']!;
        final infoRows = selected['userInfo']!;
        if (managementRows.length != 1 ||
            infoRows.length != 1 ||
            selected['agent']!.length != member.agentRows.length) {
          throw StateError('تغيرت مطابقة الصفوف؛ أعد تحميل البيانات.');
        }
        final infoTitleColumn = columnIndex(request.titleColumn);
        final infoRow = infoRows.single;
        if (infoTitleColumn >= infoRow.cells.length ||
            infoRow.cells[infoTitleColumn].entered['stringValue'] !=
                member.targetTitle) {
          throw StateError('اسم الوجهة يجب أن يكون نصًا ثابتًا بلا صيغة.');
        }
        final managementRow = managementRows.single;
        final values = _entered(managementRow);
        if (values.any((value) => value.containsKey('formulaValue'))) {
          throw StateError(
            'يحتوي صف الإدارة على صيغة؛ يلزم مراجعته قبل النسخ.',
          );
        }
        final checks = <Map<String, Object?>>[];
        for (final entry in sources.entries) {
          final source = entry.value;
          checks.add({
            'role': entry.key,
            'spreadsheetId': source.spreadsheetId,
            'sheetId': source.snapshot.ref.id,
            'idColumn': columnIndex(source.idColumn),
            'hasHeader': source.hasHeader,
            'expectedRows': selected[entry.key]!.map(_formatted).toList(),
            'expectedEnteredRows': selected[entry.key]!.map(_entered).toList(),
            if (entry.key == 'userInfo') 'titleColumn': infoTitleColumn,
          });
        }
        final source = <String, Object?>{
          'accountId': request.accountId,
          'targetSpreadsheetId': request.targetSpreadsheetId,
          'cycleConfigFingerprint': configHash,
          'checks': checks,
        };
        String? previousId;
        void add(String type, Map<String, Object?> destination) {
          final id = _uuid.v4();
          operations.add(
            OperationRecord(
              id: id,
              cycleId: request.cycleId,
              memberId: member.memberId,
              type: type,
              source: source,
              destination: {
                ...destination,
                'requiresIds': previousId == null ? <String>[] : [previousId!],
              },
              fingerprint: member.fingerprint,
              status: OperationStatus.planned,
              updatedAt: DateTime.now().toUtc(),
            ),
          );
          previousId = id;
        }

        final target = <String, Object?>{
          'spreadsheetId': request.targetSpreadsheetId,
          'sheetTitle': member.targetTitle!,
          'idColumn': columnIndex(request.management.idColumn),
          'hasHeader': false,
        };
        final color =
            member.classification == AuditClassification.management
                ? managementColor
                : agentColor;
        add('createSheet', {
          'spreadsheetId': request.targetSpreadsheetId,
          'title': member.targetTitle!,
        });
        add('copy', {...target, 'values': values});
        add('color', {
          ...target,
          'endColumn': values.length,
          'color': color,
          'rowEnteredValues': values,
        });
        add('color', {
          'spreadsheetId': request.management.spreadsheetId,
          'sheetId': request.management.snapshot.ref.id,
          'idColumn': columnIndex(request.management.idColumn),
          'hasHeader': request.management.hasHeader,
          'endColumn': managementRow.cells.length,
          'color': color,
          'rowValues': _formatted(managementRow),
        });
        for (final row in selected['agent']!) {
          add('color', {
            'spreadsheetId': request.agent.spreadsheetId,
            'sheetId': request.agent.snapshot.ref.id,
            'idColumn': columnIndex(request.agent.idColumn),
            'hasHeader': request.agent.hasHeader,
            'endColumn': row.cells.length,
            'color': agentColor,
            'rowValues': _formatted(row),
          });
        }
        final status = request.statusColumn?.trim();
        if (status != null && status.isNotEmpty) {
          final statusColumn = columnIndex(status);
          final headerRows =
              request.userInfo.snapshot.rows
                  .where((row) => row.index == 0)
                  .toList();
          if (!request.userInfo.hasHeader ||
              headerRows.length != 1 ||
              statusColumn >= headerRows.single.cells.length) {
            throw StateError('يلزم عمود بعنوان واضح مخصص لحالة التدقيق.');
          }
          final header =
              headerRows.single.cells[statusColumn].formatted.toLowerCase();
          if ((!header.contains('تدقيق') && !header.contains('audit')) ||
              RegExp(
                r'راتب|خصم|مالي|salary|payroll|discount|financial',
              ).hasMatch(header)) {
            throw StateError('عنوان العمود لا يثبت أنه مخصص لحالة التدقيق.');
          }
          final entered =
              statusColumn < infoRow.cells.length
                  ? infoRow.cells[statusColumn].entered
                  : <String, dynamic>{};
          if (entered.isNotEmpty &&
              (entered.length != 1 || entered['stringValue'] is! String)) {
            throw StateError('خلية الحالة ليست نصًا ثابتًا؛ راجع العمود.');
          }
          add('status', {
            'spreadsheetId': request.userInfo.spreadsheetId,
            'sheetId': request.userInfo.snapshot.ref.id,
            'idColumn': columnIndex(request.userInfo.idColumn),
            'hasHeader': request.userInfo.hasHeader,
            'statusColumn': statusColumn,
            'nonFinancialConfirmed': request.statusColumnIsVerifiedNonFinancial,
            'previousValue': entered['stringValue'] ?? '',
            'value': _status(member.classification),
          });
        }
      } on StateError catch (e) {
        // If constructing any step failed, discard every step for this member.
        operations.removeWhere((op) => op.memberId == member.memberId);
        issues.add(PlanIssue(member.memberId, e.message));
      }
    }
    if (requested != null) {
      final found = audit.members.map((member) => member.memberId).toSet();
      for (final id in requested.difference(found)) {
        issues.add(
          PlanIssue(
            id,
            'رقم المستخدم غير موجود في معلومات المستخدمين؛ لا يمكن إنشاء خطة له.',
          ),
        );
      }
    }
    return AuditPlanPreview._(
      audit,
      List.unmodifiable(issues),
      List.unmodifiable(operations),
      configHash,
    );
  }

  Future<void> saveApproved(AuditPlanPreview preview) async {
    if (preview._operations.isEmpty) {
      throw StateError('لا توجد عمليات صالحة في المعاينة.');
    }
    await database.createPlan(
      preview._operations,
      expectedCycleConfigFingerprint: preview._cycleConfigFingerprint,
    );
  }
}

void _checkStoredSource(
  Map<dynamic, dynamic> stored,
  PlannerSource source,
  String accountId,
) {
  if (stored['kind'] != 'google' ||
      stored['accountId'] != accountId ||
      stored['spreadsheetId'] != source.spreadsheetId ||
      stored['sheetId'] != source.snapshot.ref.id ||
      stored['sheetTitle'] != source.snapshot.ref.title) {
    throw StateError('مصدر Google أو الحساب لا يطابق إعدادات الدورة المحفوظة.');
  }
}

AuditTable _table(PlannerSource source) {
  final idColumn = columnIndex(source.idColumn);
  var verified = true;
  final rows = <List<String>>[];
  for (final row in source.snapshot.rows) {
    if (row.index < 0 || row.index > 1000000 || row.index < rows.length) {
      throw StateError('ترتيب صفوف المصدر غير صالح.');
    }
    while (rows.length < row.index) {
      rows.add(<String>[]);
    }
    final values = _formatted(row);
    if (!(source.hasHeader && row.index == 0) && idColumn < row.cells.length) {
      final cell = row.cells[idColumn];
      if (cell.entered.isNotEmpty &&
          (cell.entered.length != 1 ||
              cell.entered['stringValue'] is! String ||
              cell.formatted != cell.entered['stringValue'])) {
        verified = false;
      }
    }
    rows.add(values);
  }
  return AuditTable(
    rows: rows,
    idColumn: source.idColumn,
    hasHeader: source.hasHeader,
    spreadsheetId: source.spreadsheetId,
    sheetTitle: source.snapshot.ref.title,
    identifiersAreTextVerified: verified,
  );
}

List<GoogleRow> _matches(PlannerSource source, String id) {
  final column = columnIndex(source.idColumn);
  return source.snapshot.rows.where((row) {
    if (source.hasHeader && row.index == 0 || column >= row.cells.length) {
      return false;
    }
    final value = row.cells[column].entered['stringValue'];
    return value is String && normalizeMemberId(value) == id;
  }).toList();
}

List<String> _formatted(GoogleRow row) {
  final values = row.cells.map((c) => c.formatted).toList();
  while (values.isNotEmpty && values.last.isEmpty) {
    values.removeLast();
  }
  return values;
}

List<Map<String, dynamic>> _entered(GoogleRow row) {
  final values =
      row.cells.map((cell) => Map<String, dynamic>.from(cell.entered)).toList();
  while (values.isNotEmpty && values.last.isEmpty) {
    values.removeLast();
  }
  return values;
}

Map<String, double> _parseColor(String value) {
  if (!RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(value)) {
    throw StateError('لون التدقيق غير صالح.');
  }
  return {
    'red': int.parse(value.substring(1, 3), radix: 16) / 255,
    'green': int.parse(value.substring(3, 5), radix: 16) / 255,
    'blue': int.parse(value.substring(5, 7), radix: 16) / 255,
  };
}

String _status(AuditClassification classification) => switch (classification) {
  AuditClassification.agent => 'سحب وكيل',
  AuditClassification.agentMultiple => 'سحب وكيل راتبين',
  AuditClassification.management => 'سحب إدارة',
  AuditClassification.notFound => 'غير موجود',
};
