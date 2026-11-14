import 'dart:convert';
import 'dart:typed_data';

import 'package:excel/excel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lork_audit_standalone/data/import_service.dart';

void main() {
  test('CSV preserves identifiers and quoted commas, quotes and line breaks', () {
    final input = '\ufeffid,name\r\n0012,"أحمد, علي"\r\n'
        '999999999999999999,"first\r\nsecond ""quoted"""\r\n';
    final sheet = ImportService.decodeCsv(Uint8List.fromList(utf8.encode(input)));
    expect(sheet.rows[1], ['0012', 'أحمد, علي']);
    expect(sheet.rows[2], ['999999999999999999', 'first\r\nsecond "quoted"']);
  });

  test('CSV handles UTF-16 BOM and mixed line endings outside quotes', () {
    final units = 'id,name\r\n0001,علي\n0002,نور'.codeUnits;
    final bytes = <int>[0xff, 0xfe];
    for (final unit in units) {
      bytes.add(unit & 255);
      bytes.add(unit >> 8);
    }
    final sheet = ImportService.decodeCsv(Uint8List.fromList(bytes));
    expect(sheet.rows, [ ['id', 'name'], ['0001', 'علي'], ['0002', 'نور'] ]);
  });

  test('CSV rejects malformed text and unsupported encoding', () {
    expect(() => ImportService.decodeCsv(Uint8List.fromList(utf8.encode('"unfinished'))), throwsFormatException);
    expect(() => ImportService.decodeCsv(Uint8List.fromList([0xff]), encoding: 'unknown'), throwsFormatException);
  });

  test('CSV accepts Windows-1256 only when selected', () {
    final bytes = Uint8List.fromList([48, 48, 49, 44, 0xda, 0xe1, 0xed]);
    expect(ImportService.decodeCsv(bytes, encoding: 'windows-1256').rows.single, ['001', 'علي']);
    expect(() => ImportService.decodeCsv(bytes), throwsFormatException);
  });

  test('CSV empty cells stay empty instead of becoming identifier text', () {
    final sheet = ImportService.decodeCsv(Uint8List.fromList(utf8.encode('id,name\n,Ali\n001,')));
    expect(sheet.rows[1], ['', 'Ali']);
    expect(sheet.rows[2], ['001', '']);
  });

  test('XLSX requires a named sheet and identifies unsafe numeric IDs', () {
    final workbook = Excel.createExcel();
    workbook.rename('Sheet1', 'الإدارة');
    workbook['الإدارة'].cell(CellIndex.indexByString('A1')).value = TextCellValue('معرّف');
    workbook['الإدارة'].cell(CellIndex.indexByString('A2')).value = TextCellValue('0012');
    workbook['الوكيل'].cell(CellIndex.indexByString('A1')).value = IntCellValue(12);
    final parsed = ImportService.readXlsx(Uint8List.fromList(workbook.encode()!));
    expect(parsed.sheetNames, containsAll(['الإدارة', 'الوكيل']));
    final management = parsed.selectSheet('الإدارة');
    expect(management.rows[1][0], '0012');
    management.validateIdentifierColumn(0, firstDataRow: 1);
    expect(() => parsed.selectSheet('الوكيل').validateIdentifierColumn(0), throwsFormatException);
    expect(() => parsed.selectSheet('missing'), throwsArgumentError);
  });
}
