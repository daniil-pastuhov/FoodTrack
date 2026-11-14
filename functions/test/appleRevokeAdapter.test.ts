import { generateKeyPairSync, verify } from "node:crypto";
import { AppleSignInRevoker, clientSecret } from "../src/adapters/appleRevoke";
import { AppleTokenError } from "../src/ports";

const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const pem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
const CODE = "c0de-SECRET-authorization-code";
const TOKEN = "r.REFRESH-TOKEN-SECRET";

type Call = { url: string; body: URLSearchParams };

/** A fetch that records requests and answers from a queue. */
function fakeFetch(answers: (Response | Error)[]) {
  const calls: Call[] = [];
  const fn = (async (url: string | URL, init?: RequestInit) => {
    calls.push({ url: String(url), body: new URLSearchParams(String(init?.body)) });
    const next = answers.shift();
    if (!next) throw new Error("no answer queued");
    if (next instanceof Error) throw next;
    return next;
  }) as typeof fetch;
  return { fn, calls };
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function revoker(answers: (Response | Error)[], key = pem) {
  const f = fakeFetch(answers);
  return { r: new AppleSignInRevoker({ teamId: "TEAM123456", keyId: "KEY1234567", privateKey: key, clientId: "com.foodbest.ios", fetch: f.fn }), calls: f.calls };
}

async function errorOf(p: Promise<unknown>): Promise<AppleTokenError> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(AppleTokenError);
    return e as AppleTokenError;
  }
  throw new Error("expected an AppleTokenError");
}

/** No error an adapter throws may carry the code or the token. */
function expectNoSecret(e: Error) {
  const text = `${e.message} ${e.stack ?? ""} ${String((e as { cause?: unknown }).cause ?? "")}`;
  expect(text).not.toContain(CODE);
  expect(text).not.toContain(TOKEN);
}

describe("Sign in with Apple REST adapter", () => {
  test("the client secret is an ES256 JWT Apple accepts the shape of", () => {
    const now = 1_800_000_000;
    const jwt = clientSecret({ teamId: "TEAM123456", keyId: "KEY1234567", privateKey: pem, clientId: "com.foodbest.ios" }, now);
    const [h, p, s] = jwt.split(".");
    expect(JSON.parse(Buffer.from(h, "base64url").toString())).toEqual({ alg: "ES256", kid: "KEY1234567" });
    expect(JSON.parse(Buffer.from(p, "base64url").toString())).toEqual({
      iss: "TEAM123456", iat: now, exp: now + 300, aud: "https://appleid.apple.com", sub: "com.foodbest.ios",
    });
    const ok = verify("sha256", Buffer.from(`${h}.${p}`), { key: publicKey.export({ type: "spki", format: "pem" }).toString(), dsaEncoding: "ieee-p1363" }, Buffer.from(s, "base64url"));
    expect(ok).toBe(true);
  });

  test("exchanging a code posts the authorization_code grant and returns the refresh token", async () => {
    const { r, calls } = revoker([json(200, { access_token: "a", refresh_token: TOKEN, id_token: "i", expires_in: 3600 })]);
    expect(await r.exchangeAuthorizationCode(CODE)).toBe(TOKEN);
    expect(calls[0].url).toBe("https://appleid.apple.com/auth/token");
    expect(calls[0].body.get("grant_type")).toBe("authorization_code");
    expect(calls[0].body.get("code")).toBe(CODE);
    expect(calls[0].body.get("client_id")).toBe("com.foodbest.ios");
    expect(calls[0].body.get("client_secret")?.split(".")).toHaveLength(3);
  });

  test("revoking posts the refresh token with its hint", async () => {
    const { r, calls } = revoker([new Response(null, { status: 200 })]);
    await r.revokeRefreshToken(TOKEN);
    expect(calls[0].url).toBe("https://appleid.apple.com/auth/revoke");
    expect(calls[0].body.get("token")).toBe(TOKEN);
    expect(calls[0].body.get("token_type_hint")).toBe("refresh_token");
  });

  test("revokeWithAuthorizationCode exchanges then revokes", async () => {
    const { r, calls } = revoker([json(200, { refresh_token: TOKEN }), new Response(null, { status: 200 })]);
    await r.revokeWithAuthorizationCode(CODE);
    expect(calls.map((c) => c.url)).toEqual(["https://appleid.apple.com/auth/token", "https://appleid.apple.com/auth/revoke"]);
    expect(calls[1].body.get("token")).toBe(TOKEN);
  });

  test.each([
    [400, "rejected"],
    [401, "rejected"],
    [429, "retryable"],
    [500, "retryable"],
    [503, "retryable"],
  ])("HTTP %i is %s", async (status, kind) => {
    const { r } = revoker([json(status, { error: "invalid_grant", error_description: `echo ${CODE}` })]);
    const e = await errorOf(r.exchangeAuthorizationCode(CODE));
    expect(e.kind).toBe(kind);
    expect(e.message).toBe(`Apple /auth/token returned ${status} invalid_grant`);
    expectNoSecret(e);
  });

  test("an unknown error value or a body echoing the secret is not carried over", async () => {
    const { r } = revoker([json(400, { error: `weird ${TOKEN}` })]);
    const e = await errorOf(r.revokeRefreshToken(TOKEN));
    expect(e.message).toBe("Apple /auth/revoke returned 400 other");
    expectNoSecret(e);
  });

  test("a network failure is retryable and names only the error type", async () => {
    const failure = new TypeError(`fetch failed for body code=${CODE}`);
    const { r } = revoker([failure]);
    const e = await errorOf(r.exchangeAuthorizationCode(CODE));
    expect(e.kind).toBe("retryable");
    expect(e.message).toBe("Apple /auth/token unreachable (TypeError)");
    expectNoSecret(e);
  });

  test("an answer without a refresh token, or without JSON, is retryable", async () => {
    const a = revoker([json(200, { access_token: TOKEN })]);
    const e1 = await errorOf(a.r.exchangeAuthorizationCode(CODE));
    expect(e1.kind).toBe("retryable");
    expectNoSecret(e1);
    const b = revoker([new Response(`not json ${TOKEN}`, { status: 200 })]);
    const e2 = await errorOf(b.r.exchangeAuthorizationCode(CODE));
    expect(e2.kind).toBe("retryable");
    expectNoSecret(e2);
  });

  test("an unusable private key fails before anything is sent", async () => {
    const { r, calls } = revoker([], "not a key");
    const e = await errorOf(r.exchangeAuthorizationCode(CODE));
    expect(e.kind).toBe("retryable");
    expect(calls).toHaveLength(0);
    expectNoSecret(e);
  });
});
