// BEE-VPN identity relay — Cloudflare Worker (deploy 2 copies: A and B)
// ============================================================================
// Contract (must match the app, IdentityRelayClient.kt):
//   * POST  /v0a4471/reg        register a new WARP device
//   * PATCH /v0a4471/reg/<id>   enable WARP / attach MASQUE key
//   * Headers: X-BEE-Timestamp (epoch ms), X-BEE-Nonce (32 hex),
//              X-BEE-Signature = hex(HMAC-SHA256(secret, "METHOD\nPATH\nTS\nNONCE\nBODY"))
//   * The Worker verifies the signature, then forwards the request to the
//     Cloudflare WARP API from Cloudflare's own network — which is reachable
//     even when the carrier filters api.cloudflareclient.com for the phone.
//
// Setup:
//   1. Put your secret in SECRET below (the same value you will store as the
//      BEE_IDENTITY_RELAY_HMAC GitHub secret). Example: openssl rand -hex 32
//   2. dash.cloudflare.com -> Workers & Pages -> Create -> Worker -> paste -> Deploy.
//   3. Deploy a second worker (same code, different name).
//   4. GitHub repo -> Settings -> Secrets -> actions -> add:
//        BEE_IDENTITY_RELAY_HMAC = the secret
//        BEE_RELAY_A = https://<worker-a>.<account>.workers.dev
//        BEE_RELAY_B = https://<worker-b>.<account>.workers.dev
//   5. Re-run the release workflow (tag) — the new APK will use your Workers.
// ============================================================================

const SECRET = "CHANGE-ME-same-as-BEE_IDENTITY_RELAY_HMAC-secret";
const UPSTREAM = "https://api.cloudflareclient.com";

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=UTF-8",
  "User-Agent": "WARP for Android",
  "CF-Client-Version": "a-6.35-4471",
};

function hmacHex(secret, message) {
  const enc = new TextEncoder();
  return crypto.subtle
    .importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"])
    .then((key) => crypto.subtle.sign("HMAC", key, enc.encode(message)))
    .then((mac) => [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join(""));
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (!path.startsWith("/v0a4471/reg")) {
      return new Response(JSON.stringify({ error: "not found" }), { status: 404 });
    }
    const ts = request.headers.get("X-BEE-Timestamp") || "";
    const nonce = request.headers.get("X-BEE-Nonce") || "";
    const sig = request.headers.get("X-BEE-Signature") || "";
    const body = await request.text();

    // Replay window: 5 minutes, fresh nonce each call (the app never reuses).
    if (!/^\d{13}$/.test(ts) || Math.abs(Date.now() - Number(ts)) > 300_000) {
      return new Response(JSON.stringify({ error: "stale timestamp" }), { status: 401 });
    }
    if (!/^[0-9a-f]{32}$/.test(nonce)) {
      return new Response(JSON.stringify({ error: "bad nonce" }), { status: 401 });
    }
    const expected = await hmacHex(SECRET, request.method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + body);
    if (sig !== expected) {
      return new Response(JSON.stringify({ error: "bad signature" }), { status: 403 });
    }

    const headers = { ...JSON_HEADERS };
    const auth = request.headers.get("Authorization");
    if (auth) headers.Authorization = auth;

    let upstream;
    try {
      upstream = await fetch(UPSTREAM + path, {
        method: request.method,
        headers,
        body: body.length ? body : undefined,
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: "upstream fetch failed" }), { status: 502 });
    }
    const text = await upstream.text();
    const retryAfter = upstream.headers.get("Retry-After");
    const out = { status: upstream.status, headers: { "Content-Type": "application/json" } };
    if (retryAfter) out.headers["Retry-After"] = retryAfter;
    return new Response(text, out);
  },
};
