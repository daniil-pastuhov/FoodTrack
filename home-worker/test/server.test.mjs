// The home server's own checks (node --test home-worker/test): no dependencies, a stand-in CLI.
import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { mkdtempSync, readFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { GoogleKeys, createHomeServer, verifyGoogleIdToken } from "../server.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const AUD = "https://home.example.ts.net";
const SA = "recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com";
const SECRET = "the-shared-secret-of-the-tests-0123456789";
const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const other = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" };

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
function token(claims = {}, { kid = "k1", alg = "RS256", key = privateKey } = {}) {
  const t = Math.floor(Date.now() / 1000);
  const head = b64({ alg, kid, typ: "JWT" });
  const body = b64({ iss: "https://accounts.google.com", aud: AUD, email: SA, email_verified: true, iat: t, exp: t + 3600, sub: "1", ...claims });
  const sig = alg === "none" ? "" : sign("RSA-SHA256", Buffer.from(`${head}.${body}`), key).toString("base64url");
  return `${head}.${body}.${sig}`;
}

let fetches = 0;
let jwksDown = false;
const keys = () => new GoogleKeys(async () => {
  fetches++;
  if (jwksDown) throw new Error("down");
  return { keys: [jwk], maxAgeMs: 3_600_000 };
});

const config = {
  publicUrl: AUD, allowedServiceAccount: SA, secret: SECRET, host: "127.0.0.1", port: 0,
  model: "sonnet", effort: "", claude: join(HERE, "fake-claude.mjs"), maxJobMs: 20_000, logPrompts: false,
};
let server;
let base;
before(async () => {
  server = createHomeServer(config, { keys: keys(), failureDelayMs: 0 });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());
beforeEach(() => {
  process.env.FAKE_CLAUDE = "ok";
  delete process.env.FAKE_CLAUDE_PID_FILE;
});

const job = (over = {}) => ({ kind: "recipe", systemInstruction: "sys", userText: "Pasta, tomato", schema: { type: "object" }, maxOutputTokens: 4096, timeoutMs: 10_000, ...over });
function call({ id = token(), secret = SECRET, body = job(), path = "/v1/text", method = "POST", headers = {}, signal } = {}) {
  return fetch(base + path, {
    method,
    headers: { "content-type": "application/json", ...(id ? { authorization: `Bearer ${id}` } : {}), ...(secret ? { "x-worker-secret": secret } : {}), ...headers },
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
    signal,
  });
}

test("a valid token and secret get the CLI's answer, the user turn on stdin, no tools", async () => {
  const res = await call();
  assert.equal(res.status, 200);
  const { text, model } = await res.json();
  assert.equal(model, "fake-claude-1");
  assert.deepEqual(JSON.parse(text), { echo: "Pasta, tomato", schemaType: "object", tools: "" });
});

test("every auth failure is the same 401, before the body is read", async () => {
  const t = Math.floor(Date.now() / 1000);
  const cases = {
    "wrong audience": { id: token({ aud: "https://elsewhere.example" }) },
    "audience with a trailing slash": { id: token({ aud: AUD + "/" }) },
    "wrong email": { id: token({ email: "someone@bestbefore-f9ee1.iam.gserviceaccount.com" }) },
    "email not verified": { id: token({ email_verified: false }) },
    "wrong issuer": { id: token({ iss: "https://evil.example" }) },
    expired: { id: token({ iat: t - 7200, exp: t - 3600 }) },
    "issued in the future": { id: token({ iat: t + 600, exp: t + 4200 }) },
    "bad signature": { id: token({}, { key: other.privateKey }) },
    "alg none": { id: token({}, { alg: "none" }) },
    "alg HS256": { id: token({}, { alg: "HS256" }) },
    "unknown kid": { id: token({}, { kid: "k9" }) },
    "no token": { id: null },
    "garbage token": { id: "a.b" },
    "no secret": { secret: null },
    "right token, wrong secret": { secret: SECRET + "x" },
  };
  for (const [name, c] of Object.entries(cases)) {
    const res = await call({ ...c, body: "x".repeat(MAX_TEST_BODY) });
    assert.equal(res.status, 401, name);
    assert.deepEqual(await res.json(), { error: "UNAUTHORIZED" }, name);
  }
});

const MAX_TEST_BODY = 300 * 1024;

test("an unknown kid makes it fetch the keys again once, not on every request", async () => {
  const k = keys();
  const before = fetches;
  const opts = { audience: AUD, email: SA, keys: k };
  assert.equal(await verifyGoogleIdToken(token(), opts), true);
  assert.equal(await verifyGoogleIdToken(token({}, { kid: "k9" }), opts), false);
  assert.equal(await verifyGoogleIdToken(token({}, { kid: "k8" }), opts), false);
  assert.equal(fetches - before, 1);
});

test("keys that can't be read refuse everything (fail closed)", async () => {
  jwksDown = true;
  try {
    assert.equal(await verifyGoogleIdToken(token(), { audience: AUD, email: SA, keys: keys() }), false);
  } finally {
    jwksDown = false;
  }
});

test("anything but POST /v1/text is 404, with no detail", async () => {
  for (const [method, path] of [["GET", "/"], ["GET", "/healthz"], ["GET", "/v1/text"], ["POST", "/v1/other"], ["POST", "/../etc/passwd"]]) {
    const res = await call({ method, path });
    assert.equal(res.status, 404, `${method} ${path}`);
    assert.deepEqual(await res.json(), { error: "NOT_FOUND" });
  }
});

test("a body over the cap is 413, a malformed one 400", async () => {
  assert.equal((await call({ body: job({ userText: "x".repeat(300 * 1024) }) })).status, 413);
  assert.equal((await call({ body: "not json" })).status, 400);
  assert.equal((await call({ body: job({ kind: "dessert" }) })).status, 400);
  assert.equal((await call({ body: job({ schema: [1] }) })).status, 400);
  assert.equal((await call({ headers: { "content-type": "text/plain" } })).status, 400);
});

test("one job at a time: a second one while the first runs is 429", async () => {
  process.env.FAKE_CLAUDE = "slow";
  const first = call({ body: job({ timeoutMs: 1_500 }) });
  await new Promise((r) => setTimeout(r, 300));
  const second = await call();
  assert.equal(second.status, 429);
  assert.equal((await first).status, 504);
});

test("the CLI failing is 502, an answer over the cap 502", async () => {
  process.env.FAKE_CLAUDE = "fail";
  assert.deepEqual([(await call()).status], [502]);
  process.env.FAKE_CLAUDE = "huge";
  const res = await call();
  assert.equal(res.status, 502);
  assert.deepEqual(await res.json(), { error: "TOO_LONG" });
});

test("when the caller goes away, the CLI is stopped", async () => {
  process.env.FAKE_CLAUDE = "slow";
  const dir = mkdtempSync(join(tmpdir(), "home-worker-test-"));
  process.env.FAKE_CLAUDE_PID_FILE = join(dir, "pid");
  const controller = new AbortController();
  const pending = call({ signal: controller.signal }).catch(() => "aborted");
  for (let i = 0; i < 50 && !existsSync(process.env.FAKE_CLAUDE_PID_FILE); i++) await new Promise((r) => setTimeout(r, 100));
  const pid = Number(readFileSync(process.env.FAKE_CLAUDE_PID_FILE, "utf8"));
  assert.doesNotThrow(() => process.kill(pid, 0));
  controller.abort();
  assert.equal(await pending, "aborted");
  let alive = true;
  for (let i = 0; i < 30 && alive; i++) {
    await new Promise((r) => setTimeout(r, 100));
    try {
      process.kill(pid, 0);
    } catch {
      alive = false;
    }
  }
  assert.equal(alive, false);
  rmSync(dir, { recursive: true, force: true });
  delete process.env.FAKE_CLAUDE_PID_FILE;
  // And the server takes the next job.
  process.env.FAKE_CLAUDE = "ok";
  assert.equal((await call()).status, 200);
});
