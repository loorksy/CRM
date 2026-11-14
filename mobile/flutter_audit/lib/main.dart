// Flutter 3.50 deprecates its bundled Material/Cupertino exports. Keep using
// the SDK-provided widgets until the replacement packages are adopted together.
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'ui/audit_app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const AuditApplication());
}

class AuditApplication extends StatelessWidget {
  const AuditApplication({super.key});

  @override
  Widget build(BuildContext context) {
    ThemeData theme(Brightness brightness) {
      final scheme = ColorScheme.fromSeed(
        seedColor: const Color(0xff6252bd),
        brightness: brightness,
      );
      return ThemeData(
        useMaterial3: true,
        colorScheme: scheme,
        scaffoldBackgroundColor: scheme.surface,
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(),
        ),
      );
    }

    return MaterialApp(
      title: 'التدقيق',
      debugShowCheckedModeBanner: false,
      locale: const Locale('ar'),
      supportedLocales: const [Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: theme(Brightness.light),
      darkTheme: theme(Brightness.dark),
      home: const AuditHome(),
    );
  }
}
