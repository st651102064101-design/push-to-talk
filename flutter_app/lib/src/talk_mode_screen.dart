import 'package:flutter/material.dart';

import 'home_screen.dart';
import 'ptt_controller.dart';

class TalkModeScreen extends StatelessWidget {
  const TalkModeScreen({required this.controller, super.key});
  final PttController controller;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF091426),
        title: Text(
          'CHANNEL : ${controller.roomLabel}',
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        centerTitle: true,
      ),
      body: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
          child: Column(
            children: [
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 13,
                ),
                decoration: BoxDecoration(
                  color: const Color(0xFF17253C),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Text(
                  controller.channelMessage,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontWeight: FontWeight.w800,
                    color: controller.busy
                        ? const Color(0xFFFF7E7E)
                        : const Color(0xFF59D689),
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Expanded(
                child: HoldToTalkButton(
                  controller: controller,
                  height: double.infinity,
                ),
              ),
              const SizedBox(height: 14),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 210),
                child: SingleChildScrollView(
                  child: MembersCard(controller: controller, compact: true),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
