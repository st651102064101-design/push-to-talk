# Push-to-Talk: Wi-Fi + Internet

แอป Android 14+ แบบไม่ต้องล็อกอิน รองรับห้อง 1:1 และกลุ่ม ใช้ relay server เดียวกันทั้งบน Wi-Fi และ Internet โดยผู้พูดในห้องมีได้ทีละคน

## รัน server

```powershell
cd server
npm install
npm start
```

- Android emulator: `ws://10.0.2.2:8181`
- โทรศัพท์บน Wi-Fi เดียวกัน: `ws://<LAN-IP-ของเครื่อง-server>:8181` เช่น `ws://192.168.1.20:8181`
- Internet: นำ `server` ไปโฮสต์บน VPS พร้อม reverse proxy/TLS และใส่ `wss://your-domain` ในแอป

## ดูสถานะผู้เชื่อมต่อ

หลังเริ่ม server ให้เปิด `http://<LAN-IP-ของเครื่อง-server>:8181/` ในเบราว์เซอร์ เช่น `http://192.168.3.96:8181/` หน้า Dashboard จะอัปเดตทุก 2 วินาที และแสดงห้อง, ชื่อผู้ใช้ และผู้ที่กำลังพูด

สำหรับ Internet ต้องใช้ `wss://` เท่านั้น เพราะ `ws://` ไม่เข้ารหัส การตั้งค่า `usesCleartextTraffic` ตอนนี้มีไว้ทดสอบผ่าน LAN เท่านั้น และควรปิดก่อนปล่อย production

เปิดโฟลเดอร์นี้ใน Android Studio, ทำ Gradle Sync, ติดตั้งบน Android 14+ แล้วอนุญาตไมโครโฟนและการแจ้งเตือน

## ข้อควรพัฒนาก่อนเผยแพร่จริง

เวอร์ชันเริ่มต้นส่ง PCM 16 kHz เพื่อให้ทดสอบได้ง่าย ควรเปลี่ยนเป็น WebRTC/Opus, เพิ่มสิทธิ์เข้าห้อง (room secret), TLS, rate limit และ monitoring ก่อนเปิดให้ผู้ใช้สาธารณะใช้งาน
