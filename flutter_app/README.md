# PTT Flutter

รุ่นทดลองข้ามแพลตฟอร์ม **1.3.1 (build 13)** ทำงานคู่กับ Kotlin `v1.2.3-docker` และใช้ WebSocket protocol/Relay Server เดิม

## สถานะปัจจุบัน

- Android: หน้าจอและฟังก์ชันหลักครบ, native audio/service Compile ผ่าน และสร้าง Release APK ที่ลงนามแล้วสำเร็จ
- iOS: มี Flutter UI, Swift WebSocket/audio bridge และ Apple PushToTalk lifecycle แล้ว แต่ต้อง Compile, Sign และทดสอบบน macOS กับ iPhone จริง
- Server: รุ่นทดสอบชื่อ `ptt-relay:1.3.1-test`, ปิด Access Token ชั่วคราวด้วย `PTT_AUTH_REQUIRED=false` แต่ยังคงรหัส Dashboard และรองรับ APNs แบบเลือกเปิดใช้

## Build Android

```powershell
cd flutter_app
flutter pub get
flutter build apk --debug
flutter build apk --release
```

- Debug: `flutter_app/build/app/outputs/versioned-apk/debug/PTT-Flutter-1.3.1-debug.apk`
- Release ที่เซ็นแล้ว: `flutter_app/build/app/outputs/versioned-apk/release/PTT-Flutter-1.3.1-release.apk`
- รุ่น Flutter ใช้ Application ID `com.example.ptt_flutter` จึงติดตั้งคู่กับ Kotlin เดิมได้

## เตรียม iOS บน Mac

1. ติดตั้ง Flutter และ Xcode รุ่นล่าสุด แล้วเปิด `flutter_app/ios/Runner.xcworkspace`
2. เปลี่ยน Bundle Identifier `com.example.pttFlutter` เป็น Bundle ID ที่เป็นของคุณ
3. เลือก Apple Developer Team ที่ Signing & Capabilities
4. ตรวจว่ามี Capabilities: **Push to Talk**, **Push Notifications**, และ **Background Modes > Push to Talk**
5. Build ลง iPhone จริง เพราะ Apple PushToTalk ใช้ทดสอบเต็มรูปแบบบน Simulator ไม่ได้

## เปิด APNs บน Docker Server

1. สร้าง APNs signing key ใน Apple Developer และวางไฟล์เป็น `server/apns/AuthKey.p8`
2. เพิ่ม `APNS_ENABLED=true` และกรอกค่า `APNS_TEAM_ID`, `APNS_KEY_ID`, `APNS_BUNDLE_ID` ใน `server/.env`
3. ตั้ง `APNS_ENVIRONMENT=development` สำหรับ Debug หรือ `production` สำหรับ TestFlight/App Store
4. Build image `ptt-relay:1.3.0-flutter` แล้วทดสอบ iPhone จริง

ไฟล์ `.p8`, Token และ Password ถูก ignore จาก Git ห้ามแนบภาพหรือส่งต่อให้บุคคลอื่น

## ข้อควรทราบก่อนใช้งานจริง

ตอนนี้ iOS อนุญาต `ws://` เพื่อทดลองกับ Relay เดิมผ่าน Tailscale เมื่อเตรียม production ควรเปลี่ยน Server เป็น `wss://` แล้วนำ `NSAllowsArbitraryLoads` ออกจาก `ios/Runner/Info.plist`
