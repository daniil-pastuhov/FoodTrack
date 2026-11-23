#!/usr/bin/env node
// The FoodTracker home server (README.md beside this file; functions/README.md "Home worker").
//
// Writes the recipe text of the owner's OWN test accounts with the Claude Code CLI on this
// computer, under the owner's own Claude subscription. The deployed generateRecipe function calls
// it (through a tunnel such as Tailscale Funnel or Cloudflare Tunnel) at POST /v1/text; nothing
// else is served. A request is answered only when it carries BOTH
//   - Authorization: Bearer <a Google-signed ID token> whose audience is this server's public url
//     and whose email is the functions' service account (verified here with Google's public keys),
//   - X-Worker-Secret: <the shared secret>.
// One job at a time. No dependencies and no build step: Node 18 or later, on Windows or macOS.
//   node server.mjs            (configuration: config.json beside this file, or the environment)

import { spawn } from "node:child_process";
import { createHash, createPublicKey, timingSafeEqual, verify as verifySignature } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:[\]-]{0,79}$/;
/** The function's own cap on the answer (functions/src/constants.ts HOME_WORKER.maxResultChars). */
export const MAX_RESULT_CHARS = 200_000;
/** The largest request body read (a 4-day menu's prompt is well under 64 KiB). */
export const MAX_BODY_BYTES = 256 * 1024;
const GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["https://accounts.google.com", "accounts.google.com"]);
const CLOCK_SKEW_S = 60;

const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args);

/** An https base address without credentials, query or fragment, and without a trailing slash; null otherwise. */
export function normalUrl(v) {
  if (typeof v !== "string" || v.length > 300) return null;
  let u;
  try {
    u = new URL(v);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash) return null;
  return `${u.origin}${u.pathname.replace(/\/+$/, "")}`;
}

/** config.json (never committed) under the environment; throws a plain message when something is missing. */
export async function loadConfig(dir = HERE, env = process.env) {
  let file = {};
  try {
    file = JSON.parse(await readFile(join(dir, "config.json"), "utf8"));
  } catch (e) {
    if (e.code !== "ENOENT") throw new Error(`config.json is not valid JSON: ${e.message}`);
  }
  const pick = (envName, key, fallback) => env[envName] ?? file[key] ?? fallback;
  const c = {
    publicUrl: normalUrl(pick("HOME_WORKER_PUBLIC_URL", "publicUrl", "")),
    allowedServiceAccount: String(pick("HOME_WORKER_ALLOWED_SA", "allowedServiceAccount", "")).toLowerCase(),
    secret: String(pick("HOME_WORKER_SECRET", "secret", "")),
    host: String(pick("HOME_WORKER_HOST", "host", "127.0.0.1")),
    port: Number(pick("HOME_WORKER_PORT", "port", 8787)),
    model: String(pick("HOME_WORKER_MODEL", "model", "sonnet")),
    effort: String(pick("HOME_WORKER_EFFORT", "effort", "")),
    claude: String(pick("CLAUDE_BIN", "claude", "claude")),
    maxJobMs: Number(pick("HOME_WORKER_MAX_JOB_MS", "maxJobMs", 130_000)),
    logPrompts: String(pick("HOME_WORKER_LOG_PROMPTS", "logPrompts", "")) === "1" || file.logPrompts === true,
  };
  if (!c.publicUrl) throw new Error("Set publicUrl (or HOME_WORKER_PUBLIC_URL): this server's public https address, exactly as in config/runtime.homeWorker.url");
  if (!/^[a-z0-9-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$/.test(c.allowedServiceAccount)) {
    throw new Error("Set allowedServiceAccount (or HOME_WORKER_ALLOWED_SA): the functions' service account, e.g. recipe-functions@bestbefore-f9ee1.iam.gserviceaccount.com");
  }
  if (c.secret.length < 32) throw new Error("Set secret (or HOME_WORKER_SECRET): the shared secret, at least 32 characters");
  if (!(Number.isInteger(c.port) && c.port > 0 && c.port < 65536)) throw new Error("port must be 1 to 65535");
  if (!MODEL_ID.test(c.model)) throw new Error("model must be a Claude model id or alias, e.g. sonnet");
  if (c.effort && !["low", "medium", "high", "xhigh", "max"].includes(c.effort)) throw new Error("effort must be low, medium, high, xhigh or max");
  if (!(c.maxJobMs >= 10_000)) throw new Error("maxJobMs must be at least 10000");
  return c;
}

// --- Google ID tokens ---------------------------------------------------------------------------

/** Google's signing keys (JWKS), as the endpoint answers: { keys, maxAgeMs }. Throws on any failure. */
async function fetchGoogleJwks() {
  const res = await fetch(GOOGLE_JWKS, { signal: AbortSignal.timeout(10_000), redirect: "error" });
  if (!res.ok) throw new Error(`keys: ${res.status}`);
  const body = await res.json();
  const m = /max-age=(\d+)/.exec(res.headers.get("cache-control") ?? "");
  return { keys: body.keys, maxAgeMs: m ? Number(m[1]) * 1000 : 3_600_000 };
}

/**
 * Google's public keys by kid, cached for the endpoint's Cache-Control max-age (1 minute to a day),
 * fetched again once for an unknown kid (at most once a minute, so random kids can't make it fetch
 * on every request). A failed fetch is no key at all: every token is refused (fail closed).
 */
export class GoogleKeys {
  constructor(fetchJwks = fetchGoogleJwks, now = Date.now) {
    this.fetchJwks = fetchJwks;
    this.now = now;
    this.keys = null;
    this.expiresAt = 0;
    this.fetchedAt = -Infinity;
    this.inFlight = null;
  }

  async refresh() {
    this.inFlight ??= (async () => {
      try {
        const { keys, maxAgeMs } = await this.fetchJwks();
        const map = new Map();
        for (const jwk of Array.isArray(keys) ? keys : []) {
          if (jwk?.kty !== "RSA" || typeof jwk.kid !== "string" || (jwk.alg && jwk.alg !== "RS256")) continue;
          map.set(jwk.kid, createPublicKey({ key: { kty: "RSA", n: jwk.n, e: jwk.e }, format: "jwk" }));
        }
        this.keys = map;
        this.fetchedAt = this.now();
        this.expiresAt = this.fetchedAt + Math.min(Math.max(maxAgeMs, 60_000), 86_400_000);
      } catch (e) {
        this.keys = null;
        this.expiresAt = 0;
        this.fetchedAt = this.now();
        log(`could not read Google's keys (${e.name === "TimeoutError" ? "timeout" : e.message}): refusing every request until they are read`);
      } finally {
        this.inFlight = null;
      }
    })();
    await this.inFlight;
  }

  async key(kid) {
    if (!this.keys || this.now() >= this.expiresAt) await this.refresh();
    let k = this.keys?.get(kid);
    if (!k && this.now() - this.fetchedAt >= 60_000) {
      await this.refresh();
      k = this.keys?.get(kid);
    }
    return k ?? null;
  }
}

const b64json = (s) => JSON.parse(Buffer.from(s, "base64url").toString("utf8"));

/**
 * Whether `token` is a Google-signed ID token (RS256, a key from Google's JWKS) issued by Google for
 * `audience` exactly, unexpired (a minute's skew), for the verified email `email`.
 */
export async function verifyGoogleIdToken(token, { audience, email, keys, now = Date.now }) {
  if (typeof token !== "string" || token.length > 4096) return false;
  const parts = token.split(".");
  if (parts.length !== 3 || parts.some((p) => !/^[A-Za-z0-9_-]+$/.test(p))) return false;
  let header;
  let claims;
  try {
    header = b64json(parts[0]);
    claims = b64json(parts[1]);
  } catch {
    return false;
  }
  if (header?.alg !== "RS256" || typeof header.kid !== "string" || typeof claims !== "object" || claims === null) return false;
  const key = await keys.key(header.kid);
  if (!key) return false;
  const signed = verifySignature("RSA-SHA256", Buffer.from(`${parts[0]}.${parts[1]}`), key, Buffer.from(parts[2], "base64url"));
  if (!signed) return false;
  const t = Math.floor(now() / 1000);
  return (
    GOOGLE_ISSUERS.has(claims.iss) &&
    claims.aud === audience &&
    typeof claims.exp === "number" && claims.exp > t - CLOCK_SKEW_S &&
    typeof claims.iat === "number" && claims.iat <= t + CLOCK_SKEW_S &&
    claims.email_verified === true &&
    typeof claims.email === "string" && claims.email.toLowerCase() === email
  );
}

const sameSecret = (given, secret) =>
  typeof given === "string" && timingSafeEqual(createHash("sha256").update(given).digest(), createHash("sha256").update(secret).digest());

// --- The CLI -------------------------------------------------------------------------------------

/** Runs the CLI on one job: { text, model }, or throws an Error whose message is a fixed code. */
export function runClaude(config, job, dir, timeoutMs, signal) {
  return new Promise((resolve, reject) => {
    const args = [
      "-p",
      "--model", config.model,
      ...(config.effort ? ["--effort", config.effort] : []),
      "--output-format", "json",
      "--json-schema", JSON.stringify(job.schema),
      "--system-prompt-file", join(dir, "system.txt"),
      // No tools, none of your customizations (hooks, plugins, MCP servers, CLAUDE.md), nothing kept.
      "--tools", "",
      "--safe-mode",
      "--no-session-persistence",
    ];
    // Never through a shell: nothing in a prompt is ever parsed as a command.
    const child = spawn(config.claude, args, { cwd: dir, shell: false, windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
    let out = "";
    let err = "";
    let done = false;
    const finish = (fn) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      fn();
    };
    const stop = (code) => {
      child.kill();
      finish(() => reject(new Error(code)));
    };
    const onAbort = () => stop("ABORTED");
    const timer = setTimeout(() => stop("TIMEOUT"), timeoutMs);
    signal?.addEventListener("abort", onAbort, { once: true });
    if (signal?.aborted) onAbort();
    child.stdout.on("data", (d) => {
      if (out.length < 4_000_000) out += d;
    });
    child.stderr.on("data", (d) => {
      if (err.length < 4_000) err += d;
    });
    child.on("error", (e) => finish(() => reject(new Error(e.code === "ENOENT" ? "CLI_NOT_FOUND" : "CLI_FAILED"))));
    child.on("close", (code) => {
      finish(() => {
        let r;
        try {
          r = JSON.parse(out);
        } catch {
          if (err) log(`  claude said: ${err.trim().slice(0, 300)}`);
          return reject(new Error(code === 0 ? "CLI_OUTPUT" : "CLI_FAILED"));
        }
        if (r.is_error || r.subtype !== "success") {
          log(`  claude reported ${String(r.subtype ?? "an error").slice(0, 40)}${r.api_error_status ? ` (${r.api_error_status})` : ""}`);
          return reject(new Error("CLI_ERROR"));
        }
        const text = r.structured_output !== undefined && r.structured_output !== null ? JSON.stringify(r.structured_output) : typeof r.result === "string" ? r.result : "";
        if (!text) return reject(new Error("CLI_EMPTY"));
        const used = Object.keys(r.modelUsage ?? {})[0];
        resolve({ text, model: used && MODEL_ID.test(used) ? used : config.model });
      });
    });
    child.stdin.on("error", () => undefined);
    child.stdin.end(job.userText);
  });
}

// --- The server ----------------------------------------------------------------------------------

const JSON_HEADERS = { "content-type": "application/json", "cache-control": "no-store" };

/** Answers and, unless the body was read, closes the connection (the rest of a body is never read). */
function send(req, res, status, body, { close = false } = {}) {
  if (res.headersSent || res.destroyed) return;
  res.writeHead(status, close ? { ...JSON_HEADERS, connection: "close" } : JSON_HEADERS);
  res.end(JSON.stringify(body), () => {
    if (close) req.socket.destroy();
  });
}

/** The job as sent by the function (functions/src/core/homeWorker.ts HomeTextRequest), or null. */
export function jobOf(body, maxJobMs) {
  const b = body;
  if (typeof b !== "object" || b === null || Array.isArray(b)) return null;
  if (b.kind !== "recipe" && b.kind !== "menu") return null;
  if (typeof b.systemInstruction !== "string" || b.systemInstruction.length === 0 || b.systemInstruction.length > 100_000) return null;
  if (typeof b.userText !== "string" || b.userText.length === 0 || b.userText.length > 100_000) return null;
  if (typeof b.schema !== "object" || b.schema === null || Array.isArray(b.schema)) return null;
  if (!Number.isInteger(b.maxOutputTokens) || b.maxOutputTokens < 1) return null;
  if (!Number.isInteger(b.timeoutMs) || b.timeoutMs < 1_000) return null;
  return { kind: b.kind, systemInstruction: b.systemInstruction, userText: b.userText, schema: b.schema, timeoutMs: Math.min(b.timeoutMs, maxJobMs) };
}

/**
 * The HTTP server. `options`: `keys` (a GoogleKeys; tests give one over their own JWKS), `now`,
 * `failureDelayMs` (the pause before every 401, default 1 s), `run` (the CLI runner, for tests).
 */
export function createHomeServer(config, options = {}) {
  const keys = options.keys ?? new GoogleKeys();
  const now = options.now ?? Date.now;
  const failureDelayMs = options.failureDelayMs ?? 1_000;
  const run = options.run ?? runClaude;
  let busy = false;
  let refused = 0;
  let refusedLoggedAt = 0;

  const unauthorized = (req, res) => {
    refused++;
    if (now() - refusedLoggedAt >= 60_000) {
      log(`refused ${refused} unauthenticated request${refused === 1 ? "" : "s"} (401) since the last note`);
      refused = 0;
      refusedLoggedAt = now();
    }
    setTimeout(() => send(req, res, 401, { error: "UNAUTHORIZED" }, { close: true }), failureDelayMs);
  };

  async function handle(req, res) {
    const path = (req.url ?? "").split("?")[0];
    if (req.method !== "POST" || path !== "/v1/text") return send(req, res, 404, { error: "NOT_FOUND" }, { close: true });
    // Both proofs before a byte of the body is read.
    const auth = /^Bearer (\S+)$/.exec(req.headers.authorization ?? "");
    const secretOk = sameSecret(req.headers["x-worker-secret"], config.secret);
    const tokenOk = auth !== null && (await verifyGoogleIdToken(auth[1], { audience: config.publicUrl, email: config.allowedServiceAccount, keys, now }));
    if (!secretOk || !tokenOk) return unauthorized(req, res);
    if (Number(req.headers["content-length"] ?? 0) > MAX_BODY_BYTES) return send(req, res, 413, { error: "TOO_BIG" }, { close: true });
    if (!/^application\/json\b/.test(req.headers["content-type"] ?? "")) return send(req, res, 400, { error: "BAD_REQUEST" }, { close: true });
    if (busy) return send(req, res, 429, { error: "BUSY" }, { close: true });
    busy = true;
    const started = Date.now();
    const controller = new AbortController();
    // The function gave up (or the tunnel dropped it): stop claude at once.
    res.on("close", () => {
      if (!res.writableFinished) controller.abort();
    });
    let dir;
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) return send(req, res, 413, { error: "TOO_BIG" }, { close: true });
        chunks.push(chunk);
      }
      let job;
      try {
        job = jobOf(JSON.parse(Buffer.concat(chunks).toString("utf8")), config.maxJobMs);
      } catch {
        job = null;
      }
      if (!job) return send(req, res, 400, { error: "BAD_REQUEST" });
      log(`${job.kind}: started, ${Math.round(job.timeoutMs / 1000)} s allowed`);
      if (config.logPrompts) log(`  system:\n${job.systemInstruction}\n  user:\n${job.userText}`);
      dir = await mkdtemp(join(tmpdir(), "home-worker-"));
      await writeFile(join(dir, "system.txt"), job.systemInstruction, "utf8");
      const r = await run(config, job, dir, job.timeoutMs, controller.signal);
      const secs = ((Date.now() - started) / 1000).toFixed(1);
      if (r.text.length > MAX_RESULT_CHARS) {
        log(`${job.kind}: answer too long (${r.text.length} characters) after ${secs} s`);
        return send(req, res, 502, { error: "TOO_LONG" });
      }
      log(`${job.kind}: answered in ${secs} s (${r.text.length} characters, ${r.model})`);
      return send(req, res, 200, { text: r.text, model: r.model });
    } catch (e) {
      const code = /^[A-Z_]+$/.test(e?.message ?? "") ? e.message : "FAILED";
      log(`request failed: ${code} after ${((Date.now() - started) / 1000).toFixed(1)} s`);
      if (code === "ABORTED") return undefined;
      return send(req, res, code === "TIMEOUT" ? 504 : 502, { error: code });
    } finally {
      busy = false;
      // The prompt holds the pantry and the allergens: nothing of it stays on disk.
      if (dir) await rm(dir, { recursive: true, force: true }).catch(() => undefined);
    }
  }

  return createServer(
    { maxHeaderSize: 16 * 1024, headersTimeout: 15_000, requestTimeout: config.maxJobMs + 30_000 },
    (req, res) => {
      handle(req, res).catch(() => send(req, res, 500, { error: "ERROR" }));
    },
  );
}

async function main() {
  const config = await loadConfig();
  const server = createHomeServer(config);
  server.listen(config.port, config.host, () => {
    log(`home server on http://${config.host}:${config.port} for ${config.publicUrl}; model ${config.model}${config.effort ? `, effort ${config.effort}` : ""}; prompts are ${config.logPrompts ? "LOGGED" : "not logged"}`);
    if (config.host !== "127.0.0.1" && config.host !== "localhost" && config.host !== "::1") log("note: listening beyond this computer; the tunnel only needs 127.0.0.1");
  });
  process.on("SIGINT", () => {
    log("stopped");
    process.exit(0);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
