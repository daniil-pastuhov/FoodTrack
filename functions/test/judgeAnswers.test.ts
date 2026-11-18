/**
 * The output judge's described answer and what is kept of it (README "Prompt injection defences"
 * item 5): the answer is read best effort (only the verdict can fail it), every answer is kept in
 * `judgeAnswers` without the uid, never delaying or failing the request, the note never reaches a
 * log, the judged text only with `outputJudgeKeepText`; the lite model's thinking and prices; and
 * the owner's read-only `admin judge` command.
 */
import { randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { JUDGE_INSTRUCTION, judgeText, judgeThinkingConfigFor, parseJudgeAnswer } from "../src/adapters/gemini";
import { JUDGE, JUDGE_LOG, JUDGE_PRICES } from "../src/constants";
import { generateRecipe, judgeWorstCaseMicroUsd } from "../src/core/generation";
import { FirestoreJudgeLog, cleanJudgeNote } from "../src/core/judgeLog";
import { redactLinks } from "../src/core/outputSafety";
import { DEFAULT_RUNTIME_CONFIG, emptyUser, judgePricesOf, microUsd, parseRuntimeConfig, uidHash } from "../src/model";
import { GenerationError, type JudgeEntry } from "../src/ports";
import { parseJudgeArgs, readJudgeAnswers, runJudgeAnswers, summariseJudgeAnswers } from "../src/admin/judgeAnswers";
import { AdminRefusal } from "../src/admin/accountRequests";
import { callerFor, db, fakeUsage, makeDeps, resetEmulators, sampleRecipe, setConfig, type TestDeps } from "./helpers";

const UID = "user-judge-log";
const caller = callerFor(UID);

function request(over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "de" },
    ingredients: [{ name: "Pasta", expiresOn: "2030-01-01", quantity: 1 }],
    allergens: [],
    today: new Date().toISOString().slice(0, 10),
    ...over,
  };
}

async function answers() {
  return (await db.collection(JUDGE_LOG.collection).get()).docs.map((d) => d.data());
}

let deps: TestDeps;
beforeEach(async () => {
  await resetEmulators();
  deps = makeDeps();
  await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 10 });
});

describe("reading the judge's answer", () => {
  test("the whole answer", () => {
    expect(parseJudgeAnswer(JSON.stringify({ verdict: "ok", where: "steps", issues: ["vague_amount", "unclear_step"], quality: 4, note: "Step 2 has no time." })))
      .toEqual({ verdict: "ok", answer: { where: "steps", issues: ["vague_amount", "unclear_step"], quality: 4, note: "Step 2 has no time." } });
  });

  test("malformed extras are dropped, never a failure", () => {
    const r = parseJudgeAnswer(JSON.stringify({ verdict: "off_topic", where: "elsewhere", issues: ["made_up", "non_food", "non_food", "step_order", "implausible", "title_mismatch"], quality: 9, note: 7 }));
    expect(r).toEqual({ verdict: "off_topic", answer: { issues: ["non_food", "step_order", "implausible"] } });
    expect(parseJudgeAnswer(JSON.stringify({ verdict: "harmful" }))).toEqual({ verdict: "harmful", answer: {} });
  });

  test("an answer cut short in its note keeps its verdict; an unusable verdict is the only failure", () => {
    expect(parseJudgeAnswer('{\n  "verdict": "unsafe_food",\n  "where": "steps",\n  "note": "Raw chi')?.verdict).toBe("unsafe_food");
    expect(parseJudgeAnswer('{"verdict": "maybe"}')).toBeNull();
    expect(parseJudgeAnswer("not json")).toBeNull();
    expect(parseJudgeAnswer('{"where": "steps", "verdict": "ok"')).toBeNull();
  });

  test("the judge sees the language asked for, and still never the user's input", () => {
    const t = judgeText({ language: "de", recipe: sampleRecipe() as never });
    expect(JSON.parse(t.split("\n")[1])).toMatchObject({ language: "de", recipe: { title: "Pantry pasta" } });
  });
});

describe("the lite judge's thinking and prices", () => {
  test("the least thinking each model takes, never the generator's level", () => {
    expect(judgeThinkingConfigFor(JUDGE.model)).toEqual({ thinkingLevel: "MINIMAL" });
    expect(judgeThinkingConfigFor("gemini-3.1-flash-lite")).toEqual({ thinkingLevel: "MINIMAL" });
    expect(judgeThinkingConfigFor("gemini-3.8-flash")).toEqual({ thinkingLevel: "LOW" });
    expect(judgeThinkingConfigFor("gemini-2.5-flash-lite")).toEqual({ thinkingBudget: 0 });
  });

  test("its own price, an override, and the generator's for a model with none (never cheaper than the truth)", () => {
    const c = parseRuntimeConfig({});
    expect(judgePricesOf(c, JUDGE.model)).toEqual(JUDGE_PRICES[JUDGE.model]);
    expect(judgePricesOf(c, "gemini-3.8-flash")).toEqual({ inputUsdPerMTok: c.prices.inputUsdPerMTok, outputUsdPerMTok: c.prices.outputUsdPerMTok });
    const over = parseRuntimeConfig({ prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, textUsdPerMTok: { [`google/${JUDGE.model}`]: { inputUsdPerMTok: 0.5, outputUsdPerMTok: 3 } } } });
    expect(judgePricesOf(over, JUDGE.model)).toEqual({ inputUsdPerMTok: 0.5, outputUsdPerMTok: 3 });
    const input = Buffer.byteLength(JUDGE_INSTRUCTION, "utf8") + Math.ceil(4096 * 1.1) + 200;
    expect(judgeWorstCaseMicroUsd(c, 4096)).toBe(microUsd(JUDGE_PRICES[JUDGE.model] as never, input, JUDGE.tokenCeiling));
    expect(judgeWorstCaseMicroUsd(c, 4096, "gemini-3.8-flash")).toBe(microUsd(DEFAULT_RUNTIME_CONFIG.prices, input, JUDGE.tokenCeiling));
  });

  test("the limits keep inside the reservation", () => {
    expect(JUDGE.tokenCeiling).toBe(2 * JUDGE.maxOutputTokens);
    expect(JUDGE.abortMs.recipe).toBeLessThan(60_000 - 40_000);
    expect(JUDGE.abortMs.menu).toBeLessThan(180_000 - 120_000);
  });
});

describe("the note as kept", () => {
  test("links, addresses and numbers are redacted; control characters out; cut to 200", () => {
    expect(redactLinks("Visit www.example.com or bit.ly/x, mail a@b.co, call +47 123 45 678.")).toBe("Visit [link] or [link] mail [link] call [number].");
    expect(cleanJudgeNote("Bake at 180 °C for 25 minutes.")).toBe("Bake at 180 °C for 25 minutes.");
    expect(cleanJudgeNote("line" + String.fromCharCode(0) + "one" + String.fromCharCode(0x202e) + "two")).toBe("line one two");
    expect(cleanJudgeNote("x".repeat(500))!.length).toBe(JUDGE.noteChars);
    expect(cleanJudgeNote("  ")).toBeNull();
    expect(cleanJudgeNote(undefined)).toBeNull();
  });
});

describe("every judge answer is kept (judgeAnswers)", () => {
  test("shadow: one document per judged generation, without the uid or the text; the log line has the codes but never the note", async () => {
    await setConfig({ outputJudge: "shadow" });
    deps.judge.verdict = "ok";
    deps.judge.answer = { where: "steps", issues: ["vague_amount"], quality: 4, note: "Step 2: see www.evil.example for the rest." };
    const r = request();
    expect((await generateRecipe(deps, caller, r)).status).toBe("DONE");
    await deps.judgeLog.settled();
    const all = await answers();
    expect(all).toHaveLength(1);
    const a = all[0];
    expect(a).toMatchObject({
      kind: "recipe", language: "de", textProvider: "google", textModel: "gemini-3.8-flash", judgeModel: JUDGE.model,
      mode: "shadow", gateRule: null, verdict: "ok", failure: null, where: "steps", issues: ["vague_amount"], quality: 4,
      note: "Step 2: see [link] for the rest.", usage: { inputTokens: 800, outputTokens: 5, thinkingTokens: 20 },
    });
    expect(a.judgeVersion).toMatch(/^[0-9a-f]{12}$/);
    expect(a.promptHash).toMatch(/^[0-9a-f]{12}$/);
    expect(a.text).toBeUndefined();
    expect(a.at).toBeInstanceOf(Timestamp);
    expect(a.expiresAt.toMillis() - a.at.toMillis()).toBe(JUDGE_LOG.keepDays * 24 * 60 * 60 * 1000);
    const stored = JSON.stringify(a);
    for (const s of [UID, uidHash(UID), r.requestId, "Pasta"]) expect(stored).not.toContain(s);
    const line = deps.logs.find((l) => l.message === "prompt_judge_verdict")!.data!;
    expect(line).toMatchObject({ verdict: "ok", where: "steps", issues: ["vague_amount"], quality: 4 });
    expect(JSON.stringify(deps.logs)).not.toContain("Step 2");
  });

  test("enforce: a refusal is kept too; with outputJudgeKeepText the judged text is kept beside it", async () => {
    await setConfig({ outputJudge: "enforce", outputJudgeKeepText: true });
    deps.judge.verdict = "off_topic";
    deps.judge.answer = { where: "tip", issues: ["off_topic_text"], quality: 1, note: "The tip is an advert." };
    expect((await generateRecipe(deps, caller, request())).status).toBe("FAILED");
    await deps.judgeLog.settled();
    const [a] = await answers();
    expect(a).toMatchObject({ mode: "enforce", verdict: "off_topic", where: "tip", issues: ["off_topic_text"], quality: 1 });
    expect(a.text).toBe(judgeText(deps.judge.seen!));
    expect(a.text).toContain("Pantry pasta");
  });

  test("a judge failure is kept with its reason, and no verdict", async () => {
    await setConfig({ outputJudge: "shadow" });
    deps.judge.next.push(async () => {
      throw new GenerationError("infra", "judge answer unusable", fakeUsage({ inputTokens: 700, outputTokens: 160, thinkingTokens: 0 }));
    });
    expect((await generateRecipe(deps, caller, request())).status).toBe("DONE");
    await deps.judgeLog.settled();
    const [a] = await answers();
    expect(a).toMatchObject({ verdict: null, failure: "judge answer unusable", where: null, issues: [], quality: null, note: null, usage: { inputTokens: 700 } });
  });

  test("a write that fails costs the request nothing: logged, the answer delivered", async () => {
    await setConfig({ outputJudge: "shadow" });
    const failing = { ...deps, judgeLog: { record: () => Promise.reject(new Error("deadline exceeded")) } };
    expect((await generateRecipe(failing as never, caller, request())).status).toBe("DONE");
    await new Promise((r) => setTimeout(r, 0));
    expect(deps.logs.find((l) => l.message === "judge_log_failed")?.data).toMatchObject({ reason: "deadline exceeded" });
  });

  test("off: no judge, nothing kept; keeping the text is off by default and an invalid value reads as off", async () => {
    expect((await generateRecipe(deps, caller, request())).status).toBe("DONE");
    await deps.judgeLog.settled();
    expect(await answers()).toHaveLength(0);
    expect(parseRuntimeConfig({}).outputJudgeKeepText).toBe(false);
    expect(parseRuntimeConfig({ outputJudgeKeepText: "yes" }).outputJudgeKeepText).toBe(false);
  });
});

function entry(over: Partial<JudgeEntry> = {}): JudgeEntry {
  return {
    kind: "recipe", language: "en", textProvider: "google", textModel: "gemini-3.8-flash", textModelVersion: null,
    judgeModel: JUDGE.model, judgeModelVersion: JUDGE.model, judgeVersion: "aaaaaaaaaaaa", promptHash: "bbbbbbbbbbbb", revision: null,
    mode: "shadow", gateRule: null, verdict: "ok", failure: null, where: "none", issues: [], quality: 5, note: "Fine.",
    latencyMs: 900, usage: { inputTokens: 500, outputTokens: 30, thinkingTokens: 0, finishReason: "STOP" }, ...over,
  };
}

describe("npm run admin -- judge (read-only)", () => {
  const NOW = Date.parse("2026-11-18T12:00:00Z");

  test("arguments: the last 30 UTC days by default, both ends included; refusals", () => {
    expect(parseJudgeArgs(["judge", "summary"], NOW, "demo-foodtracker")).toMatchObject({
      command: "summary", project: "demo-foodtracker", fromMs: Date.parse("2026-10-20T00:00:00Z"), toMs: Date.parse("2026-11-19T00:00:00Z"),
    });
    expect(parseJudgeArgs(["judge", "export", "--from", "2026-11-01", "--to", "2026-11-02", "--out", "x.jsonl"], NOW, "demo-foodtracker"))
      .toMatchObject({ command: "export", fromMs: Date.parse("2026-11-01T00:00:00Z"), toMs: Date.parse("2026-11-03T00:00:00Z"), out: "x.jsonl" });
    for (const bad of [["judge"], ["judge", "delete"], ["judge", "summary", "--from", "2026-13-01"], ["judge", "summary", "--out", "x"], ["judge", "summary", "--from", "2026-11-05", "--to", "2026-11-01"]]) {
      expect(() => parseJudgeArgs(bad, NOW, "demo-foodtracker")).toThrow(AdminRefusal);
    }
  });

  test("summary: counts by verdict, issue, quality and group, never a note; export: one JSON line per answer in the range", async () => {
    let t = Date.parse("2026-11-10T08:00:00Z");
    const log = new FirestoreJudgeLog(db, () => t);
    await log.record(entry({ issues: ["vague_amount", "unclear_step"], quality: 4, note: "Secret note one." }));
    await log.record(entry({ verdict: "off_topic", where: "tip", issues: ["off_topic_text"], quality: 1, language: "ru", latencyMs: 1500 }));
    await log.record(entry({ verdict: null, failure: "judge timeout", where: null, quality: null, note: null, usage: null, latencyMs: 10_000 }));
    t = Date.parse("2026-10-01T08:00:00Z");
    await log.record(entry({ note: "Out of range." }));
    const printed: string[] = [];
    const env = { db, now: () => NOW, print: (l: string) => void printed.push(l) };
    const args = parseJudgeArgs(["judge", "summary", "--from", "2026-11-01"], NOW, "demo-foodtracker");
    expect(await runJudgeAnswers(args, env)).toEqual({ rows: 3 });
    const out = printed.join("\n");
    expect(out).toContain("Judge answers 2026-11-01 to 2026-11-18 (UTC): 3");
    const verdictLine = out.split("\n").find((l) => l.startsWith("Verdicts: "))!;
    for (const v of ["ok 1 (33.3%)", "off_topic 1 (33.3%)", "failed 1 (33.3%)"]) expect(verdictLine).toContain(v);
    expect(out).toContain("vague_amount 1");
    expect(out).toContain("Quality: 1: 1  2: 0  3: 0  4: 1  5: 0  none: 1");
    expect(out).not.toContain("Secret note");

    const s = summariseJudgeAnswers((await readJudgeAnswers(db, args.fromMs, args.toMs)).rows);
    expect(s.by.language).toMatchObject({ en: { total: 2, notOk: 0, failed: 1 }, ru: { total: 1, notOk: 1, qualityAvg: 1 } });
    expect(s.latencyMs).toEqual({ p50: 1500, p95: 10_000, max: 10_000 });

    const files: Record<string, string> = {};
    await runJudgeAnswers(parseJudgeArgs(["judge", "export", "--from", "2026-11-01", "--out", "a.jsonl"], NOW, "demo-foodtracker"), { ...env, write: (p, x) => void (files[p] = x) });
    const lines = files["a.jsonl"].trimEnd().split("\n").map((l) => JSON.parse(l));
    expect(lines).toHaveLength(3);
    expect(lines.find((l) => l.note === "Secret note one.")).toMatchObject({ at: "2026-11-10T08:00:00.000Z", issues: ["vague_amount", "unclear_step"] });
    for (const l of lines) expect(l).not.toHaveProperty("expiresAt");
  });
});
