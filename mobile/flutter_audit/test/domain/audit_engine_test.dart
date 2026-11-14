import 'package:flutter_test/flutter_test.dart';

import 'package:lork_audit_standalone/domain/audit_engine.dart';

AuditTable table(
  List<List<String>> rows, {
  String id = 'A',
  bool header = false,
  String? spreadsheet,
  String? title,
}) => AuditTable(
  rows: rows,
  idColumn: id,
  hasHeader: header,
  spreadsheetId: spreadsheet,
  sheetTitle: title,
  identifiersAreTextVerified: true,
);

AuditInput input({
  List<List<String>> management = const [
    ['001', 'original'],
  ],
  List<List<String>> agent = const [
    ['001'],
  ],
  List<List<String>> info = const [
    ['001', 'Agency'],
  ],
  String mgmtColumn = 'A',
  String agentColumn = 'A',
  String infoColumn = 'A',
  String titleColumn = 'B',
  String? statusColumn,
  bool statusVerified = false,
  bool header = false,
  String destination = 'destination',
  String? mgmtSpreadsheet,
  String? mgmtTitle,
}) => AuditInput(
  management: table(
    management,
    id: mgmtColumn,
    header: header,
    spreadsheet: mgmtSpreadsheet,
    title: mgmtTitle,
  ),
  agent: table(agent, id: agentColumn, header: header),
  userInfo: table(info, id: infoColumn, header: header),
  titleColumn: titleColumn,
  targetSpreadsheetId: destination,
  agentColor: '#112233',
  managementColor: '#445566',
  statusColumn: statusColumn,
  statusColumnIsVerifiedNonFinancial: statusVerified,
);

void main() {
  const engine = AuditEngine();
  test('identifiers are exact normalized text and preserve leading zeros and precision', () {
    expect(normalizeMemberId(' \u200f۰۰١٢۳٤۵۶۷۸۹\u200b '), '00123456789');
    expect(
      normalizeMemberId('000123456789012345678901234567890'),
      '000123456789012345678901234567890',
    );
    expect(normalizeMemberId('1 2'), '1 2');
    expect(normalizeMemberId('12abc'), '12abc');
    expect(normalizeMemberId('12.9'), '12.9');
    final preview = engine.preview(
      input(
        management: [
          ['001'],
        ],
        agent: [
          ['001'],
        ],
        info: [
          ['01', 'Agency'],
          ['001', 'Agency'],
          ['0', 'Agency'],
        ],
      ),
    );
    expect(preview.members.map((m) => m.classification), [
      AuditClassification.notFound,
      AuditClassification.agent,
      AuditClassification.notFound,
    ]);
  });

  test('spreadsheet columns parse beyond Z and reject invalid mappings', () {
    expect(columnIndex('A'), 0);
    expect(columnIndex('Z'), 25);
    expect(columnIndex('AA'), 26);
    expect(columnIndex('ab'), 27);
    expect(columnIndex('AAA'), 702);
    for (final invalid in ['', 'A1', '-A', 'A B', 'AAAAAAAA']) {
      expect(() => columnIndex(invalid), throwsFormatException);
    }
    final row = List.filled(28, '');
    row[26] = '001';
    row[27] = 'Destination';
    final p = engine.preview(
      input(
        management: [row],
        agent: [row],
        info: [row],
        mgmtColumn: 'AA',
        agentColumn: 'AA',
        infoColumn: 'AA',
        titleColumn: 'AB',
      ),
    );
    expect(p.members.single.classification, AuditClassification.agent);
    expect(p.members.single.targetTitle, 'Destination');
  });

  test(
    'classifies all presence combinations, preserving every agent match',
    () {
      final p = engine.preview(
        input(
          management: [
            ['1'],
            ['2'],
            ['3'],
          ],
          agent: [
            ['1'],
            ['2'],
            ['2'],
            ['4'],
          ],
          info: [
            ['1', 'S'],
            ['2', 'S'],
            ['3', 'S'],
            ['4', 'S'],
            ['5', 'S'],
          ],
        ),
      );
      expect(p.members.map((m) => m.classification), [
        AuditClassification.agent,
        AuditClassification.agentMultiple,
        AuditClassification.management,
        AuditClassification.notFound,
        AuditClassification.notFound,
      ]);
      expect(p.members[1].agentRows.length, 2);
      expect(p.members[3].diagnostic, contains('الوكيل فقط'));
      expect(p.members[4].diagnostic, contains('غير موجود'));
      expect(p.members[3].canAudit, isFalse);
    },
  );

  test('header is explicit, and first data row is retained when no header', () {
    expect(engine.preview(input()).members.single.managementRow!.rowNumber, 1);
    final p = engine.preview(
      input(
        header: true,
        management: [
          ['id'],
          ['001'],
        ],
        agent: [
          ['id'],
          ['001'],
        ],
        info: [
          ['id', 'title'],
          ['001', 'Agency'],
        ],
      ),
    );
    expect(p.members.single.userInfoRow.rowNumber, 2);
    expect(p.members.single.managementRow!.rowNumber, 2);
  });

  test(
    'duplicate management and user-info IDs block only affected members',
    () {
      final p = engine.preview(
        input(
          management: [
            ['1'],
            ['1'],
            ['2'],
            ['3'],
          ],
          agent: [
            ['1'],
            ['2'],
            ['3'],
          ],
          info: [
            ['1', 'S'],
            ['2', 'S'],
            ['2', 'S'],
            ['3', 'S'],
          ],
        ),
      );
      expect(p.members[0].managementRow, isNull);
      expect(
        p.members[0].conflicts.map((c) => c.code),
        contains(AuditConflictCode.duplicateManagement),
      );
      expect(
        p.members[1].conflicts.map((c) => c.code),
        contains(AuditConflictCode.duplicateUserInfo),
      );
      expect(p.members[2].canAudit, isFalse);
      expect(p.members[3].canAudit, isTrue);
    },
  );

  test(
    'invalid, empty, conflicting and source target names cannot be written',
    () {
      final p = engine.preview(
        input(
          management: [
            ['1'],
            ['2'],
            ['3'],
            ['4'],
            ['5'],
            ['6'],
          ],
          agent: [
            ['1'],
            ['2'],
            ['3'],
            ['4'],
            ['5'],
            ['6'],
          ],
          info: [
            ['1', ''],
            ['2', 'bad/name'],
            ['3', 'Team'],
            ['4', 'team'],
            ['5', 'Source'],
            ['6', List.filled(101, 'x').join()],
          ],
          destination: 's',
          mgmtSpreadsheet: 's',
          mgmtTitle: 'source',
        ),
      );
      expect(p.members.map((m) => m.canAudit), everyElement(isFalse));
      expect(
        p.members[0].conflicts.map((c) => c.code),
        contains(AuditConflictCode.missingTarget),
      );
      expect(
        p.members[2].conflicts.map((c) => c.code),
        contains(AuditConflictCode.targetNameCollision),
      );
      expect(
        p.members[4].conflicts.map((c) => c.code),
        contains(AuditConflictCode.targetIsSource),
      );
    },
  );

  test('missing destination and unverified or overlapping status mapping block plans', () {
    final noDestination = engine.preview(input(destination: ''));
    expect(noDestination.canExecute, isFalse);
    expect(noDestination.members.single.canAudit, isFalse);
    final unverified = engine.preview(input(statusColumn: 'C'));
    expect(unverified.members.single.canAudit, isFalse);
    final overlap = engine.preview(
      input(statusColumn: 'A', statusVerified: true),
    );
    expect(overlap.members.single.canAudit, isFalse);
    final formula = engine.preview(
      input(
        info: [
          ['001', 'Agency', '=SUM(1,2)'],
        ],
        statusColumn: 'C',
        statusVerified: true,
      ),
    );
    expect(formula.members.single.canAudit, isFalse);
    final safe = engine.preview(input(statusColumn: 'C', statusVerified: true));
    expect(safe.members.single.canAudit, isTrue);
  });

  test(
    'fingerprint covers rows and configuration but tolerates row movement',
    () {
      final original = engine.preview(input()).members.single;
      final changedSource = engine
          .preview(
            input(
              management: [
                ['001', 'changed'],
              ],
            ),
          )
          .members
          .single;
      expect(changedSource.fingerprint, isNot(original.fingerprint));
      final changedAgent = engine
          .preview(
            input(
              agent: [
                ['001', 'changed'],
              ],
            ),
          )
          .members
          .single;
      expect(changedAgent.fingerprint, isNot(original.fingerprint));
      final duplicateInfo = engine.preview(
        input(
          info: [
            ['001', 'Agency'],
            ['001', 'Agency'],
          ],
        ),
      );
      expect(
        duplicateInfo.members.first.fingerprint,
        isNot(original.fingerprint),
      );
      expect(duplicateInfo.members.first.canAudit, isFalse);
      final changedInfo = engine
          .preview(
            input(
              info: [
                ['001', 'Other'],
              ],
            ),
          )
          .members
          .single;
      expect(changedInfo.fingerprint, isNot(original.fingerprint));
      final changedDestination = engine
          .preview(input(destination: 'other'))
          .members
          .single;
      expect(changedDestination.fingerprint, isNot(original.fingerprint));
      final moved = engine
          .preview(
            input(
              management: [
                ['unrelated'],
                ['001', 'original'],
              ],
            ),
          )
          .members
          .single;
      expect(moved.managementRow!.rowNumber, 2);
      expect(moved.fingerprint, original.fingerprint);
    },
  );

  test('raw management cells are returned unchanged and never calculated', () {
    final p = engine.preview(
      input(
        management: [
          ['001', '=SUM(C2:D2)', '0009.50'],
        ],
      ),
    );
    expect(p.members.single.managementRow!.values, [
      '001',
      '=SUM(C2:D2)',
      '0009.50',
    ]);
    expect(p.members.single.canAudit, isTrue);
  });

  test('unverified numeric or formula identifiers can be previewed but not executed', () {
    final unverified = AuditTable(
      rows: const [
        ['001'],
      ],
      idColumn: 'A',
      hasHeader: false,
    );
    final verified = table(const [
      ['001'],
    ]);
    final p = engine.preview(
      AuditInput(
        management: unverified,
        agent: verified,
        userInfo: table(const [
          ['001', 'S'],
        ]),
        titleColumn: 'B',
        targetSpreadsheetId: 'target',
        agentColor: '#000000',
        managementColor: '#ffffff',
      ),
    );
    expect(p.members.single.classification, AuditClassification.agent);
    expect(p.members.single.canAudit, isFalse);
    expect(
      p.conflicts.map((c) => c.code),
      contains(AuditConflictCode.unverifiedIdentifierSource),
    );
  });

  test('missing columns and blank IDs never produce eligible operations', () {
    final p = engine.preview(
      input(
        info: [
          ['', 'S'],
        ],
        management: [
          ['001'],
        ],
        agent: [
          ['001'],
        ],
      ),
    );
    expect(p.members.single.canAudit, isFalse);
    expect(
      p.members.single.conflicts.map((c) => c.code),
      contains(AuditConflictCode.invalidMemberId),
    );
    final broken = engine.preview(input(mgmtColumn: 'AA'));
    expect(broken.canExecute, isFalse);
    expect(
      broken.conflicts.map((c) => c.code),
      contains(AuditConflictCode.missingColumn),
    );
  });
}
