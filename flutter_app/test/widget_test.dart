import 'package:flutter_test/flutter_test.dart';
import 'package:ptt_flutter/src/ptt_controller.dart';

void main() {
  test('controller starts disconnected in room 1', () {
    final controller = PttController();

    expect(controller.connected, isFalse);
    expect(controller.roomId, 'room-1');
    expect(controller.channelMessage, 'ยังไม่เชื่อมต่อ');

    controller.dispose();
  });
}
