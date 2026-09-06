# Changelog

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
