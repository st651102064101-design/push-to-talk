import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { createApnsNotifier } from "./apns.js";

const port = Number(process.env.PORT || 8181);
const serverVersion = String(process.env.PTT_VERSION || "1.4.0-northflank").trim();
const authRequired = String(process.env.PTT_AUTH_REQUIRED || "true").trim().toLowerCase() !== "false";
const userToken = String(process.env.PTT_USER_TOKEN || "").trim();
const adminToken = String(process.env.PTT_ADMIN_TOKEN || "").trim();
const dashboardUser = String(process.env.PTT_DASHBOARD_USER || "").trim();
const dashboardPassword = String(process.env.PTT_DASHBOARD_PASSWORD || "").trim();
const allowRemoteDashboard = String(process.env.PTT_DASHBOARD_ALLOW_REMOTE || "").trim().toLowerCase() === "true";
const trustProxy = String(process.env.PTT_TRUST_PROXY || "").trim().toLowerCase() === "true";
const socketReplacedCode = 4001;
const dashboardDisconnectedCode = 4002;
const dashboardTemporaryDisconnectedCode = 4003;
const defaultHeartbeatIntervalMs = 30_000;
const defaultMaxConnections = 100;
const defaultMaxConnectionsPerIp = 5;
const runtimeConfig = {
  heartbeatIntervalMs: defaultHeartbeatIntervalMs,
  maxConnections: defaultMaxConnections,
  maxConnectionsPerIp: defaultMaxConnectionsPerIp
};
let apns;
try { apns = createApnsNotifier(); } catch (error) {
  console.error("APNs configuration error:", error.message);
  process.exit(1);
}
const tokenConfigurationUnsafe = userToken.length < 32 || adminToken.length < 32 || userToken === adminToken;
if ((authRequired && tokenConfigurationUnsafe) || dashboardUser.length < 1 || dashboardPassword.length < 16) {
  console.error("Security configuration is missing or unsafe. Configure server/.env from .env.example.");
  process.exit(1);
}
const safeEqual = (actual, expected) => {
  const left = Buffer.from(String(actual));
  const right = Buffer.from(String(expected));
  return left.length === right.length && timingSafeEqual(left, right);
};
const roleFromRequest = request => {
  if (!authRequired) return "admin";
  const authorization = String(request.headers.authorization || "");
  if (!authorization.startsWith("Bearer ")) return null;
  const token = authorization.slice(7);
  if (safeEqual(token, adminToken)) return "admin";
  if (safeEqual(token, userToken)) return "user";
  return null;
};
const dashboardAuthorized = request => {
  const authorization = String(request.headers.authorization || "");
  if (!authorization.startsWith("Basic ")) return false;
  let credentials;
  try { credentials = Buffer.from(authorization.slice(6), "base64").toString("utf8"); } catch { return false; }
  const separator = credentials.indexOf(":");
  if (separator < 0) return false;
  return safeEqual(credentials.slice(0, separator), dashboardUser) && safeEqual(credentials.slice(separator + 1), dashboardPassword);
};
const normalizeIp = value => String(value || "unknown").trim().replace(/^::ffff:/, "").slice(0, 64);
const clientIp = request => {
  if (trustProxy) {
    const forwarded = String(request.headers["x-forwarded-for"] || "").split(",", 1)[0].trim();
    if (forwarded) return normalizeIp(forwarded);
  }
  return normalizeIp(request.socket.remoteAddress);
};
const isLoopback = request => {
  const address = clientIp(request);
  return address === "127.0.0.1" || address === "::1";
};
const rooms = new Map();
const pushRegistrations = new Map();
let allFloor = null;
const roomSettings = new Map([
  ["room-1", { name: "room-1", enabled: true }],
  ["room-2", { name: "room-2", enabled: true }],
  ["room-3", { name: "room-3", enabled: true }],
  ["room-4", { name: "room-4", enabled: true }],
  ["all", { name: "all", enabled: true }]
]);
const normalizeClientId = value => String(value || "").trim().slice(0, 80);
const auditLog = [];
const recordAudit = (action, details) => {
  auditLog.unshift({ at: new Date().toISOString(), action, details: String(details || "").slice(0, 240) });
  if (auditLog.length > 50) auditLog.length = 50;
};
const getRoomConfig = () => [...roomSettings.entries()].map(([id, settings]) => ({ id, name: settings.name, enabled: settings.enabled }));
const getStatus = () => {
  const activeRooms = new Map([...rooms.entries()].map(([id, room]) => [id, room]));
  const items = [...roomSettings.entries()].map(([id, settings]) => {
    const room = activeRooms.get(id);
    return {
      id,
      name: settings.name,
      enabled: settings.enabled,
      peers: room ? [...room.peers].map(peer => ({
        id: peer.id,
        name: peer.name,
        device: peer.device,
        ip: peer.ip,
        connectedMinutes: Math.floor((Date.now() - peer.connectedAt) / 60000),
        talkCount: peer.talkCount,
        speaking: room.floor === peer
      })) : []
    };
  });
  const totalPeers = items.reduce((total, room) => total + room.peers.length, 0);
  return {
    version: serverVersion,
    totalPeers,
    rooms: items,
    config: {
      heartbeatIntervalMs: runtimeConfig.heartbeatIntervalMs,
      maxConnections: runtimeConfig.maxConnections,
      maxConnectionsPerIp: runtimeConfig.maxConnectionsPerIp,
      authRequired,
      audit: auditLog.slice(0, 20),
      rooms: getRoomConfig()
    }
  };
};
const dashboard = `<!doctype html>
<html lang="th">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>PTT Dashboard</title>
  <style>
    :root{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#182230;background:#f6f8fb}
    body{max-width:960px;margin:32px auto;padding:0 18px}
    h1{margin:0 0 4px;font-size:2rem}h2{margin:0 0 14px;font-size:1.25rem}
    .muted{color:#617083}.card{background:#fff;border:1px solid #dce2ea;border-radius:12px;margin:16px 0;padding:18px;box-shadow:0 2px 8px #00000008}
    .toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px 0}.toolbar input{flex:1;min-width:220px}
    input,button{font:inherit;border:1px solid #c5ced9;border-radius:7px;padding:9px 11px}button{background:#1677d2;color:#fff;border-color:#1677d2;cursor:pointer;font-weight:650}button.secondary{background:#fff;color:#182230}button.danger{background:#c62828;border-color:#c62828;padding:6px 10px;font-size:.84rem}button.no-reconnect{background:#8b1a1a;border-color:#8b1a1a;padding:6px 10px;font-size:.84rem}button:disabled{opacity:.55;cursor:not-allowed}
    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}.stat{background:#f4f7fb;border-radius:8px;padding:11px}.stat b{display:block;font-size:1.1rem;margin-top:3px}
    .peer{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid #edf0f4}.peer:first-child{border-top:0}.peer-main{flex:1;min-width:0}.peer-name{font-weight:700}.details{color:#617083;font-size:.9rem;line-height:1.5}.state{display:inline-block;margin-left:8px;padding:3px 9px;border-radius:999px;font-size:.75rem;font-weight:700}.speaking{background:#fee4e2;color:#b42318}.ready{background:#dcfae6;color:#067647}.empty{padding:24px;text-align:center;color:#617083}.room-disabled{opacity:.65}.config-row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:10px 0}.config-row label{display:flex;flex-direction:column;gap:5px;font-size:.9rem;color:#617083}.config-row input{color:#182230}.room-config{display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:center;padding:8px 0}.room-config input[type=text]{min-width:0}.notice{margin:10px 0;padding:9px 11px;border-radius:7px;background:#edf7ff;color:#17527c}.error{background:#fff0f0;color:#a52222}.audit{padding-left:20px;margin:0}.audit li{margin:7px 0;color:#617083;font-size:.88rem}
    @media(max-width:560px){.config-row{grid-template-columns:1fr}.peer{align-items:flex-start;flex-wrap:wrap}.peer button{margin-left:auto}}
  </style>
</head>
<body>
  <h1>PTT ออนไลน์</h1>
  <p class="muted" id="updated">กำลังโหลด…</p>
  <div id="notice" hidden></div>
  <section class="card"><h2>ภาพรวมระบบ</h2><div id="stats" class="grid"></div></section>
  <section class="card"><h2>จัดการอุปกรณ์</h2><div class="toolbar"><input id="search" type="search" placeholder="ค้นหาชื่อหรืออุปกรณ์"><button id="refresh" class="secondary">รีเฟรช</button></div><main id="rooms"></main></section>
  <section class="card"><h2>ตั้งค่าการทำงาน</h2><p class="muted">ค่าต่อไปนี้มีผลทันทีและจะกลับค่าเริ่มต้นเมื่อรีสตาร์ตเซิร์ฟเวอร์ ค่าลับยังคงอยู่ในตัวแปรสภาพแวดล้อมของ Render</p><div class="config-row"><label>Heartbeat (วินาที)<input id="heartbeat" type="number" min="5" max="120" step="1"></label><label>จำนวนการเชื่อมต่อสูงสุด<input id="maxConnections" type="number" min="1" max="1000" step="1"></label><label>จำนวนต่อ IP สูงสุด<input id="maxConnectionsPerIp" type="number" min="1" max="100" step="1"></label></div><h3>ห้อง</h3><div id="roomConfig"></div><button id="saveConfig">บันทึกการตั้งค่า</button></section>
  <section class="card"><h2>ประวัติการจัดการ</h2><ul id="audit" class="audit"></ul></section>
  <script>
    const roomsTarget=document.querySelector('#rooms'),updated=document.querySelector('#updated'),notice=document.querySelector('#notice'),search=document.querySelector('#search');
    let latest=null;
    function showNotice(message,error){notice.hidden=false;notice.textContent=message;notice.className=error?'notice error':'notice';setTimeout(()=>{notice.hidden=true},5000)}
    function drawStats(data){const c=data.config;document.querySelector('#stats').innerHTML='<div class="stat">เวอร์ชัน<b>'+data.version+'</b></div><div class="stat">ออนไลน์<b>'+data.totalPeers+' เครื่อง</b></div><div class="stat">Heartbeat<b>'+(c.heartbeatIntervalMs/1000)+' วินาที</b></div><div class="stat">จำกัดการเชื่อมต่อ<b>'+c.maxConnections+' เครื่อง</b></div><div class="stat">ต่อ IP สูงสุด<b>'+c.maxConnectionsPerIp+' เครื่อง</b></div>'}
    function drawRooms(data){const query=search.value.trim().toLowerCase();roomsTarget.replaceChildren();let shown=0;for(const room of data.rooms){const peers=room.peers.filter(peer=>(peer.name+' '+(peer.device||'')+' '+peer.ip).toLowerCase().includes(query));if(query&&!peers.length)continue;shown++;const card=document.createElement('section');card.className='card '+(!room.enabled?'room-disabled':'');const h=document.createElement('h3');h.textContent='ห้อง: '+room.name+' ('+room.id+')'+(!room.enabled?' · ปิดใช้งาน':'');card.append(h);if(!peers.length){const empty=document.createElement('div');empty.className='empty';empty.textContent=room.enabled?'ยังไม่มีผู้ใช้เข้าห้อง':'ห้องนี้ถูกปิดใช้งาน';card.append(empty)}else{for(const peer of peers){const item=document.createElement('div');item.className='peer';const main=document.createElement('div');main.className='peer-main';const title=document.createElement('div');title.className='peer-name';title.textContent=peer.name||'Guest';const state=document.createElement('span');state.className='state '+(peer.speaking?'speaking':'ready');state.textContent=peer.speaking?'กำลังพูด':'พร้อม';title.append(state);const details=document.createElement('div');details.className='details';details.textContent='อุปกรณ์: '+(peer.device||'ไม่ทราบ')+' · IP: '+peer.ip+' · เชื่อมต่อ: '+peer.connectedMinutes+' นาที · กดพูด: '+peer.talkCount+' ครั้ง';main.append(title,details);const temporaryButton=document.createElement('button');temporaryButton.className='danger';temporaryButton.textContent='ตัดชั่วคราว';temporaryButton.addEventListener('click',()=>disconnectPeer(peer.id,peer.name||'Guest',false));const noReconnectButton=document.createElement('button');noReconnectButton.className='no-reconnect';noReconnectButton.textContent='ตัดการเชื่อมต่อ (None reconnect)';noReconnectButton.addEventListener('click',()=>disconnectPeer(peer.id,peer.name||'Guest',true));item.append(main,temporaryButton,noReconnectButton);card.append(item)}}roomsTarget.append(card)}if(!shown)roomsTarget.innerHTML='<div class="empty">ไม่พบอุปกรณ์ที่ค้นหา</div>'}
    function drawConfig(data){const c=data.config;document.querySelector('#heartbeat').value=Math.round(c.heartbeatIntervalMs/1000);document.querySelector('#maxConnections').value=c.maxConnections;document.querySelector('#maxConnectionsPerIp').value=c.maxConnectionsPerIp;const target=document.querySelector('#roomConfig');target.replaceChildren();for(const room of c.rooms){const row=document.createElement('label');row.className='room-config';const enabled=document.createElement('input');enabled.type='checkbox';enabled.checked=room.enabled;enabled.dataset.roomId=room.id;const name=document.createElement('input');name.type='text';name.maxLength=40;name.value=room.name;name.dataset.roomId=room.id;const id=document.createElement('span');id.textContent=room.id;row.append(enabled,name,id);target.append(row)}}
    function drawAudit(data){const target=document.querySelector('#audit');target.replaceChildren();if(!data.config.audit.length){target.innerHTML='<li>ยังไม่มีรายการ</li>';return}for(const item of data.config.audit){const li=document.createElement('li');li.textContent=new Date(item.at).toLocaleString()+' · '+item.action+' · '+item.details;target.append(li)}}
    function draw(data){latest=data;drawStats(data);drawRooms(data);drawConfig(data);drawAudit(data);updated.textContent='อัปเดตล่าสุด: '+new Date().toLocaleTimeString()+' · ออนไลน์ '+data.totalPeers+' เครื่อง'}
    async function refresh(){try{const response=await fetch('/status',{cache:'no-store'});if(!response.ok)throw new Error('โหลดสถานะไม่สำเร็จ');draw(await response.json())}catch(error){updated.textContent=error.message;showNotice('ไม่สามารถเชื่อมต่อ Dashboard ได้',true)}}
    async function disconnectPeer(peerId,name,noReconnect){const action=noReconnect?'ตัดการเชื่อมต่อ (None reconnect)':'ตัดการเชื่อมต่อชั่วคราว';if(!confirm(action+' '+name+' ใช่หรือไม่?'))return;try{const response=await fetch(noReconnect?'/admin/disconnect-no-reconnect':'/admin/disconnect',{method:'POST',headers:{'Content-Type':'application/json','X-PTT-Dashboard':'1'},body:JSON.stringify({peerId})});const result=await response.json();if(!response.ok)throw new Error(result.message||'ตัดการเชื่อมต่อไม่สำเร็จ');showNotice(action+' '+name+' แล้ว');refresh()}catch(error){showNotice(error.message,true)}}
    async function saveConfig(){const rooms=[...document.querySelectorAll('#roomConfig .room-config')].map(row=>({id:row.querySelector('input[type=checkbox]').dataset.roomId,enabled:row.querySelector('input[type=checkbox]').checked,name:row.querySelector('input[type=text]').value}));const payload={heartbeatIntervalMs:Number(document.querySelector('#heartbeat').value)*1000,maxConnections:Number(document.querySelector('#maxConnections').value),maxConnectionsPerIp:Number(document.querySelector('#maxConnectionsPerIp').value),rooms};try{const response=await fetch('/admin/config',{method:'POST',headers:{'Content-Type':'application/json','X-PTT-Dashboard':'1'},body:JSON.stringify(payload)});const result=await response.json();if(!response.ok)throw new Error(result.message||'บันทึกไม่สำเร็จ');showNotice('บันทึกการตั้งค่าแล้ว');draw(result);refresh()}catch(error){showNotice(error.message,true)}}
    search.addEventListener('input',()=>{if(latest)drawRooms(latest)});document.querySelector('#refresh').addEventListener('click',refresh);document.querySelector('#saveConfig').addEventListener('click',saveConfig);refresh();setInterval(refresh,2000);
  </script>
</body>
</html>`;
const jsonResponse = (res, status, value) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
  return res.end(JSON.stringify(value));
};
const readJsonBody = (req, maxBytes = 16 * 1024) => new Promise((resolve, reject) => {
  let size = 0;
  const chunks = [];
  req.on("data", chunk => {
    size += chunk.length;
    if (size > maxBytes) {
      const error = new Error("Request body is too large");
      error.code = "too_large";
      reject(error);
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });
  req.on("end", () => {
    try {
      const text = Buffer.concat(chunks).toString("utf8");
      resolve(text ? JSON.parse(text) : {});
    } catch {
      const error = new Error("Invalid JSON body");
      error.code = "invalid_json";
      reject(error);
    }
  });
  req.on("error", reject);
});
const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`).pathname;
  if (req.url === "/healthz") {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
    return res.end(JSON.stringify({ status: "ok", version: serverVersion }));
  }
  if (!allowRemoteDashboard && !isLoopback(req)) {
    res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" });
    return res.end("Dashboard is available on localhost only");
  }
  if (!dashboardAuthorized(req)) {
    res.writeHead(401, { "WWW-Authenticate": "Basic realm=\"PTT Dashboard\", charset=\"UTF-8\"", "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" });
    return res.end("Authentication required");
  }
  if (pathname === "/status" && req.method === "GET") return jsonResponse(res, 200, getStatus());
  if ((pathname === "/admin/disconnect" || pathname === "/admin/disconnect-no-reconnect") && req.method === "POST") {
    if (req.headers["x-ptt-dashboard"] !== "1") return jsonResponse(res, 400, { message: "Dashboard action header is required" });
    try {
      const body = await readJsonBody(req);
      const peerId = String(body?.peerId || "").trim();
      if (!peerId || peerId.length > 80) return jsonResponse(res, 400, { message: "ระบุอุปกรณ์ที่ต้องการตัดการเชื่อมต่อ" });
      const peer = [...wss.clients].find(candidate => candidate.id === peerId);
      if (!peer) return jsonResponse(res, 404, { message: "ไม่พบอุปกรณ์นี้ อาจตัดการเชื่อมต่อไปแล้ว" });
      const description = `${peer.name || "Guest"} (${peer.device || "Unknown"})`;
      const noReconnect = pathname === "/admin/disconnect-no-reconnect";
      leave(peer);
      if (peer.readyState === WebSocket.OPEN || peer.readyState === WebSocket.CONNECTING) peer.close(noReconnect ? dashboardDisconnectedCode : dashboardTemporaryDisconnectedCode, noReconnect ? "disconnected by dashboard without reconnect" : "temporarily disconnected by dashboard");
      else peer.terminate();
      recordAudit(noReconnect ? "ตัดการเชื่อมต่อ (None reconnect)" : "ตัดการเชื่อมต่อชั่วคราว", description);
      return jsonResponse(res, 200, { ok: true, status: getStatus() });
    } catch (error) {
      const status = error.code === "too_large" ? 413 : 400;
      return jsonResponse(res, status, { message: error.code === "too_large" ? "ข้อมูลมีขนาดใหญ่เกินไป" : "ข้อมูลไม่ถูกต้อง" });
    }
  }
  if (pathname === "/admin/config" && req.method === "POST") {
    if (req.headers["x-ptt-dashboard"] !== "1") return jsonResponse(res, 400, { message: "Dashboard action header is required" });
    try {
      const body = await readJsonBody(req);
      const heartbeat = Number(body?.heartbeatIntervalMs);
      const maxConnections = Number(body?.maxConnections);
      const maxConnectionsPerIp = Number(body?.maxConnectionsPerIp);
      if (!Number.isInteger(heartbeat) || heartbeat < 5_000 || heartbeat > 120_000) throw new Error("Heartbeat must be between 5 and 120 seconds");
      if (!Number.isInteger(maxConnections) || maxConnections < 1 || maxConnections > 1_000) throw new Error("Maximum connections is invalid");
      if (!Number.isInteger(maxConnectionsPerIp) || maxConnectionsPerIp < 1 || maxConnectionsPerIp > 100) throw new Error("Maximum connections per IP is invalid");
      if (!Array.isArray(body?.rooms) || body.rooms.length !== roomSettings.size) throw new Error("Room configuration is incomplete");
      const nextRooms = new Map();
      for (const item of body.rooms) {
        const id = String(item?.id || "").trim();
        if (!roomSettings.has(id) || nextRooms.has(id)) throw new Error("Room configuration is invalid");
        const name = String(item?.name || "").trim().slice(0, 40);
        if (!name) throw new Error("Room name cannot be empty");
        nextRooms.set(id, { name, enabled: item.enabled !== false });
      }
      runtimeConfig.heartbeatIntervalMs = heartbeat;
      runtimeConfig.maxConnections = maxConnections;
      runtimeConfig.maxConnectionsPerIp = maxConnectionsPerIp;
      for (const [id, settings] of nextRooms) roomSettings.set(id, settings);
      restartHeartbeatTimer();
      recordAudit("บันทึกการตั้งค่า", `heartbeat ${heartbeat / 1000}s · สูงสุด ${maxConnections} เครื่อง · ต่อ IP ${maxConnectionsPerIp} เครื่อง`);
      return jsonResponse(res, 200, getStatus());
    } catch (error) {
      const status = error.code === "too_large" ? 413 : 400;
      return jsonResponse(res, status, { message: error.code === "too_large" ? "ข้อมูลมีขนาดใหญ่เกินไป" : error.message || "การตั้งค่าไม่ถูกต้อง" });
    }
  }
  if (pathname === "/" || pathname === "/index.html") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" }); return res.end(dashboard); }
  res.writeHead(404); res.end("Not found");
});
const wss = new WebSocketServer({
  server,
  maxPayload: 8 * 1024,
  perMessageDeflate: false,
  verifyClient: ({ req, origin }, done) => {
    const role = roleFromRequest(req);
    const ip = clientIp(req);
    if (!role) { console.warn(`Rejected unauthenticated WebSocket from ${ip}`); return done(false, 401, "Unauthorized"); }
    if (origin) { console.warn(`Rejected browser WebSocket origin ${origin} from ${ip}`); return done(false, 403, "Browser connections are not allowed"); }
    const connectionsFromIp = [...wss.clients].filter(peer => peer.ip === ip).length;
    if (wss.clients.size >= runtimeConfig.maxConnections || connectionsFromIp >= runtimeConfig.maxConnectionsPerIp) return done(false, 429, "Too many connections");
    done(true);
  }
});
const emit = (room, value, except) => { const text = JSON.stringify(value); for (const p of room.peers) if (p !== except && p.readyState === WebSocket.OPEN) p.send(text); };
const emitEverywhere = (value, except) => { for (const room of rooms.values()) emit(room, value, except); };
const registerPushToken = (peer, token) => {
  const normalized = String(token || "").trim().toLowerCase();
  if (!/^[a-f0-9]{32,400}$/.test(normalized) || !peer.roomId) return false;
  if (peer.pushToken && peer.pushToken !== normalized) pushRegistrations.delete(peer.pushToken);
  peer.pushToken = normalized;
  pushRegistrations.set(normalized, { roomId: peer.roomId, name: peer.name, peerId: peer.id, updatedAt: Date.now() });
  return true;
};
const unregisterPushToken = peer => {
  if (peer.pushToken) pushRegistrations.delete(peer.pushToken);
  peer.pushToken = null;
};
const notifyPushTargets = (roomId, speaker, exceptPeer) => {
  if (!apns.enabled) return;
  const now = Date.now();
  for (const [token, registration] of pushRegistrations) {
    if (now - registration.updatedAt > 24 * 60 * 60 * 1000) { pushRegistrations.delete(token); continue; }
    const visible = roomId === "all" || registration.roomId === roomId;
    if (!visible || registration.peerId === exceptPeer.id) continue;
    apns.send(token, { activeSpeaker: speaker, room: roomId }).then(result => {
      if (result.status === 410 || result.reason === "BadDeviceToken" || result.reason === "Unregistered") {
        pushRegistrations.delete(token);
      } else if (!result.ok) {
        console.warn(`APNs delivery failed (${result.status || "network"}${result.reason ? `: ${result.reason}` : ""})`);
      }
    }).catch(error => console.warn("APNs delivery failed:", error.message));
  }
};
function broadcastMembers() {
  const allPeers = [...rooms.values()].flatMap(room => [...room.peers]);
  for (const viewer of allPeers) {
    const visible = viewer.roomId === "all" ? allPeers : [...viewer.room.peers];
    const members = visible.map(peer => ({ name: peer.name, room: peer.roomId, self: peer === viewer }));
    if (viewer.readyState === WebSocket.OPEN) viewer.send(JSON.stringify({ type: "members", allView: viewer.roomId === "all", members }));
  }
}
function release(room, peer) {
  if (room?.floor !== peer) return;
  room.floor = null;
  if (allFloor === peer) { allFloor = null; emitEverywhere({ type: "speaker", active: false }); }
  else emit(room, { type: "speaker", active: false });
}
function leave(peer) {
  const room = peer.room;
  if (!room) return;
  const previousRoomId = peer.roomId;
  release(room, peer); room.peers.delete(peer);
  peer.room = null; peer.roomId = null;
  if (!room.peers.size) rooms.delete(previousRoomId);
  broadcastMembers();
}

wss.on("connection", (peer, request) => {
  peer.id = randomUUID();
  peer.ip = clientIp(request);
  peer.role = roleFromRequest(request);
  peer.connectedAt = Date.now();
  peer.talkCount = 0;
  peer.clientId = "";
  peer.isAlive = true;
  peer.on("pong", () => { peer.isAlive = true; });
  peer.send(JSON.stringify({ type: "authenticated", role: peer.role }));
  peer.on("message", (data, binary) => {
    if (binary) {
      if (peer.room?.floor === peer) {
        peer.expires = Date.now() + 2000;
        const targets = peer.roomId === "all" ? rooms.values() : [peer.room];
        for (const room of targets) for (const p of room.peers) if (p !== peer && p.readyState === WebSocket.OPEN) p.send(data, { binary: true });
      }
      return;
    }
    let msg; try { msg = JSON.parse(data.toString()); } catch { peer.send(JSON.stringify({ type: "error", message: "invalid message" })); return; }
    if (msg.type === "heartbeat") {
      if (peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({ type: "heartbeat", ts: Number.isFinite(msg.ts) ? msg.ts : Date.now() }));
      }
    } else if (msg.type === "join") {
      const id = String(msg.room || "").trim().slice(0, 40);
      const roomSettingsForId = roomSettings.get(id);
      if (!roomSettingsForId || !roomSettingsForId.enabled) { peer.send(JSON.stringify({ type: "error", code: "invalid_room", message: "ไม่พบห้องที่ระบุหรือห้องถูกปิดใช้งาน" })); return; }
      if (id === "all" && peer.role !== "admin") { peer.send(JSON.stringify({ type: "error", code: "forbidden_all", message: "Token นี้ไม่มีสิทธิ์ใช้งาน All" })); return; }
      const clientId = normalizeClientId(msg.clientId);
      if (clientId) {
        for (const candidate of wss.clients) {
          if (candidate !== peer && candidate.clientId === clientId) {
            leave(candidate);
            candidate.close(socketReplacedCode, "replaced by newer connection");
          }
        }
      }
      leave(peer); const room = rooms.get(id) || { peers: new Set(), floor: null }; rooms.set(id, room); peer.room = room; peer.roomId = id; peer.clientId = clientId; peer.name = String(msg.name || "Guest").slice(0, 30); peer.device = String(msg.device || "Unknown").slice(0, 80); room.peers.add(peer);
      if (peer.pushToken) registerPushToken(peer, peer.pushToken);
      peer.send(JSON.stringify({ type: "joined", room: id, id: peer.id, role: peer.role }));
      broadcastMembers();
    } else if (msg.type === "register-push") {
      const registered = registerPushToken(peer, msg.token);
      peer.send(JSON.stringify({ type: "push-registered", enabled: apns.enabled, registered }));
    } else if (msg.type === "unregister-push") {
      unregisterPushToken(peer);
      peer.send(JSON.stringify({ type: "push-unregistered" }));
    } else if (msg.type === "request-talk" && peer.room) {
      const isAll = peer.roomId === "all";
      const roomBusy = peer.room.floor && peer.room.floor !== peer;
      const anotherRoomBusy = isAll && [...rooms.values()].some(room => room !== peer.room && room.floor);
      if (roomBusy || (allFloor && allFloor !== peer) || anotherRoomBusy) {
        const activePeer = allFloor || peer.room.floor || [...rooms.values()].map(room => room.floor).find(Boolean);
        peer.send(JSON.stringify({ type: "floor-busy", name: activePeer?.name || "ผู้ใช้อื่น" }));
      }
      else {
        peer.room.floor = peer; peer.expires = Date.now() + 2000; peer.talkCount += 1;
        if (isAll) allFloor = peer;
        peer.send(JSON.stringify({ type: "floor-granted" }));
        if (isAll) emitEverywhere({ type: "speaker", active: true, name: peer.name }, peer);
        else emit(peer.room, { type: "speaker", active: true, name: peer.name }, peer);
        notifyPushTargets(peer.roomId, peer.name, peer);
      }
    } else if (msg.type === "release-talk") release(peer.room, peer);
  });
  peer.on("close", () => leave(peer));
});
let heartbeatTimer;
const restartHeartbeatTimer = () => {
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = setInterval(() => {
  for (const peer of wss.clients) {
    if (peer.isAlive === false) {
      peer.terminate();
      continue;
    }
    peer.isAlive = false;
    peer.ping();
  }
  }, runtimeConfig.heartbeatIntervalMs);
  heartbeatTimer.unref?.();
};
restartHeartbeatTimer();
setInterval(() => { for (const room of rooms.values()) if (room.floor?.expires < Date.now()) release(room, room.floor); }, 1000);
server.listen(port, () => {
  console.log("PTT relay and dashboard listening on port " + port);
  console.log("PTT Server version: " + serverVersion);
  if (authRequired) console.log("PTT Access Token authentication: enabled");
  else console.warn("PTT Access Token authentication: DISABLED (testing only)");
  console.log(`Apple Push to Talk notifications: ${apns.enabled ? "enabled" : "disabled"}`);
  if (allowRemoteDashboard) console.warn("Dashboard remote access is enabled; protect port 8181 and keep Dashboard credentials private.");
  if (trustProxy) console.log("Trusted reverse proxy client IP headers: enabled");
});
const shutdown = () => { clearInterval(heartbeatTimer); apns.close(); server.close(() => process.exit(0)); };
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
