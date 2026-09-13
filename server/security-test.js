import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import WebSocket from "ws";

const port = 8183;
const anonymousPort = 8184;
const userToken = "u".repeat(64);
const adminToken = "a".repeat(64);
const dashboardUser = "admin";
const dashboardPassword = "dashboard-password-for-test";
const relay = spawn(process.execPath, ["index.js"], {
  cwd: import.meta.dirname,
  env: {
    ...process.env,
    PORT: String(port),
    PTT_USER_TOKEN: userToken,
    PTT_ADMIN_TOKEN: adminToken,
    PTT_DASHBOARD_USER: dashboardUser,
    PTT_DASHBOARD_PASSWORD: dashboardPassword,
    PTT_TRUST_PROXY: "true",
    APNS_ENABLED: "false"
  },
  stdio: ["ignore", "pipe", "pipe"]
});
let anonymousRelay;

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const waitForServer = async targetPort => {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try { await fetch(`http://127.0.0.1:${targetPort}/`); return; } catch { await delay(50); }
  }
  throw new Error("Relay did not start");
};
const connect = (token, targetPort = port) => {
  const messages = [];
  const options = token ? { headers: { Authorization: `Bearer ${token}` } } : {};
  const ws = new WebSocket(`ws://127.0.0.1:${targetPort}`, options);
  ws.on("message", raw => messages.push(JSON.parse(raw)));
  const next = async type => {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const index = messages.findIndex(message => message.type === type);
      if (index >= 0) return messages.splice(index, 1)[0];
      await delay(20);
    }
    throw new Error(`Timed out waiting for ${type}`);
  };
  return { ws, next };
};

try {
  await waitForServer(port);

  const health = await fetch(`http://127.0.0.1:${port}/healthz`);
  assert.equal(health.status, 200);
  assert.equal((await health.json()).version, "1.4.0-northflank");

  const unauthenticatedDashboard = await fetch(`http://127.0.0.1:${port}/status`);
  assert.equal(unauthenticatedDashboard.status, 401);
  const basic = Buffer.from(`${dashboardUser}:${dashboardPassword}`).toString("base64");
  const authenticatedDashboard = await fetch(`http://127.0.0.1:${port}/status`, { headers: { Authorization: `Basic ${basic}` } });
  assert.equal(authenticatedDashboard.status, 200);
  const dashboardPage = await fetch(`http://127.0.0.1:${port}/`, { headers: { Authorization: `Basic ${basic}` } });
  assert.equal(dashboardPage.status, 200);
  const dashboardHtml = await dashboardPage.text();
  assert.match(dashboardHtml, /จัดการอุปกรณ์/);
  assert.match(dashboardHtml, /ตัดการเชื่อมต่อ \(None reconnect\)/);
  const initialStatus = await authenticatedDashboard.clone().json();
  assert.equal(initialStatus.config.maxConnections, 100);
  assert.equal(initialStatus.config.maxConnectionsPerIp, 5);
  const missingActionHeader = await fetch(`http://127.0.0.1:${port}/admin/config`, {
    method: "POST",
    headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/json" },
    body: JSON.stringify({})
  });
  assert.equal(missingActionHeader.status, 400);
  const configResponse = await fetch(`http://127.0.0.1:${port}/admin/config`, {
    method: "POST",
    headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/json", "X-PTT-Dashboard": "1" },
    body: JSON.stringify({
      heartbeatIntervalMs: 5_000,
      maxConnections: 120,
      maxConnectionsPerIp: 6,
      rooms: [
        { id: "room-1", name: "ห้องทดสอบ", enabled: true },
        { id: "room-2", name: "room-2", enabled: true },
        { id: "room-3", name: "room-3", enabled: true },
        { id: "room-4", name: "room-4", enabled: false },
        { id: "all", name: "all", enabled: true }
      ]
    })
  });
  assert.equal(configResponse.status, 200);
  const configuredStatus = await configResponse.json();
  assert.equal(configuredStatus.config.heartbeatIntervalMs, 5_000);
  assert.equal(configuredStatus.config.maxConnections, 120);
  assert.equal(configuredStatus.rooms.find(room => room.id === "room-1").name, "ห้องทดสอบ");
  assert.equal(configuredStatus.rooms.find(room => room.id === "room-4").enabled, false);

  await new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.on("unexpected-response", (_request, response) => { try { assert.equal(response.statusCode, 401); response.resume(); resolve(); } catch (error) { reject(error); } });
    ws.on("open", () => reject(new Error("Unauthenticated WebSocket was accepted")));
    ws.on("error", () => {});
  });

  const user = connect(userToken);
  assert.equal((await user.next("authenticated")).role, "user");
  user.ws.send(JSON.stringify({ type: "join", room: "all", name: "User" }));
  assert.equal((await user.next("error")).code, "forbidden_all");
  user.ws.send(JSON.stringify({ type: "join", room: "room-1", name: "User", clientId: "android-test" }));
  assert.equal((await user.next("joined")).room, "room-1");
  user.ws.send(JSON.stringify({ type: "heartbeat", ts: 123456789 }));
  assert.equal((await user.next("heartbeat")).ts, 123456789);
  user.ws.send(JSON.stringify({ type: "register-push", token: "f".repeat(64) }));
  const pushRegistration = await user.next("push-registered");
  assert.equal(pushRegistration.registered, true);
  assert.equal(pushRegistration.enabled, false);

  const userReconnected = connect(userToken);
  const oldUserClosed = new Promise(resolve => user.ws.once("close", resolve));
  assert.equal((await userReconnected.next("authenticated")).role, "user");
  userReconnected.ws.send(JSON.stringify({ type: "join", room: "room-1", name: "User", clientId: "android-test" }));
  assert.equal((await userReconnected.next("joined")).room, "room-1");
  await oldUserClosed;

  const admin = connect(adminToken);
  assert.equal((await admin.next("authenticated")).role, "admin");
  admin.ws.send(JSON.stringify({ type: "join", room: "all", name: "Admin" }));
  const joined = await admin.next("joined");
  assert.equal(joined.room, "all");
  assert.equal(joined.role, "admin");

  const statusBeforeDisconnect = await fetch(`http://127.0.0.1:${port}/status`, { headers: { Authorization: `Basic ${basic}` } }).then(response => response.json());
  const userPeer = statusBeforeDisconnect.rooms.find(room => room.id === "room-1")?.peers.find(peer => peer.name === "User");
  assert.ok(userPeer?.id);
  const userDisconnected = new Promise(resolve => userReconnected.ws.once("close", (code, reason) => resolve({ code, reason: String(reason) })));
  const disconnectResponse = await fetch(`http://127.0.0.1:${port}/admin/disconnect`, {
    method: "POST",
    headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/json", "X-PTT-Dashboard": "1" },
    body: JSON.stringify({ peerId: userPeer.id })
  });
  assert.equal(disconnectResponse.status, 200);
  const temporaryDisconnect = await userDisconnected;
  assert.equal(temporaryDisconnect.code, 4003);
  assert.match(temporaryDisconnect.reason, /temporarily/);
  const statusAfterDisconnect = await disconnectResponse.clone().json();
  assert.equal(statusAfterDisconnect.status.rooms.find(room => room.id === "room-1")?.peers.length, 0);
  assert.equal(statusAfterDisconnect.status.config.audit[0].action, "ตัดการเชื่อมต่อชั่วคราว");

  const noReconnectUser = connect(userToken);
  assert.equal((await noReconnectUser.next("authenticated")).role, "user");
  noReconnectUser.ws.send(JSON.stringify({ type: "join", room: "room-1", name: "No reconnect", clientId: "android-no-reconnect-test" }));
  assert.equal((await noReconnectUser.next("joined")).room, "room-1");
  const noReconnectStatus = await fetch(`http://127.0.0.1:${port}/status`, { headers: { Authorization: `Basic ${basic}` } }).then(response => response.json());
  const noReconnectPeer = noReconnectStatus.rooms.find(room => room.id === "room-1")?.peers.find(peer => peer.name === "No reconnect");
  assert.ok(noReconnectPeer?.id);
  const noReconnectClosed = new Promise(resolve => noReconnectUser.ws.once("close", (code, reason) => resolve({ code, reason: String(reason) })));
  const noReconnectResponse = await fetch(`http://127.0.0.1:${port}/admin/disconnect-no-reconnect`, {
    method: "POST",
    headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/json", "X-PTT-Dashboard": "1" },
    body: JSON.stringify({ peerId: noReconnectPeer.id })
  });
  assert.equal(noReconnectResponse.status, 200);
  const permanentDisconnect = await noReconnectClosed;
  assert.equal(permanentDisconnect.code, 4002);
  assert.match(permanentDisconnect.reason, /without reconnect/);
  const statusAfterNoReconnect = await noReconnectResponse.clone().json();
  assert.equal(statusAfterNoReconnect.status.rooms.find(room => room.id === "room-1")?.peers.length, 0);
  assert.equal(statusAfterNoReconnect.status.config.audit[0].action, "ตัดการเชื่อมต่อ (None reconnect)");

  const proxied = new WebSocket(`ws://127.0.0.1:${port}`, {
    headers: { Authorization: `Bearer ${userToken}`, "X-Forwarded-For": "203.0.113.9" }
  });
  const proxiedMessages = [];
  proxied.on("message", raw => proxiedMessages.push(JSON.parse(raw)));
  for (let attempt = 0; attempt < 100 && !proxiedMessages.some(message => message.type === "authenticated"); attempt += 1) await delay(20);
  proxied.send(JSON.stringify({ type: "join", room: "room-2", name: "Proxy test" }));
  for (let attempt = 0; attempt < 100 && !proxiedMessages.some(message => message.type === "joined"); attempt += 1) await delay(20);
  const proxiedDashboard = await fetch(`http://127.0.0.1:${port}/status`, { headers: { Authorization: `Basic ${basic}` } });
  const proxiedStatus = await proxiedDashboard.json();
  assert.equal(proxiedStatus.rooms.find(room => room.id === "room-2")?.peers[0]?.ip, "203.0.113.9");
  proxied.close();

  anonymousRelay = spawn(process.execPath, ["index.js"], {
    cwd: import.meta.dirname,
    env: {
      ...process.env,
      PORT: String(anonymousPort),
      PTT_AUTH_REQUIRED: "false",
      PTT_USER_TOKEN: "",
      PTT_ADMIN_TOKEN: "",
      PTT_DASHBOARD_USER: dashboardUser,
      PTT_DASHBOARD_PASSWORD: dashboardPassword,
      APNS_ENABLED: "false"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  await waitForServer(anonymousPort);
  const anonymous = connect(null, anonymousPort);
  assert.equal((await anonymous.next("authenticated")).role, "admin");
  anonymous.ws.send(JSON.stringify({ type: "join", room: "all", name: "Tester" }));
  assert.equal((await anonymous.next("joined")).room, "all");
  anonymous.ws.close();

  user.ws.close();
  userReconnected.ws.close();
  admin.ws.close();
  console.log("SECURITY_TESTS_OK");
} finally {
  anonymousRelay?.kill();
  relay.kill();
}
