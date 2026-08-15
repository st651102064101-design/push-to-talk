# Tailscale access control for PTT

สถานะที่ตรวจจากเครื่อง Server เมื่อ 15 สิงหาคม 2026 คือ `NeedsLogin` และ `Tailscale is stopped` จึงยังไม่สามารถ Apply policy เข้าบัญชีจริงโดยอัตโนมัติได้

## วิธีนำ Grants ไปใช้

1. Login Tailscale บน Server และอุปกรณ์ Android ทุกเครื่อง
2. ใน Tailscale Admin Console กำหนด tag `tag:ptt-server` ให้เครื่อง Server
3. กำหนด tag `tag:ptt-client` เฉพาะโทรศัพท์/Zebra ที่อนุญาตให้ใช้ PTT
4. เปิด **Access controls** แล้วผสานเนื้อหาจาก `ptt-grants.fragment.hujson` เข้ากับ policy เดิม
5. ใช้ Preview/Tests ของ Tailscale ตรวจว่า client ที่มี tag เข้าถึง `tag:ptt-server:8181` ได้ และอุปกรณ์อื่นเข้าไม่ได้ ก่อนกด Save

ข้อควรระวัง: Tailscale Grants ไม่ควบคุมการเชื่อมต่อผ่าน LAN โดยตรง ควรใช้ Windows Firewall จำกัด TCP 8181 บน Wi-Fi/LAN เพิ่มเติม หรือให้อุปกรณ์เชื่อมผ่าน Tailscale IP เท่านั้น
