/**
 * Sign in with Apple REST API (Apple's account-deletion requirement): exchange an authorization
 * code at /auth/token for a refresh token, and revoke a token at /auth/revoke. The client secret
 * is an ES256 JWT signed with a Sign in with Apple key (Secret Manager). Errors never carry a code
 * or a token: only the endpoint, the HTTP status and Apple's `error` value.
 */
import { createPrivateKey, sign } from "node:crypto";
import { AppleTokenError, type AppleTokenRevoker } from "../ports";

export interface AppleRevokeOptions {
  teamId: string;
  keyId: string;
  /** Sign in with Apple private key (`.p8` contents). */
  privateKey: string;
  /** The bundle id for codes from the native iOS flow. */
  clientId: string;
  /** For tests; the global fetch otherwise. */
  fetch?: typeof fetch;
}

const b64url = (b: Buffer | string) => Buffer.from(b).toString("base64url");

export function clientSecret(o: AppleRevokeOptions, nowS: number): string {
  const header = b64url(JSON.stringify({ alg: "ES256", kid: o.keyId }));
  const payload = b64url(
    JSON.stringify({ iss: o.teamId, iat: nowS, exp: nowS + 300, aud: "https://appleid.apple.com", sub: o.clientId }),
  );
  const signature = sign("sha256", Buffer.from(`${header}.${payload}`), {
    key: createPrivateKey(o.privateKey),
    dsaEncoding: "ieee-p1363",
  });
  return `${header}.${payload}.${b64url(signature)}`;
}

/** Apple's `error` values (ErrorResponse); anything else is reported as "other". */
const APPLE_ERRORS = new Set([
  "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client", "unsupported_grant_type", "invalid_scope",
]);

export class AppleSignInRevoker implements AppleTokenRevoker {
  constructor(private readonly options: AppleRevokeOptions) {}

  private async post(path: "/auth/token" | "/auth/revoke", form: Record<string, string>): Promise<Response> {
    let secret: string;
    try {
      secret = clientSecret(this.options, Math.floor(Date.now() / 1000));
    } catch {
      throw new AppleTokenError("retryable", "Sign in with Apple key is not usable");
    }
    let res: Response;
    try {
      res = await (this.options.fetch ?? fetch)(`https://appleid.apple.com${path}`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: this.options.clientId, client_secret: secret, ...form }).toString(),
        signal: AbortSignal.timeout(10_000),
      });
    } catch (e) {
      // The error names the cause (timeout, DNS, reset), never the request body.
      throw new AppleTokenError("retryable", `Apple ${path} unreachable (${(e as Error).name})`);
    }
    if (res.ok) return res;
    let error = "other";
    try {
      const body = (await res.json()) as { error?: unknown };
      if (typeof body.error === "string" && APPLE_ERRORS.has(body.error)) error = body.error;
    } catch {
      // No JSON body.
    }
    const kind = res.status >= 400 && res.status < 500 && res.status !== 429 ? "rejected" : "retryable";
    throw new AppleTokenError(kind, `Apple ${path} returned ${res.status} ${error}`);
  }

  async exchangeAuthorizationCode(authorizationCode: string): Promise<string> {
    const res = await this.post("/auth/token", { code: authorizationCode, grant_type: "authorization_code" });
    let tokens: { refresh_token?: unknown };
    try {
      tokens = (await res.json()) as { refresh_token?: unknown };
    } catch {
      throw new AppleTokenError("retryable", "Apple /auth/token answered without JSON");
    }
    if (typeof tokens.refresh_token !== "string" || tokens.refresh_token.length === 0) {
      throw new AppleTokenError("retryable", "Apple /auth/token returned no refresh token");
    }
    return tokens.refresh_token;
  }

  async revokeRefreshToken(refreshToken: string): Promise<void> {
    await this.post("/auth/revoke", { token: refreshToken, token_type_hint: "refresh_token" });
  }

  async revokeWithAuthorizationCode(authorizationCode: string): Promise<void> {
    await this.revokeRefreshToken(await this.exchangeAuthorizationCode(authorizationCode));
  }
}
