// Flutter's newer material_ui package is not available on older supported SDKs.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/local_audit_database.dart';
import '../domain/audit_engine.dart';
import '../google/google_sheets_gateway.dart';
import 'audit_flow.dart';
import 'cycle_editor.dart';

class AuditHome extends StatefulWidget {
  const AuditHome({super.key});
  @override
  State<AuditHome> createState() => _AuditHomeState();
}

class _AuditHomeState extends State<AuditHome> {
  LocalAuditDatabase? db;
  final session = GoogleSession();
  List<CycleRecord> cycles = [];
  String? currentId;
  int page = 0;
  String? loadError;

  @override
  void initState() {
    super.initState();
    _open();
  }

  Future<void> _open() async {
    try {
      final database = await LocalAuditDatabase.open();
      if (!mounted) {
        await database.close();
        return;
      }
      db = database;
      await _refresh();
      try {
        await session.restore();
      } catch (_) {
        /* A manual connection can be retried. */
      }
      if (mounted) setState(() {});
    } catch (_) {
      if (mounted) {
        setState(() => loadError = 'تعذر فتح البيانات المحفوظة على الجهاز.');
      }
    }
  }

  Future<void> _refresh() async {
    final data = await db!.listCycles();
    if (!mounted) return;
    setState(() {
      cycles = data;
      if (currentId != null && !cycles.any((c) => c.id == currentId)) {
        currentId = null;
      }
      currentId ??= cycles.isEmpty ? null : cycles.first.id;
    });
  }

  Future<void> _edit([CycleRecord? cycle]) async {
    if (cycle?.auditClosed == true) {
      _message('أعد فتح الدورة قبل تعديلها.');
      return;
    }
    if (cycle != null) {
      final operations = await db!.listOperations(cycle.id);
      if (operations.any(
        (operation) => operation.status != OperationStatus.verified,
      )) {
        _message('لا يمكن تعديل الدورة أثناء وجود عمليات غير مكتملة.');
        return;
      }
    }
    if (!mounted) return;
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => CycleEditor(db: db!, session: session, cycle: cycle),
      ),
    );
    if (changed == true) await _refresh();
  }

  void _message(String message) {
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _connection() async {
    try {
      if (session.accountId != null) {
        final disconnect = await showDialog<bool>(
          context: context,
          builder:
              (context) => AlertDialog(
                title: const Text('حساب Google'),
                content: Text(
                  'الحساب المتصل: ${session.email}\nيمكنك قطع الاتصال ثم ربط حساب آخر.',
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('إلغاء'),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('قطع الاتصال'),
                  ),
                ],
              ),
        );
        if (disconnect == true) await session.disconnect();
      } else {
        await session.connect();
      }
      if (mounted) setState(() {});
    } catch (_) {
      _message(
        'تعذر ربط حساب Google. تحقق من الاتصال وإعداد التطبيق ثم حاول مجددًا.',
      );
    }
  }

  Future<void> _openPreview(CycleRecord cycle) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => AuditFlowPage(db: db!, session: session, cycle: cycle),
      ),
    );
    if (mounted) await _refresh();
  }

  Future<void> _changeClosed(CycleRecord cycle) async {
    try {
      if (cycle.auditClosed) {
        await db!.reopenCycle(cycle.id);
      } else {
        await db!.closeCycle(cycle.id);
      }
      await _refresh();
      _message(cycle.auditClosed ? 'أُعيد فتح الدورة.' : 'أُغلقت الدورة.');
    } catch (_) {
      _message('تعذر تغيير حالة الدورة. تأكد من عدم وجود عمليات غير مكتملة.');
    }
  }

  Future<void> _delete(CycleRecord cycle) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder:
          (context) => AlertDialog(
            title: const Text('حذف الدورة من الجهاز؟'),
            content: const Text(
              'ستُحذف بيانات الدورة وسجلها من هذا الجهاز فقط. لن تُحذف ملفات Google.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('إلغاء'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('حذف'),
              ),
            ],
          ),
    );
    if (confirmed != true) return;
    await db!.deleteCycle(cycle.id);
    await _refresh();
  }

  @override
  void dispose() {
    db?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final database = db;
    if (database == null) {
      return Scaffold(
        body: Center(
          child:
              loadError == null
                  ? const CircularProgressIndicator()
                  : Text(loadError!),
        ),
      );
    }
    final selected = cycles.where((c) => c.id == currentId).firstOrNull;
    final views = <Widget>[
      _CyclesPage(
        cycles: cycles,
        db: database,
        onCreate: () => _edit(),
        onEdit: _edit,
        onDelete: _delete,
        onToggle: _changeClosed,
        onSelect:
            (c) => setState(() {
              currentId = c.id;
              page = 1;
            }),
      ),
      _AuditPage(
        cycles: cycles,
        selected: selected,
        session: session,
        onConnect: _connection,
        onSelect: (id) => setState(() => currentId = id),
        onPreview: _openPreview,
      ),
      _SearchPage(cycles: cycles, db: database),
    ];
    return Scaffold(
      appBar: AppBar(
        title: Text(['الدورات', 'التدقيق', 'البحث'][page]),
        actions: [
          IconButton(
            tooltip: 'حساب Google',
            onPressed: _connection,
            icon: Icon(
              session.accountId == null
                  ? Icons.account_circle_outlined
                  : Icons.account_circle,
            ),
          ),
        ],
      ),
      body: SafeArea(child: views[page]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: page,
        onDestinationSelected: (v) => setState(() => page = v),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.folder_outlined),
            selectedIcon: Icon(Icons.folder),
            label: 'الدورات',
          ),
          NavigationDestination(
            icon: Icon(Icons.fact_check_outlined),
            selectedIcon: Icon(Icons.fact_check),
            label: 'التدقيق',
          ),
          NavigationDestination(icon: Icon(Icons.search), label: 'البحث'),
        ],
      ),
    );
  }
}

class _CyclesPage extends StatelessWidget {
  const _CyclesPage({
    required this.cycles,
    required this.db,
    required this.onCreate,
    required this.onEdit,
    required this.onDelete,
    required this.onToggle,
    required this.onSelect,
  });
  final List<CycleRecord> cycles;
  final LocalAuditDatabase db;
  final VoidCallback onCreate;
  final ValueChanged<CycleRecord> onEdit, onDelete, onToggle, onSelect;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      FilledButton.icon(
        onPressed: onCreate,
        icon: const Icon(Icons.add),
        label: const Text('إنشاء دورة'),
      ),
      const SizedBox(height: 16),
      if (cycles.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 48),
          child: Center(child: Text('لا توجد دورات محفوظة بعد.')),
        ),
      for (final cycle in cycles)
        Card(
          child: ListTile(
            onTap: () => onSelect(cycle),
            title: Text(cycle.name),
            subtitle: FutureBuilder<List<OperationRecord>>(
              future: db.listOperations(cycle.id),
              builder: (context, snapshot) {
                final pending =
                    snapshot.data
                        ?.where((o) => o.status != OperationStatus.verified)
                        .length;
                final date =
                    cycle.createdAt
                        .toLocal()
                        .toIso8601String()
                        .split('T')
                        .first;
                return Text(
                  '$date · ${cycle.auditClosed ? 'مغلقة' : 'مفتوحة'}${pending == null ? '' : ' · عمليات غير مكتملة: $pending'}',
                );
              },
            ),
            trailing: PopupMenuButton<String>(
              onSelected: (choice) {
                switch (choice) {
                  case 'edit':
                    onEdit(cycle);
                  case 'toggle':
                    onToggle(cycle);
                  case 'delete':
                    onDelete(cycle);
                }
              },
              itemBuilder:
                  (_) => [
                    const PopupMenuItem(
                      value: 'edit',
                      child: Text('تعديل الدورة'),
                    ),
                    PopupMenuItem(
                      value: 'toggle',
                      child: Text(
                        cycle.auditClosed ? 'إعادة الفتح' : 'إغلاق الدورة',
                      ),
                    ),
                    const PopupMenuItem(
                      value: 'delete',
                      child: Text('حذف من الجهاز'),
                    ),
                  ],
            ),
          ),
        ),
    ],
  );
}

class _AuditPage extends StatelessWidget {
  const _AuditPage({
    required this.cycles,
    required this.selected,
    required this.session,
    required this.onConnect,
    required this.onSelect,
    required this.onPreview,
  });
  final List<CycleRecord> cycles;
  final CycleRecord? selected;
  final GoogleSession session;
  final VoidCallback onConnect;
  final ValueChanged<String?> onSelect;
  final ValueChanged<CycleRecord> onPreview;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      if (cycles.isEmpty)
        const Text('أنشئ دورة أولًا من قسم الدورات.')
      else ...[
        DropdownButtonFormField<String>(
          // Keep compatibility with Flutter releases before initialValue.
          // ignore: deprecated_member_use
          value: selected?.id,
          decoration: const InputDecoration(labelText: 'الدورة'),
          items:
              cycles
                  .map(
                    (c) => DropdownMenuItem(value: c.id, child: Text(c.name)),
                  )
                  .toList(),
          onChanged: onSelect,
        ),
        const SizedBox(height: 24),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(session.email ?? 'حساب Google غير متصل'),
          subtitle: Text(
            selected!.auditClosed ? 'الدورة مغلقة' : 'الدورة مفتوحة',
          ),
          trailing: TextButton(
            onPressed: onConnect,
            child: Text(session.accountId == null ? 'ربط' : 'الحساب'),
          ),
        ),
        const SizedBox(height: 16),
        const Text(
          'يمكنك إعداد التدقيق ومعاينة النتائج محليًا. تنفيذ التدقيق والمزامنة غير متاحين بعد.',
        ),
        const SizedBox(height: 20),
        FilledButton(
          onPressed: () => onPreview(selected!),
          child: const Text('إعداد التدقيق وعرض المعاينة'),
        ),
      ],
    ],
  );
}

List<List<String>> _rows(Object? value) {
  if (value is! List) return [];
  return value
      .whereType<List>()
      .map((row) => row.map((v) => v?.toString() ?? '').toList())
      .toList();
}

String _classification(AuditClassification value) => switch (value) {
  AuditClassification.agent => 'سحب وكيل',
  AuditClassification.agentMultiple => 'سحب وكيل راتبين',
  AuditClassification.management => 'سحب إدارة',
  AuditClassification.notFound => 'غير موجود',
};

class _SearchResult {
  const _SearchResult(this.cycle, this.member, this.operations);
  final CycleRecord cycle;
  final MemberPreview member;
  final List<OperationRecord> operations;
}

class _SearchPage extends StatefulWidget {
  const _SearchPage({required this.cycles, required this.db});
  final List<CycleRecord> cycles;
  final LocalAuditDatabase db;
  @override
  State<_SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<_SearchPage> {
  final controller = TextEditingController();
  String? cycleId;
  List<_SearchResult>? results;
  String? error;
  bool busy = false;
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> search() async {
    final id = normalizeMemberId(controller.text);
    if (id.isEmpty) {
      setState(() => error = 'أدخل رقم المستخدم أولًا.');
      return;
    }
    setState(() {
      busy = true;
      error = null;
      results = null;
    });
    final found = <_SearchResult>[];
    try {
      for (final cycle in widget.cycles.where(
        (c) => cycleId == null || c.id == cycleId,
      )) {
        final management = _rows(cycle.snapshots['management']);
        final agent = _rows(cycle.snapshots['agent']);
        if (management.isEmpty) continue;
        final result = const AuditEngine().preview(
          AuditInput(
            management: AuditTable(
              rows: management,
              idColumn: cycle.columnMappings['managementId'] as String? ?? 'A',
              hasHeader:
                  cycle.columnMappings['managementHeader'] as bool? ?? true,
              identifiersAreTextVerified:
                  cycle.columnMappings['managementVerifiedTextColumn'] !=
                      null &&
                  cycle.columnMappings['managementVerifiedTextColumn'] ==
                      cycle.columnMappings['managementId'] &&
                  cycle.columnMappings['managementVerifiedTextHeader'] ==
                      cycle.columnMappings['managementHeader'],
            ),
            agent: AuditTable(
              rows: agent,
              idColumn: cycle.columnMappings['agentId'] as String? ?? 'A',
              hasHeader: cycle.columnMappings['agentHeader'] as bool? ?? true,
              identifiersAreTextVerified:
                  cycle.columnMappings['agentVerifiedTextColumn'] != null &&
                  cycle.columnMappings['agentVerifiedTextColumn'] ==
                      cycle.columnMappings['agentId'] &&
                  cycle.columnMappings['agentVerifiedTextHeader'] ==
                      cycle.columnMappings['agentHeader'],
            ),
            userInfo: AuditTable(
              rows: [
                [id, 'بحث محلي'],
              ],
              idColumn: 'A',
              hasHeader: false,
              identifiersAreTextVerified: true,
            ),
            titleColumn: 'B',
            targetSpreadsheetId: '',
            agentColor: '#6252bd',
            managementColor: '#278164',
          ),
        );
        final member = result.members.single;
        if (member.managementRow != null ||
            member.agentRows.isNotEmpty ||
            member.conflicts.any(
              (c) => c.code == AuditConflictCode.duplicateManagement,
            )) {
          final ops =
              (await widget.db.listOperations(
                cycle.id,
              )).where((o) => o.memberId == id).toList();
          found.add(_SearchResult(cycle, member, ops));
        }
      }
      if (mounted) {
        setState(() {
          results = found;
          busy = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          error = 'تعذر البحث في البيانات المحفوظة. تحقق من إعدادات الدورة.';
          busy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      DropdownButtonFormField<String>(
        // ignore: deprecated_member_use
        value: widget.cycles.any((c) => c.id == cycleId) ? cycleId : null,
        decoration: const InputDecoration(labelText: 'نطاق البحث'),
        items: [
          const DropdownMenuItem<String>(
            value: null,
            child: Text('كل الدورات'),
          ),
          ...widget.cycles.map(
            (c) => DropdownMenuItem(value: c.id, child: Text(c.name)),
          ),
        ],
        onChanged: (v) => setState(() => cycleId = v),
      ),
      const SizedBox(height: 16),
      TextField(
        controller: controller,
        textDirection: TextDirection.ltr,
        decoration: InputDecoration(
          labelText: 'رقم المستخدم',
          suffixIcon: IconButton(
            tooltip: 'لصق',
            icon: const Icon(Icons.content_paste),
            onPressed: () async {
              final data = await Clipboard.getData(Clipboard.kTextPlain);
              if (data?.text != null) controller.text = data!.text!;
            },
          ),
        ),
        onSubmitted: (_) => search(),
      ),
      const SizedBox(height: 16),
      FilledButton.icon(
        onPressed: busy ? null : search,
        icon: const Icon(Icons.search),
        label: const Text('بحث'),
      ),
      if (busy)
        const Padding(
          padding: EdgeInsets.all(20),
          child: Center(child: CircularProgressIndicator()),
        ),
      if (error != null)
        Padding(padding: const EdgeInsets.only(top: 16), child: Text(error!)),
      if (results != null && results!.isEmpty)
        const Padding(
          padding: EdgeInsets.only(top: 24),
          child: Text('لم يُعثر على المستخدم في الدورات المحددة.'),
        ),
      if (results != null)
        for (final result in results!)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    result.cycle.name,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text(_classification(result.member.classification)),
                  Text(
                    'الإدارة: ${result.member.managementRow != null ? 'موجود' : 'غير مؤكد أو غير موجود'} · الوكيل: ${result.member.agentRows.length} صف',
                  ),
                  if (result.member.diagnostic != null)
                    Text(result.member.diagnostic!),
                  for (final conflict in result.member.conflicts.where(
                    (c) => c.code == AuditConflictCode.duplicateManagement,
                  ))
                    Text(conflict.message),
                  Text(
                    result.operations.isEmpty
                        ? 'لا توجد عملية تدقيق مسجلة لهذا المستخدم.'
                        : 'العمليات المسجلة: ${result.operations.length}؛ غير المكتملة: ${result.operations.where((o) => o.status != OperationStatus.verified).length}',
                  ),
                  const Text('التدقيق الفردي والمزامنة غير متاحين بعد.'),
                ],
              ),
            ),
          ),
    ],
  );
}
