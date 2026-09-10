import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ptt_flutter/src/home_screen.dart';
import 'package:ptt_flutter/src/ptt_controller.dart';

class _HoldToTalkTestController extends PttController {
  int beginCount = 0;
  int endCount = 0;

  @override
  Future<void> beginTalk() async => beginCount += 1;

  @override
  Future<void> endTalk() async => endCount += 1;
}

void main() {
  test('controller starts disconnected in room 1', () {
    final controller = PttController();

    expect(controller.connected, isFalse);
    expect(controller.roomId, 'room-1');
    expect(controller.channelMessage, 'ยังไม่เชื่อมต่อ');
    expect(PttController.accessTokenEnabled, isTrue);
    expect(controller.endpoint, 'wss://ptt-relay-ixpl.onrender.com');

    controller.dispose();
  });

  testWidgets('hold-to-talk ignores a short tap', (tester) async {
    final controller = _HoldToTalkTestController()..connected = true;
    await tester.pumpWidget(
      MaterialApp(
        home: HoldToTalkButton(controller: controller, height: 120),
      ),
    );

    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(HoldToTalkButton)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    await gesture.up();
    await tester.pump();

    expect(controller.beginCount, 0);
    expect(controller.endCount, 0);
    controller.dispose();
  });

  testWidgets('hold-to-talk starts after the hold threshold', (tester) async {
    final controller = _HoldToTalkTestController()..connected = true;
    await tester.pumpWidget(
      MaterialApp(
        home: HoldToTalkButton(controller: controller, height: 120),
      ),
    );

    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(HoldToTalkButton)),
    );
    await tester.pump(const Duration(milliseconds: 350));
    expect(controller.beginCount, 1);
    await gesture.up();
    await tester.pump();
    expect(controller.endCount, 1);
    controller.dispose();
  });
}
