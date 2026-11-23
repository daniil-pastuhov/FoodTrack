/**
 * The owner's command for the home worker (core/homeWorker.ts, README "Home worker"):
 *
 *   npm run admin -- home-worker list [--project <id>]                          the setting, the testers (with their sign-in providers)
 *   npm run admin -- home-worker add <uid> | remove <uid> [--apply] [--project <id>]
 *   npm run admin -- home-worker add-email <email> | remove-email <email> [--apply] [--project <id>]
 *   npm run admin -- home-worker on | off [--apply] [--project <id>]
 *   npm run admin -- home-worker wait <recipe|menu|recipeImage> <ms> [--apply] [--project <id>]
 *   npm run admin -- home-worker url <https-url> | url none [--apply] [--project <id>]
 *
 * Every change is a dry run without --apply. It writes only `config/runtime.homeWorker` (in a
 * transaction, checked by the parser the functions use), so instances pick it up within 30 s.
 * `add` refuses a uid that has no Firebase Auth user: the list is for the owner's own accounts.
 * An email matches only a caller whose ID token says that address is verified (core/homeWorker.ts).
 */
import { parseArgs } from "node:util";
import type { Auth } from "firebase-admin/auth";
import type { Firestore } from "firebase-admin/firestore";
import { HOME_WORKER } from "../constants";
import { HOME_WAIT_KINDS, TESTER_UID, homeWorkerUrlOf, testerEmailOf, validHomeWorker, type HomeWaitKind, type HomeWorkerConfig } from "../model";
import { maskedUrl } from "../core/homeWorker";

export { maskedUrl };
import { AdminRefusal } from "./accountRequests";

export const HOME_WORKER_USAGE = `usage:
  npm run admin -- home-worker list [--project <id>]
  npm run admin -- home-worker add <uid> | remove <uid> [--apply] [--project <id>]
  npm run admin -- home-worker add-email <email> | remove-email <email> [--apply] [--project <id>]
  npm run admin -- home-worker on | off [--apply] [--project <id>]
  npm run admin -- home-worker wait <recipe|menu|recipeImage> <ms: ${HOME_WAIT_KINDS.map((k) => `${k} ${HOME_WORKER.minWaitMs}-${HOME_WORKER.maxWaitMs[k]}`).join(", ")}> [--apply] [--project <id>]
  npm run admin -- home-worker url <https://your-tunnel-address> | url none [--apply] [--project <id>]
Only the owner's own accounts belong on the list (README "Home worker"). Changes are dry runs without --apply.`;

export type HomeWorkerArgs =
  | { command: "list"; project: string }
  | { command: "add" | "remove"; uid: string; apply: boolean; project: string }
  | { command: "on" | "off"; apply: boolean; project: string }
  | { command: "add-email" | "remove-email"; email: string; apply: boolean; project: string }
  | { command: "wait"; kind: HomeWaitKind; ms: number; apply: boolean; project: string }
  | { command: "url"; url: string; apply: boolean; project: string };

export function parseHomeWorkerArgs(argv: string[], emulatorProject: string): HomeWorkerArgs {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: { project: { type: "string" }, apply: { type: "boolean" } }, allowPositionals: true, strict: true });
  } catch (e) {
    throw new AdminRefusal(`${(e as Error).message}\n${HOME_WORKER_USAGE}`);
  }
  const { values, positionals } = parsed;
  const project = values.project ?? emulatorProject;
  const apply = values.apply === true;
  const [first, command, a, b] = positionals;
  if (first !== "home-worker") throw new AdminRefusal(HOME_WORKER_USAGE);
  if (command === "list" && positionals.length === 2) {
    if (apply) throw new AdminRefusal("--apply belongs to a change, not to list.");
    return { command, project };
  }
  if ((command === "add" || command === "remove") && positionals.length === 3) {
    if (!TESTER_UID.test(a)) throw new AdminRefusal(`Not a uid: ${JSON.stringify(a)}.`);
    return { command, uid: a, apply, project };
  }
  if ((command === "add-email" || command === "remove-email") && positionals.length === 3) {
    const email = testerEmailOf(a);
    if (email === null) throw new AdminRefusal("Not an email address.");
    return { command, email, apply, project };
  }
  if ((command === "on" || command === "off") && positionals.length === 2) return { command, apply, project };
  if (command === "wait" && positionals.length === 4) {
    if (!(HOME_WAIT_KINDS as readonly string[]).includes(a)) throw new AdminRefusal(`wait takes ${HOME_WAIT_KINDS.join(", ")}.\n${HOME_WORKER_USAGE}`);
    const kind = a as HomeWaitKind;
    const ms = Number(b);
    if (!Number.isSafeInteger(ms) || ms < HOME_WORKER.minWaitMs || ms > HOME_WORKER.maxWaitMs[kind]) {
      throw new AdminRefusal(`The ${kind} wait must be ${HOME_WORKER.minWaitMs} to ${HOME_WORKER.maxWaitMs[kind]} ms.`);
    }
    return { command, kind, ms, apply, project };
  }
  if (command === "url" && positionals.length === 3) {
    if (a === "none") return { command, url: "", apply, project };
    const url = homeWorkerUrlOf(a);
    if (url === null) throw new AdminRefusal(`The url must be https, with no user, query or fragment, at most ${HOME_WORKER.maxUrlChars} characters.`);
    return { command, url, apply, project };
  }
  throw new AdminRefusal(HOME_WORKER_USAGE);
}


export interface HomeWorkerCommandEnv {
  db: Firestore;
  auth: Auth;
  print(line: string): void;
}

const RUNTIME = "config/runtime";

/** The stored setting, or the off one when there is none; refuses one the functions would read as invalid. */
function current(data: Record<string, unknown> | undefined): HomeWorkerConfig {
  const raw = data?.homeWorker;
  if (raw === undefined) return { enabled: false, testers: [], emails: [], waitMs: { ...HOME_WORKER.waitMs }, url: "" };
  const v = validHomeWorker(raw);
  if (!v) throw new AdminRefusal(`${RUNTIME}.homeWorker is invalid (the functions read it as off): fix or delete it by hand first. It is: ${JSON.stringify(raw)}`);
  return v;
}

async function describeUid(auth: Auth, uid: string): Promise<string> {
  try {
    const u = await auth.getUser(uid);
    const providers = u.providerData.map((p) => p.providerId);
    return providers.length ? providers.join(", ") : "anonymous";
  } catch {
    return "NO SUCH USER";
  }
}

export async function runHomeWorkerCommand(args: HomeWorkerArgs, env: HomeWorkerCommandEnv): Promise<void> {
  const { db, auth, print } = env;
  const ref = db.doc(RUNTIME);
  if (args.command === "list") {
    const now = current((await ref.get()).data());
    print(`Home worker: ${now.enabled ? "ON" : "off"}; url ${maskedUrl(now.url)}; waits ${now.waitMs.recipe} ms (recipe), ${now.waitMs.menu} ms (menu), ${now.waitMs.recipeImage} ms (recipe with pictures).`);
    if (now.enabled && !now.url) print("Note: on, but no url is set, so nothing goes to the server.");
    if (!now.testers.length && !now.emails.length) print("Testers: none (nobody's recipes go to the server).");
    for (const uid of now.testers) print(`  uid ${uid}  (${await describeUid(auth, uid)})`);
    for (const email of now.emails) print(`  email ${email}  (matched only while the account's ID token says it is verified)`);
    return;
  }
  if (args.command === "add") {
    const who = await describeUid(auth, args.uid);
    if (who === "NO SUCH USER") throw new AdminRefusal(`No Firebase Auth user has the uid ${args.uid}.`);
    print(`${args.uid}: signed in with ${who}. Only the owner's own accounts belong on the list.`);
  }
  const next = (before: HomeWorkerConfig): HomeWorkerConfig => {
    switch (args.command) {
      case "add":
        if (before.testers.includes(args.uid)) return before;
        if (before.testers.length >= HOME_WORKER.maxTesters) throw new AdminRefusal(`The list holds at most ${HOME_WORKER.maxTesters} uids.`);
        return { ...before, testers: [...before.testers, args.uid] };
      case "remove":
        if (!before.testers.includes(args.uid)) throw new AdminRefusal(`${args.uid} is not on the list.`);
        return { ...before, testers: before.testers.filter((t) => t !== args.uid) };
      case "add-email":
        if (before.emails.includes(args.email)) return before;
        if (before.emails.length >= HOME_WORKER.maxEmails) throw new AdminRefusal(`The list holds at most ${HOME_WORKER.maxEmails} emails.`);
        return { ...before, emails: [...before.emails, args.email] };
      case "remove-email":
        if (!before.emails.includes(args.email)) throw new AdminRefusal("That email is not on the list.");
        return { ...before, emails: before.emails.filter((e) => e !== args.email) };
      case "on":
      case "off":
        return { ...before, enabled: args.command === "on" };
      case "wait":
        return { ...before, waitMs: { ...before.waitMs, [args.kind]: args.ms } };
      case "url":
        return { ...before, url: args.url };
    }
  };
  const outcome = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const before = current(snap.data());
    const after = next(before);
    if (!validHomeWorker(after)) throw new AdminRefusal("The result would be invalid; nothing written.");
    const changed = JSON.stringify(before) !== JSON.stringify(after);
    if (changed && args.apply) {
      if (snap.exists) tx.update(ref, "homeWorker", after);
      else tx.set(ref, { homeWorker: after });
    }
    return { before, after, changed };
  });
  const line = (h: HomeWorkerConfig) => `${h.enabled ? "on" : "off"}, url ${maskedUrl(h.url)}, uids [${h.testers.join(", ")}], emails [${h.emails.join(", ")}], waits ${h.waitMs.recipe} / ${h.waitMs.menu} / ${h.waitMs.recipeImage} ms`;
  print(`From: ${line(outcome.before)}`);
  print(`To:   ${line(outcome.after)}`);
  if (!outcome.changed) print("Already so: nothing to write.");
  else if (args.apply) print(`Done, written to ${RUNTIME}.homeWorker. Instances use it within 30 s (their config cache).`);
  else print("Dry run, nothing changed. Add --apply to write it.");
  if (outcome.after.enabled && outcome.after.testers.length === 0 && outcome.after.emails.length === 0) print("Note: on, but nobody is listed, so nothing goes to the server.");
  if (outcome.after.enabled && !outcome.after.url) print("Note: on, but no url is set, so nothing goes to the server.");
}
