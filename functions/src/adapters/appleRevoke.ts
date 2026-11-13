/**
 * Sign in with Apple token revocation (Apple's account-deletion requirement): exchange a fresh
 * authorization code at /auth/token, then revoke the refresh token at /auth/revoke. The client
 * secret is an ES256 JWT signed with a Sign in with Apple key (Secret Manager).
 */
import { createPrivateKey, sign } from "node:crypto";
import type { AppleTokenRevoker } from "../ports";

export interface AppleRevokeOptions {
  teamId: string;
  keyId: string;
  /** Sign in with Apple private key (`.p8` contents). */
  privateKey: string;
  /** The bundle id for codes from the native iOS flow. */
  clientId: string;
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

export class AppleSignInRevoker implements AppleTokenRevoker {
  constructor(private readonly options: AppleRevokeOptions) {}

  private async post(path: string, form: Record<string, string>): Promise<Response> {
    const res = await fetch(`https://appleid.apple.com${path}`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form).toString(),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`Apple ${path} returned ${res.status}`);
    return res;
  }

  async revokeWithAuthorizationCode(authorizationCode: string): Promise<void> {
    const secret = clientSecret(this.options, Math.floor(Date.now() / 1000));
    const base = { client_id: this.options.clientId, client_secret: secret };
    const tokens = (await (await this.post("/auth/token", {
      ...base,
      code: authorizationCode,
      grant_type: "authorization_code",
    })).json()) as { refresh_token?: string; access_token?: string };
    const token = tokens.refresh_token ?? tokens.access_token;
    if (!token) throw new Error("Apple returned no token to revoke");
    await this.post("/auth/revoke", {
      ...base,
      token,
      token_type_hint: tokens.refresh_token ? "refresh_token" : "access_token",
    });
  }
}
