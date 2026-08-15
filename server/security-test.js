import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import WebSocket from "ws";

const port = 8183;
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
    PTT_DASHBOARD_PASSWORD: dashboardPassword
  },
  stdio: ["ignore", "pipe", "pipe"]
});

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const waitForServer = async () => {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try { await fetch(`http://127.0.0.1:${port}/`); return; } catch { await delay(50); }
  }
  throw new Error("Relay did not start");
};
const connect = token => {
  const messages = [];
  const ws = new WebSocket(`ws://127.0.0.1:${port}`, { headers: { Authorization: `Bearer ${token}` } });
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
  await waitForServer();

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
  user.ws.send(JSON.stringify({ type: "join", room: "room-1", name: "User" }));
  assert.equal((await user.next("joined")).room, "room-1");

  const admin = connect(adminToken);
  assert.equal((await admin.next("authenticated")).role, "admin");
  admin.ws.send(JSON.stringify({ type: "join", room: "all", name: "Admin" }));
  const joined = await admin.next("joined");
  assert.equal(joined.room, "all");
  assert.equal(joined.role, "admin");

  user.ws.close();
  admin.ws.close();
  console.log("SECURITY_TESTS_OK");
} finally {
  relay.kill();
}
