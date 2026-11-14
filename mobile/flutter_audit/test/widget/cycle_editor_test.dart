// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/data/local_audit_database.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';
import 'package:lork_audit_standalone/ui/cycle_editor.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();

  testWidgets(
    'editing a cycle persists its name and preserves audit settings',
    (tester) async {
      // This test checks persistence; use a viewport that keeps the wizard's
      // controls built while each step changes.
      tester.view.physicalSize = const Size(1000, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final db =
          (await tester.runAsync(
            () => LocalAuditDatabase.open(
              factory: databaseFactoryFfi,
              databasePath: inMemoryDatabasePath,
            ),
          ))!;
      addTearDown(db.close);
      final source = <String, Object?>{
        'kind': 'file',
        'label': 'ملف',
        'nonTextCells': <List<int>>[],
      };
      final cycle = CycleRecord(
        id: 'cycle-1',
        name: 'الدورة الأولى',
        createdAt: DateTime.utc(2025),
        managementSource: source,
        agentSource: source,
        columnMappings: const {
          'managementId': 'A',
          'agentId': 'A',
          'managementHeader': true,
          'agentHeader': true,
          'userInfoId': 'C',
        },
        snapshots: const {
          'management': [
            ['الرقم'],
            ['001'],
          ],
          'agent': [
            ['الرقم'],
            ['001'],
          ],
          'userInfo': [
            ['رقم'],
            ['001'],
          ],
        },
      );
      await tester.runAsync(() => db.saveCycle(cycle));
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('ar'),
          supportedLocales: const [Locale('ar')],
          localizationsDelegates: const [
            // ignore: deprecated_member_use
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            // ignore: deprecated_member_use
            GlobalCupertinoLocalizations.delegate,
          ],
          home: CycleEditor(db: db, session: GoogleSession(), cycle: cycle),
        ),
      );
      await tester.scrollUntilVisible(
        find.text('التالي'),
        250,
        scrollable:
            find
                .descendant(
                  of: find.byType(ListView),
                  matching: find.byType(Scrollable),
                )
                .first,
      );
      await tester.ensureVisible(find.text('التالي'));
      await tester.tap(find.text('التالي'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('التالي'),
        250,
        scrollable:
            find
                .descendant(
                  of: find.byType(ListView),
                  matching: find.byType(Scrollable),
                )
                .first,
      );
      await tester.ensureVisible(find.text('التالي'));
      await tester.tap(find.text('التالي'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byType(TextField).last);
      await tester.enterText(find.byType(TextField).last, 'دورة معدلة');
      await tester.ensureVisible(find.text('حفظ الدورة'));
      await tester.tap(find.text('حفظ الدورة'));
      CycleRecord? updated;
      for (var attempt = 0; attempt < 100; attempt++) {
        updated = await tester.runAsync<CycleRecord?>(() async {
          await Future<void>.delayed(const Duration(milliseconds: 20));
          return db.getCycle(cycle.id);
        });
        await tester.pump();
        if (updated?.name == 'دورة معدلة') break;
      }
      expect(updated?.name, 'دورة معدلة');
      expect(updated?.columnMappings['userInfoId'], 'C');
      expect(updated?.snapshots['userInfo'], [
        ['رقم'],
        ['001'],
      ]);
      expect(updated?.columnMappings['managementVerifiedTextColumn'], 'A');
    },
  );
}
