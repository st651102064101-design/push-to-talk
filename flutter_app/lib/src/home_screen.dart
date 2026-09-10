import 'dart:async';

import 'package:flutter/material.dart';

import 'ptt_controller.dart';
import 'talk_mode_screen.dart';

const _navy = Color(0xFF091426);
const _surface = Color(0xFF17253C);
const _surfaceAlt = Color(0xFF2A3D5A);
const _blue = Color(0xFF257AFF);
const _green = Color(0xFF59D689);
const _red = Color(0xFFFF7E7E);
const _orange = Color(0xFFFFB547);
const _muted = Color(0xFF9FB2CC);
const _white = Color(0xFFF1F6FC);

class HomeScreen extends StatelessWidget {
  const HomeScreen({required this.controller, super.key});

  final PttController controller;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final message = controller.takeEventMessage();
      if (message != null) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (context.mounted) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(content: Text(message)));
          }
        });
      }
      return Scaffold(
        backgroundColor: _navy,
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 560),
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
                children: [
                  _Header(onSettings: () => _openSettings(context)),
                  const SizedBox(height: 20),
                  _ChannelCard(controller: controller),
                  const SizedBox(height: 12),
                  _ConnectionRow(controller: controller),
                  const SizedBox(height: 12),
                  _ChannelStatus(controller: controller),
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 54,
                    child: FilledButton.tonalIcon(
                      onPressed: controller.connected
                          ? () => Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) =>
                                    TalkModeScreen(controller: controller),
                              ),
                            )
                          : null,
                      icon: const Icon(Icons.fullscreen_rounded),
                      label: const Text(
                        'โหมดคุยเต็มหน้าจอ',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      style: FilledButton.styleFrom(
                        backgroundColor: _surfaceAlt,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  HoldToTalkButton(controller: controller, height: 140),
                  const SizedBox(height: 12),
                  MembersCard(controller: controller),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );

  Future<void> _openSettings(BuildContext context) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => SettingsSheet(controller: controller),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.onSettings});
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      const Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'PTT  •  PUSH TO TALK',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w800,
                color: _white,
              ),
            ),
            SizedBox(height: 2),
            Text(
              'สื่อสารทันที  •  ปลอดภัย  •  รวดเร็ว',
              style: TextStyle(fontSize: 13, color: _muted),
            ),
          ],
        ),
      ),
      IconButton.filledTonal(
        onPressed: onSettings,
        icon: const Icon(Icons.settings_rounded),
        style: IconButton.styleFrom(
          fixedSize: const Size(52, 52),
          backgroundColor: _surface,
          foregroundColor: _white,
        ),
      ),
    ],
  );
}

class _ChannelCard extends StatelessWidget {
  const _ChannelCard({required this.controller});
  final PttController controller;

  @override
  Widget build(BuildContext context) => _Card(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _Label('CHANNEL'),
        const SizedBox(height: 10),
        Row(
          children: List.generate(5, (index) {
            final room = index + 1;
            final selected = controller.selectedRoom == room;
            final allowed =
                room != 5 || !controller.connected || controller.isAdmin;
            return Expanded(
              child: Padding(
                padding: EdgeInsets.only(left: index == 0 ? 0 : 6),
                child: SizedBox(
                  height: 48,
                  child: FilledButton(
                    onPressed: allowed
                        ? () => controller.selectRoom(room)
                        : null,
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      backgroundColor: selected ? _blue : _surfaceAlt,
                      disabledBackgroundColor: _surfaceAlt.withValues(
                        alpha: .45,
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(15),
                      ),
                    ),
                    child: Text(
                      room == 5 ? 'All' : 'ห้อง $room',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                ),
              ),
            );
          }),
        ),
      ],
    ),
  );
}

class _ConnectionRow extends StatelessWidget {
  const _ConnectionRow({required this.controller});
  final PttController controller;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 84,
    child: Row(
      children: [
        Expanded(
          child: _Card(
            padding: const EdgeInsets.all(8),
            child: SizedBox.expand(
              child: FilledButton(
                onPressed: () async {
                  if (controller.connected) {
                    await controller.disconnect();
                  } else {
                    final error = await controller.connect();
                    if (error != null && context.mounted) {
                      ScaffoldMessenger.of(
                        context,
                      ).showSnackBar(SnackBar(content: Text(error)));
                    }
                  }
                },
                style: FilledButton.styleFrom(
                  backgroundColor: controller.connected ? _surfaceAlt : _blue,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: Text(
                  controller.connected ? 'ตัดการเชื่อมต่อ' : 'เชื่อมต่อ',
                ),
              ),
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _Card(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  'Status : ${controller.connected ? 'ON' : 'OFF'}',
                  style: TextStyle(
                    color: controller.connected ? _green : _red,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 7),
                Text(
                  'Server : ${controller.serverHost}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: _muted, fontSize: 11),
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class _ChannelStatus extends StatelessWidget {
  const _ChannelStatus({required this.controller});
  final PttController controller;

  @override
  Widget build(BuildContext context) {
    final color = !controller.connected
        ? _muted
        : controller.selfTalking
        ? _orange
        : controller.busy
        ? _red
        : _green;
    return Container(
      height: 56,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Text(
        controller.channelMessage,
        style: TextStyle(
          color: color,
          fontSize: 16,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class HoldToTalkButton extends StatefulWidget {
  const HoldToTalkButton({
    required this.controller,
    required this.height,
    super.key,
  });
  final PttController controller;
  final double height;

  @override
  State<HoldToTalkButton> createState() => _HoldToTalkButtonState();
}

class _HoldToTalkButtonState extends State<HoldToTalkButton> {
  static const _holdThreshold = Duration(milliseconds: 300);
  bool _pointerDown = false;
  bool _talkRequested = false;
  bool _releaseSent = false;
  Timer? _holdTimer;

  void _press() {
    if (_pointerDown) return;
    _pointerDown = true;
    _talkRequested = false;
    _releaseSent = false;
    _holdTimer?.cancel();
    _holdTimer = Timer(_holdThreshold, _startTalk);
    setState(() {});
  }

  Future<void> _startTalk() async {
    _holdTimer = null;
    if (!_pointerDown || !widget.controller.canTalk) return;
    _talkRequested = true;
    final request = widget.controller.beginTalk();
    await request;
    if (!_pointerDown && !_releaseSent) {
      _releaseSent = true;
      await widget.controller.endTalk();
    }
    if (mounted) setState(() {});
  }

  Future<void> _release() async {
    if (!_pointerDown) return;
    _pointerDown = false;
    _holdTimer?.cancel();
    _holdTimer = null;
    if (_talkRequested && !_releaseSent) {
      _releaseSent = true;
      await widget.controller.endTalk();
    }
    _talkRequested = false;
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _holdTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final enabled = controller.canTalk;
    final color = controller.selfTalking
        ? _orange
        : controller.busy
        ? const Color(0xFF8C2F39)
        : _blue;
    final label = !controller.connected
        ? 'ยังไม่เชื่อมต่อ'
        : controller.selfTalking
        ? 'ปล่อยเพื่อหยุดพูด'
        : controller.busy
        ? 'ช่องสัญญาณไม่ว่าง'
        : 'กดค้างเพื่อพูด';
    return Opacity(
      opacity: enabled ? 1 : .58,
      child: Listener(
        onPointerDown: enabled
            ? (_) => _press()
            : null,
        onPointerUp: (_) => _release(),
        onPointerCancel: (_) => _release(),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          height: widget.height,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(20),
            boxShadow: _pointerDown
                ? const []
                : [
                    BoxShadow(
                      color: color.withValues(alpha: .2),
                      blurRadius: 18,
                      offset: const Offset(0, 8),
                    ),
                  ],
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 25,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ),
    );
  }
}

class MembersCard extends StatelessWidget {
  const MembersCard({
    required this.controller,
    this.compact = false,
    super.key,
  });
  final PttController controller;
  final bool compact;

  @override
  Widget build(BuildContext context) => _Card(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          controller.allMembersView
              ? 'ออนไลน์ทุกห้อง (${controller.members.length})'
              : 'ออนไลน์ในห้องนี้ (${controller.members.length})',
          style: const TextStyle(
            color: _white,
            fontSize: 16,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 8),
        if (!controller.connected || controller.members.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              controller.connected
                  ? 'ยังไม่มีสมาชิกออนไลน์'
                  : 'เชื่อมต่อเพื่อดูสมาชิกออนไลน์',
              style: const TextStyle(color: _muted),
            ),
          )
        else
          ...controller.members.map(
            (member) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 7),
              child: Row(
                children: [
                  const Icon(Icons.circle, color: _green, size: 11),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      member,
                      style: const TextStyle(color: _white, fontSize: 15),
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    ),
  );
}

class SettingsSheet extends StatefulWidget {
  const SettingsSheet({required this.controller, super.key});
  final PttController controller;

  @override
  State<SettingsSheet> createState() => _SettingsSheetState();
}

class _SettingsSheetState extends State<SettingsSheet> {
  late final TextEditingController _endpoint;
  late final TextEditingController _name;
  late final TextEditingController _token;
  bool _hideToken = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _endpoint = TextEditingController(text: widget.controller.endpoint);
    _name = TextEditingController(text: widget.controller.displayName);
    _token = TextEditingController(text: widget.controller.token);
  }

  @override
  void dispose() {
    _endpoint.dispose();
    _name.dispose();
    _token.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
    child: Container(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      decoration: const BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(26)),
      ),
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 42,
                  height: 4,
                  decoration: BoxDecoration(
                    color: _muted.withValues(alpha: .45),
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              const Text(
                'ตั้งค่า',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 18),
              const _Label('SERVER ADDRESS'),
              const SizedBox(height: 7),
              TextField(
                controller: _endpoint,
                keyboardType: TextInputType.url,
                autocorrect: false,
              ),
              const SizedBox(height: 14),
              if (PttController.accessTokenEnabled) ...[
                const _Label('ACCESS TOKEN'),
                const SizedBox(height: 7),
                TextField(
                  controller: _token,
                  obscureText: _hideToken,
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: InputDecoration(
                    suffixIcon: IconButton(
                      onPressed: () =>
                          setState(() => _hideToken = !_hideToken),
                      icon: Icon(
                        _hideToken
                            ? Icons.visibility_off_rounded
                            : Icons.visibility_rounded,
                      ),
                    ),
                  ),
                ),
              ] else
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFF4CC),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Text(
                    'โหมดทดสอบ: ปิดการใช้งาน Access Token ชั่วคราว',
                    style: TextStyle(
                      color: Color(0xFF8A5A00),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              const SizedBox(height: 14),
              const _Label('DISPLAY NAME'),
              const SizedBox(height: 7),
              TextField(controller: _name, maxLength: 30),
              const SizedBox(height: 6),
              const _Label('อุปกรณ์เสียง'),
              const SizedBox(height: 7),
              SizedBox(
                height: 48,
                child: FilledButton.tonalIcon(
                  onPressed: widget.controller.connected
                      ? widget.controller.cycleOutput
                      : null,
                  icon: const Icon(Icons.volume_up_rounded),
                  label: Text('เสียง: ${widget.controller.outputLabel}'),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                height: 48,
                child: OutlinedButton.icon(
                  onPressed: widget.controller.testSpeaker,
                  icon: const Icon(Icons.speaker_rounded),
                  label: const Text('ทดสอบลำโพง'),
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 50,
                child: FilledButton(
                  onPressed: _saving ? null : _save,
                  style: FilledButton.styleFrom(backgroundColor: _blue),
                  child: Text(
                    _saving ? 'กำลังบันทึก…' : 'บันทึก',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Text(
                'เวอร์ชัน ${widget.controller.versionLabel}',
                textAlign: TextAlign.center,
                style: const TextStyle(color: _muted, fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  Future<void> _save() async {
    setState(() => _saving = true);
    final error = await widget.controller.saveSettings(
      newEndpoint: _endpoint.text,
      newName: _name.text,
      newToken: _token.text,
    );
    if (!mounted) return;
    setState(() => _saving = false);
    if (error == null) {
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('บันทึกการตั้งค่าแล้ว')));
    } else {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error)));
    }
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.child, this.padding = const EdgeInsets.all(16)});
  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: _surface,
      borderRadius: BorderRadius.circular(17),
    ),
    child: Padding(padding: padding, child: child),
  );
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: const TextStyle(color: _muted, fontSize: 12, letterSpacing: 1),
  );
}
