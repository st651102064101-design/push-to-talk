# Push-to-Talk: Wi-Fi + Internet

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

## Authentication และสิทธิ์

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
