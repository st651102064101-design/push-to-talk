import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'src/home_screen.dart';
import 'src/ptt_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.portraitUp,
  ]);
  final controller = PttController();
  await controller.initialize();
  runApp(PttApp(controller: controller));
}

class PttApp extends StatelessWidget {
  const PttApp({required this.controller, super.key});

  final PttController controller;

  @override
  Widget build(BuildContext context) {
    const navy = Color(0xFF091426);
    return MaterialApp(
      title: 'PTT Flutter',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: navy,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF257AFF),
          brightness: Brightness.dark,
          surface: const Color(0xFF17253C),
        ),
        inputDecorationTheme: const InputDecorationTheme(
          filled: true,
          fillColor: Color(0xFF101D31),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.all(Radius.circular(14)),
            borderSide: BorderSide.none,
          ),
        ),
      ),
      home: HomeScreen(controller: controller),
    );
  }
}
