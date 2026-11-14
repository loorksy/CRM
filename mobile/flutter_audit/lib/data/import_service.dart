import 'dart:convert';
import 'dart:typed_data';

import 'package:csv/csv.dart';
import 'package:excel/excel.dart';

/// Rows are strings. Non-text spreadsheet cells are tracked, because an
/// Excel numeric cell cannot prove the original spelling of an identifier.
class ImportedSheet {
  const ImportedSheet(this.name, this.rows, this.nonTextCells);
  final String name;
  final List<List<String>> rows;
  /// Zero-based `(row,column)` positions of nonempty, non-text cells.
  final Set<(int, int)> nonTextCells;

  void validateIdentifierColumn(int column, {int firstDataRow = 0}) {
    if (column < 0 || firstDataRow < 0) throw ArgumentError('Invalid column or header row.');
    if (nonTextCells.any((cell) => cell.$2 == column && cell.$1 >= firstDataRow)) {
      throw const FormatException('يجب أن تكون أرقام المستخدمين في Excel محفوظة كنص حتى لا تضيع الأصفار أو الدقة.');
    }
  }
}

class ImportedWorkbook {
  ImportedWorkbook._(this._excel);
  final Excel _excel;

  List<String> get sheetNames => List.unmodifiable(_excel.tables.keys);

  /// Select explicitly; never substitute a different sheet on failure.
  ImportedSheet selectSheet(String name) {
    final sheet = _excel.tables[name];
    if (sheet == null) throw ArgumentError('Worksheet not found: $name');
    final rows = <List<String>>[];
    final unsafe = <(int, int)>{};
    for (final row in sheet.rows) {
      final result = <String>[];
      for (final cell in row) {
        final value = cell?.value;
        if (value != null && value is! TextCellValue) {
          unsafe.add((rows.length, result.length));
        }
        result.add(value?.toString() ?? '');
      }
      rows.add(result);
    }
    return ImportedSheet(name, rows, unsafe);
  }
}

class ImportService {
  /// Supports UTF-8 and UTF-16 LE/BE with BOM, or explicit encoding names
  /// `utf-8`, `utf-16le`, `utf-16be`, and `windows-1256`.
  static ImportedSheet decodeCsv(Uint8List bytes, {String? encoding}) {
    var text = _decodeText(bytes, encoding);
    // Normalize line endings only outside quoted fields, preserving quoted data.
    final output = StringBuffer();
    var quoted = false;
    for (var i = 0; i < text.length; i++) {
      final c = text[i];
      if (c == '"') {
        output.write(c);
        if (quoted && i + 1 < text.length && text[i + 1] == '"') {
          output.write('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && c == '\r') {
        output.write('\n');
        if (i + 1 < text.length && text[i + 1] == '\n') i++;
      } else {
        output.write(c);
      }
    }
    if (quoted) throw const FormatException('اقتباس CSV غير مكتمل.');
    text = output.toString();
    final rows = const CsvToListConverter(
      eol: '\n', shouldParseNumbers: false, allowInvalid: false, convertEmptyTo: '',
    ).convert(text).map((row) => row.map((value) => value.toString()).toList()).toList();
    return ImportedSheet('CSV', rows, const {});
  }

  static ImportedWorkbook readXlsx(Uint8List bytes) =>
      ImportedWorkbook._(Excel.decodeBytes(bytes));

  static String _decodeText(Uint8List bytes, String? encoding) {
    var offset = 0;
    String? bom;
    if (bytes.length >= 3 && bytes[0] == 0xef && bytes[1] == 0xbb && bytes[2] == 0xbf) {
      bom = 'utf-8'; offset = 3;
    } else if (bytes.length >= 2 && bytes[0] == 0xff && bytes[1] == 0xfe) {
      bom = 'utf-16le'; offset = 2;
    } else if (bytes.length >= 2 && bytes[0] == 0xfe && bytes[1] == 0xff) {
      bom = 'utf-16be'; offset = 2;
    }
    final chosen = encoding?.toLowerCase() ?? bom ?? 'utf-8';
    if (bom != null && bom != chosen) throw const FormatException('ترميز الملف لا يطابق العلامة الموجودة فيه.');
    if (chosen == 'utf-8') return utf8.decode(bytes.sublist(offset), allowMalformed: false);
    if (chosen == 'windows-1256') {
      return String.fromCharCodes(bytes.map((byte) => byte < 128 ? byte : _windows1256[byte - 128]));
    }
    if (chosen != 'utf-16le' && chosen != 'utf-16be') {
      throw const FormatException('ترميز الملف غير مدعوم. اختر UTF-8 أو UTF-16 أو Windows-1256.');
    }
    if ((bytes.length - offset).isOdd) throw const FormatException('ملف UTF-16 غير مكتمل.');
    final units = <int>[];
    for (var i = offset; i < bytes.length; i += 2) {
      units.add(chosen == 'utf-16le' ? bytes[i] | bytes[i + 1] << 8 : bytes[i] << 8 | bytes[i + 1]);
    }
    for (var i = 0; i < units.length; i++) {
      final u = units[i];
      if (u >= 0xd800 && u <= 0xdbff) {
        if (++i >= units.length || units[i] < 0xdc00 || units[i] > 0xdfff) {
          throw const FormatException('ملف UTF-16 غير صالح.');
        }
      } else if (u >= 0xdc00 && u <= 0xdfff) {
        throw const FormatException('ملف UTF-16 غير صالح.');
      }
    }
    return String.fromCharCodes(units);
  }
}

// Windows-1256's single-byte mapping; used only when the user selects it.
const _windows1256 = <int>[
  0x20ac, 0x067e, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0679, 0x2039, 0x0152, 0x0686, 0x0698, 0x0688,
  0x06af, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x06a9, 0x2122, 0x0691, 0x203a, 0x0153, 0x200c, 0x200d, 0x06ba,
  0x00a0, 0x060c, 0x00a2, 0x00a3, 0x00a4, 0x00a5, 0x00a6, 0x00a7, 0x00a8, 0x00a9, 0x06be, 0x00ab, 0x00ac, 0x00ad, 0x00ae, 0x00af,
  0x00b0, 0x00b1, 0x00b2, 0x00b3, 0x00b4, 0x00b5, 0x00b6, 0x00b7, 0x00b8, 0x00b9, 0x061b, 0x00bb, 0x00bc, 0x00bd, 0x00be, 0x061f,
  0x06c1, 0x0621, 0x0622, 0x0623, 0x0624, 0x0625, 0x0626, 0x0627, 0x0628, 0x0629, 0x062a, 0x062b, 0x062c, 0x062d, 0x062e, 0x062f,
  0x0630, 0x0631, 0x0632, 0x0633, 0x0634, 0x0635, 0x0636, 0x00d7, 0x0637, 0x0638, 0x0639, 0x063a, 0x0640, 0x0641, 0x0642, 0x0643,
  0x00e0, 0x0644, 0x00e2, 0x0645, 0x0646, 0x0647, 0x0648, 0x00e7, 0x00e8, 0x00e9, 0x00ea, 0x00eb, 0x0649, 0x064a, 0x00ee, 0x00ef,
  0x064b, 0x064c, 0x064d, 0x064e, 0x00f4, 0x064f, 0x0650, 0x00f7, 0x0651, 0x00f9, 0x0652, 0x00fb, 0x00fc, 0x200e, 0x200f, 0x06d2,
];
