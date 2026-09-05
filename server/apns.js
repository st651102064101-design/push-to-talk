import http2 from "node:http2";
import { createPrivateKey, sign } from "node:crypto";
import { readFileSync } from "node:fs";

const required = (environment, name) => {
  const value = String(environment[name] || "").trim();
  if (!value) throw new Error(`APNs is enabled but ${name} is missing`);
  return value;
};

const base64Url = value => Buffer.from(value).toString("base64url");

export function createApnsNotifier(environment = process.env) {
  const enabled = String(environment.APNS_ENABLED || "false").trim().toLowerCase() === "true";
  if (!enabled) {
    return { enabled: false, send: async () => ({ ok: false, status: 0, disabled: true }), close: () => {} };
  }

  const teamId = required(environment, "APNS_TEAM_ID");
  const keyId = required(environment, "APNS_KEY_ID");
  const bundleId = required(environment, "APNS_BUNDLE_ID");
  const keyPath = required(environment, "APNS_KEY_PATH");
  const target = String(environment.APNS_ENVIRONMENT || "production").toLowerCase() === "development"
    ? "https://api.sandbox.push.apple.com"
    : "https://api.push.apple.com";
  const privateKey = createPrivateKey(readFileSync(keyPath));
  let session;
  let cachedToken = "";
  let tokenCreatedAt = 0;

  const authorizationToken = () => {
    const now = Math.floor(Date.now() / 1000);
    if (cachedToken && now - tokenCreatedAt < 50 * 60) return cachedToken;
    const header = base64Url(JSON.stringify({ alg: "ES256", kid: keyId }));
    const payload = base64Url(JSON.stringify({ iss: teamId, iat: now }));
    const content = `${header}.${payload}`;
    const signature = sign("sha256", Buffer.from(content), { key: privateKey, dsaEncoding: "ieee-p1363" });
    cachedToken = `${content}.${signature.toString("base64url")}`;
    tokenCreatedAt = now;
    return cachedToken;
  };

  const activeSession = () => {
    if (session && !session.closed && !session.destroyed) return session;
    session = http2.connect(target);
    session.on("error", error => console.error("APNs connection error:", error.message));
    session.on("close", () => { session = undefined; });
    return session;
  };

  const send = (deviceToken, payload) => new Promise((resolve, reject) => {
    const token = String(deviceToken || "").trim().toLowerCase();
    if (!/^[a-f0-9]{32,400}$/.test(token)) {
      resolve({ ok: false, status: 0, reason: "invalid_device_token" });
      return;
    }
    const client = activeSession();
    const request = client.request({
      ":method": "POST",
      ":path": `/3/device/${token}`,
      authorization: `bearer ${authorizationToken()}`,
      "apns-push-type": "pushtotalk",
      "apns-topic": `${bundleId}.voip-ptt`,
      "apns-priority": "10",
      "apns-expiration": "0"
    });
    let status = 0;
    let responseBody = "";
    request.setEncoding("utf8");
    request.on("response", headers => { status = Number(headers[":status"] || 0); });
    request.on("data", chunk => { responseBody += chunk; });
    request.on("end", () => {
      let reason = "";
      try { reason = JSON.parse(responseBody).reason || ""; } catch {}
      resolve({ ok: status === 200, status, reason });
    });
    request.on("error", reject);
    request.end(JSON.stringify({ aps: {}, ...payload }));
  });

  return {
    enabled: true,
    send,
    close: () => {
      session?.close();
      session = undefined;
    }
  };
}
