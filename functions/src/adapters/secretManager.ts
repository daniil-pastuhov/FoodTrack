/**
 * Reads one Secret Manager secret at run time, as the functions' service account, and keeps it in
 * memory for `ttlMs`. For credentials that only one of several switchable providers needs (the
 * OpenAI API key, README "Recipe pictures"): a secret bound with defineSecret must exist at every
 * deploy, while this one is read only when its provider is selected, so the provider can be
 * switched in `config/runtime` without a deploy. The service account needs
 * roles/secretmanager.secretAccessor on that secret alone (docs/recipe-setup.md).
 *
 * The value is never logged, stored or put in an error message.
 */
import { GoogleAuth } from "google-auth-library";

export interface SecretReaderOptions {
  project: string;
  secret: string;
  ttlMs?: number;
  now?: () => number;
  /** For tests: returns the secret's payload. */
  access?: (url: string) => Promise<string>;
}

export class SecretReader {
  private value: string | undefined;
  private readAt = Number.NEGATIVE_INFINITY;
  private inFlight: Promise<string> | undefined;
  private auth: GoogleAuth | undefined;

  constructor(private readonly options: SecretReaderOptions) {}

  private url(): string {
    return `https://secretmanager.googleapis.com/v1/projects/${encodeURIComponent(this.options.project)}` +
      `/secrets/${encodeURIComponent(this.options.secret)}/versions/latest:access`;
  }

  private async access(): Promise<string> {
    if (this.options.access) return this.options.access(this.url());
    this.auth ??= new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/cloud-platform"] });
    const client = await this.auth.getClient();
    const res = await client.request<{ payload?: { data?: string } }>({ url: this.url(), timeout: 10_000 });
    const data = res.data.payload?.data;
    if (!data) throw new Error("secret has no payload");
    return Buffer.from(data, "base64").toString("utf8").trim();
  }

  get(): Promise<string> {
    const now = (this.options.now ?? Date.now)();
    if (this.value !== undefined && now - this.readAt < (this.options.ttlMs ?? 10 * 60_000)) return Promise.resolve(this.value);
    this.inFlight ??= this.access().then(
      (v) => {
        this.value = v;
        this.readAt = (this.options.now ?? Date.now)();
        this.inFlight = undefined;
        return v;
      },
      () => {
        this.inFlight = undefined;
        // A fixed message: the cause could name the project or the secret's path.
        throw new Error("secret unavailable");
      },
    );
    return this.inFlight;
  }
}
