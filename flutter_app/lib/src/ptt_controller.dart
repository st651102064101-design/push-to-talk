import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'ptt_platform.dart';

class PttController extends ChangeNotifier {
  PttController({PttPlatform? platform})
    : _platform = platform ?? PttPlatform();

  static const _endpointKey = 'endpoint';
  static const _nameKey = 'display_name';
  static const _roomKey = 'room';
  static const _tokenKey = 'access_token';

  final PttPlatform _platform;
  final FlutterSecureStorage _secureStorage = const FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );
  StreamSubscription<Map<String, dynamic>>? _statusSubscription;

  String endpoint = 'ws://100.108.250.41:8181';
  String displayName = Platform.isIOS ? 'iPhone' : 'Android';
  String token = '';
  int selectedRoom = 1;
  String statusText = 'ยังไม่เชื่อมต่อ';
  String outputLabel = 'อัตโนมัติ';
  String activeSpeaker = '';
  String eventMessage = '';
  String versionLabel = 'Flutter';
  List<String> members = const [];
  bool connected = false;
  bool busy = false;
  bool selfTalking = false;
  bool allMembersView = false;
  bool isAdmin = false;
  bool initialized = false;

  String get roomId => selectedRoom == 5 ? 'all' : 'room-$selectedRoom';
  String get roomLabel => selectedRoom == 5 ? 'ALL' : 'ห้อง $selectedRoom';
  bool get canTalk => connected && (!busy || selfTalking);
  String get serverHost {
    final uri = Uri.tryParse(endpoint);
    return uri?.host.isNotEmpty == true ? uri!.host : '-';
  }

  String get channelMessage {
    if (!connected) return 'ยังไม่เชื่อมต่อ';
    if (selfTalking) return 'คุณกำลังพูด';
    if (busy) {
      return 'กำลังพูด: ${activeSpeaker.isEmpty ? 'ผู้ใช้อื่น' : activeSpeaker}';
    }
    return 'พร้อมพูด';
  }

  Future<void> initialize() async {
    final preferences = await SharedPreferences.getInstance();
    endpoint = preferences.getString(_endpointKey) ?? endpoint;
    displayName = preferences.getString(_nameKey) ?? displayName;
    selectedRoom = (preferences.getInt(_roomKey) ?? 1).clamp(1, 5).toInt();
    token = await _secureStorage.read(key: _tokenKey) ?? '';
    versionLabel = 'Flutter ${await _platform.appVersion()}';
    _statusSubscription = _platform.statusEvents.listen(
      _handleStatus,
      onError: (Object error) {
        statusText = 'ไม่สามารถอ่านสถานะระบบ PTT ได้';
        notifyListeners();
      },
    );
    initialized = true;
    notifyListeners();
    await _safeCall(_platform.requestState);
  }

  void _handleStatus(Map<String, dynamic> event) {
    connected = event['ready'] == true;
    busy = event['busy'] == true;
    selfTalking = event['selfTalking'] == true;
    isAdmin = event['admin'] == true;
    allMembersView = event['allMembersView'] == true;
    statusText = (event['status'] as String?) ?? statusText;
    outputLabel = (event['output'] as String?) ?? outputLabel;
    activeSpeaker = (event['speaker'] as String?) ?? '';
    final activeRoom = event['activeRoom'] as String?;
    if (activeRoom == 'all') {
      selectedRoom = 5;
    } else if (activeRoom?.startsWith('room-') == true) {
      selectedRoom =
          int.tryParse(activeRoom!.substring(5))?.clamp(1, 4).toInt() ??
          selectedRoom;
    }
    members = ((event['members'] as List?) ?? const [])
        .map((value) => value.toString())
        .toList(growable: false);
    final eventCode = event['event'] as String?;
    if (eventCode == 'floor_busy') eventMessage = 'ช่องสัญญาณไม่ว่าง';
    if (eventCode == 'forbidden_all') {
      eventMessage = 'Token นี้ไม่มีสิทธิ์ใช้งาน All';
      selectedRoom = 1;
    }
    notifyListeners();
  }

  Future<String?> saveSettings({
    required String newEndpoint,
    required String newName,
    required String newToken,
  }) async {
    final endpointValue = newEndpoint.trim();
    final uri = Uri.tryParse(endpointValue);
    if (uri == null ||
        !const {'ws', 'wss'}.contains(uri.scheme) ||
        uri.host.isEmpty) {
      return 'Server Address ต้องขึ้นต้นด้วย ws:// หรือ wss://';
    }
    if (newName.trim().isEmpty) return 'กรุณาระบุ Display Name';
    endpoint = endpointValue;
    displayName = newName.trim();
    token = newToken.trim();
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_endpointKey, endpoint);
    await preferences.setString(_nameKey, displayName);
    await preferences.setInt(_roomKey, selectedRoom);
    if (token.isEmpty) {
      await _secureStorage.delete(key: _tokenKey);
    } else {
      await _secureStorage.write(key: _tokenKey, value: token);
    }
    notifyListeners();
    if (connected) await connect();
    return null;
  }

  Future<void> selectRoom(int room) async {
    final target = room.clamp(1, 5).toInt();
    if (target == 5 && connected && !isAdmin) {
      eventMessage = 'เฉพาะ Admin เท่านั้นที่ใช้งาน All ได้';
      notifyListeners();
      return;
    }
    selectedRoom = target;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setInt(_roomKey, selectedRoom);
    notifyListeners();
    if (connected) {
      await _safeCall(
        () => _platform.switchRoom(room: roomId, name: displayName),
      );
    }
  }

  Future<String?> connect() async {
    if (token.isEmpty) return 'กรุณาใส่ Access Token ในเมนูตั้งค่า';
    final uri = Uri.tryParse(endpoint);
    if (uri == null ||
        !const {'ws', 'wss'}.contains(uri.scheme) ||
        uri.host.isEmpty) {
      return 'Server Address ไม่ถูกต้อง';
    }
    final permissionsGranted = await _platform.requestPermissions();
    if (!permissionsGranted) return 'ต้องอนุญาตไมโครโฟนก่อน';
    statusText = 'กำลังเชื่อมต่อ…';
    eventMessage = '';
    notifyListeners();
    return _safeCall(
      () => _platform.connect(
        endpoint: endpoint,
        room: roomId,
        name: displayName,
        token: token,
      ),
    );
  }

  Future<void> disconnect() async {
    await _safeCall(_platform.disconnect);
  }

  Future<void> beginTalk() async {
    if (!canTalk || selfTalking) return;
    await _safeCall(_platform.requestTalk);
  }

  Future<void> endTalk() async {
    if (!connected) return;
    await _safeCall(_platform.releaseTalk);
  }

  Future<void> cycleOutput() async {
    await _safeCall(_platform.cycleOutput);
  }

  Future<void> testSpeaker() async {
    await _safeCall(_platform.testSpeaker);
  }

  String? takeEventMessage() {
    if (eventMessage.isEmpty) return null;
    final value = eventMessage;
    eventMessage = '';
    return value;
  }

  Future<String?> _safeCall(Future<void> Function() callback) async {
    try {
      await callback();
      return null;
    } on PlatformException catch (error) {
      final message = error.message ?? 'ระบบ PTT ไม่พร้อมใช้งาน';
      statusText = message;
      notifyListeners();
      return message;
    } catch (error) {
      statusText = 'เกิดข้อผิดพลาดในการสื่อสารกับระบบ PTT';
      notifyListeners();
      return statusText;
    }
  }

  @override
  void dispose() {
    _statusSubscription?.cancel();
    super.dispose();
  }
}
