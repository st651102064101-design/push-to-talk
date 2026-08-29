# Changelog

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
