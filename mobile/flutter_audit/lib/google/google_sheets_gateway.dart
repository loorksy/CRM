import 'dart:async';
import 'dart:convert';

import 'package:google_sign_in/google_sign_in.dart';
import 'package:http/http.dart' as http;

/// Google is accessed directly from Android: no service account or backend.
/// Drive readonly is needed to list existing spreadsheets; Sheets is needed
/// to read, create sheets, and perform the explicitly approved audit writes.
const googleAuditScopes = <String>[
  'https://www.googleapis.com/auth/spreadsheets',
  'https://www.googleapis.com/auth/drive.readonly',
];

class GoogleFailure implements Exception {
  const GoogleFailure(this.code, this.message, {this.statusCode});
  final String code;
  final String message;
  final int? statusCode;
  @override
  String toString() => message;
}

class GoogleSession {
  GoogleSession({GoogleSignIn? signIn})
      : _signIn = signIn ?? GoogleSignIn(scopes: googleAuditScopes);
  final GoogleSignIn _signIn;
  GoogleSignInAccount? _account;

  String? get accountId => _account?.id;
  String? get email => _account?.email;

  Future<String?> restore() async {
    _account = await _signIn.signInSilently(suppressErrors: false);
    return _account?.id;
  }

  Future<String?> connect() async {
    _account = await _signIn.signIn();
    return _account?.id;
  }

  Future<void> disconnect() async {
    await _signIn.disconnect();
    _account = null;
  }

  Future<String> accessToken(String expectedAccountId) async {
    final account = _account;
    if (account == null || account.id != expectedAccountId) {
      throw const GoogleFailure('account_changed',
          'الحساب المتصل مختلف عن حساب هذه العملية. أعد اختيار الحساب والملفات.');
    }
    final authentication = await account.authentication;
    final token = authentication.accessToken;
    if (token == null || token.isEmpty) {
      throw const GoogleFailure('unauthorized', 'انتهى الاتصال بحساب Google. أعد الربط.');
    }
    return token;
  }
}

class SpreadsheetRef {
  const SpreadsheetRef({required this.id, required this.name});
  final String id;
  final String name;
}

class SheetRef {
  const SheetRef({required this.id, required this.title});
  final int id;
  final String title;
}

/// Coordinates are zero-based; every populated row is retained, even when
/// the API omits empty rows before it. userEnteredValue retains text and numbers
/// without converting identifiers to floating point.
class GoogleCell {
  const GoogleCell({required this.entered, this.formatted = '', this.color});
  final Map<String, dynamic> entered;
  final String formatted;
  final Map<String, dynamic>? color;
}

class GoogleRow {
  const GoogleRow(this.index, this.cells);
  final int index;
  final List<GoogleCell> cells;
}

class GoogleSheetData {
  const GoogleSheetData({required this.ref, required this.rows,
    required this.merges, required this.protectedRanges,
    this.originAccountId, this.originSpreadsheetId});
  final String? originAccountId;
  final String? originSpreadsheetId;
  final SheetRef ref;
  final List<GoogleRow> rows;
  final List<Map<String, dynamic>> merges;
  final List<Map<String, dynamic>> protectedRanges;
}

abstract class SheetsGateway {
  Future<List<SpreadsheetRef>> listSpreadsheets(String accountId);
  Future<List<SheetRef>> listSheets(String accountId, String spreadsheetId);
  Future<GoogleSheetData> readSheet(String accountId, String spreadsheetId, int sheetId);
  Future<void> createSheet(String accountId, String spreadsheetId, String title);
  Future<void> writeRow(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, List<Map<String, dynamic>> values);
  Future<void> colorRow(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, int endColumn, Map<String, double> color);
  Future<void> writeStatus(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, int columnIndex, String value);
}

class GoogleSheetsGateway implements SheetsGateway {
  GoogleSheetsGateway(this.session, {http.Client? client}) : _client = client ?? http.Client();
  final GoogleSession session;
  final http.Client _client;

  Future<Map<String, dynamic>> _request(String accountId, String method,
      Uri uri, {Map<String, dynamic>? body}) async {
    final token = await session.accessToken(accountId);
    late http.Response response;
    try {
      final headers = <String, String>{
        'Authorization': 'Bearer $token', 'Content-Type': 'application/json; charset=utf-8'};
      if (method == 'GET') {
        response = await _client.get(uri, headers: headers).timeout(const Duration(seconds: 45));
      } else {
        response = await _client.post(uri, headers: headers, body: jsonEncode(body))
            .timeout(const Duration(seconds: 45));
      }
    } on TimeoutException {
      throw const GoogleFailure('uncertain', 'انتهت مهلة الاتصال. سيُتحقق من نتيجة العملية قبل المحاولة مجددًا.');
    } catch (_) {
      throw const GoogleFailure('uncertain', 'تعذر الاتصال بـGoogle. سيُتحقق من نتيجة العملية قبل المحاولة مجددًا.');
    }
    final status = response.statusCode;
    if (status < 200 || status >= 300) {
      if (status == 401) throw GoogleFailure('unauthorized', 'انتهى تفويض Google. أعد ربط الحساب.', statusCode: status);
      if (status == 403) throw GoogleFailure('forbidden', 'لا يملك الحساب صلاحية الوصول إلى الملف أو تعديله.', statusCode: status);
      if (status == 404) throw GoogleFailure('missing', 'لم يعد الملف أو الورقة موجودًا.', statusCode: status);
      if (status == 429) throw GoogleFailure('quota', 'تم بلوغ حد طلبات Google. حاول لاحقًا.', statusCode: status);
      if (status >= 500) throw GoogleFailure('uncertain', 'تعذر التأكد من نتيجة العملية لدى Google.', statusCode: status);
      throw GoogleFailure('rejected', 'رفض Google العملية. راجع أذونات الملف والخلايا المحمية.', statusCode: status);
    }
    if (response.body.isEmpty) return <String, dynamic>{};
    try {
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) throw const FormatException();
      return decoded;
    } on FormatException {
      throw const GoogleFailure('invalid_response', 'تعذر فهم رد Google.');
    }
  }

  @override
  Future<List<SpreadsheetRef>> listSpreadsheets(String accountId) async {
    final result = <SpreadsheetRef>[];
    String? page;
    do {
      final q = <String, String>{
        'q': "mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false",
        'fields': 'nextPageToken,files(id,name)', 'pageSize': '100',
        if (page != null) 'pageToken': page,
      };
      final json = await _request(accountId, 'GET', Uri.https('www.googleapis.com', '/drive/v3/files', q));
      for (final item in (json['files'] as List? ?? const [])) {
        final map = item as Map<String, dynamic>;
        result.add(SpreadsheetRef(id: map['id'] as String, name: map['name'] as String));
      }
      page = json['nextPageToken'] as String?;
    } while (page != null && page.isNotEmpty);
    return result;
  }

  @override
  Future<List<SheetRef>> listSheets(String accountId, String spreadsheetId) async {
    final json = await _request(accountId, 'GET', Uri.https('sheets.googleapis.com',
        '/v4/spreadsheets/$spreadsheetId', {'fields': 'sheets(properties(sheetId,title))'}));
    return (json['sheets'] as List? ?? const []).map((value) {
      final p = (value as Map<String, dynamic>)['properties'] as Map<String, dynamic>;
      return SheetRef(id: p['sheetId'] as int, title: p['title'] as String);
    }).toList();
  }

  @override
  Future<GoogleSheetData> readSheet(String accountId, String spreadsheetId, int sheetId) async {
    final sheets = await listSheets(accountId, spreadsheetId);
    final matches = sheets.where((item) => item.id == sheetId).toList();
    if (matches.length != 1) throw const GoogleFailure('missing', 'الورقة المحددة غير موجودة.');
    final ref = matches.single;
    final range = "'${ref.title.replaceAll("'", "''")}'";
    final json = await _request(accountId, 'GET', Uri.https('sheets.googleapis.com',
        '/v4/spreadsheets/$spreadsheetId', {
          'ranges': range, 'includeGridData': 'true',
          'fields': 'sheets(properties(sheetId,title),merges,protectedRanges(range,warningOnly),data(startRow,startColumn,rowData(values(userEnteredValue,formattedValue,effectiveFormat(backgroundColor,backgroundColorStyle)))))',
        }));
    final sheetsJson = json['sheets'] as List? ?? const [];
    final candidates = sheetsJson.where((dynamic s) => s['properties']['sheetId'] == sheetId).toList();
    if (candidates.length != 1) throw const GoogleFailure('missing', 'تغيرت الورقة المحددة أثناء القراءة.');
    final sheet = candidates.single as Map<String, dynamic>;
    final rows = <GoogleRow>[];
    for (final blockDynamic in (sheet['data'] as List? ?? const [])) {
      final block = blockDynamic as Map<String, dynamic>;
      final startRow = block['startRow'] as int? ?? 0;
      final startColumn = block['startColumn'] as int? ?? 0;
      final rowData = block['rowData'] as List? ?? const [];
      for (var i = 0; i < rowData.length; i++) {
        final row = rowData[i] as Map<String, dynamic>;
        final cells = List<GoogleCell>.generate(startColumn,
            (_) => const GoogleCell(entered: {}));
        for (final cellDynamic in (row['values'] as List? ?? const [])) {
          final cell = cellDynamic as Map<String, dynamic>;
          final format = cell['effectiveFormat'] as Map<String, dynamic>?;
          final colorStyle = format?['backgroundColorStyle'] as Map<String, dynamic>?;
          final color = (colorStyle?['rgbColor'] ?? format?['backgroundColor']) as Map<String, dynamic>?;
          cells.add(GoogleCell(entered: Map<String, dynamic>.from(cell['userEnteredValue'] as Map? ?? const {}),
              formatted: cell['formattedValue'] as String? ?? '', color: color));
        }
        rows.add(GoogleRow(startRow + i, cells));
      }
    }
    List<Map<String, dynamic>> maps(String key) => (sheet[key] as List? ?? const [])
        .map((dynamic v) => Map<String, dynamic>.from(v as Map)).toList();
    return GoogleSheetData(ref: ref, rows: rows, merges: maps('merges'), protectedRanges: maps('protectedRanges'),
        originAccountId: accountId, originSpreadsheetId: spreadsheetId);
  }

  Future<void> _batch(String accountId, String spreadsheetId, List<Map<String, dynamic>> requests) async {
    // A read-before-write cannot protect a row against a concurrent editor.
    // Fail closed until the write protocol has real, isolated Google tests.
    throw const GoogleFailure('writes_disabled', 'الكتابة إلى Google متوقفة إلى حين اكتمال اختبارات السلامة.');
  }

  @override
  Future<void> createSheet(String accountId, String spreadsheetId, String title) =>
      _batch(accountId, spreadsheetId, [{'addSheet': {'properties': {'title': title}}}]);

  @override
  Future<void> writeRow(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, List<Map<String, dynamic>> values) async {
    // A guessed last+1 row index can overwrite another editor's concurrent
    // change. Keep copy disabled until appendCells and its retry/read-back
    // behavior are tested against isolated real Google spreadsheets.
    throw const GoogleFailure('unsafe_copy', 'نسخ الصفوف متوقف لحين التحقق من سلامته مع التعديلات المتزامنة.');
  }

  @override
  Future<void> colorRow(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, int endColumn, Map<String, double> color) => _batch(accountId, spreadsheetId, [
    {'repeatCell': {'range': {'sheetId': sheetId, 'startRowIndex': rowIndex,
      'endRowIndex': rowIndex + 1, 'startColumnIndex': 0, 'endColumnIndex': endColumn},
      'cell': {'userEnteredFormat': {'backgroundColorStyle': {'rgbColor': color}}},
      'fields': 'userEnteredFormat.backgroundColorStyle'}}
  ]);

  @override
  Future<void> writeStatus(String accountId, String spreadsheetId, int sheetId,
      int rowIndex, int columnIndex, String value) => _batch(accountId, spreadsheetId, [
    {'updateCells': {'start': {'sheetId': sheetId, 'rowIndex': rowIndex, 'columnIndex': columnIndex},
      'rows': [{'values': [{'userEnteredValue': {'stringValue': value}}]}],
      'fields': 'userEnteredValue'}}
  ]);
}
