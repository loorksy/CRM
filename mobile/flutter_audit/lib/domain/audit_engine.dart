import 'dart:convert';

import 'package:crypto/crypto.dart';

/// Normalizes an identifier as text. Internal whitespace and punctuation are
/// preserved; no numeric conversion, rounding or substring matching is used.
String normalizeMemberId(Object? value) {
  if (value == null) return '';
  final valueText = value.toString();
  final buffer = StringBuffer();
  for (final rune in valueText.runes) {
    if ((rune >= 0x200b && rune <= 0x200f) ||
        rune == 0x2060 ||
        rune == 0xfeff ||
        (rune >= 0x202a && rune <= 0x202e) ||
        (rune >= 0x2066 && rune <= 0x2069)) {
      continue;
    }
    if (rune >= 0x0660 && rune <= 0x0669) {
      buffer.writeCharCode(0x30 + rune - 0x0660);
    } else if (rune >= 0x06f0 && rune <= 0x06f9) {
      buffer.writeCharCode(0x30 + rune - 0x06f0);
    } else {
      buffer.writeCharCode(rune);
    }
  }
  return buffer.toString().trim();
}

/// A = 0, Z = 25, AA = 26. Invalid or unreasonably large input is rejected.
int columnIndex(String column) {
  final text = column.trim().toUpperCase();
  if (!RegExp(r'^[A-Z]{1,6}$').hasMatch(text)) {
    throw FormatException('حرف العمود غير صالح', column);
  }
  var result = 0;
  for (final char in text.codeUnits) {
    result = result * 26 + char - 64;
  }
  return result - 1;
}

/// A physical source and a frozen snapshot. Row numbers below are 1-based.
class AuditTable {
  AuditTable({
    required this.rows,
    required this.idColumn,
    required this.hasHeader,
    this.spreadsheetId,
    this.sheetTitle,
    this.sourceId,
    this.identifiersAreTextVerified = false,
  });

  final List<List<String>> rows;
  final String idColumn;
  final bool hasHeader;

  /// Null for a locally imported file.
  final String? spreadsheetId;
  final String? sheetTitle;

  /// Stable identity of a local source or snapshot when available.
  final String? sourceId;

  /// True only after the importer inspected original cell types and confirmed
  /// every nonempty identifier is literal text (not a number or formula).
  /// CSV may set this when decoded losslessly; never infer it from stringified
  /// numeric Excel/Sheets cells, which may already have lost digits/zeros.
  final bool identifiersAreTextVerified;
}

class AuditInput {
  AuditInput({
    required this.management,
    required this.agent,
    required this.userInfo,
    required this.titleColumn,
    required this.targetSpreadsheetId,
    required this.agentColor,
    required this.managementColor,
    this.statusColumn,
    this.statusColumnIsVerifiedNonFinancial = false,
  });

  final AuditTable management;
  final AuditTable agent;
  final AuditTable userInfo;
  final String titleColumn;

  /// Explicit destination: never inferred for imported files.
  final String targetSpreadsheetId;
  final String agentColor;
  final String managementColor;
  final String? statusColumn;

  /// A caller must explicitly verify the chosen column is not financial and
  /// does not overlap an identifier, target-name column, or protected formula.
  final bool statusColumnIsVerifiedNonFinancial;
}

enum AuditClassification { agent, agentMultiple, management, notFound }

enum AuditConflictCode {
  invalidMemberId,
  duplicateUserInfo,
  duplicateManagement,
  missingTarget,
  invalidTarget,
  targetNameCollision,
  targetIsSource,
  missingDestination,
  invalidStatusColumn,
  emptyManagement,
  emptyAgent,
  emptyUserInfo,
  missingColumn,
  unverifiedIdentifierSource,
}

class AuditConflict {
  const AuditConflict(this.code, this.message);
  final AuditConflictCode code;
  final String message;
}

class SourceRow {
  const SourceRow(this.rowNumber, this.values);
  final int rowNumber;
  final List<String> values;
}

class MemberPreview {
  const MemberPreview({
    required this.memberId,
    required this.classification,
    required this.managementRow,
    required this.agentRows,
    required this.userInfoRow,
    required this.targetTitle,
    required this.fingerprint,
    required this.conflicts,
    required this.diagnostic,
  });
  final String memberId;
  final AuditClassification classification;

  /// Null when no unambiguous management row exists.
  final SourceRow? managementRow;
  final List<SourceRow> agentRows;
  final SourceRow userInfoRow;
  final String? targetTitle;

  /// SHA-256 of the matching input rows and all relevant settings, not a
  /// substitute for re-reading the source before a write.
  final String fingerprint;
  final List<AuditConflict> conflicts;
  final String? diagnostic;
  bool get canAudit =>
      conflicts.isEmpty &&
      managementRow != null &&
      classification != AuditClassification.notFound;
}

class AuditPreview {
  const AuditPreview({required this.members, required this.conflicts});
  final List<MemberPreview> members;

  /// Configuration conflicts apply to the entire preview.
  final List<AuditConflict> conflicts;
  bool get canExecute => conflicts.isEmpty && members.any((m) => m.canAudit);
}

class AuditEngine {
  const AuditEngine();

  AuditPreview preview(AuditInput input) {
    final mgmtColumn = columnIndex(input.management.idColumn);
    final agentColumn = columnIndex(input.agent.idColumn);
    final infoColumn = columnIndex(input.userInfo.idColumn);
    final titleColumn = columnIndex(input.titleColumn);
    final String? statusText = input.statusColumn?.trim();
    final statusColumn = statusText == null || statusText.isEmpty
        ? null
        : columnIndex(statusText);
    final common = <AuditConflict>[];
    if (input.management.rows.isEmpty ||
        input.management.rows.length <= (input.management.hasHeader ? 1 : 0)) {
      common.add(
        const AuditConflict(
          AuditConflictCode.emptyManagement,
          'لا توجد بيانات إدارة محفوظة للدورة',
        ),
      );
    }
    if (input.userInfo.rows.isEmpty ||
        input.userInfo.rows.length <= (input.userInfo.hasHeader ? 1 : 0)) {
      common.add(
        const AuditConflict(
          AuditConflictCode.emptyUserInfo,
          'لا توجد بيانات معلومات مستخدمين محفوظة للدورة',
        ),
      );
    }
    // An empty agent snapshot is a legitimate dataset; a missing snapshot must
    // be distinguished by persistence before invoking the domain engine.
    if (input.targetSpreadsheetId.trim().isEmpty) {
      common.add(
        const AuditConflict(
          AuditConflictCode.missingDestination,
          'اختر جدول الوجهة قبل تنفيذ التدقيق',
        ),
      );
    }
    if (statusColumn != null &&
        (!input.statusColumnIsVerifiedNonFinancial ||
            statusColumn == infoColumn ||
            statusColumn == titleColumn)) {
      common.add(
        const AuditConflict(
          AuditConflictCode.invalidStatusColumn,
          'تحقق من أن عمود الحالة مخصص للتدقيق ولا يحتوي بيانات أخرى أو صيغًا',
        ),
      );
    }
    for (final entry in <(AuditTable, int)>[
      (input.management, mgmtColumn),
      (input.agent, agentColumn),
      (input.userInfo, infoColumn),
      (input.userInfo, titleColumn),
    ]) {
      final rows = entry.$1.rows.skip(entry.$1.hasHeader ? 1 : 0).toList();
      if (rows.isNotEmpty && rows.every((r) => r.length <= entry.$2)) {
        common.add(
          const AuditConflict(
            AuditConflictCode.missingColumn,
            'أحد الأعمدة المحددة غير موجود في البيانات',
          ),
        );
      }
    }
    if (!input.management.identifiersAreTextVerified ||
        !input.agent.identifiersAreTextVerified ||
        !input.userInfo.identifiersAreTextVerified) {
      common.add(
        const AuditConflict(
          AuditConflictCode.unverifiedIdentifierSource,
          'تعذّر التأكد من أن أرقام المستخدمين نصية؛ راجع خلايا الأرقام والصيغ ثم أعد الاستيراد',
        ),
      );
    }
    final mgmt = _indexRows(input.management, mgmtColumn);
    final agents = _indexRows(input.agent, agentColumn);
    final info = _indexRows(input.userInfo, infoColumn);
    final infoStart = input.userInfo.hasHeader ? 1 : 0;

    // Distinct spellings that fold to the same sheet name are ambiguous.
    final targetSpellings = <String, Set<String>>{};
    for (var i = infoStart; i < input.userInfo.rows.length; i++) {
      final row = input.userInfo.rows[i];
      final title = _cell(row, titleColumn).trim();
      if (title.isNotEmpty) {
        targetSpellings
            .putIfAbsent(title.toLowerCase(), () => <String>{})
            .add(title);
      }
    }

    final members = <MemberPreview>[];
    for (var i = infoStart; i < input.userInfo.rows.length; i++) {
      final cells = input.userInfo.rows[i];
      if (cells.every((cell) => cell.trim().isEmpty)) continue;
      final id = normalizeMemberId(_cell(cells, infoColumn));
      final infoRow = SourceRow(i + 1, List.unmodifiable(cells));
      final foundMgmt = mgmt[id] ?? const <SourceRow>[];
      final foundAgent = agents[id] ?? const <SourceRow>[];
      final memberConflicts = <AuditConflict>[...common];
      final title = _cell(cells, titleColumn).trim();
      if (infoColumn >= cells.length || titleColumn >= cells.length) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.missingColumn,
            'صف معلومات المستخدم لا يحتوي الأعمدة المحددة',
          ),
        );
      }
      if (statusColumn != null &&
          _cell(cells, statusColumn).trimLeft().startsWith('=')) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.invalidStatusColumn,
            'خلية الحالة تحتوي صيغة ولا يمكن الكتابة فوقها',
          ),
        );
      }
      if (id.isEmpty || RegExp(r'[\x00-\x1f\x7f]').hasMatch(id)) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.invalidMemberId,
            'رقم المستخدم فارغ أو غير صالح',
          ),
        );
      }
      if (id.isNotEmpty && (info[id]?.length ?? 0) > 1) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.duplicateUserInfo,
            'رقم المستخدم مكرر في معلومات المستخدمين',
          ),
        );
      }
      if (foundMgmt.length > 1) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.duplicateManagement,
            'رقم المستخدم مكرر في الإدارة؛ راجع الصفوف قبل التدقيق',
          ),
        );
      }
      if (title.isEmpty) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.missingTarget,
            'اسم الورقة الهدف فارغ',
          ),
        );
      } else if (!_validSheetTitle(title)) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.invalidTarget,
            'اسم الورقة الهدف غير صالح',
          ),
        );
      } else if ((targetSpellings[title.toLowerCase()]?.length ?? 0) > 1) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.targetNameCollision,
            'توجد أسماء أوراق متشابهة تختلف بحالة الأحرف',
          ),
        );
      }
      if (title.isNotEmpty &&
          (_isSource(input.management, input.targetSpreadsheetId, title) ||
              _isSource(input.agent, input.targetSpreadsheetId, title) ||
              _isSource(input.userInfo, input.targetSpreadsheetId, title))) {
        memberConflicts.add(
          const AuditConflict(
            AuditConflictCode.targetIsSource,
            'الورقة الهدف هي إحدى أوراق المصدر',
          ),
        );
      }
      final hasMgmt = foundMgmt.isNotEmpty;
      final hasAgent = foundAgent.isNotEmpty;
      final classification = hasMgmt
          ? (hasAgent
                ? (foundAgent.length > 1
                      ? AuditClassification.agentMultiple
                      : AuditClassification.agent)
                : AuditClassification.management)
          : AuditClassification.notFound;
      final diagnostic = !hasMgmt && hasAgent
          ? 'المستخدم موجود لدى الوكيل فقط ولا يوجد صف إدارة لنسخه'
          : !hasMgmt
          ? 'المستخدم غير موجود في الإدارة أو الوكيل'
          : null;
      final fingerprint = _fingerprint(
        input,
        id,
        cells,
        info[id] ?? const <SourceRow>[],
        foundMgmt,
        foundAgent,
      );
      members.add(
        MemberPreview(
          memberId: id,
          classification: classification,
          managementRow: foundMgmt.length == 1 ? foundMgmt.single : null,
          agentRows: List.unmodifiable(foundAgent),
          userInfoRow: infoRow,
          targetTitle: title.isEmpty ? null : title,
          fingerprint: fingerprint,
          conflicts: List.unmodifiable(memberConflicts),
          diagnostic: diagnostic,
        ),
      );
    }
    return AuditPreview(
      members: List.unmodifiable(members),
      conflicts: List.unmodifiable(common),
    );
  }
}

String _cell(List<String> row, int column) =>
    column < row.length ? row[column] : '';

Map<String, List<SourceRow>> _indexRows(AuditTable table, int idColumn) {
  final result = <String, List<SourceRow>>{};
  final start = table.hasHeader ? 1 : 0;
  for (var i = start; i < table.rows.length; i++) {
    final cells = table.rows[i];
    final id = normalizeMemberId(_cell(cells, idColumn));
    if (id.isNotEmpty) {
      result
          .putIfAbsent(id, () => <SourceRow>[])
          .add(SourceRow(i + 1, List.unmodifiable(cells)));
    }
  }
  return result;
}

bool _validSheetTitle(String title) =>
    title.runes.length <= 100 &&
    !RegExp(r'[\[\]:*?/\\\x00-\x1f]').hasMatch(title) &&
    !title.startsWith("'") &&
    !title.endsWith("'");

bool _isSource(AuditTable table, String destination, String title) =>
    table.spreadsheetId != null &&
    table.spreadsheetId!.trim() == destination.trim() &&
    table.sheetTitle != null &&
    table.sheetTitle!.trim().toLowerCase() == title.toLowerCase();

String _fingerprint(
  AuditInput input,
  String id,
  List<String> info,
  List<SourceRow> infoMatches,
  List<SourceRow> mgmt,
  List<SourceRow> agent,
) {
  Map<String, Object?> tableConfig(AuditTable t) => <String, Object?>{
    'sourceId': t.sourceId,
    'identifiersAreTextVerified': t.identifiersAreTextVerified,
    'spreadsheet': t.spreadsheetId,
    'sheet': t.sheetTitle,
    'idColumn': t.idColumn.trim().toUpperCase(),
    'header': t.hasHeader,
  };
  final payload = <String, Object?>{
    'version': 1,
    'memberId': id,
    'management': tableConfig(input.management),
    'agent': tableConfig(input.agent),
    'userInfo': tableConfig(input.userInfo),
    'managementRows': mgmt.map((r) => r.values).toList(),
    'agentRows': agent.map((r) => r.values).toList(),
    'userInfoRow': info,
    'userInfoMatches': infoMatches.map((r) => r.values).toList(),
    'targetSpreadsheet': input.targetSpreadsheetId.trim(),
    'targetColumn': input.titleColumn.trim().toUpperCase(),
    'statusColumn': input.statusColumn?.trim().toUpperCase(),
    'statusVerified': input.statusColumnIsVerifiedNonFinancial,
    'agentColor': input.agentColor,
    'managementColor': input.managementColor,
  };
  return sha256.convert(utf8.encode(jsonEncode(payload))).toString();
}
