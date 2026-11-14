// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';
import 'package:lork_audit_standalone/ui/audit_flow.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  setUpAll(sqfliteFfiInit);

  testWidgets('saved snapshots can be previewed without Google writes', (
    tester,
  ) async {
    // Keep this persistence-focused test independent of lazy viewport building.
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final database =
        (await tester.runAsync(
          () => LocalAuditDatabase.open(
            databasePath: inMemoryDatabasePath,
            factory: databaseFactoryFfi,
          ),
        ))!;
    addTearDown(database.close);
    final source = <String, Object?>{
      'kind': 'file',
      'label': 'ملف',
      'nonTextCells': <Object?>[],
    };
    final cycle = CycleRecord(
      id: 'cycle-1',
      name: 'دورة اختبار',
      createdAt: DateTime.utc(2024),
      managementSource: source,
      agentSource: source,
      columnMappings: {
        'managementId': 'A',
        'agentId': 'A',
        'managementHeader': true,
        'agentHeader': true,
        'userInfoId': 'A',
        'userInfoTitle': 'B',
        'userInfoHeader': true,
      },
      snapshots: {
        'management': [
          ['المعرف'],
          ['001'],
        ],
        'agent': [
          ['المعرف'],
          ['001'],
        ],
        'userInfo': [
          ['المعرف', 'الورقة'],
          ['001', 'الهدف'],
        ],
        'userInfoSource': {
          'kind': 'google',
          'label': 'معلومات',
          'nonTextCells': <Object?>[],
        },
      },
    );
    await tester.runAsync(() => database.saveCycle(cycle));
    await tester.pumpWidget(
      MaterialApp(
        home: AuditFlowPage(
          db: database,
          session: GoogleSession(),
          cycle: cycle,
        ),
      ),
    );
    await tester.scrollUntilVisible(
      find.text('عرض المعاينة'),
      250,
      scrollable:
          find
              .descendant(
                of: find.byType(ListView),
                matching: find.byType(Scrollable),
              )
              .first,
    );
    await tester.ensureVisible(find.text('عرض المعاينة'));
    await tester.tap(find.text('عرض المعاينة'));
    // SQLite FFI completes on a real isolate; let its futures progress outside
    // the widget test's fake clock, while pumping the resulting UI updates.
    for (
      var attempt = 0;
      attempt < 100 && find.text('النتائج: 1').evaluate().isEmpty;
      attempt++
    ) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 20)),
      );
      await tester.pump();
    }
    expect(find.text('النتائج: 1'), findsOneWidget);
    expect(find.text('سحب وكيل'), findsOneWidget);
    expect(
      find.textContaining('لم تُكتب أي بيانات على Google'),
      findsOneWidget,
    );
    expect(
      await tester.runAsync(() => database.listOperations(cycle.id)),
      isEmpty,
    );
  });
}
