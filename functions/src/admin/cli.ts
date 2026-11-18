/**
 * The owner's command for deletion requests by email (README "Deletion requests by email"):
 *
 *   npm run admin -- find   --email <address> | --play-order <GPA.…> | --apple-order <id> | --apple-transaction <id> | --uid <uid>
 *   npm run admin -- delete <one of the above> [--apply --confirm <uid>] [--kind <request kind>, with --uid]
 *   npm run admin -- board  [--days 30] [--out <file.html>]   (README "The board": read-only)
 *   npm run admin -- providers list | use <profile> [--apply]  (README "Provider profiles"; `image` is the older name)
 *   npm run admin -- config list | set <id> <value> ... | reset <id> ... [--apply [--confirm <id,id>]]  (README "The owner console")
 *
 * Against the emulators (project demo-foodtracker, 127.0.0.1:8080 / :9099) unless `--project` names
 * a live project; then with the operator's Application Default Credentials. `delete` is a dry run
 * unless `--apply` is given together with `--confirm` set to the uid it deletes.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { IOS_BUNDLE_ID, KMS_APPLE_TOKEN_KEY, KMS_KEY_RING, KMS_LOCATION } from "../constants";
import { loadRuntimeConfig } from "../model";
import type { AppStoreVerifier, Deps, Logger, OutputJudge, PlayVerifier, RecipeGenerator, RecipeImageGenerator, TextTarget } from "../ports";
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
import { BOARD_MAX_DAYS, buildBoard } from "../board/boardData";
import { boardSummary, renderBoardHtml } from "../board/boardHtml";
import { readBoardInput } from "../board/boardReader";
import { LiveStoreLookups, accessSecret, cloudLogger, emulatorStoreLookups } from "./stores";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION } from "../constants";
import { providerSecrets, textProviderById } from "../providers/registry";
import { validImageSelection, type ImageSelection } from "../model";
import { SwitchRefused, activateProfile, listProfiles, type PlannedWrite, type SetupView, type SwitchEnv } from "../core/providerSwitch";
import { HttpsError } from "firebase-functions/v2/https";
import { SETTING_CHANGES, applySettings, listSettings, settingById, type Control, type SettingDef } from "../core/ownerSettings";

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
  npm run admin -- board  [--days <1-${BOARD_MAX_DAYS}, default 30>] [--out <file.html>] [--project <id>]
  npm run admin -- providers list [--project <id>]        (or: image list)
  npm run admin -- providers use <profile> [--apply] [--project <id>]
  npm run admin -- config list [--project <id>]
  npm run admin -- config set <id> <value> [<id> <value> ...] [--apply [--confirm <sensitive id,...>]] [--project <id>]
  npm run admin -- config reset <id> [<id> ...] [--apply [--confirm <sensitive id,...>]] [--project <id>]
Without --project: the local emulators (${EMULATOR_PROJECT}). delete, providers use and config set / reset are dry runs without --apply.`;

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

// --- The board (read-only) ----------------------------------------------------------------------

const BOARD_OPTIONS = {
  days: { type: "string" },
  out: { type: "string" },
  project: { type: "string" },
} as const;

export interface BoardArgs {
  days: number;
  /** Absolute path of the HTML file; default under functions/board/ (git-ignored). */
  out?: string;
  project: string;
}

export function parseBoardArgs(argv: string[]): BoardArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: BOARD_OPTIONS, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${USAGE}`);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "board" || positionals.length !== 1) throw new AdminRefusal(USAGE);
  const days = values.days === undefined ? 30 : Number(values.days);
  if (!Number.isInteger(days) || days < 1 || days > BOARD_MAX_DAYS) {
    throw new AdminRefusal(`--days must be a whole number from 1 to ${BOARD_MAX_DAYS}.`);
  }
  if (values.out !== undefined && !/\.html?$/i.test(values.out)) throw new AdminRefusal("--out must name an .html file.");
  return {
    days,
    ...(values.out !== undefined ? { out: resolve(values.out) } : {}),
    project: values.project ?? EMULATOR_PROJECT,
  };
}

/** Where the board goes without --out: functions/board/ (git-ignored). */
export function defaultBoardPath(project: string, nowMs: number): string {
  const stamp = new Date(nowMs).toISOString().slice(0, 16).replace(/[-:]/g, "").replace("T", "-");
  return join(__dirname, "..", "..", "board", `board-${project.replace(/[^a-z0-9-]/gi, "_")}-${stamp}.html`);
}

export interface BoardEnv {
  db: Firestore;
  now(): number;
  print(line: string): void;
  logger?: Logger;
}

/** Reads, aggregates, writes the HTML file and prints the summary. Writes nothing to Firestore. */
export async function runBoard(args: BoardArgs, env: BoardEnv): Promise<{ path: string; html: string }> {
  const nowMs = env.now();
  const input = await readBoardInput(env.db, { project: args.project, days: args.days, nowMs, logger: env.logger });
  const board = buildBoard(input);
  const html = renderBoardHtml(board);
  const path = args.out ?? defaultBoardPath(args.project, nowMs);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, html, { encoding: "utf8", mode: 0o600 });
  for (const line of boardSummary(board)) env.print(line);
  env.print(`Board written to ${path}`);
  return { path, html };
}

// --- Provider profiles: text and pictures (README "Provider profiles") ---------------------------------------------

const IMAGE_OPTIONS = { project: { type: "string" }, apply: { type: "boolean" } } as const;

export type ImageArgs = { command: "list"; project: string } | { command: "use"; profile: string; apply: boolean; project: string };

export function parseImageArgs(argv: string[]): ImageArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: IMAGE_OPTIONS, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${USAGE}`);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "image" && positionals[0] !== "providers") throw new AdminRefusal(USAGE);
  const project = values.project ?? EMULATOR_PROJECT;
  if (positionals[1] === "list" && positionals.length === 2) {
    if (values.apply) throw new AdminRefusal(`--apply belongs to ${positionals[0]} use.`);
    return { command: "list", project };
  }
  if (positionals[1] === "use" && positionals.length === 3) return { command: "use", profile: positionals[2], apply: values.apply === true, project };
  throw new AdminRefusal(USAGE);
}

export interface ImageEnv extends SwitchEnv {
  print(line: string): void;
  flush?(): Promise<void>;
}

const usd = (n: number) => `USD ${n.toFixed(3).replace(/0$/, "")}`;

/** The recipe text's part of a line: shown only when it is not the deploy default with no fallback. */
function textPart(s: Pick<SetupView, "textProvider" | "textModel" | "textFallbackProvider" | "textFallbackModel">, defaults: TextTarget | undefined): string {
  if (defaults && s.textProvider === defaults.provider && s.textModel === defaults.model && !s.textFallbackModel) return "";
  const fb = s.textFallbackModel ? `${s.textFallbackProvider}/${s.textFallbackModel}` : "none";
  return `; text ${s.textProvider} / ${s.textModel}, fallback ${fb}`;
}

function setupLine(s: SetupView, defaults?: TextTarget): string {
  if (s.provider === "off") return `${s.profile} (pictures off${textPart(s, defaults)})`;
  const fb = s.fallbackModel ? `${s.fallbackProvider ? `${s.fallbackProvider}/` : ""}${s.fallbackModel}` : "none";
  return `${s.profile}: ${s.provider} / ${s.model}, fallback ${fb}; step pictures at most ${s.maxStepImages}; ` +
    `pictures ${s.picturesOn ? "offered" : "NOT offered (no price, or costs.recipeImage is 0)"}${textPart(s, defaults)}`;
}

const writeLine = (w: PlannedWrite) => `  - ${w.path.join(" > ")} = ${JSON.stringify(w.value)}`;

/** `image list` and `image use`; throws AdminRefusal for a refused switch (nothing changed then). */
export async function runImage(args: ImageArgs, env: ImageEnv): Promise<void> {
  const { print } = env;
  if (args.command === "list") {
    const view = await listProfiles(env);
    print(`Active:      ${setupLine(view.active, env.textDefaults)}`);
    print(`Last change: ${view.lastChange ? `${view.lastChange.at} (${view.lastChange.via}) ${view.lastChange.from} -> ${view.lastChange.to}` : "none recorded"}`);
    print("Profiles:");
    for (const p of view.profiles) {
      const mark = p.id === view.active.profile ? "*" : " ";
      const fbAt = p.fallbackProvider || p.provider;
      const models = p.provider === "off" ? "no pictures"
        : [[p.provider, p.model], [fbAt, p.fallbackModel]].filter(([, m]) => Boolean(m)).map(([pr, m]) => `${pr}/${m} (${usd(p.usdPerImage[`${pr}/${m}`])})`).join(", fallback ");
      print(`${mark} ${p.id.padEnd(12)} ${p.ready ? "ready    " : "NOT READY"}  ${p.label}: ${models}${p.setsText ? textPart(p, undefined) : ""}`);
      for (const m of p.missing) print(`                         - ${m}`);
    }
    print("Switch with: npm run admin -- providers use <profile> (a dry run; add --apply to write).");
    return;
  }
  let out;
  try {
    out = await activateProfile(env, args.profile, { apply: args.apply, actor: { via: "cli" } });
  } catch (e) {
    if (e instanceof SwitchRefused) throw new AdminRefusal(`Refused (${e.reason}): ${e.message}`);
    throw e;
  }
  print(`From: ${setupLine(out.plan.from, env.textDefaults)}`);
  print(`To:   ${setupLine(out.plan.to, env.textDefaults)}`);
  if (!out.changed) {
    print("Already in force: nothing to write.");
    return;
  }
  print(out.applied ? "Done, written to config/runtime:" : "Dry run, nothing changed. --apply would write to config/runtime:");
  for (const w of out.plan.writes) print(writeLine(w));
  print(out.applied
    ? `Audit record providerProfileChanges/${out.changeId}. New requests use it within 30 s (the instances' config cache).`
    : `To switch: npm run admin -- providers use ${args.profile} --apply${args.project === EMULATOR_PROJECT ? "" : ` --project ${args.project}`}`);
}

export function buildImageEnv(project: string): ImageEnv {
  const { live, app } = adminApp(project);
  const db = getFirestore(app);
  const params = live ? deployParams(project) : {};
  const logging = live ? cloudLogger(project, stderrLogger) : { logger: stderrLogger, flush: async () => undefined };
  // The deploy's picture defaults, as the functions read them (only shown when config/runtime.image says nothing).
  const fromParams = live
    ? validImageSelection({
        ...(params.IMAGE_PROVIDER ? { provider: params.IMAGE_PROVIDER } : {}),
        ...(params.IMAGE_MODEL ? { model: params.IMAGE_MODEL } : {}),
        ...(params.IMAGE_FALLBACK_MODEL !== undefined ? { fallbackModel: params.IMAGE_FALLBACK_MODEL } : {}),
      }, DEFAULT_IMAGE_SELECTION)
    : null;
  const imageDefaults: ImageSelection = fromParams ?? { ...DEFAULT_IMAGE_SELECTION };
  // The text's deploy default, as the functions read it (TEXT_PROVIDER and its model param).
  const textD = textProviderById(params.TEXT_PROVIDER || DEFAULT_TEXT_SELECTION.provider) ?? textProviderById(DEFAULT_TEXT_SELECTION.provider)!;
  const textDefaults: TextTarget = { provider: textD.id, model: (textD.defaultModelParam && params[textD.defaultModelParam]) || textD.defaultModel };
  return {
    db,
    now: () => Date.now(),
    logger: logging.logger,
    imageDefaults,
    textDefaults,
    // Live: the operator's credentials read the secret (and drop it); the emulators hold no secrets.
    secretReadable: async (id) => {
      const s = providerSecrets().find((x) => x.id === id);
      return live && s ? accessSecret(project, params[s.param] || s.name).then(() => true, () => false) : false;
    },
    print: (l) => console.log(l),
    flush: logging.flush,
  };
}

// --- Runtime settings (README "The owner console", "config/runtime") ---------------------------

const CONFIG_OPTIONS = { project: { type: "string" }, apply: { type: "boolean" }, confirm: { type: "string" } } as const;

export type ConfigArgs =
  | { command: "list"; project: string }
  | { command: "set"; pairs: [string, string][]; apply: boolean; confirm: string[]; project: string }
  | { command: "reset"; ids: string[]; apply: boolean; confirm: string[]; project: string };

export function parseConfigArgs(argv: string[]): ConfigArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: CONFIG_OPTIONS, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${USAGE}`);
  }
  const { values, positionals } = parsed;
  if (positionals[0] !== "config") throw new AdminRefusal(USAGE);
  const project = values.project ?? EMULATOR_PROJECT;
  const confirm = values.confirm === undefined ? [] : values.confirm.split(",").map((s) => s.trim()).filter(Boolean);
  const rest = positionals.slice(2);
  if (positionals[1] === "list" && rest.length === 0) {
    if (values.apply || values.confirm !== undefined) throw new AdminRefusal("--apply and --confirm belong to config set and config reset.");
    return { command: "list", project };
  }
  if (values.confirm !== undefined && !values.apply) throw new AdminRefusal("--confirm goes with --apply.");
  if (positionals[1] === "set" && rest.length >= 2 && rest.length % 2 === 0) {
    const pairs: [string, string][] = [];
    for (let i = 0; i < rest.length; i += 2) pairs.push([rest[i], rest[i + 1]]);
    return { command: "set", pairs, apply: values.apply === true, confirm, project };
  }
  if (positionals[1] === "reset" && rest.length >= 1) return { command: "reset", ids: rest, apply: values.apply === true, confirm, project };
  throw new AdminRefusal(USAGE);
}

/** A value typed on the command line, as its setting's control takes it (micro-USD for USD amounts). */
export function cliValue(def: SettingDef, raw: string): unknown {
  const c = def.control;
  switch (c.kind) {
    case "toggle":
      if (/^(true|on)$/i.test(raw)) return true;
      if (/^(false|off)$/i.test(raw)) return false;
      throw new AdminRefusal(`${def.id}: on or off (true or false).`);
    case "number":
      if (!/^-?\d+(\.\d+)?$/.test(raw)) throw new AdminRefusal(`${def.id}: a number${c.unit === "usd" ? " in micro-USD (1000000 is USD 1)" : ""}.`);
      return Number(raw);
    case "select":
      return raw;
    case "multi":
      return raw === "" ? [] : raw.split(",").map((s) => s.trim());
    case "table":
      try {
        return JSON.parse(raw) as unknown;
      } catch {
        throw new AdminRefusal(`${def.id}: a JSON table such as {"1":{"1":1,"2":2,"3":3},...}.`);
      }
  }
}

/** A setting's value as the command prints it. */
export function showSetting(control: Control, v: unknown): string {
  if (v === null || v === undefined) return control.kind === "number" && control.emptyMeans ? control.emptyMeans : "none";
  switch (control.kind) {
    case "toggle":
      return v ? "on" : "off";
    case "number":
      if (control.unit === "usd") return `USD ${((v as number) / 1_000_000).toFixed(control.step < 10_000 ? 3 : 2)} (${v})`;
      return v === 0 && control.offAtZero ? `0 (${control.offAtZero})` : String(v);
    case "multi":
      return (v as string[]).length ? (v as string[]).join(",") : "none";
    default:
      return typeof v === "string" ? v : JSON.stringify(v);
  }
}

export async function runConfig(args: ConfigArgs, env: ImageEnv): Promise<void> {
  const { print } = env;
  if (args.command === "list") {
    const view = await listSettings(env);
    if (view.invalid.length) print(`Invalid as stored (their safe values are in force): ${view.invalid.join(", ")}`);
    for (const s of view.sections) {
      print(`## ${s.title}`);
      for (const x of s.settings) {
        const marks = [x.overridden ? "set" : "", x.changed ? "changed from default" : "", x.sensitive ? "sensitive" : "", x.blocked ? "BLOCKED" : ""].filter(Boolean);
        print(`  ${x.id.padEnd(30)} ${showSetting(x.control, x.value)}   (default ${showSetting(x.control, x.default)})${marks.length ? `  [${marks.join(", ")}]` : ""}`);
        if (x.lastChange) print(`  ${"".padEnd(30)} last changed ${x.lastChange.at} (${x.lastChange.via})`);
      }
    }
    print("## Read-only here");
    for (const r of view.readOnly) print(`  ${r.id.padEnd(30)} ${JSON.stringify(r.value)}   (default ${JSON.stringify(r.default)})`);
    print("Change with: npm run admin -- config set <id> <value> [<id> <value> ...] | config reset <id> ... (dry runs; --apply writes, and sensitive ones need --confirm <id,id>).");
    return;
  }
  const changes = args.command === "set"
    ? args.pairs.map(([id, raw]) => {
        const def = settingById(id);
        if (!def) throw new AdminRefusal(`No setting ${id}; config list shows them.`);
        return { id, value: cliValue(def, raw) };
      })
    : args.ids.map((id) => {
        if (!settingById(id)) throw new AdminRefusal(`No setting ${id}; config list shows them.`);
        return { id, reset: true };
      });
  let out;
  try {
    out = await applySettings(env, changes, { apply: args.apply, actor: { via: "cli" }, confirmSensitive: args.confirm });
  } catch (e) {
    if (e instanceof SwitchRefused) throw new AdminRefusal(`Refused (${e.reason}): ${e.message}`);
    if (e instanceof HttpsError) throw new AdminRefusal(`Refused: ${e.message}`);
    throw e;
  }
  const plan = out.plan;
  for (const id of plan.unchanged) print(`${id}: already so, nothing to write`);
  if (!out.changed) {
    print("Nothing to write.");
    return;
  }
  for (const c of plan.changes) {
    const ctl = settingById(c.id)!.control;
    print(`${c.id}: ${showSetting(ctl, c.from)} -> ${showSetting(ctl, c.to)}${c.reset ? " (reset to default)" : ""}${c.sensitive ? "  [sensitive]" : ""}`);
  }
  for (const s of plan.sideEffects) print(`  also ${s.id}: ${showSetting(settingById(s.id)!.control, s.from)} -> ${showSetting(settingById(s.id)!.control, s.to)}`);
  print(out.applied ? "Done, written to config/runtime:" : "Dry run, nothing changed. --apply would write to config/runtime:");
  for (const w of plan.writes) print(`  - ${w.path.join(" > ")}${w.delete ? " deleted" : ` = ${JSON.stringify(w.value)}`}`);
  if (out.applied) {
    print(`Audit record ${SETTING_CHANGES}/${out.changeId}. New requests use it within 30 s (the instances' config cache).`);
    return;
  }
  const verb = args.command === "set" ? `set ${args.pairs.map(([id, v]) => `${id} ${JSON.stringify(v)}`).join(" ")}` : `reset ${args.ids.join(" ")}`;
  const project = args.project === EMULATOR_PROJECT ? "" : ` --project ${args.project}`;
  print(`To write: npm run admin -- config ${verb} --apply${plan.sensitive.length ? ` --confirm ${plan.sensitive.join(",")}` : ""}${project}`);
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

/**
 * The Admin SDK app for `project`: the emulators for a demo- project (their default hosts unless
 * the environment names others), the live project only when named, and never a live project while
 * emulator hosts are set.
 */
function adminApp(project: string) {
  const live = !project.startsWith("demo-");
  if (live) {
    if (process.env.FIRESTORE_EMULATOR_HOST || process.env.FIREBASE_AUTH_EMULATOR_HOST) {
      throw new AdminRefusal("--project names a live project but emulator hosts are set in the environment; unset them first.");
    }
  } else {
    process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
    process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9099";
  }
  return { live, app: getApps()[0] ?? initializeApp({ projectId: project }) };
}

export function buildBoardEnv(project: string): BoardEnv {
  const { app } = adminApp(project);
  return { db: getFirestore(app), now: () => Date.now(), print: (l) => console.log(l), logger: stderrLogger };
}

export function buildEnv(project: string): AdminEnv {
  const { live, app } = adminApp(project);
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
    imageGenerator: { generate: notUsed("The image model") } as RecipeImageGenerator,
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
    if (argv[0] === "image" || argv[0] === "providers") {
      const args = parseImageArgs(argv);
      const imageEnv = buildImageEnv(args.project);
      console.log(args.project.startsWith("demo-") ? `[emulators, ${args.project}]` : `[LIVE project ${args.project}]`);
      try {
        await runImage(args, imageEnv);
      } finally {
        await imageEnv.flush?.();
      }
      return;
    }
    if (argv[0] === "config") {
      const args = parseConfigArgs(argv);
      const configEnv = buildImageEnv(args.project);
      console.log(args.project.startsWith("demo-") ? `[emulators, ${args.project}]` : `[LIVE project ${args.project}]`);
      try {
        await runConfig(args, configEnv);
      } finally {
        await configEnv.flush?.();
      }
      return;
    }
    if (argv[0] === "board") {
      const args = parseBoardArgs(argv);
      const boardEnv = buildBoardEnv(args.project);
      console.log(args.project.startsWith("demo-") ? `[emulators, ${args.project}]` : `[LIVE project ${args.project}, read-only]`);
      await runBoard(args, boardEnv);
      return;
    }
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
