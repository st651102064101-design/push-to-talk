# Changelog

## 1.4.4-flutter — Hold-to-talk tap fix — 2026-09-10

- เปลี่ยนปุ่ม Push-to-Talk ให้เริ่มพูดเมื่อกดค้างอย่างน้อย 300 มิลลิวินาที
- แตะสั้นแล้วปล่อยจะไม่ส่งคำขอพูดและจะไม่แสดงสถานะ “ปล่อยเพื่อหยุดพูด”
- ยังคงหยุดพูดทันทีเมื่อยกนิ้วออกหลังเริ่มพูดแล้ว
- เพิ่มเวอร์ชัน Flutter เป็น `1.4.4` และ Android versionCode เป็น 18
- สร้าง Release APK ที่ลงนามแล้วสำเร็จ: `PTT-Flutter-1.4.4-release.apk` (SHA-256: `8E7D91BBC1EC07DEC1F9B7AEF9149FDE35D0F6A20340CC142BDB8F7A530C43B9`)

## 1.4.3-flutter — Render heartbeat and reconnect — 2026-09-10

- เพิ่มข้อความ WebSocket heartbeat จาก Android ทุก 25 วินาที เพื่อให้ Render Free เห็น inbound traffic ระหว่างมีการเชื่อมต่อ
- เพิ่ม Server heartbeat response และ WebSocket ping/pong เพื่อตรวจจับการเชื่อมต่อที่ค้าง
- เพิ่ม Android auto-reconnect แบบ exponential backoff สูงสุด 32 วินาที และหยุดลองอัตโนมัติเมื่อ Access Token ไม่ถูกต้อง
- เพิ่มเวอร์ชัน Flutter เป็น `1.4.3` และ Android versionCode เป็น 17
- ตรวจ `npm test` ผ่าน รวมการทดสอบ heartbeat response
- สร้าง Release APK ที่ลงนามแล้วสำเร็จ: `PTT-Flutter-1.4.3-release.apk` (SHA-256: `D3CBA8528CF803AC76205369E092E538E41D6F89306508B026FDB7D1601B0F31`)

## 1.4.2-flutter — Render Free — 2026-09-10

- เปลี่ยน Server เริ่มต้นของ Flutter เป็น `wss://ptt-relay-ixpl.onrender.com`
- ย้ายค่าที่บันทึกเป็น URL เก่าของ Back4App หรือ Tailscale ไปยัง Render อัตโนมัติเมื่ออัปเดตแอป
- เพิ่ม Flutter เป็นเวอร์ชัน `1.4.2` และ Android versionCode เป็น 16
- สร้าง Web Service `ptt-relay` บน Render Free ภูมิภาค Singapore จาก GitHub และตรวจสถานะเป็น Live แล้ว
- คง Access Token และ Dashboard credentials เดิมไว้ในตัวแปรลับของ Render โดยไม่บันทึกลง Git
- `flutter analyze` และ `flutter test` ผ่านทั้งหมด
- สร้าง Release APK ที่ลงนามแล้วสำเร็จ: `PTT-Flutter-1.4.2-release.apk` (SHA-256: `3E354B74F1103EF974B3B35BC4992F7040ABF86F55A0C2A5FB3E9D870EBB2C2E`)
- ยืนยัน APK เป็น versionName `1.4.2`, versionCode 16, applicationId `com.example.ptt_flutter` และ APK Signature Scheme v2 ผ่าน

## 1.4.1-flutter — Back4App URL refresh — 2026-09-10

- เปลี่ยน Server เริ่มต้นเป็น `wss://pttrelay-vwzw2gr4.b4a.run` หลัง URL ฟรีเดิมของ Back4App หมดอายุ
- ย้ายค่าที่บันทึกเป็น Back4App URL เดิมไปยัง URL ปัจจุบันอัตโนมัติเมื่ออัปเดตแอป
- เพิ่ม Flutter เป็นเวอร์ชัน `1.4.1` และ Android versionCode เป็น 15
- ตรวจ Runtime Logs ของ Back4App แล้วไม่พบคำขอจาก Android ไปถึง Server ก่อนแก้ URL
- `flutter analyze` และ `flutter test` ผ่านทั้งหมด
- สร้าง Release APK ที่ลงนามแล้วสำเร็จ: `PTT-Flutter-1.4.1-release.apk`
- ยืนยัน APK เป็น versionName `1.4.1`, versionCode 15, applicationId `com.example.ptt_flutter` และลายเซ็น APK Signature Scheme v2 ผ่าน

## 1.4.0-flutter — Back4App — 2026-09-09

- เปลี่ยน Server เริ่มต้นของ Flutter เป็น `wss://pttrelay-khsyb3go.b4a.run`
- ย้ายค่าที่เคยบันทึกเป็น Tailscale URL เดิมไปยัง Back4App อัตโนมัติเมื่ออัปเดตแอป
- เปิดช่อง Access Token อีกครั้งให้ตรงกับ Server บน Back4App ที่บังคับ Authentication
- เพิ่ม Flutter เป็นเวอร์ชัน `1.4.0` และ Android versionCode เป็น 14
- ไม่ฝัง Access Token ลงใน APK ผู้ใช้ต้องกรอก `PTT_USER_TOKEN` หรือ `PTT_ADMIN_TOKEN` ในเมนูตั้งค่า

### การตรวจสอบรอบ Build

- `flutter analyze`: ผ่าน ไม่พบปัญหา
- `flutter test`: ผ่านทั้งหมด
- สร้าง Release APK ที่ลงนามแล้วสำเร็จ: `PTT-Flutter-1.4.0-release.apk`
- ยืนยัน APK เป็น versionName `1.4.0`, versionCode 14 และ applicationId `com.example.ptt_flutter`
- ตรวจลายเซ็น APK Signature Scheme v2 ผ่าน และยืนยันว่า URL ของ Back4App อยู่ภายใน APK

## 1.4.0-northflank — กำลังนำขึ้น Cloud — 2026-09-06

- เตรียม Docker Server สำหรับ Northflank โดยคง WebSocket protocol และพอร์ตภายใน 8181
- เพิ่ม `PTT_TRUST_PROXY` เพื่ออ่าน IP ผู้ใช้งานผ่าน Northflank reverse proxy และไม่จำกัดทุกเครื่องรวมเป็น IP เดียว
- เพิ่ม `/healthz` สำหรับตรวจสุขภาพ Container โดยไม่เปิดเผย Dashboard
- เปิด Authentication เป็นค่าเริ่มต้น และกำหนดให้ Dashboard บนอินเทอร์เน็ตต้องใช้ Username/Password
- แสดง Server version `1.4.0-northflank` บน Dashboard และผลตรวจสุขภาพ
- Repository สำหรับ Deploy ต้องมีเฉพาะไฟล์ใน `server`; ห้ามนำ `.env`, Token, Password หรือ APNs key ขึ้น GitHub

## 1.3.1-test — กำลังทดสอบ — 2026-09-05

- ปิดการตรวจ Access Token ชั่วคราวด้วย `PTT_AUTH_REQUIRED=false`; Dashboard ยังคงบังคับ Username/Password
- ซ่อนช่อง Access Token ใน Flutter และลบ Token เดิมออกจาก Secure Storage เมื่อเปิดแอป
- อนุญาตให้ผู้ทดสอบทุกเครื่องใช้ห้อง 1–4 และ `All` ระหว่างโหมดทดสอบ
- เปลี่ยน Server เริ่มต้นของ Flutter เป็น `ws://100.112.185.32:8181`
- เพิ่มเวอร์ชัน Flutter เป็น 1.3.1 (build 13) และ Docker image เป็น `ptt-relay:1.3.1-test`
- สร้างและตรวจลายเซ็น Release APK `PTT-Flutter-1.3.1-release.apk` สำเร็จ

## 1.3.0-flutter — กำลังพัฒนา — 2026-09-03

- เก็บ Kotlin/Docker รุ่น `v1.2.3-docker` ไว้เป็นรุ่นสำรองที่ใช้งานได้ และแยกงานใหม่ไว้บน branch `codex/flutter-migration`
- สร้าง Flutter app แยกต่างหาก รองรับหน้าหลัก โหมดคุยเต็มหน้าจอ ห้อง 1–4/All สถานะผู้พูด รายชื่อออนไลน์ ตั้งค่า Server/Token/ชื่อ และเลขเวอร์ชัน
- Android ใช้ Kotlin native สำหรับ WebSocket, PCM 16 kHz, ไมโครโฟน, ลำโพง/Bluetooth และ Foreground Service เพื่อรักษาการเชื่อมต่อเมื่อหมุนหน้าจอ
- เก็บ Access Token ใน Android Keystore ผ่าน Secure Storage และใช้ Release signing key เดิมจากไฟล์นอก Git
- เพิ่ม Swift bridge, Apple PushToTalk lifecycle และเสียง PCM สำหรับ iOS โดยคง UI ร่วมไว้ใน Flutter
- เพิ่ม APNs provider แบบเปิดใช้ภายหลังใน Docker Server; เมื่อ `APNS_ENABLED=false` จะไม่เปลี่ยนพฤติกรรม Android เดิม
- แยก Docker image ใหม่เป็น `ptt-relay:1.3.0-flutter` เพื่อไม่เขียนทับ image `1.2.3-docker`

### การตรวจสอบรอบพัฒนา

- `flutter analyze`: ผ่าน ไม่พบปัญหา
- `flutter test`: ผ่านทั้งหมด
- Android native Kotlin Compile: ผ่าน
- Flutter Release APK: สร้างและลงนามสำเร็จ (`PTT-Flutter-1.3.0-release.apk`, versionCode 12) และตรวจสอบ APK Signature Scheme v2 ผ่าน
- `npm test`: ผ่าน (`SECURITY_TESTS_OK`) รวม Authentication, สิทธิ์ All และ APNs token registration
- iOS ยังต้อง Build/Sign บน macOS ด้วย Xcode และทดสอบบน iPhone จริงก่อนนำไปใช้จริง
- APNs ยังต้องใส่ Apple Team ID, Key ID, Bundle ID และไฟล์ `.p8` จริงก่อนทดสอบการปลุกแอปเบื้องหลัง

## 1.2.3-docker — 2026-08-29

- แก้ Gradle validation ที่เกิดจากงานคัดลอก APK เขียนไฟล์กลับเข้าโฟลเดอร์ผลลัพธ์เดิม โดยคัดลอกหลังงาน Assemble เสร็จโดยตรง
- ย้าย APK ที่มีเลขเวอร์ชันในชื่อไปไว้ที่ `app/build/outputs/versioned-apk/` แยกจากผลลัพธ์มาตรฐานของ Android Gradle Plugin
- เพิ่ม Android versionCode เป็น 11 และปรับ Android, Node และ Docker เป็น `1.2.3-docker`
- ไม่เปลี่ยนการทำงานของ PTT, WebSocket, ห้อง, Token หรือ Tailscale

### การตรวจสอบรอบบันทึกเวอร์ชัน

- Android Studio สร้าง Debug APK สำเร็จ (`BUILD SUCCESSFUL`)
- ยืนยัน APK เป็น versionName `1.2.3-docker`, versionCode 11 และ applicationId `com.example.pushtotalk`
- `npm test` ผ่าน (`SECURITY_TESTS_OK`)
- `docker compose config --quiet` ผ่าน

## 1.2.2-docker — 2026-08-29

- พัฒนาต่อจาก `v1.2.0-docker` ตามเวอร์ชันที่เลือกใช้งานบน Docker
- เพิ่ม Android versionCode เป็น 10 และ versionName เป็น `1.2.2-docker`
- แสดงเวอร์ชันและ versionCode จากข้อมูลของ APK ในเมนูตั้งค่าทุก Build
- เมื่อ Build ผ่าน Gradle จะสร้างสำเนา APK ที่มีเวอร์ชันในชื่อไฟล์อัตโนมัติสำหรับ Debug และ Release
- นำการตั้งค่าเซ็น Release ด้วยกุญแจภายนอก Git มาใช้ เพื่อให้คำสั่ง Gradle สร้าง APK ที่เซ็นแล้วเมื่อมี `keystore.properties`
- อัปเดตเลขเวอร์ชันของ Node package, Docker image และเอกสารให้ตรงกัน
- ไม่เปลี่ยน WebSocket, พอร์ต 8181, Token, สิทธิ์ห้อง หรือ Tailscale

### การตรวจสอบรอบบันทึกเวอร์ชัน

- `npm test` ผ่าน (`SECURITY_TESTS_OK`)
- `docker compose config --quiet` ผ่าน โดยไม่ได้เริ่มหรือเปลี่ยน Container
- ตรวจความสอดคล้องของ versionName, versionCode, BuildConfig, Node package และ Docker image ผ่าน
- เครื่องที่ตรวจยังไม่สามารถเริ่ม Gradle Compile ได้เนื่องจาก `Unable to establish loopback connection` จึงยังไม่มี APK `1.2.2-docker` ที่ยืนยันจากการ Build รอบนี้
- เวอร์ชันนี้มีปัญหา Gradle validation ในงาน `copyVersionedDebugApk`; แก้แล้วใน `1.2.3-docker`

## 1.2.0-docker

- เวอร์ชันฐานที่ใช้งานบน Docker: Git tag `v1.2.0-docker`
