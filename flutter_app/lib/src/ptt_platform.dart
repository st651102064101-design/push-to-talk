import 'dart:async';

import 'package:flutter/services.dart';

class PttPlatform {
  static const MethodChannel _control = MethodChannel('ptt/control');
  static const EventChannel _status = EventChannel('ptt/status');

  Stream<Map<String, dynamic>> get statusEvents => _status
      .receiveBroadcastStream()
      .where((event) => event is Map)
      .map((event) => Map<String, dynamic>.from(event as Map));

  Future<bool> requestPermissions() async =>
      await _control.invokeMethod<bool>('requestPermissions') ?? false;

  Future<String> appVersion() async =>
      await _control.invokeMethod<String>('appVersion') ?? 'ไม่ทราบเวอร์ชัน';

  Future<void> connect({
    required String endpoint,
    required String room,
    required String name,
    required String token,
  }) => _control.invokeMethod<void>('connect', <String, Object>{
    'endpoint': endpoint,
    'room': room,
    'name': name,
    'token': token,
  });

  Future<void> switchRoom({required String room, required String name}) =>
      _control.invokeMethod<void>('switchRoom', <String, Object>{
        'room': room,
        'name': name,
      });

  Future<void> requestTalk() => _control.invokeMethod<void>('requestTalk');
  Future<void> releaseTalk() => _control.invokeMethod<void>('releaseTalk');
  Future<void> disconnect() => _control.invokeMethod<void>('disconnect');
  Future<void> requestState() => _control.invokeMethod<void>('requestState');
  Future<void> cycleOutput() => _control.invokeMethod<void>('cycleOutput');
  Future<void> testSpeaker() => _control.invokeMethod<void>('testSpeaker');
}
