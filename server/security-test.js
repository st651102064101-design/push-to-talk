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
