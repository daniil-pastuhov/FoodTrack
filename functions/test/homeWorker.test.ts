/**
 * The home worker (core/homeWorker.ts, README "Home worker"): the owner's own test accounts' recipe
 * text from the HTTP server on the owner's computer, everyone else untouched, and every failure a
 * fallback to the normal provider. The server is a local http server here, reached through an
 * injected fetch that maps the configured https host to it.
 */
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { generateRecipe } from "../src/core/generation";
import { HttpHomeWorker, homePlanOf, homeWorkerMatch, homeWorkerWaitMs, maskedUrl, parseHomeAnswer } from "../src/core/homeWorker";
import { GENERATE_TIMEOUT_SECONDS, HOME_WORKER, JUDGE, TIMINGS } from "../src/constants";
import { emptyUser, homeWorkerUrlOf, parseRuntimeConfig, testerEmailOf } from "../src/model";
import { imageProviders, imageTimingOf } from "../src/providers/registry";
import { parseHomeWorkerArgs, runHomeWorkerCommand } from "../src/admin/homeWorkerCli";
import { AdminRefusal } from "../src/admin/accountRequests";
import { SAMPLE_BRIEF, SAMPLE_STEP_BRIEFS, auth, callerFor, db, makeDeps, passwordUser, resetEmulators, sampleMenu, sampleRecipe, setConfig, type TestDeps } from "./helpers";
import { validateGenerateRequest, type GenerateRequest } from "../src/contract";

const OWNER = "owner-tester-uid";
const OTHER = "someone-else-uid";
const SECRET = "the-shared-secret-of-the-tests-0123456789";
const URL_OK = "https://home.test";

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" },
    ingredients: [
      { name: "Pasta", expiresOn: "2030-01-01", quantity: 1 },
      { name: "crème fraîche", expiresOn: null, quantity: null },
    ],
    allergens: ["peanut"],
    allergenConsent: true,
    today: new Date().toISOString().slice(0, 10),
    ...over,
  };
}

async function seed(uid: string, balance: number) {
  await db.collection("users").doc(uid).set({ ...emptyUser(), balance, purchasedCredits: balance });
}

// --- The stand-in home server --------------------------------------------------------------------

interface Seen {
  method: string;
  path: string;
  headers: IncomingMessage["headers"];
  body: Record<string, unknown>;
}
type Handler = (seen: Seen, res: ServerResponse) => void;
let handler: Handler;
let seen: Seen[] = [];
let home: Server;
/** https host as configured -> where the test reaches it. */
const hosts = new Map<string, string>();

beforeAll(async () => {
  home = createServer((req, res) => {
    let raw = "";
    req.on("data", (d) => (raw += d));
    req.on("end", () => {
      const s = { method: req.method ?? "", path: req.url ?? "", headers: req.headers, body: raw ? JSON.parse(raw) : {} };
      seen.push(s);
      handler(s, res);
    });
  });
  await new Promise<void>((r) => home.listen(0, "127.0.0.1", r));
  hosts.set("home.test", `http://127.0.0.1:${(home.address() as AddressInfo).port}`);
  // A port nobody listens on: connection refused.
  const probe = createServer();
  await new Promise<void>((r) => probe.listen(0, "127.0.0.1", r));
  hosts.set("refused.test", `http://127.0.0.1:${(probe.address() as AddressInfo).port}`);
  await new Promise((r) => probe.close(r));
});
afterAll(() => new Promise((r) => home.close(r)));

const testFetch: typeof fetch = (input, init) => {
  const u = new URL(String(input));
  return fetch(`${hosts.get(u.host)}${u.pathname}`, init);
};

const answer = (text: string, model = "claude-sonnet-test"): Handler => (_s, res) => {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify({ text, model }));
};

let deps: TestDeps;
let audiences: string[];
let secretFails = false;
let identityFails = false;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  seen = [];
  audiences = [];
  secretFails = false;
  identityFails = false;
  handler = answer(JSON.stringify(sampleRecipe("Home pasta")));
  deps.homeWorker = new HttpHomeWorker({
    logger: deps.logger,
    secret: async () => {
      if (secretFails) throw new Error("secret unavailable");
      return SECRET;
    },
    idToken: async (audience) => {
      if (identityFails) throw new Error("no metadata server");
      audiences.push(audience);
      return `id-token-for:${audience}`;
    },
    fetch: testFetch,
  });
});

const on = (over: Record<string, unknown> = {}) => setConfig({ homeWorker: { enabled: true, testers: [OWNER], url: URL_OK, waitMs: { recipe: 5_000 }, ...over } });
const logged = (message: string) => deps.logs.filter((l) => l.message === message);

describe("routing", () => {
  test("a uid not on the list never makes a call", async () => {
    await on();
    await seed(OTHER, 3);
    const res = await generateRecipe(deps, callerFor(OTHER), request());
    expect(res).toMatchObject({ status: "DONE", balance: 2 });
    expect(seen).toHaveLength(0);
    expect(deps.generator.calls).toBe(1);
    expect(logged("home_worker")).toHaveLength(0);
    const gen = (await db.collection("users").doc(OTHER).collection("generations").get()).docs[0].data();
    expect(gen.deadlineAt.toMillis() - gen.createdAt.toMillis()).toBe(60_000);
  });

  test("off, without a url or for another uid: never asked", async () => {
    const live = parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER], url: URL_OK } });
    expect(homeWorkerWaitMs(live, OWNER, { kind: "recipe" })).toBe(HOME_WORKER.waitMs.recipe);
    expect(homeWorkerWaitMs(live, OWNER, { kind: "menu" })).toBe(HOME_WORKER.waitMs.menu);
    expect(homeWorkerWaitMs(live, OWNER, { kind: "recipe", image: "dish" })).toBe(HOME_WORKER.waitMs.recipeImage);
    expect(homeWorkerWaitMs(live, OWNER, { kind: "recipe", image: "steps" })).toBe(HOME_WORKER.waitMs.recipeImage);
    expect(homeWorkerWaitMs(live, OTHER, { kind: "recipe" })).toBe(0);
    expect(homeWorkerWaitMs(parseRuntimeConfig({ homeWorker: { enabled: false, testers: [OWNER], url: URL_OK } }), OWNER, { kind: "recipe" })).toBe(0);
    expect(homeWorkerWaitMs(parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER] } }), OWNER, { kind: "recipe" })).toBe(0);
    // A url that is not https turns the whole setting off.
    expect(homeWorkerWaitMs(parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER], url: "http://home.test" } }), OWNER, { kind: "recipe" })).toBe(0);
    await on({ url: "" });
    await seed(OWNER, 3);
    await generateRecipe(deps, callerFor(OWNER), request());
    expect(seen).toHaveLength(0);
    expect(deps.generator.calls).toBe(1);
  });

  test("a tester's text comes from the server, with the ID token, the secret and the prompt only, through the usual checks and the judge", async () => {
    await on();
    await setConfig({ outputJudge: "enforce" });
    await seed(OWNER, 3);
    handler = answer("```json\n" + JSON.stringify(sampleRecipe("Home pasta")) + "\n```");
    const res = await generateRecipe(deps, callerFor(OWNER), request());
    expect(res).toMatchObject({ status: "DONE", charged: true, balance: 2 });
    expect(res.recipe?.title).toBe("Home pasta");
    expect(deps.generator.calls).toBe(0);
    expect(deps.judge.calls).toBe(1);
    expect(seen).toHaveLength(1);
    const call = seen[0];
    expect(call).toMatchObject({ method: "POST", path: "/v1/text" });
    expect(audiences).toEqual([URL_OK]);
    expect(call.headers.authorization).toBe(`Bearer id-token-for:${URL_OK}`);
    expect(call.headers["x-worker-secret"]).toBe(SECRET);
    expect(Object.keys(call.body).sort()).toEqual(["kind", "maxOutputTokens", "schema", "systemInstruction", "timeoutMs", "userText"]);
    expect(call.body).toMatchObject({ kind: "recipe", maxOutputTokens: 4096, timeoutMs: 5_000 - HOME_WORKER.serverMarginMs });
    expect(call.body.userText).toContain("Pasta");
    expect(JSON.stringify(call.body)).not.toContain(OWNER);
    expect(logged("home_worker")[0].data).toMatchObject({ outcome: "answered", status: 200 });
    expect(JSON.stringify(logged("home_worker")[0].data)).not.toContain("home.test");
    expect(logged("llm_call")[0].data).toMatchObject({ provider: "home", textFallback: false, model: "claude-sonnet-test", outcome: "done" });
    const gen = (await db.collection("users").doc(OWNER).collection("generations").get()).docs[0].data();
    expect(gen.deadlineAt.toMillis() - gen.createdAt.toMillis()).toBe(60_000 + 5_000);
  });

  test("an answer the output checks refuse is the usual content failure, not a second call", async () => {
    await on();
    await seed(OWNER, 3);
    handler = answer(JSON.stringify({ title: "Only a title" }));
    const res = await generateRecipe(deps, callerFor(OWNER), request());
    expect(res.status).toBe("FAILED");
    expect(deps.generator.calls).toBe(0);
    expect(logged("llm_call")[0].data).toMatchObject({ provider: "home", outcome: "content" });
  });

  const json = (status: number, body: unknown, headers: Record<string, string> = {}): Handler => (_s, res) => {
    res.writeHead(status, { "content-type": "application/json", ...headers });
    res.end(typeof body === "string" ? body : JSON.stringify(body));
  };
  test.each<[string, () => void, string, Record<string, unknown>?]>([
    ["no answer in time", () => (handler = () => undefined), "timeout", { waitMs: { recipe: 1_000 } }],
    ["connection refused", () => undefined, "unreachable", { url: "https://refused.test" }],
    ["busy (429)", () => (handler = json(429, { error: "BUSY" })), "status"],
    ["a server error (502)", () => (handler = json(502, { error: "CLI_FAILED" })), "status"],
    ["a 401", () => (handler = json(401, { error: "UNAUTHORIZED" })), "status"],
    ["a redirect", () => (handler = json(302, "", { location: "https://elsewhere.test/v1/text" })), "unreachable"],
    ["a body that is not JSON", () => (handler = json(200, "<html>tunnel error</html>")), "unusable"],
    ["a text that is not JSON", () => (handler = answer("Sorry, I can't help with that.")), "unusable"],
    ["no text", () => (handler = json(200, { model: "x" })), "unusable"],
    ["a text over the cap", () => (handler = answer("x".repeat(HOME_WORKER.maxResultChars + 1))), "oversized"],
    ["a body over the cap", () => (handler = json(200, "x".repeat(HOME_WORKER.maxResponseBytes + 1))), "oversized"],
    ["no secret", () => (secretFails = true), "no_secret"],
    ["no ID token", () => (identityFails = true), "no_identity"],
  ])("%s: the usual provider writes it", async (_name, arrange, outcome, over) => {
    arrange();
    await on(over);
    await seed(OWNER, 3);
    const res = await generateRecipe(deps, callerFor(OWNER), request());
    expect(res).toMatchObject({ status: "DONE", balance: 2 });
    expect(res.recipe?.title).toBe("Pantry pasta");
    expect(deps.generator.calls).toBe(1);
    expect(logged("home_worker")[0].data).toMatchObject({ outcome });
    expect(logged("llm_call")[0].data?.provider).toBeUndefined();
    if (outcome === "no_secret" || outcome === "no_identity") expect(seen).toHaveLength(0);
  });
});

describe("testers by email", () => {
  const live = parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER], emails: ["owner@example.com"], url: URL_OK } });

  test("a verified email on the list matches, case aside; unverified, another address or none never does", () => {
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: "owner@example.com" })).toBe("email");
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: "Owner@Example.COM" })).toBe("email");
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: null })).toBeNull();
    expect(homeWorkerMatch(live, { uid: "x" })).toBeNull();
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: "someone@example.com" })).toBeNull();
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: "owner@example.com.evil.test" })).toBeNull();
    expect(homeWorkerMatch(live, { uid: "x", verifiedEmail: "xowner@example.com" })).toBeNull();
    // The uid list still works (an anonymous account has no email), and comes first.
    expect(homeWorkerMatch(live, { uid: OWNER, verifiedEmail: null })).toBe("uid");
    // Off, or no url: nobody.
    expect(homeWorkerMatch(parseRuntimeConfig({ homeWorker: { enabled: false, emails: ["owner@example.com"], url: URL_OK } }), { uid: "x", verifiedEmail: "owner@example.com" })).toBeNull();
    expect(homeWorkerMatch(parseRuntimeConfig({ homeWorker: { enabled: true, emails: ["owner@example.com"] } }), { uid: "x", verifiedEmail: "owner@example.com" })).toBeNull();
  });

  test("a caller found by verified email gets the server's text; the log says how, never the address", async () => {
    await setConfig({ homeWorker: { enabled: true, emails: ["owner@example.com"], url: URL_OK, waitMs: { recipe: 5_000 } } });
    await seed(OTHER, 3);
    const res = await generateRecipe(deps, { ...callerFor(OTHER, "google.com"), verifiedEmail: "owner@example.com" }, request());
    expect(res.recipe?.title).toBe("Home pasta");
    expect(seen).toHaveLength(1);
    expect(logged("home_worker")[0].data).toMatchObject({ outcome: "answered", match: "email" });
    expect(JSON.stringify(deps.logs)).not.toContain("owner@example.com");
    expect(JSON.stringify(seen[0].body)).not.toContain("owner@example.com");
  });

  test("the same email unverified, or another account's, sends nothing", async () => {
    await setConfig({ homeWorker: { enabled: true, emails: ["owner@example.com"], url: URL_OK } });
    await seed(OTHER, 3);
    await generateRecipe(deps, { ...callerFor(OTHER, "password"), verifiedEmail: null }, request());
    await generateRecipe(deps, { ...callerFor(OTHER, "google.com"), verifiedEmail: "other@example.com" }, request());
    expect(seen).toHaveLength(0);
    expect(deps.generator.calls).toBe(2);
    expect(logged("home_worker")).toHaveLength(0);
  });

  test("by uid the log says uid", async () => {
    await on();
    await seed(OWNER, 3);
    await generateRecipe(deps, callerFor(OWNER), request());
    expect(logged("home_worker")[0].data).toMatchObject({ outcome: "answered", match: "uid" });
  });
});

describe("menus and recipes with pictures", () => {
  test("a menu from the server: its own wait, the reservation lengthened by it, the usual checks", async () => {
    await on({ waitMs: { menu: 7_000 } });
    await seed(OWNER, 10);
    const raw = request({ kind: "menu", courses: 2 });
    handler = (_s, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ text: JSON.stringify(sampleMenu(validateGenerateRequest(raw, Date.now()) as GenerateRequest)), model: "claude-test" }));
    };
    const res = await generateRecipe(deps, callerFor(OWNER), raw);
    expect(res).toMatchObject({ status: "DONE", charged: true });
    expect(res.menu?.days).toHaveLength(3);
    expect(deps.generator.calls).toBe(0);
    expect(seen[0].body).toMatchObject({ kind: "menu", timeoutMs: 7_000 - HOME_WORKER.serverMarginMs });
    const gen = (await db.collection("users").doc(OWNER).collection("generations").get()).docs[0].data();
    expect(gen.deadlineAt.toMillis() - gen.createdAt.toMillis()).toBe(TIMINGS.menu.reservationMs + 7_000);
  });

  test("a menu the server can't write in time: the usual provider writes it", async () => {
    await on({ waitMs: { menu: 1_000 } });
    await seed(OWNER, 10);
    handler = () => undefined;
    const res = await generateRecipe(deps, callerFor(OWNER), request({ kind: "menu", courses: 2 }));
    expect(res).toMatchObject({ status: "DONE" });
    expect(deps.generator.calls).toBe(1);
    expect(logged("home_worker")[0].data).toMatchObject({ outcome: "timeout", waitMs: 1_000 });
  });

  test.each(["dish", "steps"] as const)("a recipe with pictures (%s): the text, brief and step marks from the server, the pictures from the picture router", async (level) => {
    await on({ waitMs: { recipeImage: 6_000 } });
    await seed(OWNER, 10);
    const answer = { ...sampleRecipe("Home pasta"), imageBrief: SAMPLE_BRIEF, ...(level === "steps" ? { stepBriefs: SAMPLE_STEP_BRIEFS } : {}) };
    handler = (_s, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ text: JSON.stringify(answer), model: "claude-test" }));
    };
    const res = await generateRecipe(deps, callerFor(OWNER), request({ image: level }));
    expect(res).toMatchObject({ status: "DONE", charged: true });
    expect(res.recipe?.title).toBe("Home pasta");
    expect(res.image).toMatchObject({ mimeType: "image/jpeg" });
    expect(deps.generator.calls).toBe(0);
    // The schema the server got asks for the brief (and the step marks) exactly as a provider's does.
    const schema = seen[0].body.schema as { properties: Record<string, unknown>; required: string[] };
    expect(schema.required).toContain("imageBrief");
    if (level === "steps") expect(schema.required).toContain("stepBriefs");
    expect(seen[0].body).toMatchObject({ timeoutMs: 6_000 - HOME_WORKER.serverMarginMs });
    // The pictures: the usual router, from the checked brief.
    expect(deps.imageGenerator.calls).toBe(level === "steps" ? 1 + SAMPLE_STEP_BRIEFS.length : 1);
    expect(deps.imageGenerator.prompts[0].text).toContain("glossy tomato sauce");
    const gen = (await db.collection("users").doc(OWNER).collection("generations").get()).docs[0].data();
    expect(gen.deadlineAt.toMillis() - gen.createdAt.toMillis()).toBe(TIMINGS.recipe.reservationMs + 6_000 + imageTimingOf("vertex").reservationExtraMs[level]);
  });

  test("a recipe with pictures whose server answer fails: the usual provider writes the text, the pictures still come", async () => {
    await on({ waitMs: { recipeImage: 1_000 } });
    await seed(OWNER, 10);
    handler = (_s, res) => {
      res.writeHead(503, { "content-type": "application/json" });
      res.end("{}");
    };
    const res = await generateRecipe(deps, callerFor(OWNER), request({ image: "dish" }));
    expect(res).toMatchObject({ status: "DONE" });
    expect(res.image).toBeDefined();
    expect(deps.generator.calls).toBe(1);
    expect(deps.imageGenerator.calls).toBe(1);
  });

  test("homePlanOf picks the wait by what is asked", () => {
    const c = parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER], url: URL_OK, waitMs: { recipe: 10_000, menu: 20_000, recipeImage: 30_000 } } });
    expect(homePlanOf(c, { uid: OWNER }, { kind: "recipe" })).toEqual({ url: URL_OK, waitMs: 10_000, match: "uid" });
    expect(homePlanOf(c, { uid: OWNER }, { kind: "menu" })?.waitMs).toBe(20_000);
    expect(homePlanOf(c, { uid: OWNER }, { kind: "recipe", image: "dish" })?.waitMs).toBe(30_000);
    expect(maskedUrl("https://my-laptop.tail1234.ts.net")).toBe("https://my-…ts.net");
  });
});

describe("config/runtime.homeWorker", () => {
  test("missing is off; invalid turns it off and is logged", () => {
    expect(parseRuntimeConfig({}).homeWorker).toEqual({ enabled: false, testers: [], emails: [], waitMs: HOME_WORKER.waitMs, url: "" });
    const warn: unknown[] = [];
    const logger = { info: () => undefined, warn: (_m: string, d?: unknown) => warn.push(d), error: () => undefined };
    const bad = [
      { enabled: "yes", testers: [OWNER] },
      { enabled: true, testers: OWNER },
      { enabled: true, testers: [OWNER, OWNER] },
      { enabled: true, testers: ["has space"] },
      { enabled: true, testers: Array.from({ length: HOME_WORKER.maxTesters + 1 }, (_, i) => `uid${i}`) },
      { enabled: true, testers: [OWNER], waitMs: { recipe: HOME_WORKER.maxWaitMs.recipe + 1 } },
      { enabled: true, testers: [OWNER], waitMs: { menu: 10 } },
      { enabled: true, testers: [OWNER], url: "http://home.test" },
      { enabled: true, testers: [OWNER], url: "https://user:pw@home.test" },
      { enabled: true, testers: [OWNER], url: "https://home.test/?x=1" },
      { enabled: true, testers: [OWNER], url: 42 },
      { enabled: true, emails: "owner@example.com" },
      { enabled: true, emails: ["not an email"] },
      { enabled: true, emails: ["owner@example.com", " Owner@Example.com "] },
      { enabled: true, emails: Array.from({ length: HOME_WORKER.maxEmails + 1 }, (_, i) => `o${i}@example.com`) },
      { enabled: true, testers: [OWNER], waitMs: { recipeImage: HOME_WORKER.maxWaitMs.recipeImage + 1 } },
      [OWNER],
    ];
    for (const b of bad) expect(parseRuntimeConfig({ homeWorker: b }, logger).homeWorker.enabled).toBe(false);
    expect(warn.length).toBe(bad.length);
    expect(parseRuntimeConfig({ homeWorker: { enabled: true, testers: [OWNER], waitMs: { menu: 20_000 }, url: "https://home.test/api/" } }).homeWorker)
      .toEqual({ enabled: true, testers: [OWNER], emails: [], waitMs: { ...HOME_WORKER.waitMs, menu: 20_000 }, url: "https://home.test/api" });
    // Emails are read trimmed and lower-cased.
    expect(parseRuntimeConfig({ homeWorker: { emails: [" Owner@Example.COM "] } }).homeWorker.emails).toEqual(["owner@example.com"]);
    expect(testerEmailOf("a b@example.com")).toBeNull();
    expect(testerEmailOf("owner@localhost")).toBeNull();
    expect(homeWorkerUrlOf("https://Home.Test:8443/")).toBe("https://home.test:8443");
  });

  test("the longest waits, then the normal provider, fit the function's timeout; the defaults the apps' own waits", () => {
    const T = HOME_WORKER.functionTimeoutSeconds * 1000;
    const margin = 10_000;
    // The apps: the callable, then getGeneration until 150 s (recipe) / 270 s (menu, pictures) from the first send.
    const APP = { recipe: 150_000, long: 270_000 };
    expect(T).toBeGreaterThanOrEqual(GENERATE_TIMEOUT_SECONDS * 1000);
    const recipe = (w: number) => w + TIMINGS.recipe.llmAbortMs + JUDGE.abortMs.recipe;
    const menu = (w: number) => w + TIMINGS.menu.llmAbortMs + JUDGE.abortMs.menu;
    expect(recipe(HOME_WORKER.maxWaitMs.recipe) + margin).toBeLessThanOrEqual(T);
    expect(menu(HOME_WORKER.maxWaitMs.menu) + margin).toBeLessThanOrEqual(T);
    expect(recipe(HOME_WORKER.waitMs.recipe) + margin).toBeLessThanOrEqual(APP.recipe);
    expect(menu(HOME_WORKER.waitMs.menu) + margin).toBeLessThanOrEqual(APP.long);
    // A recipe with pictures: text, judge, then the pictures' budget, for every picture provider and level.
    for (const d of imageProviders()) {
      const t = imageTimingOf(d.id);
      for (const level of ["dish", "steps"] as const) {
        expect(recipe(HOME_WORKER.maxWaitMs.recipeImage) + t.budgetMs[level] + margin, `${d.id} ${level}`).toBeLessThanOrEqual(Math.min(T, APP.long));
      }
    }
    for (const k of ["recipe", "menu", "recipeImage"] as const) expect(HOME_WORKER.waitMs[k]).toBeLessThanOrEqual(HOME_WORKER.maxWaitMs[k]);
  });

  test("the server's text is read as a JSON object, fenced or not", () => {
    expect(parseHomeAnswer('{"a":1}')).toEqual({ a: 1 });
    expect(parseHomeAnswer('```json\n{"a":1}\n```')).toEqual({ a: 1 });
    expect(parseHomeAnswer("[1]")).toBeUndefined();
    expect(parseHomeAnswer("not json")).toBeUndefined();
    expect(parseHomeAnswer(undefined)).toBeUndefined();
  });
});

describe("npm run admin -- home-worker", () => {
  const runtime = async () => (await db.doc("config/runtime").get()).data()?.homeWorker;

  test("add, on, wait, off and remove write config/runtime.homeWorker only with --apply", async () => {
    const { uid } = await passwordUser();
    const lines: string[] = [];
    const cmd = (...argv: string[]) => runHomeWorkerCommand(parseHomeWorkerArgs(["home-worker", ...argv], "demo-foodtracker"), { db, auth, print: (l) => lines.push(l) });
    await setConfig({ dailyCap: 900 });
    await cmd("add", uid);
    expect(await runtime()).toBeUndefined();
    expect(lines.at(-1)).toContain("Dry run");
    await cmd("add", uid, "--apply");
    await cmd("on", "--apply");
    await cmd("wait", "menu", "20000", "--apply");
    await cmd("url", "https://my-laptop.tail1234.ts.net/", "--apply");
    await cmd("add-email", " Owner@Example.com ", "--apply");
    expect(await runtime()).toEqual({ enabled: true, testers: [uid], emails: ["owner@example.com"], waitMs: { ...HOME_WORKER.waitMs, menu: 20_000 }, url: "https://my-laptop.tail1234.ts.net" });
    // The rest of config/runtime is left alone, and the functions read it as written.
    expect((await db.doc("config/runtime").get()).data()?.dailyCap).toBe(900);
    expect(homeWorkerWaitMs(parseRuntimeConfig((await db.doc("config/runtime").get()).data()!), uid, { kind: "menu" })).toBe(20_000);
    lines.length = 0;
    await cmd("list");
    expect(lines.join("\n")).toMatch(/Home worker: ON/);
    expect(lines.join("\n")).toContain(`${uid}  (password)`);
    // The address is the owner's home: shortened on screen.
    expect(lines.join("\n")).not.toContain("tail1234");
    expect(lines.join("\n")).toContain("https://my-…ts.net");
    expect(lines.join("\n")).toContain("email owner@example.com");
    await cmd("off", "--apply");
    await cmd("remove", uid, "--apply");
    await cmd("remove-email", "owner@example.com", "--apply");
    await cmd("url", "none", "--apply");
    expect(await runtime()).toEqual({ enabled: false, testers: [], emails: [], waitMs: { ...HOME_WORKER.waitMs, menu: 20_000 }, url: "" });
  });

  test("refuses a uid with no Auth user, a wait out of range and a stored setting it can't read", async () => {
    const cmd = (...argv: string[]) => runHomeWorkerCommand(parseHomeWorkerArgs(["home-worker", ...argv], "demo-foodtracker"), { db, auth, print: () => undefined });
    await expect(cmd("add", "no-such-user-uid", "--apply")).rejects.toBeInstanceOf(AdminRefusal);
    expect(() => parseHomeWorkerArgs(["home-worker", "wait", "menu", String(HOME_WORKER.maxWaitMs.menu + 1)], "demo-foodtracker")).toThrow(AdminRefusal);
    expect(() => parseHomeWorkerArgs(["home-worker", "add", "bad uid"], "demo-foodtracker")).toThrow(AdminRefusal);
    expect(() => parseHomeWorkerArgs(["home-worker", "add-email", "not-an-email"], "demo-foodtracker")).toThrow(AdminRefusal);
    expect(() => parseHomeWorkerArgs(["home-worker", "wait", "recipeImage", String(HOME_WORKER.maxWaitMs.recipeImage + 1)], "demo-foodtracker")).toThrow(AdminRefusal);
    await expect(cmd("remove-email", "nobody@example.com", "--apply")).rejects.toBeInstanceOf(AdminRefusal);
    for (const url of ["http://my-laptop.example", "https://user:pw@my-laptop.example", "https://my-laptop.example/?a=1", "ftp://x"]) {
      expect(() => parseHomeWorkerArgs(["home-worker", "url", url], "demo-foodtracker")).toThrow(AdminRefusal);
    }
    await setConfig({ homeWorker: { enabled: "yes" } });
    await expect(cmd("on", "--apply")).rejects.toBeInstanceOf(AdminRefusal);
    expect(await runtime()).toEqual({ enabled: "yes" });
  });
});
