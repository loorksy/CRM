import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../data/local_audit_database.dart';
import '../domain/audit_engine.dart';
import '../google/google_sheets_gateway.dart';

/// Durable single-step executor. Every operation is stored before its first
/// mutation, and uncertain writes are read back before any retry.
///
/// source = {accountId, checks: [
///   {role: management|agent|userInfo, spreadsheetId, sheetId, idColumn,
///    hasHeader, expectedRows: [[formatted cell strings...], ...]}
/// ]}. Expected rows exclude headings and include *all* exact ID matches.
/// destination depends on type (copy, color, status, createSheet), see below.
/// Plans must be generated from the approved preview; never construct one
/// from unverified UI input. Local-file sources cannot yet be synchronized.
class AuditSyncEngine {
  AuditSyncEngine(this.database, this.gateway);
  final LocalAuditDatabase database;
  final SheetsGateway gateway;
  static final Set<String> _activeCycles = <String>{};

  Future<OperationRecord> execute(String operationId) async {
    final initial = await database.getOperation(operationId);
    if (initial == null) throw StateError('العملية غير موجودة');
    var op = initial;
    if (_activeCycles.contains(op.cycleId)) throw StateError('توجد عملية أخرى جارية للدورة');
    _activeCycles.add(op.cycleId);
    try {
      // The first read only identifies the cycle to lock. Another executor
      // could have completed while that read was in flight, so base every
      // decision on a fresh read made after acquiring the in-isolate lock.
      final current = await database.getOperation(operationId);
      if (current == null || current.cycleId != initial.cycleId) {
        throw StateError('تغيرت العملية أثناء فتحها');
      }
      op = current;
      final cycle = await database.getCycle(op.cycleId);
      if (cycle == null || cycle.auditClosed) throw StateError('الدورة مغلقة أو غير موجودة');
      if (op.status == OperationStatus.verified || op.status == OperationStatus.conflict) return op;
      final dependencies = op.destination['requiresIds'];
      if (dependencies != null) {
        if (dependencies is! List || dependencies.any((dynamic id) => id is! String || id == op.id)) {
          return await _save(op, OperationStatus.conflict, error: 'ترتيب خطوات العملية غير صالح.');
        }
        for (final dynamic id in dependencies) {
          final prior = await database.getOperation(id as String);
          if (prior == null || prior.cycleId != op.cycleId || prior.status != OperationStatus.verified) {
            // A prerequisite disappearing cannot prove an earlier request for
            // this operation was not accepted. Never downgrade uncertainty.
            final status = op.status == OperationStatus.executing ||
                    op.status == OperationStatus.verificationRequired
                ? OperationStatus.verificationRequired
                : OperationStatus.pending;
            return await _save(op, status, error: 'بانتظار اكتمال خطوة سابقة والتحقق منها.');
          }
        }
      }
      if (op.memberId == null || normalizeMemberId(op.memberId).isEmpty) {
        return await _save(op, OperationStatus.conflict, error: 'رقم المستخدم غير صالح');
      }
      final configFingerprint = sha256.convert(utf8.encode(jsonEncode([
        cycle.managementSource, cycle.agentSource, cycle.columnMappings,
        cycle.snapshots['userInfoSource'], cycle.snapshots['auditTarget'],
      ]))).toString();
      if (op.source['cycleConfigFingerprint'] != configFingerprint) {
        return await _save(op, OperationStatus.conflict, error: 'تغيرت إعدادات الدورة منذ المعاينة.');
      }
      final source = op.source;
      final account = source['accountId'];
      if (account is! String || account.isEmpty) {
        return await _save(op, OperationStatus.conflict, error: 'الحساب غير محدد');
      }
      if (op.status == OperationStatus.executing || op.status == OperationStatus.verificationRequired) {
        // This step may have succeeded just before a timeout or app shutdown.
        final outcome = await _verify(op, account);
        if (outcome == _Verification.present) {
          return await _save(op, OperationStatus.verified, evidence: await _evidence(op, account, 'google_readback'));
        }
        if (outcome == _Verification.conflict) {
          return await _save(op, OperationStatus.conflict, error: 'تغيرت البيانات أو تعذر تأكيد النتيجة؛ راجع العملية.');
        }
        // Absence on a read does not prove that an earlier request will never
        // complete: it could still be in flight at Google. Do not retry or
        // downgrade the persisted uncertainty without stronger evidence.
        return await _save(op, OperationStatus.verificationRequired,
            error: 'لم تظهر نتيجة الطلب السابق بعد؛ يلزم التحقق والمراجعة قبل إعادة المحاولة.');
      }
      final sources = await _validateSources(op, account);
      await _validatePlan(op, account, sources);
      final verification = await _verify(op, account);
      if (verification == _Verification.present) {
        return await _save(op, OperationStatus.verified, evidence: await _evidence(op, account, 'google_existing'));
      }
      if (verification == _Verification.conflict) {
        return await _save(op, OperationStatus.conflict, error: 'البيانات الحالية لا تطابق الخطة. راجع المعاينة.');
      }
      // Google offers no row-level compare-and-swap for the color/status
      // operations, and append behavior has not been tested on a real test
      // spreadsheet. Do not mutate until concurrent-edit safety is resolved.
      if (!_writesEnabled()) {
        return await _save(op, OperationStatus.pending, error: 'الكتابة إلى Google متوقفة إلى حين اكتمال اختبارات السلامة.');
      }
      op = await _save(op, OperationStatus.executing, attempts: op.attempts + 1);
      await _write(op, account, sources);
      op = await _save(op, OperationStatus.verificationRequired);
      final actual = await _verify(op, account);
      if (actual == _Verification.present) {
        return await _save(op, OperationStatus.verified, evidence: await _evidence(op, account, 'google_readback'));
      }
      return await _save(op, actual == _Verification.conflict ? OperationStatus.conflict : OperationStatus.verificationRequired,
          error: 'لم تثبت نتيجة الكتابة. ستتم مراجعتها قبل إعادة المحاولة.');
    } on _Conflict catch (e) {
      return await _save(op, OperationStatus.conflict, error: e.message);
    } on GoogleFailure catch (e) {
      // Once a write starts, no HTTP or transport error proves it did not land.
      final uncertain = op.status == OperationStatus.executing || op.status == OperationStatus.verificationRequired;
      return await _save(op, uncertain ? OperationStatus.verificationRequired : OperationStatus.failed, error: e.message);
    } catch (_) {
      final uncertain = op.status == OperationStatus.executing || op.status == OperationStatus.verificationRequired;
      return await _save(op, uncertain ? OperationStatus.verificationRequired : OperationStatus.failed,
          error: 'تعذر تنفيذ العملية بأمان. راجع الإعدادات ثم حاول مجددًا.');
    } finally {
      _activeCycles.remove(op.cycleId);
    }
  }

  bool _writesEnabled() => false;

  Future<OperationRecord> _save(OperationRecord op, OperationStatus status,
      {String? error, int? attempts, Map<String, Object?>? evidence}) async {
    final updated = OperationRecord(id: op.id, cycleId: op.cycleId,
        memberId: op.memberId, type: op.type, source: op.source,
        destination: op.destination, fingerprint: op.fingerprint, status: status,
        attempts: attempts ?? op.attempts, lastError: error,
        updatedAt: DateTime.now().toUtc(), evidence: evidence ?? op.evidence);
    await database.putOperation(updated);
    return updated;
  }

  Future<Map<String, _SourceMatch>> _validateSources(OperationRecord op, String account) async {
    final checks = op.source['checks'];
    if (checks is! List || checks.length != 3) throw _Conflict('بيانات المصادر غير مكتملة.');
    final roles = <String>{};
    final matches = <String, _SourceMatch>{};
    for (final raw in checks) {
      if (raw is! Map) throw _Conflict('خطة المصدر غير صالحة.');
      final check = Map<String, dynamic>.from(raw);
      final role = check['role'];
      if (role is! String || !{'management', 'agent', 'userInfo'}.contains(role) || !roles.add(role)) {
        throw _Conflict('مصادر الخطة غير مكتملة أو مكررة.');
      }
      final spreadsheet = _string(check, 'spreadsheetId');
      final sheet = _int(check, 'sheetId');
      final idColumn = _int(check, 'idColumn');
      final expected = check['expectedRows'];
      if (expected is! List) throw _Conflict('بيانات المعاينة غير مكتملة.');
      final data = await gateway.readSheet(account, spreadsheet, sheet);
      final found = _matching(data, idColumn, op.memberId!, hasHeader: check['hasHeader'] == true);
      if ((role == 'management' || role == 'userInfo') && found.length != 1) {
        throw _Conflict('رقم المستخدم مفقود أو مكرر في أحد المصادر.');
      }
      final expectedEntered = check['expectedEnteredRows'];
      if (expectedEntered is! List || expectedEntered.length != expected.length) {
        throw _Conflict('أنواع خلايا المصادر غير محفوظة في المعاينة.');
      }
      final actualPairs = found.map((row) {
        final entered = row.cells.map((cell) => cell.entered).toList();
        while (entered.isNotEmpty && entered.last.isEmpty) { entered.removeLast(); }
        return jsonEncode([_formatted(row), entered]);
      }).toList()..sort();
      final expectedPairs = <String>[];
      for (var i = 0; i < expected.length; i++) {
        final values = expected[i];
        final cells = expectedEntered[i];
        if (values is! List || values.any((dynamic c) => c is! String) || cells is! List || cells.any((dynamic c) => c is! Map)) {
          throw _Conflict('بيانات المعاينة غير صالحة.');
        }
        final formatted = List<String>.from(values);
        final entered = cells.map((dynamic c) => Map<String, dynamic>.from(c as Map)).toList();
        while (formatted.isNotEmpty && formatted.last.isEmpty) { formatted.removeLast(); }
        while (entered.isNotEmpty && entered.last.isEmpty) { entered.removeLast(); }
        expectedPairs.add(jsonEncode([formatted, entered]));
      }
      expectedPairs.sort();
      if (jsonEncode(actualPairs) != jsonEncode(expectedPairs)) {
        throw _Conflict('تغيرت بيانات المستخدم أو صيغها منذ المعاينة. أعد المعاينة.');
      }
      matches[role] = _SourceMatch(check, data, found);
    }
    if (roles.length != 3) throw _Conflict('مصادر الخطة غير مكتملة.');
    return matches;
  }

  /// IDs stored as Google numbers are rejected: they may already have lost
  /// precision or leading zeroes. Only full, normalized text IDs are matched.
  List<GoogleRow> _matching(GoogleSheetData sheet, int column, String id, {bool hasHeader = false}) {
    if (column < 0) throw _Conflict('عمود رقم المستخدم غير صالح.');
    final normalized = normalizeMemberId(id);
    final result = <GoogleRow>[];
    for (final row in sheet.rows) {
      if (hasHeader && row.index == 0) continue;
      if (column >= row.cells.length) continue;
      final entered = row.cells[column].entered;
      if (entered.containsKey('numberValue') || entered.containsKey('formulaValue')) {
        throw _Conflict('يوجد رقم مستخدم محفوظ كعدد أو صيغة؛ حوّل المعرفات إلى نص ثم أعد التحميل.');
      }
      final value = entered['stringValue'];
      if (value is String && normalizeMemberId(value) == normalized) result.add(row);
    }
    return result;
  }

  List<String> _formatted(GoogleRow row) {
    final values = row.cells.map((c) => c.formatted).toList();
    while (values.isNotEmpty && values.last.isEmpty) { values.removeLast(); }
    return values;
  }

  Future<_Verification> _verify(OperationRecord op, String account) async {
    final dest = op.destination;
    final spreadsheet = _string(dest, 'spreadsheetId');
    if (op.type == 'createSheet') {
      final title = _string(dest, 'title');
      _validateTitle(title);
      final matching = (await gateway.listSheets(account, spreadsheet))
          .where((sheet) => sheet.title.toLowerCase() == title.toLowerCase()).toList();
      if (matching.isEmpty) return _Verification.absent;
      if (matching.length != 1 || matching.single.title != title) return _Verification.conflict;
      return _Verification.present;
    }
    final sheetId = await _resolveSheet(account, spreadsheet, dest, op);
    final idColumn = _int(dest, 'idColumn');
    final data = await gateway.readSheet(account, spreadsheet, sheetId);
    var rows = _matching(data, idColumn, op.memberId!, hasHeader: dest['hasHeader'] == true);
    if (op.type == 'color' && rows.length > 1 && !_isAgentSource(op, spreadsheet, sheetId)) {
      throw _Conflict('تكرر رقم المستخدم خارج مصدر الوكيل.');
    }
    if (op.type == 'color') rows = _selectColorRows(rows, dest);
    if (rows.length > 1 && op.type != 'color') return _Verification.conflict;
    if (rows.isEmpty) return _Verification.absent;
    final row = rows.first;
    if (op.type == 'copy') {
      final expected = _expectedValues(dest);
      return _sameEntered(row, expected) ? _Verification.present : _Verification.conflict;
    }
    if (op.type == 'color') {
      final endColumn = _int(dest, 'endColumn');
      final expected = _color(dest);
      if (endColumn <= 0 || rows.any((r) => endColumn > r.cells.length)) return _Verification.conflict;
      return rows.every((r) => r.cells.take(endColumn).every((cell) => _sameColor(cell.color, expected)))
          ? _Verification.present : _Verification.absent;
    }
    if (op.type == 'status') {
      final column = _int(dest, 'statusColumn');
      final value = _string(dest, 'value');
      if (column >= row.cells.length) return _Verification.absent;
      final cell = row.cells[column];
      if (cell.entered.containsKey('formulaValue') || cell.entered.containsKey('numberValue')) return _Verification.conflict;
      if (cell.entered['stringValue'] == value) return _Verification.present;
      final previous = dest['previousValue'] as String? ?? '';
      return (cell.entered['stringValue'] ?? '') == previous ? _Verification.absent : _Verification.conflict;
    }
    throw _Conflict('نوع العملية غير مدعوم.');
  }

  Future<int> _resolveSheet(String account, String spreadsheet, Map<dynamic, dynamic> destination, OperationRecord op) async {
    final title = destination['sheetTitle'];
    final storedId = destination['sheetId'];
    if (title == null) return _int(destination, 'sheetId');
    if (title is! String || title.isEmpty) throw _Conflict('اسم الوجهة غير صالح.');
    _validateTitle(title);
    final matches = (await gateway.listSheets(account, spreadsheet))
        .where((s) => s.title.toLowerCase() == title.toLowerCase()).toList();
    if (matches.length != 1 || matches.single.title != title ||
        (storedId != null && storedId != matches.single.id)) {
      throw _Conflict('تعذر تحديد ورقة الوجهة بشكل فريد.');
    }
    final actualId = matches.single.id;
    if (storedId == null) {
      final requires = destination['requiresIds'];
      if (requires is! List) throw _Conflict('لم يتم تثبيت هوية ورقة الوجهة.');
      var pinned = false;
      for (final dynamic dependency in requires) {
        if (dependency is! String) continue;
        final prior = await database.getOperation(dependency);
        if (prior == null || prior.status != OperationStatus.verified || prior.cycleId != op.cycleId) continue;
        final previousTitle = prior.destination['sheetTitle'] ?? prior.destination['title'];
        if (prior.destination['spreadsheetId'] == spreadsheet && previousTitle == title) {
          if (prior.evidence['sheetId'] != actualId) throw _Conflict('تغيرت هوية ورقة الوجهة منذ التحقق منها.');
          pinned = true;
        }
      }
      if (!pinned) throw _Conflict('لم يتم تثبيت هوية ورقة الوجهة بخطوة سابقة.');
    }
    return actualId;
  }

  Future<Map<String, Object?>> _evidence(OperationRecord op, String account, String method) async {
    final spreadsheet = _string(op.destination, 'spreadsheetId');
    int sheetId;
    if (op.type == 'createSheet') {
      final title = _string(op.destination, 'title');
      final matches = (await gateway.listSheets(account, spreadsheet)).where((s) => s.title == title).toList();
      if (matches.length != 1) throw _Conflict('تعذر تثبيت هوية الورقة بعد إنشائها.');
      sheetId = matches.single.id;
    } else {
      sheetId = await _resolveSheet(account, spreadsheet, op.destination, op);
    }
    return {'verifiedAt': DateTime.now().toUtc().toIso8601String(), 'method': method,
      'spreadsheetId': spreadsheet, 'sheetId': sheetId};
  }

  bool _isAgentSource(OperationRecord op, String spreadsheet, int sheetId) {
    final checks = op.source['checks'];
    if (checks is! List) return false;
    return checks.whereType<Map>().any((check) => check['role'] == 'agent' &&
        check['spreadsheetId'] == spreadsheet && check['sheetId'] == sheetId);
  }

  List<GoogleRow> _selectColorRows(List<GoogleRow> rows, Map<dynamic, dynamic> destination) {
    final entered = destination['rowEnteredValues'];
    if (entered != null) {
      if (entered is! List || entered.any((dynamic v) => v is! Map)) throw _Conflict('بصمة خلايا التلوين غير صالحة.');
      final expected = entered.map((dynamic v) => Map<String, dynamic>.from(v as Map)).toList();
      return rows.where((r) => _sameEntered(r, expected)).toList();
    }
    final values = destination['rowValues'];
    if (values == null) return rows;
    if (values is! List || values.any((dynamic c) => c is! String)) throw _Conflict('بصمة صف التلوين غير صالحة.');
    final expected = List<String>.from(values);
    while (expected.isNotEmpty && expected.last.isEmpty) { expected.removeLast(); }
    return rows.where((r) => jsonEncode(_formatted(r)) == jsonEncode(expected)).toList();
  }

  Future<void> _validatePlan(OperationRecord op, String account, Map<String, _SourceMatch> sources) async {
    final destination = op.destination;
    final spreadsheet = _string(destination, 'spreadsheetId');
    final info = sources['userInfo']!;
    final titleColumn = _int(info.check, 'titleColumn');
    final infoRow = info.rows.single;
    if (titleColumn >= infoRow.cells.length) throw _Conflict('عمود الوجهة غير موجود.');
    final targetTitle = infoRow.cells[titleColumn].entered['stringValue'];
    if (targetTitle is! String || targetTitle.isEmpty) throw _Conflict('اسم ورقة الوجهة فارغ أو ليس نصًا.');
    _validateTitle(targetTitle);
    final targetSpreadsheet = _string(op.source, 'targetSpreadsheetId');
    if (op.type == 'createSheet' || op.type == 'copy') {
      if (spreadsheet != targetSpreadsheet) throw _Conflict('جدول الوجهة لا يطابق الجدول المعتمد.');
      final plannedTitle = op.type == 'createSheet'
          ? _string(destination, 'title')
          : (await gateway.readSheet(account, spreadsheet, await _resolveSheet(account, spreadsheet, destination, op))).ref.title;
      if (plannedTitle != targetTitle) throw _Conflict('الوجهة لا تطابق معلومات المستخدم.');
      await _ensureNotSource(op, account, spreadsheet, plannedTitle);
      if (op.type == 'copy' && !_sameEntered(sources['management']!.rows.single, _expectedValues(destination))) {
        throw _Conflict('الصف المراد نسخه لا يطابق الإدارة.');
      }
    } else if (op.type == 'status') {
      if (spreadsheet != info.check['spreadsheetId'] || destination['sheetId'] != info.check['sheetId'] ||
          destination['idColumn'] != info.check['idColumn'] || destination['statusColumn'] == titleColumn) {
        throw _Conflict('وجهة حالة التدقيق لا تطابق معلومات المستخدم.');
      }
      if (destination['nonFinancialConfirmed'] != true) throw _Conflict('عمود الحالة لم يعتمد للتدقيق.');
      final value = _string(destination, 'value');
      if (!{'سحب وكيل', 'سحب وكيل راتبين', 'سحب إدارة', 'غير موجود'}.contains(value)) {
        throw _Conflict('وصف الحالة غير صالح.');
      }
      final col = _int(destination, 'statusColumn');
      // A dedicated audit header is required; a user selection alone does not
      // establish that a salary or formula column is safe to overwrite.
      final headers = info.data.rows.where((r) => r.index == 0).toList();
      if (info.check['hasHeader'] != true || headers.length != 1 || col >= headers.single.cells.length) {
        throw _Conflict('يلزم عمود بعنوان واضح مخصص لحالة التدقيق.');
      }
      final header = headers.single.cells[col].formatted.toLowerCase();
      if ((!header.contains('تدقيق') && !header.contains('audit')) ||
          RegExp(r'راتب|خصم|مالي|salary|payroll|discount|financial').hasMatch(header)) {
        throw _Conflict('عنوان العمود لا يثبت أنه مخصص لحالة التدقيق.');
      }
    } else if (op.type == 'color') {
      final sheetId = await _resolveSheet(account, spreadsheet, destination, op);
      final matchingSources = sources.values.where((s) => s.check['spreadsheetId'] == spreadsheet && s.check['sheetId'] == sheetId && s.check['role'] != 'userInfo').toList();
      final data = await gateway.readSheet(account, spreadsheet, sheetId);
      if (matchingSources.isEmpty && (spreadsheet != targetSpreadsheet || data.ref.title != targetTitle)) {
        throw _Conflict('ورقة التلوين لا تخص المستخدم أو مصادره.');
      }
      final expectedColumn = matchingSources.isEmpty ? sources['management']!.check['idColumn'] : matchingSources.single.check['idColumn'];
      if (destination['idColumn'] != expectedColumn) throw _Conflict('عمود المعرف للتلوين غير مطابق للمصدر.');
    } else {
      throw _Conflict('نوع العملية غير مدعوم.');
    }
  }

  Future<void> _write(OperationRecord op, String account, Map<String, _SourceMatch> sources) async {
    final dest = op.destination;
    final spreadsheet = _string(dest, 'spreadsheetId');
    if (op.type == 'createSheet') {
      final title = _string(dest, 'title');
      _validateTitle(title);
      // Source titles are forbidden even if the plan is malformed.
      await _ensureNotSource(op, account, spreadsheet, title);
      await gateway.createSheet(account, spreadsheet, title);
      return;
    }
    final sheetId = await _resolveSheet(account, spreadsheet, dest, op);
    final data = await gateway.readSheet(account, spreadsheet, sheetId);
    final idColumn = _int(dest, 'idColumn');
    var rows = _matching(data, idColumn, op.memberId!, hasHeader: dest['hasHeader'] == true);
    if (op.type == 'color' && rows.length > 1 && !_isAgentSource(op, spreadsheet, sheetId)) {
      throw _Conflict('تكرر رقم المستخدم خارج مصدر الوكيل.');
    }
    if (op.type == 'color') rows = _selectColorRows(rows, dest);
    if (rows.length > 1 && op.type != 'color') throw _Conflict('تكرر رقم المستخدم في الوجهة.');
    if (op.type == 'copy') {
      if (rows.isNotEmpty) throw _Conflict('يوجد صف بهذا الرقم بالفعل في الوجهة.');
      await _ensureNotSource(op, account, spreadsheet, data.ref.title);
      final values = _expectedValues(dest);
      final management = sources['management']!.rows.single;
      if (!_sameEntered(management, values)) throw _Conflict('الصف المطلوب نسخه لا يطابق مصدر الإدارة.');
      if (idColumn >= values.length || normalizeMemberId(values[idColumn]['stringValue']) != normalizeMemberId(op.memberId)) {
        throw _Conflict('رقم المستخدم في الصف المراد نسخه غير مطابق.');
      }
      if (values.any((v) => v.containsKey('formulaValue'))) throw _Conflict('يحتوي الصف على صيغة ويحتاج مراجعة قبل النسخ.');
      // Google Sheets does not provide a compare-and-swap for an indexed
      // row update. Never risk overwriting a row added by another editor.
      throw _Conflict('نسخ الصفوف متوقف لحين التحقق من سلامته مع التعديلات المتزامنة.');
    }
    if (rows.isEmpty) throw _Conflict('لا يوجد صف مطابق لرقم المستخدم.');
    if (op.type == 'color') {
      final endColumn = _int(dest, 'endColumn');
      if (endColumn <= 0 || rows.any((r) => endColumn > r.cells.length)) throw _Conflict('نطاق التلوين غير صالح.');
      for (final row in rows) { _ensureWritable(data, row.index, 0, endColumn); }
      for (final row in rows) {
        if (!row.cells.take(endColumn).every((cell) => _sameColor(cell.color, _color(dest)))) {
          await gateway.colorRow(account, spreadsheet, sheetId, row.index, endColumn, _color(dest));
        }
      }
      return;
    }
    final row = rows.single;
    if (op.type == 'status') {
      final col = _int(dest, 'statusColumn');
      if (dest['nonFinancialConfirmed'] != true || col == idColumn) throw _Conflict('لم يتم التحقق من أن عمود الحالة مخصص للتدقيق.');
      final value = _string(dest, 'value');
      final current = col < row.cells.length ? row.cells[col].entered : <String, dynamic>{};
      if (current.containsKey('formulaValue') || current.containsKey('numberValue') ||
          (current['stringValue'] ?? '') != (dest['previousValue'] ?? '')) {
        throw _Conflict('خلية الحالة تغيرت أو تحتوي بيانات لا يمكن استبدالها.');
      }
      _ensureWritable(data, row.index, col, col + 1);
      await gateway.writeStatus(account, spreadsheet, sheetId, row.index, col, value);
      return;
    }
    throw _Conflict('نوع العملية غير مدعوم.');
  }

  Future<void> _ensureNotSource(OperationRecord op, String account, String spreadsheet, String title) async {
    final checks = op.source['checks'];
    if (checks is! List) throw _Conflict('المصادر غير مكتملة.');
    for (final raw in checks) {
      final check = Map<String, dynamic>.from(raw as Map);
      if (check['spreadsheetId'] != spreadsheet) continue;
      final source = await gateway.readSheet(account, spreadsheet, _int(check, 'sheetId'));
      if (source.ref.title.toLowerCase() == title.toLowerCase()) {
        throw _Conflict('لا يمكن النسخ إلى إحدى أوراق المصدر.');
      }
    }
  }
}

class _SourceMatch {
  _SourceMatch(this.check, this.data, this.rows);
  final Map<String, dynamic> check;
  final GoogleSheetData data;
  final List<GoogleRow> rows;
}

enum _Verification { present, absent, conflict }
class _Conflict implements Exception { _Conflict(this.message); final String message; }

String _string(Map<dynamic, dynamic> map, String key) {
  final value = map[key];
  if (value is! String || value.trim().isEmpty) throw _Conflict('إعداد العملية غير مكتمل: $key');
  return value;
}
int _int(Map<dynamic, dynamic> map, String key) {
  final value = map[key];
  if (value is! int || value < 0) throw _Conflict('إعداد العملية غير صالح: $key');
  return value;
}
List<Map<String, dynamic>> _expectedValues(Map<dynamic, dynamic> map) {
  final raw = map['values'];
  if (raw is! List || raw.isEmpty) throw _Conflict('الصف المراد نسخه غير محفوظ في الخطة.');
  return raw.map((dynamic value) => Map<String, dynamic>.from(value as Map)).toList();
}
bool _sameEntered(GoogleRow row, List<Map<String, dynamic>> expected) {
  final actual = row.cells.map((c) => c.entered).toList();
  while (actual.isNotEmpty && actual.last.isEmpty) { actual.removeLast(); }
  final planned = List<Map<String, dynamic>>.from(expected);
  while (planned.isNotEmpty && planned.last.isEmpty) { planned.removeLast(); }
  return jsonEncode(actual) == jsonEncode(planned);
}
Map<String, double> _color(Map<dynamic, dynamic> map) {
  final raw = map['color'];
  if (raw is! Map) throw _Conflict('اللون غير محدد.');
  final result = <String, double>{};
  for (final name in ['red', 'green', 'blue']) {
    final value = raw[name];
    if (value is! num || value < 0 || value > 1) throw _Conflict('قيمة اللون غير صالحة.');
    result[name] = value.toDouble();
  }
  return result;
}
bool _sameColor(Map<String, dynamic>? actual, Map<String, double> expected) {
  if (actual == null) return false;
  return expected.entries.every((entry) => ((actual[entry.key] as num? ?? 0).toDouble() - entry.value).abs() < 0.00001);
}
void _validateTitle(String title) {
  if (title.trim() != title || title.runes.length > 100 || title.startsWith("'") || title.endsWith("'") ||
      RegExp(r'[\[\]:*?/\\\x00-\x1f]').hasMatch(title)) {
    throw _Conflict('اسم الورقة الهدف غير صالح.');
  }
}
void _ensureWritable(GoogleSheetData data, int row, int startColumn, int endColumn) {
  bool intersects(Map<String, dynamic> range) {
    final startRow = range['startRowIndex'] as int? ?? 0;
    final endRow = range['endRowIndex'] as int? ?? 2147483647;
    final startCol = range['startColumnIndex'] as int? ?? 0;
    final endCol = range['endColumnIndex'] as int? ?? 2147483647;
    return row >= startRow && row < endRow && startColumn < endCol && endColumn > startCol;
  }
  if (data.merges.any(intersects)) throw _Conflict('الصف يتقاطع مع خلايا مدمجة.');
  for (final protection in data.protectedRanges) {
    final range = protection['range'];
    // warningOnly protections still need a human review.
    if (range == null || (range is Map && intersects(Map<String, dynamic>.from(range)))) {
      throw _Conflict('الصف أو الخلية ضمن نطاق محمي.');
    }
  }
}
