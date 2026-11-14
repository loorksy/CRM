import 'package:file_picker/file_picker.dart';
// Flutter's newer material_ui package is not available on older supported SDKs.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../application/audit_plan_review_service.dart';
import '../data/import_service.dart';
import '../data/local_audit_database.dart';
import '../domain/audit_engine.dart';
import '../google/google_sheets_gateway.dart';

class CycleEditor extends StatefulWidget {
  const CycleEditor({
    super.key,
    required this.db,
    required this.session,
    this.cycle,
  });
  final LocalAuditDatabase db;
  final GoogleSession session;
  final CycleRecord? cycle;
  @override
  State<CycleEditor> createState() => _CycleEditorState();
}

class _Source {
  _Source(this.label, this.sheet, this.metadata);
  final String label;
  final ImportedSheet sheet;
  final Map<String, Object?> metadata;
}

class _CycleEditorState extends State<CycleEditor> {
  final name = TextEditingController();
  final managementColumn = TextEditingController(text: 'A');
  final agentColumn = TextEditingController(text: 'A');
  _Source? management, agent;
  bool? managementHeader, agentHeader;
  int step = 0;
  bool busy = false;
  String? error;
  GoogleSheetsGateway get gateway => GoogleSheetsGateway(widget.session);

  @override
  void initState() {
    super.initState();
    final c = widget.cycle;
    if (c != null) {
      name.text = c.name;
      managementColumn.text =
          c.columnMappings['managementId'] as String? ?? 'A';
      agentColumn.text = c.columnMappings['agentId'] as String? ?? 'A';
      managementHeader = c.columnMappings['managementHeader'] as bool?;
      agentHeader = c.columnMappings['agentHeader'] as bool?;
      management = _restore(c.managementSource, c.snapshots['management']);
      agent = _restore(c.agentSource, c.snapshots['agent']);
    }
  }

  _Source? _restore(Map<String, Object?> metadata, Object? data) {
    if (data is! List) return null;
    final rows =
        data
            .whereType<List>()
            .map((r) => r.map((c) => c?.toString() ?? '').toList())
            .toList();
    final rawUnsafe = metadata['nonTextCells'];
    // A missing type record is not evidence that the original cells were text.
    if (rawUnsafe is! List) return null;
    final unsafe = <(int, int)>{};
    for (final value in rawUnsafe) {
      if (value is! List ||
          value.length != 2 ||
          value[0] is! int ||
          value[1] is! int) {
        return null;
      }
      unsafe.add((value[0] as int, value[1] as int));
    }
    final title = metadata['label'] as String? ?? 'مصدر محفوظ';
    return _Source(title, ImportedSheet(title, rows, unsafe), metadata);
  }

  @override
  void dispose() {
    name.dispose();
    managementColumn.dispose();
    agentColumn.dispose();
    super.dispose();
  }

  void _setSource(bool isManagement, _Source source) => setState(() {
    if (isManagement) {
      management = source;
      managementHeader = null;
    } else {
      agent = source;
      agentHeader = null;
    }
    error = null;
  });

  Future<T?> _select<T>(String title, List<T> items, String Function(T) label) {
    var query = '';
    return showDialog<T>(
      context: context,
      builder:
          (context) => StatefulBuilder(
            builder: (context, setDialogState) {
              final visible =
                  items
                      .where(
                        (item) => label(
                          item,
                        ).toLowerCase().contains(query.toLowerCase()),
                      )
                      .toList();
              return AlertDialog(
                title: Text(title),
                content: SizedBox(
                  width: double.maxFinite,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextField(
                        decoration: const InputDecoration(
                          labelText: 'بحث بالاسم',
                          prefixIcon: Icon(Icons.search),
                        ),
                        onChanged: (text) => setDialogState(() => query = text),
                      ),
                      const SizedBox(height: 8),
                      SizedBox(
                        height: 300,
                        child:
                            visible.isEmpty
                                ? const Center(
                                  child: Text('لا توجد عناصر متاحة.'),
                                )
                                : ListView.builder(
                                  itemCount: visible.length,
                                  itemBuilder:
                                      (context, index) => ListTile(
                                        title: Text(label(visible[index])),
                                        onTap:
                                            () => Navigator.pop(
                                              context,
                                              visible[index],
                                            ),
                                      ),
                                ),
                      ),
                    ],
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('إلغاء'),
                  ),
                ],
              );
            },
          ),
    );
  }

  Future<void> _file(bool isManagement) async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['xlsx', 'csv'],
        withData: true,
      );
      if (result == null || !mounted) return;
      final file = result.files.single;
      if (file.bytes == null) {
        throw const FormatException('تعذرت قراءة الملف المحدد.');
      }
      ImportedSheet sheet;
      if (file.extension?.toLowerCase() == 'csv') {
        final encoding = await _select<String>(
          'اختر ترميز الملف',
          ['auto', 'utf-8', 'utf-16le', 'utf-16be', 'windows-1256'],
          (x) => switch (x) {
            'auto' => 'تلقائي (UTF-8 أو علامة UTF-16)',
            'windows-1256' => 'Windows-1256 عربي',
            _ => x.toUpperCase(),
          },
        );
        if (encoding == null) return;
        sheet = ImportService.decodeCsv(
          file.bytes!,
          encoding: encoding == 'auto' ? null : encoding,
        );
      } else {
        final book = ImportService.readXlsx(file.bytes!);
        if (!mounted) return;
        final selected = await _select<String>(
          'اختر ورقة العمل',
          book.sheetNames,
          (x) => x,
        );
        if (selected == null) return;
        sheet = book.selectSheet(selected);
      }
      _setSource(
        isManagement,
        _Source('${file.name} · ${sheet.name}', sheet, {
          'kind': 'file',
          'label': '${file.name} · ${sheet.name}',
          'fileName': file.name,
          'sheetTitle': sheet.name,
          'nonTextCells': sheet.nonTextCells.map((p) => [p.$1, p.$2]).toList(),
        }),
      );
    } catch (e) {
      if (mounted) setState(() => error = 'تعذر استيراد الملف: ${_error(e)}');
    }
  }

  Future<void> _google(bool isManagement) async {
    try {
      final account =
          widget.session.accountId ?? await widget.session.connect();
      if (account == null || !mounted) return;
      setState(() {
        busy = true;
        error = null;
      });
      final spreadsheets = await gateway.listSpreadsheets(account);
      if (!mounted) return;
      setState(() => busy = false);
      final spreadsheet = await _select<SpreadsheetRef>(
        'اختر جدول البيانات',
        spreadsheets,
        (x) => x.name,
      );
      if (spreadsheet == null || !mounted) return;
      setState(() => busy = true);
      final sheets = await gateway.listSheets(account, spreadsheet.id);
      if (!mounted) return;
      setState(() => busy = false);
      final tab = await _select<SheetRef>(
        'اختر ورقة العمل',
        sheets,
        (x) => x.title,
      );
      if (tab == null || !mounted) return;
      setState(() => busy = true);
      final data = await gateway.readSheet(account, spreadsheet.id, tab.id);
      if (!mounted) return;
      final rows = <List<String>>[];
      final unsafe = <(int, int)>{};
      for (final row in data.rows) {
        while (rows.length <= row.index) {
          rows.add([]);
        }
        final cells = <String>[];
        for (final cell in row.cells) {
          final value = cell.entered;
          if (value.isNotEmpty && !value.containsKey('stringValue')) {
            unsafe.add((row.index, cells.length));
          }
          cells.add(value['stringValue']?.toString() ?? cell.formatted);
        }
        rows[row.index] = cells;
      }
      final sheet = ImportedSheet(tab.title, rows, unsafe);
      final label = '${spreadsheet.name} · ${tab.title}';
      _setSource(
        isManagement,
        _Source(label, sheet, {
          'kind': 'google',
          'label': label,
          'accountId': account,
          'spreadsheetId': spreadsheet.id,
          'spreadsheetName': spreadsheet.name,
          'sheetId': tab.id,
          'sheetTitle': tab.title,
          'nonTextCells': unsafe.map((p) => [p.$1, p.$2]).toList(),
          'googleReviewProof': AuditPlanReviewService.captureProof(data),
        }),
      );
    } catch (e) {
      if (mounted) setState(() => error = 'تعذر قراءة Google: ${_error(e)}');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  String _error(Object e) {
    if (e is FormatException) return e.message;
    if (e is GoogleFailure) return e.message;
    return 'تحقق من الملف أو الاتصال وحاول مجددًا.';
  }

  void _validate(
    _Source source,
    String column,
    bool? header, {
    bool allowEmpty = false,
  }) {
    if (header == null) {
      throw const FormatException('حدد وجود صف العناوين لكل مصدر.');
    }
    final index = columnIndex(column);
    source.sheet.validateIdentifierColumn(index, firstDataRow: header ? 1 : 0);
    final rows = source.sheet.rows.skip(header ? 1 : 0);
    if (!rows.any(
      (r) => r.length > index && normalizeMemberId(r[index]).isNotEmpty,
    )) {
      if (allowEmpty &&
          rows.every((r) => r.every((cell) => cell.trim().isEmpty))) {
        return;
      }
      throw const FormatException(
        'عمود رقم المستخدم لا يحتوي بيانات. اختر العمود الصحيح.',
      );
    }
  }

  Future<void> _save() async {
    try {
      if (management == null || agent == null) {
        throw const FormatException('اختر مصدري الإدارة والوكيل.');
      }
      _validate(management!, managementColumn.text, managementHeader);
      _validate(agent!, agentColumn.text, agentHeader, allowEmpty: true);
      if (name.text.trim().isEmpty) {
        throw const FormatException('أدخل اسم الدورة.');
      }
      setState(() {
        busy = true;
        error = null;
      });
      final existing = widget.cycle;
      await widget.db.saveCycle(
        CycleRecord(
          id: existing?.id ?? const Uuid().v4(),
          name: name.text.trim(),
          createdAt: existing?.createdAt ?? DateTime.now(),
          managementSource: management!.metadata,
          agentSource: agent!.metadata,
          columnMappings: {
            ...?existing?.columnMappings,
            'managementId': managementColumn.text.trim().toUpperCase(),
            'agentId': agentColumn.text.trim().toUpperCase(),
            'managementHeader': managementHeader,
            'agentHeader': agentHeader,
            'managementVerifiedTextColumn':
                managementColumn.text.trim().toUpperCase(),
            'agentVerifiedTextColumn': agentColumn.text.trim().toUpperCase(),
            'managementVerifiedTextHeader': managementHeader,
            'agentVerifiedTextHeader': agentHeader,
          },
          snapshots: {
            ...?existing?.snapshots,
            'management': management!.sheet.rows,
            'agent': agent!.sheet.rows,
          },
        ),
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = _error(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Widget _source(bool isManagement) {
    final source = isManagement ? management : agent;
    final title = isManagement ? 'بيانات الإدارة' : 'بيانات الوكيل';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(source?.label ?? 'لم يتم اختيار مصدر بعد'),
        Wrap(
          spacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: busy ? null : () => _file(isManagement),
              icon: const Icon(Icons.upload_file),
              label: const Text('ملف من الجهاز'),
            ),
            OutlinedButton.icon(
              onPressed: busy ? null : () => _google(isManagement),
              icon: const Icon(Icons.cloud_outlined),
              label: const Text('Google Sheets'),
            ),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _mapping(bool isManagement) {
    final source = (isManagement ? management : agent)!;
    final controller = isManagement ? managementColumn : agentColumn;
    final header = isManagement ? managementHeader : agentHeader;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          isManagement ? 'الإدارة' : 'الوكيل',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        Text('${source.label} · ${source.sheet.rows.length} صف'),
        const SizedBox(height: 12),
        DropdownButtonFormField<bool>(
          // ignore: deprecated_member_use
          value: header,
          decoration: const InputDecoration(labelText: 'هل الصف الأول عناوين؟'),
          items: const [
            DropdownMenuItem(
              value: true,
              child: Text('نعم، الصف الأول عناوين'),
            ),
            DropdownMenuItem(
              value: false,
              child: Text('لا، البيانات تبدأ من الصف الأول'),
            ),
          ],
          onChanged:
              (value) => setState(() {
                if (isManagement) {
                  managementHeader = value;
                } else {
                  agentHeader = value;
                }
              }),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: controller,
          textCapitalization: TextCapitalization.characters,
          textDirection: TextDirection.ltr,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(
            labelText: 'عمود رقم المستخدم',
            helperText: 'مثال: A أو AA',
          ),
        ),
        const SizedBox(height: 12),
        const Text('معاينة عمود رقم المستخدم:'),
        for (final value in _previewColumn(source, controller.text))
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(value, maxLines: 2, overflow: TextOverflow.ellipsis),
          ),
        const SizedBox(height: 24),
      ],
    );
  }

  List<String> _previewColumn(_Source source, String column) {
    try {
      final index = columnIndex(column);
      return source.sheet.rows
          .take(3)
          .map(
            (row) =>
                row.length > index && row[index].isNotEmpty ? row[index] : '—',
          )
          .toList();
    } on FormatException {
      return const [];
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.cycle == null ? 'إنشاء دورة' : 'تعديل الدورة'),
    ),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            'الخطوة ${step + 1} من 3',
            style: Theme.of(context).textTheme.labelLarge,
          ),
          const SizedBox(height: 16),
          if (step == 0) ...[_source(true), _source(false)],
          if (step == 1 && management != null && agent != null) ...[
            const Text(
              'تأكد من العناوين وأعمدة أرقام المستخدمين. يجب حفظ المعرفات كنص في ملفات Excel وGoogle.',
            ),
            const SizedBox(height: 16),
            _mapping(true),
            _mapping(false),
          ],
          if (step == 2)
            TextField(
              controller: name,
              decoration: const InputDecoration(labelText: 'اسم الدورة'),
            ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text(
                error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (busy)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Center(child: CircularProgressIndicator()),
            ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed:
                busy
                    ? null
                    : () {
                      if (step == 0) {
                        if (management == null || agent == null) {
                          setState(
                            () => error = 'اختر مصدري الإدارة والوكيل أولًا.',
                          );
                          return;
                        }
                        setState(() {
                          step++;
                          error = null;
                        });
                      } else if (step == 1) {
                        try {
                          _validate(
                            management!,
                            managementColumn.text,
                            managementHeader,
                          );
                          _validate(
                            agent!,
                            agentColumn.text,
                            agentHeader,
                            allowEmpty: true,
                          );
                          setState(() {
                            step++;
                            error = null;
                          });
                        } catch (e) {
                          setState(() => error = _error(e));
                        }
                      } else {
                        _save();
                      }
                    },
            child: Text(step == 2 ? 'حفظ الدورة' : 'التالي'),
          ),
          if (step > 0)
            TextButton(
              onPressed:
                  busy
                      ? null
                      : () => setState(() {
                        step--;
                        error = null;
                      }),
              child: const Text('السابق'),
            ),
        ],
      ),
    ),
  );
}
