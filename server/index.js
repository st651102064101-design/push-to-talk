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
const heartbeatIntervalMs = 30_000;
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
const allowedRooms = new Set(["room-1", "room-2", "room-3", "room-4", "all"]);
const dashboard = `<!doctype html><html lang="th"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PTT status</title><style>body{font-family:system-ui,sans-serif;background:#f6f8fb;color:#182230;max-width:760px;margin:40px auto;padding:0 20px}h1{margin-bottom:4px}.muted{color:#617083}.card{background:#fff;border:1px solid #dce2ea;border-radius:12px;margin:16px 0;padding:16px;box-shadow:0 2px 8px #00000008}ul{padding-left:0;list-style:none}.peer{padding:12px 0;border-top:1px solid #edf0f4}.peer:first-child{border-top:0}.details{color:#617083;font-size:.9rem;line-height:1.55}.state{display:inline-block;margin-left:10px;padding:3px 9px;border-radius:999px;font-size:.75rem;font-weight:700}.speaking{background:#fee4e2;color:#b42318}.ready{background:#dcfae6;color:#067647}.empty{padding:24px;text-align:center}</style></head><body><h1>PTT ออนไลน์</h1><p class="muted" id="updated">กำลังโหลด…</p><main id="rooms"></main><script>const target=document.querySelector('#rooms'),updated=document.querySelector('#updated');function draw(data){updated.textContent='เวอร์ชัน '+data.version+' · อัปเดตล่าสุด: '+new Date().toLocaleTimeString()+' · ออนไลน์ '+data.totalPeers+' เครื่อง';target.replaceChildren();if(!data.rooms.length){target.innerHTML='<section class="card empty">ยังไม่มีผู้ใช้เข้าห้อง</section>';return}for(const room of data.rooms){const card=document.createElement('section');card.className='card';const h=document.createElement('h2');h.textContent='ห้อง: '+room.id;card.append(h);const list=document.createElement('ul');for(const peer of room.peers){const item=document.createElement('li');item.className='peer';const title=document.createElement('strong');title.textContent=peer.name;const state=document.createElement('span');state.className='state '+(peer.speaking?'speaking':'ready');state.textContent=peer.speaking?'กำลังพูด':'พร้อม';const details=document.createElement('div');details.className='details';details.textContent='อุปกรณ์: '+(peer.device||'ไม่ทราบ')+' · IP: '+peer.ip+' · เชื่อมต่อ: '+peer.connectedMinutes+' นาที · กดพูด: '+peer.talkCount+' ครั้ง';item.append(title,state,details);list.append(item)}card.append(list);target.append(card)}}async function refresh(){try{draw(await fetch('/status',{cache:'no-store'}).then(r=>r.json()))}catch{updated.textContent='ไม่สามารถเชื่อมต่อ server ได้'}}refresh();setInterval(refresh,250)</script></body></html>`;
const server = http.createServer((req, res) => {
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
  if (req.url === "/status") {
    const items = [...rooms.entries()].map(([id, room]) => ({ id, peers: [...room.peers].map(peer => ({ name: peer.name, device: peer.device, ip: peer.ip, connectedMinutes: Math.floor((Date.now() - peer.connectedAt) / 60000), talkCount: peer.talkCount, speaking: room.floor === peer })) }));
    const totalPeers = items.reduce((total, room) => total + room.peers.length, 0);
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
    return res.end(JSON.stringify({ version: serverVersion, totalPeers, rooms: items }));
  }
  if (req.url === "/" || req.url === "/index.html") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); return res.end(dashboard); }
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
    if (wss.clients.size >= 100 || connectionsFromIp >= 5) return done(false, 429, "Too many connections");
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
      if (!allowedRooms.has(id)) { peer.send(JSON.stringify({ type: "error", code: "invalid_room", message: "ไม่พบห้องที่ระบุ" })); return; }
      if (id === "all" && peer.role !== "admin") { peer.send(JSON.stringify({ type: "error", code: "forbidden_all", message: "Token นี้ไม่มีสิทธิ์ใช้งาน All" })); return; }
      leave(peer); const room = rooms.get(id) || { peers: new Set(), floor: null }; rooms.set(id, room); peer.room = room; peer.roomId = id; peer.name = String(msg.name || "Guest").slice(0, 30); peer.device = String(msg.device || "Unknown").slice(0, 80); room.peers.add(peer);
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
const heartbeatTimer = setInterval(() => {
  for (const peer of wss.clients) {
    if (peer.isAlive === false) {
      peer.terminate();
      continue;
    }
    peer.isAlive = false;
    peer.ping();
  }
}, heartbeatIntervalMs);
heartbeatTimer.unref?.();
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
