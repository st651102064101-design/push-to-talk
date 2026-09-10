# Push-to-Talk: Wi-Fi + Internet

รุ่นใช้งานจริงปัจจุบัน: **1.2.3-docker** (Kotlin, Android versionCode: 11)

รุ่นทดลองข้ามแพลตฟอร์ม: **1.4.1** (Flutter, build 15) อยู่ในโฟลเดอร์ `flutter_app` และใช้ Server เริ่มต้น `wss://pttrelay-vwzw2gr4.b4a.run` รุ่น Kotlin `v1.2.3-docker` และ Flutter รุ่นก่อนหน้ายังเก็บไว้และไม่ได้ถูกเขียนทับ

Docker Server สำหรับ Cloud: **1.4.0-northflank** ทำงานบน Back4App ผ่าน HTTPS/WSS โดยเปิด Authentication เป็นค่าเริ่มต้น รายละเอียดการ Deploy อยู่ที่ [server/README.md](server/README.md)

แอปจะแสดงเลขเวอร์ชันและ versionCode ในเมนูตั้งค่าจากข้อมูลของ APK โดยตรง เมื่อ Build ด้วย Gradle ระบบจะสร้างสำเนา APK ที่มีเลขเวอร์ชันในชื่อไฟล์ให้อัตโนมัติ เช่น `PTT-1.2.3-docker-debug.apk` หรือ `PTT-1.2.3-docker-release.apk`

ประวัติการเปลี่ยนแปลง: [CHANGELOG.md](CHANGELOG.md)

แผนและวิธี Build รุ่น Flutter: [flutter_app/README.md](flutter_app/README.md)

## Build APK พร้อมเลขเวอร์ชัน

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

- Debug: `app/build/outputs/versioned-apk/debug/PTT-1.2.3-docker-debug.apk`
- Release ที่เซ็นแล้ว: `app/build/outputs/versioned-apk/release/PTT-1.2.3-docker-release.apk`
- หาก Release ยังไม่ได้เซ็น ระบบจะใช้คำว่า `release-unsigned` ในชื่อไฟล์ ห้ามนำไฟล์ดังกล่าวไปติดตั้งใช้งานจริง
- Android Studio อาจแสดงไฟล์ชื่อมาตรฐานควบคู่กัน แต่เลขเวอร์ชันภายในแอปยังอ่านจาก APK โดยตรงเสมอ

แอป Android 14+ ใช้ Token Authentication รองรับห้อง 1–4 และห้อง `All` สำหรับ Admin ใช้ relay server เดียวกันทั้งบน Wi-Fi และ Internet โดยผู้พูดในห้องมีได้ทีละคน

## รัน server

แนะนำให้ดับเบิลคลิก `Start-PTT-Server.bat` ไฟล์นี้จะสร้าง Secret ใน `server/.env`, เปิด Relay และเปิด Dashboard ให้อัตโนมัติ

การรันด้วย PowerShell:

```powershell
cd server
Copy-Item .env.example .env
# แก้ค่า Token/Password ใน .env ให้เป็นค่าสุ่มที่ไม่ซ้ำกันก่อนเริ่ม Server
npm install
npm start
```

ห้าม commit หรือส่งไฟล์ `server/.env` ให้บุคคลอื่น

- Android emulator: `ws://10.0.2.2:8181`
- โทรศัพท์บน Wi-Fi เดียวกัน: `ws://<LAN-IP-ของเครื่อง-server>:8181` เช่น `ws://192.168.1.20:8181`
- Internet: นำ `server` ไปโฮสต์บน VPS พร้อม reverse proxy/TLS และใส่ `wss://your-domain` ในแอป

## รันด้วย Docker

ต้องติดตั้ง Docker Desktop และเปิดให้ Docker Engine พร้อมทำงานก่อน จากนั้นดับเบิลคลิก `Start-PTT-Docker.bat` โปรแกรมจะตรวจหรือสร้าง `server/.env`, Build image, เปิด Container และเปิด Dashboard ให้อัตโนมัติ

หรือรันด้วย PowerShell จากโฟลเดอร์หลัก:

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f ptt-server
```

หยุดและลบ Container โดยไม่ลบ Token ใน `server/.env`:

```powershell
docker compose down
```

- Relay: `ws://<IP-ของเครื่อง-Docker>:8181`
- Dashboard บนเครื่อง Server: `http://localhost:8181/`
- ค่า Secret อ่านจาก `server/.env` ตอนเริ่ม Container และไม่ถูกคัดลอกเข้า Docker image
- Container ทำงานด้วยผู้ใช้ที่ไม่ใช่ root, filesystem แบบ read-only, ตัด Linux capabilities และมี Health Check
- Docker bridge ทำให้ Server แยก `localhost` ไม่ได้ จึงเปิด Dashboard ให้เข้าจาก network แต่ยังบังคับ Username/Password ควรใช้ Firewall/Tailscale จำกัดพอร์ต 8181 เฉพาะอุปกรณ์ที่อนุญาต

## Authentication และสิทธิ์

- รุ่น Flutter `1.4.1` เปิดช่อง Access Token และเชื่อมต่อ Back4App ผ่าน `wss://` เป็นค่าเริ่มต้น
- `PTT_USER_TOKEN`: ใช้กับอุปกรณ์ทั่วไป เข้าได้เฉพาะห้อง 1–4
- `PTT_ADMIN_TOKEN`: ใช้เฉพาะผู้ดูแล เข้าได้ทั้งห้อง 1–4 และ `All`
- กรอก Token ที่เมนูตั้งค่าของ Android App; Token ถูกเข้ารหัสด้วย Android Keystore
- Dashboard ใช้ `PTT_DASHBOARD_USER` และ `PTT_DASHBOARD_PASSWORD`

## ดูสถานะผู้เชื่อมต่อ

หลังเริ่ม Server ให้เปิด `http://localhost:8181/` และใส่ Username/Password ที่ Batch แสดง หน้า Dashboard จะแสดงห้อง ชื่อผู้ใช้ และผู้ที่กำลังพูด

## Tailscale Grants

ไฟล์ `tailscale/ptt-grants.fragment.hujson` จำกัด TCP 8181 ให้เฉพาะอุปกรณ์ `tag:ptt-client` ติดต่อ `tag:ptt-server` อ่านขั้นตอนใน `tailscale/README.md` และผสานกับ Policy เดิมโดยไม่ลบ Grants ของระบบอื่น

สำหรับ Internet ต้องใช้ `wss://` เท่านั้น เพราะ `ws://` ไม่เข้ารหัส การตั้งค่า `usesCleartextTraffic` ตอนนี้มีไว้ทดสอบผ่าน LAN เท่านั้น และควรปิดก่อนปล่อย production

เปิดโฟลเดอร์นี้ใน Android Studio, ทำ Gradle Sync, ติดตั้งบน Android 14+ แล้วอนุญาตไมโครโฟนและการแจ้งเตือน

## ข้อควรพัฒนาก่อนเปิดสาธารณะ

ระบบยังใช้ `ws://` และส่ง PCM 16 kHz จึงควรใช้งานผ่าน Tailscale เท่านั้น หากจะเปิดออก Internet สาธารณะต้องเพิ่ม TLS (`wss://`), monitoring และ rate limit สำหรับ audio เพิ่มเติม
