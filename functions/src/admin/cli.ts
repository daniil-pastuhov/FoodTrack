/**
 * The owner's command for deletion requests by email (README "Deletion requests by email"):
 *
 *   npm run admin -- find   --email <address> | --play-order <GPA.…> | --apple-order <id> | --apple-transaction <id> | --uid <uid>
 *   npm run admin -- delete <one of the above> [--apply --confirm <uid>] [--kind <request kind>, with --uid]
 *
 * Against the emulators (project demo-foodtracker, 127.0.0.1:8080 / :9099) unless `--project` names
 * a live project; then with the operator's Application Default Credentials. `delete` is a dry run
 * unless `--apply` is given together with `--confirm` set to the uid it deletes.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { IOS_BUNDLE_ID, KMS_APPLE_TOKEN_KEY, KMS_KEY_RING, KMS_LOCATION } from "../constants";
import { loadRuntimeConfig } from "../model";
import type { AppStoreVerifier, Deps, Logger, OutputJudge, PlayVerifier, RecipeGenerator } from "../ports";
import { AppleSignInRevoker } from "../adapters/appleRevoke";
import { EnvelopeSealer, KmsKeyWrapper, LocalKeyWrapper } from "../adapters/envelope";
import { EmulatorAppleTokens } from "../emulatorFakes";
import {
  AdminRefusal,
  REQUEST_KINDS,
  deleteForRequest,
  describeAccount,
  findAccounts,
  type AccountSummary,
  type AdminStoreLookups,
  type Lookup,
  type RequestKind,
} from "./accountRequests";
import { LiveStoreLookups, accessSecret, cloudLogger, emulatorStoreLookups } from "./stores";

export const EMULATOR_PROJECT = "demo-foodtracker";

export interface AdminEnv {
  deps: Deps;
  stores: AdminStoreLookups;
  print(line: string): void;
  /** Waits for log lines still being written (live: Cloud Logging). */
  flush?(): Promise<void>;
}

const USAGE = `usage:
  npm run admin -- find   (--email <a> | --play-order <GPA.…> | --apple-order <id> | --apple-transaction <id> | --uid <uid>) [--project <id>]
  npm run admin -- delete (same lookup) [--project <id>] [--apply --confirm <uid>] [--kind <${REQUEST_KINDS.join("|")}>, with --uid]
Without --project: the local emulators (${EMULATOR_PROJECT}). delete is a dry run without --apply.`;

const OPTIONS = {
  email: { type: "string" },
  "play-order": { type: "string" },
  "apple-order": { type: "string" },
  "apple-transaction": { type: "string" },
  uid: { type: "string" },
  project: { type: "string" },
  apply: { type: "boolean" },
  confirm: { type: "string" },
  kind: { type: "string" },
} as const;

export interface ParsedArgs {
  command: "find" | "delete";
  lookup: Lookup;
  project: string;
  apply: boolean;
  confirm?: string;
  kind: RequestKind;
}

export function parseAdminArgs(argv: string[]): ParsedArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${USAGE}`);
  }
  const { values, positionals } = parsed;
  const command = positionals[0];
  if ((command !== "find" && command !== "delete") || positionals.length !== 1) throw new AdminRefusal(USAGE);
  const given = REQUEST_KINDS.filter((k) => values[k] !== undefined);
  if (given.length !== 1) throw new AdminRefusal(`Give exactly one of --${REQUEST_KINDS.join(", --")}.\n${USAGE}`);
  const kindOf = given[0];
  let kind: RequestKind = kindOf;
  if (values.kind !== undefined) {
    if (kindOf !== "uid") throw new AdminRefusal("--kind goes with --uid only (the other lookups are their own kind).");
    if (!(REQUEST_KINDS as readonly string[]).includes(values.kind)) throw new AdminRefusal(`--kind must be one of ${REQUEST_KINDS.join(", ")}.`);
    kind = values.kind as RequestKind;
  }
  if (command === "find" && (values.apply || values.confirm !== undefined)) {
    throw new AdminRefusal("--apply and --confirm belong to delete.");
  }
  return {
    command,
    lookup: { kind: kindOf, value: values[kindOf]! },
    project: values.project ?? EMULATOR_PROJECT,
    apply: values.apply === true,
    ...(values.confirm !== undefined ? { confirm: values.confirm } : {}),
    kind,
  };
}

function printSummary(print: (l: string) => void, s: AccountSummary, via: string): void {
  print(`account ${s.uid}  (uidHash ${s.uidHash}, found by ${via})`);
  print(`  state:        ${s.state}${s.deletedAt ? ` (deleted ${s.deletedAt})` : ""}`);
  print(`  auth user:    ${s.authUser ? (s.anonymous ? "yes, anonymous" : `yes, providers ${s.providers.join(", ")}`) : "none"}`);
  print(`  created:      ${s.createdAt ?? "unknown"}`);
  print(`  balance:      ${s.balance}`);
  print(`  purchases:    ${s.purchases.linked} linked, ${s.purchases.detached} detached by an earlier deletion`);
  for (const p of s.purchaseList) {
    print(`    ${p.store} ${p.productId ?? "?"} granted ${p.grantedAt ?? "never"}, ${p.state}${p.detached ? " (detached)" : ""}`);
  }
  print(`  generations:  ${s.inFlight} in flight, ${s.pendingGenerations.length} pending` +
    (s.pendingGenerations.some((g) => g.pastDeadline) ? " (some past their deadline)" : ""));
  print(`  apple token:  ${s.appleTokenStored ? "stored" : "none"}`);
  for (const hop of s.mergeChain) print(`  merged into:  ${hop.uid} (${hop.state})`);
}

/** Runs one command; throws AdminRefusal for anything refused (nothing changed then). */
export async function runAdmin(argv: string[], env: AdminEnv, parsed = parseAdminArgs(argv)): Promise<void> {
  const { deps, stores, print } = env;
  const found = await findAccounts(deps, stores, parsed.lookup);
  for (const n of found.notes) print(`note: ${n}`);
  if (found.matches.length === 0) {
    print("No account found.");
    return;
  }
  if (parsed.command === "find") {
    for (const m of found.matches) printSummary(print, await describeAccount(deps, m.uid), m.via);
    return;
  }
  if (found.matches.length > 1) {
    throw new AdminRefusal(`${found.matches.length} accounts match (${found.matches.map((m) => m.uid).join(", ")}). ` +
      `Run find, then delete each one with --uid <uid> --kind ${parsed.kind}.`);
  }
  const match = found.matches[0];
  const out = await deleteForRequest(deps, match.uid, parsed.kind, { apply: parsed.apply, ...(parsed.confirm !== undefined ? { confirm: parsed.confirm } : {}) });
  printSummary(print, out.summary, match.via);
  print(out.applied ? "Done:" : "Dry run, nothing changed. --apply would:");
  for (const s of out.plan.steps) print(`  - ${s}`);
  print("Kept:");
  for (const k of out.plan.kept) print(`  - ${k}`);
  if (out.applied && out.appleLinkRemoved === false) {
    print("Apple's link was NOT removed: tell the person to turn off Sign in with Apple for the app in their Apple Account settings.");
  }
  if (!out.applied) print(`To delete: the same command with --apply --confirm ${match.uid}`);
}

// --- Wiring -----------------------------------------------------------------------------------

/** One JSON line per log entry, on stderr, so stdout stays the report. */
const stderrLogger: Logger = {
  info: (message, data) => console.error(JSON.stringify({ severity: "INFO", message, ...data })),
  warn: (message, data) => console.error(JSON.stringify({ severity: "WARNING", message, ...data })),
  error: (message, data) => console.error(JSON.stringify({ severity: "ERROR", message, ...data })),
};

const notUsed = (what: string) => () => {
  throw new Error(`${what} is not used by the admin command`);
};

/** The committed deploy parameters (functions/.env.<project>; no secrets in it), overridable by the environment. */
function deployParams(project: string): Record<string, string> {
  const out: Record<string, string> = {};
  let text = "";
  try {
    text = readFileSync(join(__dirname, "..", "..", `.env.${project}`), "utf8");
  } catch {
    // None for this project: the environment must supply them.
  }
  for (const line of text.split("\n")) {
    const m = /^([A-Z_]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]] = m[2];
  }
  for (const k of ["APPLE_APP_ID", "APPSTORE_KEY_ID", "APPSTORE_ISSUER_ID", "APPLE_TEAM_ID", "SIWA_KEY_ID"]) {
    if (process.env[k]) out[k] = process.env[k]!;
  }
  return out;
}

function param(params: Record<string, string>, name: string, project: string): string {
  const v = params[name];
  if (!v) throw new AdminRefusal(`${name} is not set (functions/.env.${project} or the environment).`);
  return v;
}

export function buildEnv(project: string): AdminEnv {
  const live = !project.startsWith("demo-");
  if (live) {
    if (process.env.FIRESTORE_EMULATOR_HOST || process.env.FIREBASE_AUTH_EMULATOR_HOST) {
      throw new AdminRefusal("--project names a live project but emulator hosts are set in the environment; unset them first.");
    }
  } else {
    process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
    process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9099";
  }
  const app = getApps()[0] ?? initializeApp({ projectId: project });
  const db = getFirestore(app);
  const params = live ? deployParams(project) : {};
  const logging = live ? cloudLogger(project, stderrLogger) : { logger: stderrLogger, flush: async () => undefined };
  let siwa: AppleSignInRevoker | undefined;
  const appleClient = async () =>
    (siwa ??= new AppleSignInRevoker({
      teamId: param(params, "APPLE_TEAM_ID", project),
      keyId: param(params, "SIWA_KEY_ID", project),
      privateKey: await accessSecret(project, "SIWA_PRIVATE_KEY"),
      clientId: IOS_BUNDLE_ID,
    }));
  const deps: Deps = {
    db,
    auth: getAuth(app),
    play: { getPurchase: notUsed("Play"), consume: notUsed("Play"), listVoided: notUsed("Play") } as PlayVerifier,
    appStore: {
      verifyTransaction: notUsed("App Store"),
      fetchTransaction: notUsed("App Store"),
      verifyNotification: notUsed("App Store"),
      sendConsumptionInformation: notUsed("App Store"),
    } as AppStoreVerifier,
    generator: { generate: notUsed("The model") } as RecipeGenerator,
    judge: { judge: notUsed("The judge") } as OutputJudge,
    appleRevoker: live
      ? {
          revokeWithAuthorizationCode: async (code) => (await appleClient()).revokeWithAuthorizationCode(code),
          exchangeAuthorizationCode: async (code) => (await appleClient()).exchangeAuthorizationCode(code),
          revokeRefreshToken: async (token) => (await appleClient()).revokeRefreshToken(token),
        }
      : new EmulatorAppleTokens(),
    sealer: new EnvelopeSealer(
      live
        ? new KmsKeyWrapper(`projects/${project}/locations/${KMS_LOCATION}/keyRings/${KMS_KEY_RING}/cryptoKeys/${KMS_APPLE_TOKEN_KEY}`)
        // The emulator's tokens were sealed by the functions emulator's in-memory key: they don't
        // open here, so deletion removes them without a revocation (logged), as Apple is fake anyway.
        : new LocalKeyWrapper(),
    ),
    now: () => Date.now(),
    logger: logging.logger,
    config: () => loadRuntimeConfig(db, logging.logger),
  };
  const stores = live
    ? new LiveStoreLookups(project, {
        appAppleId: Number(param(params, "APPLE_APP_ID", project)),
        keyId: param(params, "APPSTORE_KEY_ID", project),
        issuerId: param(params, "APPSTORE_ISSUER_ID", project),
        // Apple's public root certificate, bundled with the functions (not a key).
        rootCertificates: [readFileSync(join(__dirname, "..", "..", "certs", "AppleRootCA-G3.cer"))],
      })
    : emulatorStoreLookups;
  return { deps, stores, print: (l) => console.log(l), flush: logging.flush };
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  let env: AdminEnv | undefined;
  try {
    const parsed = parseAdminArgs(argv);
    env = buildEnv(parsed.project);
    console.log(parsed.project.startsWith("demo-") ? `[emulators, ${parsed.project}]` : `[LIVE project ${parsed.project}]`);
    await runAdmin(argv, env, parsed);
    await env.flush?.();
  } catch (e) {
    await env?.flush?.();
    if (e instanceof AdminRefusal) {
      console.error(e.message);
      process.exitCode = 2;
      return;
    }
    const reason = (e as { details?: { reason?: string } }).details?.reason;
    console.error(`Failed${reason ? ` (${reason})` : ""}: ${String((e as Error).message ?? e).slice(0, 300)}`);
    process.exitCode = 1;
  }
}

if (require.main === module) void main();
