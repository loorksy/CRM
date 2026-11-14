import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:lork_audit_standalone/google/google_sheets_gateway.dart';

class TestSession extends GoogleSession {
  @override
  Future<String> accessToken(String expectedAccountId) async => 'secret-token';
}

void main() {
  test('all writes fail closed without making a Google request', () async {
    final gateway = GoogleSheetsGateway(TestSession(), client: MockClient((r) async {
      fail('write must not make a network request');
    }));
    await expectLater(gateway.createSheet('a', 'f', 'target'), throwsA(isA<GoogleFailure>()));
    await expectLater(gateway.writeRow('a', 'f', 1, 1, [{'stringValue': 'x'}]), throwsA(isA<GoogleFailure>()));
    await expectLater(gateway.colorRow('a', 'f', 1, 1, 2, {'red': 0, 'green': 0, 'blue': 0}), throwsA(isA<GoogleFailure>()));
    await expectLater(gateway.writeStatus('a', 'f', 1, 1, 2, 'سحب إدارة'), throwsA(isA<GoogleFailure>()));
  });

  test('permission errors never expose response bodies or access tokens', () async {
    final gateway = GoogleSheetsGateway(TestSession(), client: MockClient((r) async =>
        http.Response('secret-token details', 403)));
    try {
      await gateway.listSheets('a', 'file');
      fail('expected permission error');
    } on GoogleFailure catch (error) {
      expect(error.code, 'forbidden');
      expect(error.toString(), isNot(contains('secret-token')));
    }
  });
}
