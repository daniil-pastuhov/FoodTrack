/**
 * The four provider switches (src/providers/catalogue.ts, src/core/providerSwitch.ts): the
 * catalogue built from the registry, readiness, the planned writes of each switch on its own (the
 * other three never change), cross-provider fallbacks, the refusals, prices, setups set by hand,
 * the transaction against the emulators (an audit record, the log line), the owner gate with real
 * Auth emulator ID tokens (unsigned, not Google, not listed, revoked, disabled, an old sign-in),
 * the admin command's dry run and --apply, and the board and console page showing nothing more.
 */
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { AdminRefusal } from "../src/admin/accountRequests";
import { parseProviderArgs, runProviders, type ImageEnv } from "../src/admin/cli";
import { buildBoard, boardStatsDays } from "../src/board/boardData";
import { boardJson, boardSummary, renderBoardHtml } from "../src/board/boardHtml";
import { DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION } from "../src/constants";
import { loadRuntimeConfig, parseRuntimeConfig, uidHash } from "../src/model";
import type { Logger } from "../src/ports";
import {
  OWNER_DOC,
  RUNTIME_DOC,
  SWITCH_CHANGES,
  SwitchRefused,
  getProviderSwitches,
  listSwitches,
  ownerConsoleOrigins,
  parseOwnerDoc,
  planSwitch,
  setProviderSwitch,
  setupOf,
  switchProvider,
  type OwnerEnv,
  type Setup,
  type SwitchesView,
} from "../src/core/providerSwitch";
import {
  OWNER_OTHERS_PER_MINUTE,
  OWNER_PER_ADDRESS_PER_MINUTE,
  OwnerCallGate,
  type OwnerCallRequest,
} from "../src/core/ownerCall";
import { SLOTS, catalogue, catalogueModels, catalogueProblems, optionOf, readinessOf, type SlotId } from "../src/providers/catalogue";
import { openaiText } from "../src/adapters/openaiText";
import { openaiImage } from "../src/adapters/image";
import { bflImage } from "../src/adapters/bfl";
import { registerProviderForTests } from "../src/providers/registry";
import type { ImageProviderDescriptor } from "../src/providers/types";
import { auth, db, passwordUser, resetEmulators } from "./helpers";

interface Line { level: string; message: string; data?: Record<string, unknown> }

const GEMINI = "google/gemini-3.8-flash";
const OPENAI_TEXT = "openai/gpt-6.1-terra";
const LUNA = "openai/gpt-6-luna";
const FL = "vertex/gemini-3.1-flash-lite-image";
const NB = "vertex/gemini-nano-banana-2.1";
const OPENAI_IMAGE = "openai/gpt-image-2.6-flare";
const KLEIN = "bfl/flux-3-image-klein";
const DEFAULT_SETUP: Setup = { text: GEMINI, "text-fallback": "none", image: FL, "image-fallback": NB };
const D = { image: DEFAULT_IMAGE_SELECTION, text: DEFAULT_TEXT_SELECTION };

function makeEnv(over: Partial<OwnerEnv> = {}): OwnerEnv & { logs: Line[] } {
  const logs: Line[] = [];
  const rec = (level: string) => (message: string, data?: Record<string, unknown>) => void logs.push({ level, message, data });
  const logger: Logger = { info: rec("info"), warn: rec("warn"), error: rec("error") };
  return { db, auth, now: () => Date.now(), logger, secretReadable: async () => false, logs, ...over };
}

async function reason(p: Promise<unknown> | (() => unknown)): Promise<string> {
  try {
    await (typeof p === "function" ? p() : p);
  } catch (e) {
    if (e instanceof SwitchRefused) return e.reason;
    if (e instanceof HttpsError) return `${e.code}:${(e.details as { reason?: string } | undefined)?.reason ?? ""}`;
    if (e instanceof AdminRefusal) return `admin:${e.message}`;
    throw e;
  }
  return "no refusal";
}

const runtime = async () => (await db.doc(RUNTIME_DOC).get()).data() as Record<string, unknown> | undefined;
const audits = async () => (await db.collection(SWITCH_CHANGES).get()).docs.map((d) => d.data());
const setup = (data: Record<string, unknown>) => setupOf(data, D);
const slotView = (v: SwitchesView, id: SlotId) => v.slots.find((s) => s.id === id)!;
const optionView = (v: SwitchesView, id: SlotId, value: string) => slotView(v, id).options.find((o) => o.value === value);

/** A config/runtime with many fields, to show a switch leaves every other one as it was. */
const FULL_CONFIG = {
  generationEnabled: false,
  allowSandbox: true,
  dailyCap: 77,
  costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 3 },
  prices: { inputUsdPerMTok: 2, outputUsdPerMTok: 9, imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08 } },
  welcome: { recipes: 2, perDay: 50 },
  tasteQuiz: { hidden: ["units"] },
  image: { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 3 },
};

beforeEach(async () => {
  await resetEmulators();
});

// --- The catalogue ----------------------------------------------------------------------------

describe("the catalogue", () => {
  test("is sound, comes from the registry, and holds the deploy defaults", () => {
    expect(catalogueProblems()).toEqual([]);
    const values = (slot: SlotId) => catalogue(slot).map((o) => o.value);
    expect(values("text")).toEqual([GEMINI, OPENAI_TEXT, LUNA]);
    expect(values("text-fallback")).toEqual([GEMINI, OPENAI_TEXT, LUNA, "none"]);
    expect(values("image")).toEqual([NB, FL, OPENAI_IMAGE, KLEIN, "bfl/flux-3-image", "off"]);
    expect(values("image-fallback")).toEqual([NB, FL, OPENAI_IMAGE, KLEIN, "bfl/flux-3-image", "none"]);
    expect(setup({})).toEqual(DEFAULT_SETUP);
    // A picture option carries its adapter's price; a text option its provider's label.
    expect(optionOf("image", OPENAI_IMAGE)).toMatchObject({ usdPerImage: 0.035, label: "gpt-image-2.6-flare", providerLabel: openaiImage.label });
    expect(optionOf("text", OPENAI_TEXT)).toMatchObject({ label: "gpt-6.1-terra", providerLabel: openaiText.label });
    // The text's deploy default is an option when its provider prices it at run time (GEMINI_MODEL set to another Gemini).
    expect(catalogue("text", { provider: "google", model: "gemini-3.9-flash" }).map((o) => o.value)).toContain("google/gemini-3.9-flash");
    // Every model a switch offers is one the lifecycle table must know.
    expect(catalogueModels().map((m) => `${m.provider}/${m.model}`)).toEqual([GEMINI, OPENAI_TEXT, LUNA, NB, FL, OPENAI_IMAGE, KLEIN, "bfl/flux-3-image"]);
  });

  test("a provider added to the registry is offered with no second list, and a bad price is caught", () => {
    const pix: ImageProviderDescriptor = {
      kind: "image", id: "newpix", label: "New pictures", processing: "x", privacy: { approved: true, missing: "" },
      usdPerImage: { "draw-1": 0.02, "draw-dear": 5 }, create: () => ({ generate: async () => { throw new Error("unused"); } }),
    };
    const remove = registerProviderForTests(pix);
    try {
      expect(catalogue("image").map((o) => o.value)).toEqual(expect.arrayContaining(["newpix/draw-1", "newpix/draw-dear"]));
      expect(catalogue("image-fallback").map((o) => o.value)).toContain("newpix/draw-1");
      expect(catalogueProblems().join("\n")).toContain('image option "newpix/draw-dear": its price must be 0.001 to 2 USD');
    } finally {
      remove();
    }
    expect(catalogue("image").map((o) => o.value)).not.toContain("newpix/draw-1");
  });

  test("readiness: OpenAI (text and pictures) is listed but not ready, even with its key; FLUX needs its key", async () => {
    for (const [slot, value] of [["text", OPENAI_TEXT], ["text-fallback", LUNA], ["image", OPENAI_IMAGE], ["image-fallback", OPENAI_IMAGE]] as const) {
      const o = optionOf(slot, value)!;
      expect(await readinessOf(o, async () => false)).toEqual({ ready: false, missing: o.requires.map((r) => r.missing) });
      const withKey = await readinessOf(o, async () => true);
      expect(withKey.ready).toBe(false);
      expect(withKey.missing).toEqual([expect.stringContaining("privacy policy")]);
    }
    expect(optionOf("text", OPENAI_TEXT)!.requires.map((r) => r.kind)).toEqual(["approval", "secret"]);
    const klein = optionOf("image", KLEIN)!;
    expect(await readinessOf(klein, async (id) => id === "bfl")).toEqual({ ready: true, missing: [] });
    expect(await readinessOf(klein, async () => false)).toEqual({ ready: false, missing: [bflImage.secret!.missing] });
    expect((await readinessOf(klein, async () => { throw new Error("denied"); })).ready).toBe(false);
    for (const [slot, value] of [["text", GEMINI], ["image", FL], ["image", NB], ["image", "off"], ["text-fallback", "none"], ["image-fallback", "none"]] as const) {
      expect((await readinessOf(optionOf(slot, value)!, async () => false)).ready, value).toBe(true);
    }
  });
});

// --- Planning (pure) --------------------------------------------------------------------------

describe("planSwitch", () => {
  const paths = (data: Record<string, unknown>, slot: SlotId, value: string) =>
    planSwitch(data, slot, value).writes.map((w) => [w.path.join(">"), w.value]);

  test("each switch on its own writes only its own fields, and the other three read the same", () => {
    expect(paths({}, "text", OPENAI_TEXT)).toEqual([["text>provider", "openai"], ["text>model", "gpt-6.1-terra"]]);
    expect(paths({}, "text-fallback", LUNA)).toEqual([["text>fallbackProvider", "openai"], ["text>fallbackModel", "gpt-6-luna"]]);
    expect(paths({}, "image-fallback", "none")).toEqual([["image>fallbackModel", ""]]);
    expect(paths({}, "image", "off")).toEqual([["image>provider", "off"]]);
    for (const [slot, value] of [["text", OPENAI_TEXT], ["text", LUNA], ["text-fallback", LUNA], ["text-fallback", OPENAI_TEXT], ["image", OPENAI_IMAGE], ["image", KLEIN], ["image", "off"], ["image-fallback", KLEIN], ["image-fallback", "none"], ["image-fallback", OPENAI_IMAGE]] as const) {
      for (const base of [{}, FULL_CONFIG]) {
        const plan = planSwitch(base, slot, value);
        expect(plan.to, `${slot}=${value}`).toEqual({ ...DEFAULT_SETUP, [slot]: value });
        expect(plan.from).toEqual(DEFAULT_SETUP);
        // Only the map of the switch's kind (and a picture price) is written.
        for (const w of plan.writes) expect([slot.startsWith("text") ? "text" : "image", "prices"]).toContain(w.path[0]);
      }
    }
  });

  test("a picture model at another provider pins the fallback that was at the same provider", () => {
    // The default's fallback (Nano Banana) is stored without its provider: at OpenAI it would read
    // as openai/gemini-nano-banana-2.1 (unpriced, so dropped), so its provider is written too.
    expect(paths({}, "image", OPENAI_IMAGE)).toEqual([
      ["image>provider", "openai"],
      ["image>model", "gpt-image-2.6-flare"],
      ["image>fallbackProvider", "vertex"],
      ["image>fallbackModel", "gemini-nano-banana-2.1"],
    ]);
    expect(paths(FULL_CONFIG, "image", OPENAI_IMAGE)).toEqual([
      ["image>provider", "openai"],
      ["image>model", "gpt-image-2.6-flare"],
      ["image>fallbackProvider", "vertex"],
    ]);
    // Back to Vertex: the provider written explicitly is cleared as it now is the same one.
    const atOpenAI = { image: { provider: "openai", model: "gpt-image-2.6-flare", fallbackProvider: "vertex", fallbackModel: "gemini-nano-banana-2.1" } };
    expect(setup(atOpenAI)).toMatchObject({ image: OPENAI_IMAGE, "image-fallback": NB });
    expect(planSwitch(atOpenAI, "image", FL).to).toEqual(DEFAULT_SETUP);
    // The same for the text: a fallback stored without its provider keeps it when the text moves.
    const openaiPair = { text: { provider: "openai", model: "gpt-6.1-terra", fallbackModel: "gpt-6-luna" } };
    expect(setup(openaiPair)).toMatchObject({ text: OPENAI_TEXT, "text-fallback": LUNA });
    expect(paths(openaiPair, "text", GEMINI)).toEqual([["text>provider", "google"], ["text>model", "gemini-3.8-flash"], ["text>fallbackProvider", "openai"]]);
  });

  test("cross-provider fallbacks, both ways", () => {
    expect(paths({}, "image-fallback", KLEIN)).toEqual([["image>fallbackModel", "flux-3-image-klein"], ["image>fallbackProvider", "bfl"]]);
    const flux = { image: { provider: "bfl", model: "flux-3-image-klein", fallbackProvider: "vertex", fallbackModel: "gemini-3.1-flash-lite-image" } };
    expect(setup(flux)).toMatchObject({ image: KLEIN, "image-fallback": FL });
    expect(paths(flux, "image-fallback", "bfl/flux-3-image")).toEqual([["image>fallbackModel", "flux-3-image"], ["image>fallbackProvider", ""]]);
    expect(planSwitch({}, "text-fallback", OPENAI_TEXT).to["text-fallback"]).toBe(OPENAI_TEXT);
    const openaiText = { text: { provider: "openai", model: "gpt-6.1-terra" } };
    expect(paths(openaiText, "text-fallback", GEMINI)).toEqual([["text>fallbackProvider", "google"], ["text>fallbackModel", "gemini-3.8-flash"]]);
  });

  test("a fallback equal to its primary, or a primary set to its fallback, is refused", async () => {
    expect(await reason(() => planSwitch({}, "text-fallback", GEMINI))).toBe("SAME_AS_PRIMARY");
    expect(await reason(() => planSwitch({}, "image-fallback", FL))).toBe("SAME_AS_PRIMARY");
    expect(await reason(() => planSwitch({}, "image", NB))).toBe("SAME_AS_PRIMARY");
    expect(() => planSwitch({}, "image", NB)).toThrow(/switch the fallback first/);
    const withFb = { text: { fallbackProvider: "openai", fallbackModel: "gpt-6-luna" } };
    expect(await reason(() => planSwitch(withFb, "text", LUNA))).toBe("SAME_AS_PRIMARY");
    // "none" and "off" never clash; with the fallback moved first the primary goes through.
    expect(planSwitch({}, "image", "off").to).toMatchObject({ image: "off", "image-fallback": NB });
    expect(planSwitch({ image: { fallbackModel: "" } }, "image", NB).to).toMatchObject({ image: NB, "image-fallback": "none" });
  });

  test("unknown switches and options (a value set by hand is not an option)", async () => {
    expect(await reason(() => planSwitch({}, "pictures", FL))).toBe("UNKNOWN_SWITCH");
    expect(await reason(() => planSwitch({}, "text", "none"))).toBe("UNKNOWN_OPTION");
    expect(await reason(() => planSwitch({}, "text", "off"))).toBe("UNKNOWN_OPTION");
    expect(await reason(() => planSwitch({}, "text-fallback", "off"))).toBe("UNKNOWN_OPTION");
    expect(await reason(() => planSwitch({}, "image", "vertex/gemini-3-pro-image"))).toBe("UNKNOWN_OPTION");
    expect(await reason(() => planSwitch({}, "image", { value: FL }))).toBe("UNKNOWN_OPTION");
    expect(await reason(() => planSwitch({}, "text", "openai/gpt-image-2.6-flare"))).toBe("UNKNOWN_OPTION");
  });

  test("nothing to write when already in force", () => {
    for (const slot of SLOTS) expect(planSwitch({}, slot, DEFAULT_SETUP[slot]).writes).toEqual([]);
    expect(planSwitch({ image: { provider: "off" } }, "image", "off").writes).toEqual([]);
  });

  test("pictures off keeps the model and fallback stored, inert; coming back keeps the fallback", () => {
    const off = { image: { provider: "off", model: "gemini-3.1-flash-lite-image" } };
    expect(setup(off)).toEqual({ ...DEFAULT_SETUP, image: "off" });
    // The fallback can be switched while off; it is what pictures get when they are back.
    expect(paths(off, "image-fallback", KLEIN)).toEqual([["image>fallbackModel", "flux-3-image-klein"], ["image>fallbackProvider", "bfl"]]);
    expect(planSwitch(off, "image-fallback", KLEIN).to).toMatchObject({ image: "off", "image-fallback": KLEIN });
    expect(paths(off, "image", FL)).toEqual([["image>provider", "vertex"]]);
    // Back on at OpenAI: the fallback stays Nano Banana on Vertex.
    expect(planSwitch(off, "image", OPENAI_IMAGE).to).toEqual({ ...DEFAULT_SETUP, image: OPENAI_IMAGE });
    // While off, a fallback equal to the stored model is fine: that model is not in force.
    expect(planSwitch({ image: { provider: "off", fallbackModel: "" } }, "image-fallback", FL).to["image-fallback"]).toBe(FL);
  });

  test("an image or text value that is not a map is replaced as a whole, with the pair as it reads", () => {
    expect(paths({ image: "nano" }, "image-fallback", "none")).toEqual([
      ["image", { provider: "off", model: "gemini-3.1-flash-lite-image", fallbackModel: "", fallbackProvider: "", maxStepImages: 2 }],
    ]);
    expect(setup({ image: "nano" })).toEqual({ ...DEFAULT_SETUP, image: "off" });
    expect(paths({ text: 7 }, "text", OPENAI_TEXT)).toEqual([["text", { provider: "openai", model: "gpt-6.1-terra", fallbackProvider: "", fallbackModel: "" }]]);
  });

  test("raises a picture price that is lower, never lowers one set higher", () => {
    const at = (usd: number) => ({ prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { [OPENAI_IMAGE]: usd } } });
    expect(paths(at(0.02), "image-fallback", OPENAI_IMAGE)).toContainEqual([`prices>imageUsdPerImage>${OPENAI_IMAGE}`, 0.035]);
    expect(paths(at(0.05), "image-fallback", OPENAI_IMAGE).some(([p]) => String(p).startsWith("prices"))).toBe(false);
    expect(paths(at(0.02), "image", OPENAI_IMAGE)).toContainEqual([`prices>imageUsdPerImage>${OPENAI_IMAGE}`, 0.035]);
    // Text has no price writes: its prices are the adapter's table (or config/runtime's override).
    expect(paths(at(0.02), "text", OPENAI_TEXT).some(([p]) => String(p).startsWith("prices"))).toBe(false);
    // An invalid prices map is ignored as a whole, so the defaults are in force: nothing to add, and the switch still goes through.
    const broken = { prices: { outputUsdPerMTok: 7.5, imageUsdPerImage: { [OPENAI_IMAGE]: 0.02 } } };
    expect(paths(broken, "image", OPENAI_IMAGE).some(([p]) => String(p).startsWith("prices"))).toBe(false);
  });

  test("a setup set by hand reads as it is (custom) and every switch can move off it", () => {
    const hand = {
      text: { provider: "google", model: "gemini-3.9-flash" },
      image: { provider: "vertex", model: "gemini-3-pro-image", fallbackModel: "" },
      prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { "vertex/gemini-3-pro-image": 0.15 } },
    };
    expect(setup(hand)).toEqual({ text: "google/gemini-3.9-flash", "text-fallback": "none", image: "vertex/gemini-3-pro-image", "image-fallback": "none" });
    expect(planSwitch(hand, "text-fallback", LUNA).to).toEqual({ text: "google/gemini-3.9-flash", "text-fallback": LUNA, image: "vertex/gemini-3-pro-image", "image-fallback": "none" });
    expect(planSwitch(hand, "image-fallback", NB).to["image"]).toBe("vertex/gemini-3-pro-image");
    expect(planSwitch(hand, "text", GEMINI).to.text).toBe(GEMINI);
    expect(planSwitch(hand, "image", FL).to).toMatchObject({ image: FL, "image-fallback": "none" });
  });

  test("an invalid image map is rewritten whole; a write that would make a field invalid is refused", async () => {
    // maxStepImages 99 makes the whole image map invalid (pictures off): the map is written whole, with the default step count.
    expect(setup({ image: { maxStepImages: 99 } })).toMatchObject({ image: "off" });
    expect(paths({ image: { maxStepImages: 99 } }, "image", FL)).toEqual([
      ["image", { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", fallbackProvider: "", maxStepImages: 2 }],
    ]);
    // A price the switch would have to raise past what config/runtime accepts (a test provider's 5 USD).
    const pix: ImageProviderDescriptor = {
      kind: "image", id: "newpix", label: "New pictures", processing: "x", privacy: { approved: true, missing: "" },
      usdPerImage: { "draw-dear": 5 }, create: () => ({ generate: async () => { throw new Error("unused"); } }),
    };
    const remove = registerProviderForTests(pix);
    try {
      const lower = { prices: { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5, imageUsdPerImage: { "newpix/draw-dear": 0.02 } } };
      expect(await reason(() => planSwitch(lower, "image-fallback", "newpix/draw-dear"))).toBe("INVALID_RESULT");
      expect(() => planSwitch(lower, "image-fallback", "newpix/draw-dear")).toThrow(/invalid: prices/);
    } finally {
      remove();
    }
  });
});

// --- The switch against the emulators ---------------------------------------------------------

describe("switchProvider", () => {
  test("apply: only the switch's fields change, one audit record with the switch, one log line", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const out = await switchProvider(env, "image-fallback", "none", { apply: true, actor: { via: "cli" } });
    expect(out).toMatchObject({ applied: true, changed: true });
    expect(await runtime()).toEqual({ ...FULL_CONFIG, image: { ...FULL_CONFIG.image, fallbackModel: "" } });
    const parsed = await loadRuntimeConfig(db);
    expect(parsed.image.maxStepImages).toBe(3);
    const [audit, ...more] = await audits();
    expect(more).toEqual([]);
    expect(audit).toMatchObject({
      actor: { via: "cli" },
      slot: "image-fallback",
      from: NB,
      to: "none",
      before: DEFAULT_SETUP,
      after: { ...DEFAULT_SETUP, "image-fallback": "none" },
      writes: [{ path: "image > fallbackModel", value: "" }],
    });
    expect(audit.at.toMillis()).toBeGreaterThan(Date.now() - 60_000);
    expect(env.logs.filter((l) => l.message === "provider_profile_changed")).toEqual([
      { level: "info", message: "provider_profile_changed", data: { via: "cli", slot: "image-fallback", from: NB, to: "none", change: out.changeId } },
    ]);
  });

  test("each of the four, switched in turn, leaves the other three as they were", async () => {
    const env = makeEnv({ secretReadable: async (id) => id === "bfl" });
    const steps: [SlotId, string][] = [["image-fallback", KLEIN], ["image", NB], ["text-fallback", GEMINI], ["image", "off"], ["image-fallback", "none"], ["image", FL]];
    // text-fallback = GEMINI is the text model now: refused; then the rest.
    let want: Setup = { ...DEFAULT_SETUP };
    for (const [slot, value] of steps) {
      const before = setup((await runtime()) ?? {});
      const r = await reason(switchProvider(env, slot, value, { apply: true, actor: { via: "cli" } }));
      if (slot === "text-fallback") {
        expect(r).toBe("SAME_AS_PRIMARY");
        continue;
      }
      expect(r, `${slot}=${value}`).toBe("no refusal");
      want = { ...want, [slot]: value };
      const after = setup((await runtime()) ?? {});
      expect(after).toEqual({ ...before, [slot]: value });
      expect(after).toEqual(want);
    }
    expect((await audits()).map((a) => [a.slot, a.from, a.to]).sort()).toEqual([
      ["image", "off", FL], ["image", FL, NB], ["image", NB, "off"], ["image-fallback", KLEIN, "none"], ["image-fallback", NB, KLEIN],
    ].sort());
  });

  test("a price write keeps the other prices; a missing document is created with only what the switch needs", async () => {
    await db.doc(RUNTIME_DOC).set({ ...FULL_CONFIG, prices: { ...FULL_CONFIG.prices, imageUsdPerImage: { ...FULL_CONFIG.prices.imageUsdPerImage, [KLEIN]: 0.005 } } });
    await switchProvider(makeEnv({ secretReadable: async () => true }), "image-fallback", KLEIN, { apply: true, actor: { via: "cli" } });
    expect((await runtime())!.prices).toEqual({
      inputUsdPerMTok: 2,
      outputUsdPerMTok: 9,
      imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08, [KLEIN]: 0.01 },
    });
    await resetEmulators();
    await switchProvider(makeEnv(), "image", "off", { apply: true, actor: { via: "cli" } });
    expect(await runtime()).toEqual({ image: { provider: "off" } });
    expect(parseRuntimeConfig((await runtime())!).costs.recipeImage).toBeUndefined();
  });

  test("dry run and an unchanged switch write nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const dry = await switchProvider(env, "image", "off", { apply: false, actor: { via: "cli" } });
    expect(dry).toMatchObject({ applied: false, changed: true });
    expect(dry.plan.writes).toEqual([{ path: ["image", "provider"], value: "off" }]);
    const same = await switchProvider(env, "image", FL, { apply: true, actor: { via: "cli" } });
    expect(same).toMatchObject({ applied: false, changed: false });
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
    expect(env.logs.filter((l) => l.message === "provider_profile_changed")).toEqual([]);
  });

  test("refuses an option not ready, an unknown one, the same as its pair, and a stale view, writing nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv({ secretReadable: async () => true });
    expect(await reason(switchProvider(env, "text", OPENAI_TEXT, { apply: true, actor: { via: "cli" } }))).toBe("NOT_READY");
    expect(await reason(switchProvider(env, "image", OPENAI_IMAGE, { apply: true, actor: { via: "cli" } }))).toBe("NOT_READY");
    expect(await reason(switchProvider(makeEnv(), "image", KLEIN, { apply: true, actor: { via: "cli" } }))).toBe("NOT_READY");
    expect(await reason(switchProvider(env, "image", "dall-e", { apply: true, actor: { via: "cli" } }))).toBe("UNKNOWN_OPTION");
    expect(await reason(switchProvider(env, "everything", "off", { apply: true, actor: { via: "cli" } }))).toBe("UNKNOWN_SWITCH");
    expect(await reason(switchProvider(env, "image", NB, { apply: true, actor: { via: "cli" } }))).toBe("SAME_AS_PRIMARY");
    expect(await reason(switchProvider(env, "image", "off", { apply: true, actor: { via: "cli" }, expect: NB }))).toBe("STALE");
    // expect is that switch's value only: another switch's change does not make it stale.
    expect(await reason(switchProvider(env, "text-fallback", "none", { apply: true, actor: { via: "cli" }, expect: "none" }))).toBe("no refusal");
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
  });

  test("FLUX switches once its key is readable; OpenAI still doesn't", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv({ secretReadable: async (id) => id === "bfl" || id === "openai" });
    await switchProvider(env, "image", KLEIN, { apply: true, actor: { via: "cli" } });
    expect((await runtime())?.image).toMatchObject({ provider: "bfl", model: "flux-3-image-klein", fallbackProvider: "vertex", fallbackModel: "gemini-nano-banana-2.1" });
    await switchProvider(env, "image-fallback", FL, { apply: true, actor: { via: "cli" } });
    expect(setup((await runtime())!)).toEqual({ ...DEFAULT_SETUP, image: KLEIN, "image-fallback": FL });
  });

  test("listSwitches: the four in force, options with price and readiness, custom, inert, blocked, the last change", async () => {
    const calls: string[] = [];
    const env = makeEnv({ secretReadable: async (id) => { calls.push(id); return id === "bfl"; } });
    let view = await listSwitches(env);
    expect(view.slots.map((s) => [s.id, s.label, s.value, s.custom, s.inert])).toEqual([
      ["text", "Text model", GEMINI, false, false],
      ["text-fallback", "Text fallback", "none", false, false],
      ["image", "Picture model", FL, false, false],
      ["image-fallback", "Picture fallback", NB, false, false],
    ]);
    // Each secret is read once for the whole view.
    expect(calls.sort()).toEqual(["bfl", "openai"]);
    expect(view).toMatchObject({ picturesOn: true, maxStepImages: 2, lastChange: null });
    expect(optionView(view, "text", OPENAI_TEXT)).toMatchObject({ ready: false, current: false, price: "USD 1.00 in, USD 5.00 out per 1M tokens" });
    expect(optionView(view, "text", GEMINI)).toMatchObject({ ready: true, current: true, price: "USD 1.50 in, USD 7.50 out per 1M tokens" });
    expect(optionView(view, "image", KLEIN)).toMatchObject({ ready: true, price: "USD 0.01 a picture" });
    expect(optionView(view, "image", NB)).toMatchObject({ ready: true, blocked: "it is the picture fallback now: switch the fallback first" });
    expect(optionView(view, "image-fallback", FL)).toMatchObject({ blocked: "it is the picture model now" });
    expect(optionView(view, "image-fallback", "none")).toMatchObject({ label: "No fallback", ready: true, price: "" });
    expect(optionView(view, "image", "off")).toMatchObject({ label: "Pictures off" });
    await switchProvider(env, "image", "off", { apply: true, actor: { via: "cli" } });
    view = await listSwitches(env);
    expect(view).toMatchObject({ picturesOn: false, lastChange: { via: "cli", slot: "image", from: FL, to: "off" } });
    expect(slotView(view, "image-fallback")).toMatchObject({ value: NB, inert: true });
    await db.doc(RUNTIME_DOC).set({ image: { provider: "vertex", model: "gemini-3-pro-image" } }, { merge: true });
    expect(slotView(await listSwitches(env), "image")).toMatchObject({ value: "vertex/gemini-3-pro-image", valueLabel: "vertex/gemini-3-pro-image", custom: true });
  });

  test("an audit record of a profile switch (before the four switches) still reads as the last change", async () => {
    await db.collection(SWITCH_CHANGES).add({
      at: Timestamp.fromMillis(Date.now() - 1000),
      actor: { via: "web", uid: "u" },
      from: { profile: "flash-lite", provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1" },
      to: { profile: "flux-eu", provider: "bfl", model: "flux-3-image-klein", fallbackModel: "gemini-3.1-flash-lite-image" },
      writes: [],
    });
    expect((await listSwitches(makeEnv())).lastChange).toMatchObject({ via: "web", slot: "profile", from: "flash-lite", to: "flux-eu" });
    await switchProvider(makeEnv(), "image", "off", { apply: true, actor: { via: "cli" } });
    expect((await listSwitches(makeEnv())).lastChange).toMatchObject({ slot: "image", from: FL, to: "off" });
  });
});

// --- The owner gate with Auth emulator tokens ------------------------------------------------

/**
 * A sign-in with an identity provider in the Auth emulator (it accepts an unsigned id_token);
 * with `linkTo` (a session's ID token) the identity is linked to that user instead.
 */
async function idpSignIn(providerId: string, claims: { sub: string; email: string; email_verified: boolean }, linkTo?: string): Promise<{ uid: string; idToken: string }> {
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        postBody: `id_token=${encodeURIComponent(JSON.stringify({ ...claims, name: "Some Name" }))}&providerId=${providerId}`,
        requestUri: "http://localhost",
        returnSecureToken: true,
        returnIdpCredential: true,
        ...(linkTo ? { idToken: linkTo } : {}),
      }),
    },
  );
  const json = (await res.json()) as { localId: string; idToken: string };
  if (!res.ok) throw new Error(`signInWithIdp failed: ${JSON.stringify(json)}`);
  return { uid: json.localId, idToken: json.idToken };
}

/** A Google sign-in in the Auth emulator. */
async function googleUser(email = `owner-${randomUUID()}@example.com`, emailVerified = true): Promise<{ uid: string; idToken: string; email: string; sub: string }> {
  const sub = randomUUID();
  return { ...(await idpSignIn("google.com", { sub, email, email_verified: emailVerified })), email, sub };
}

const newEmail = (who: string) => `${who}-${randomUUID()}@example.com`;

const bearer = (t: string) => `Bearer ${t}`;

// (the owner callables)
describe("the owner callables", () => {
  test("unsigned, garbage, not Google, not listed and no allowlist are all refused", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    expect(await reason(getProviderSwitches(env, undefined))).toBe("SIGN_IN");
    expect(await reason(getProviderSwitches(env, "Bearer not-a-token"))).toBe("SIGN_IN");
    expect(await reason(getProviderSwitches(env, owner.idToken))).toBe("SIGN_IN");
    // No config/owner at all: nobody.
    expect(await reason(getProviderSwitches(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    // Another Google account.
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const other = await googleUser();
    expect(await reason(getProviderSwitches(env, bearer(other.idToken)))).toBe("NOT_OWNER");
    expect(await reason(setProviderSwitch(env, bearer(other.idToken), { slot: "image", value: "off", expect: FL, confirm: true }))).toBe("NOT_OWNER");
    // A listed uid that signed in some other way (email and password, unverified).
    const pw = await passwordUser();
    await db.doc(OWNER_DOC).set({ uids: [pw.uid], emails: [owner.email] });
    expect(await reason(getProviderSwitches(env, bearer(pw.idToken)))).toBe("NOT_OWNER");
    // An invalid allowlist: nobody.
    await db.doc(OWNER_DOC).set({ emails: owner.email });
    expect(await reason(getProviderSwitches(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    expect(await runtime()).toBeUndefined();
    expect(await audits()).toEqual([]);
  });

  test("the owner, by email (any case) or uid, sees the switches and switches one with confirm", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [owner.email.toUpperCase()] });
    const view = await getProviderSwitches(env, bearer(owner.idToken));
    expect(view.slots.map((s) => [s.id, s.value])).toEqual([["text", GEMINI], ["text-fallback", "none"], ["image", FL], ["image-fallback", NB]]);
    expect(optionView(view, "image", OPENAI_IMAGE)).toMatchObject({ ready: false });
    expect(optionView(view, "text", OPENAI_TEXT)).toMatchObject({ ready: false });
    // Google's name was cleared from the Auth user, as for the apps.
    expect((await auth.getUser(owner.uid)).displayName).toBeUndefined();

    expect(await reason(setProviderSwitch(env, bearer(owner.idToken), { slot: "image", value: "off", expect: FL }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(setProviderSwitch(env, bearer(owner.idToken), { slot: "image", value: OPENAI_IMAGE, expect: FL, confirm: true }))).toBe("NOT_READY");
    const done = await setProviderSwitch(env, bearer(owner.idToken), { slot: "image-fallback", value: "none", expect: NB, confirm: true });
    expect(done).toMatchObject({ changed: true, lastChange: { via: "web", slot: "image-fallback", from: NB, to: "none" } });
    expect(slotView(done, "image-fallback").value).toBe("none");
    expect(await reason(setProviderSwitch(env, bearer(owner.idToken), { slot: "image-fallback", value: "none", expect: NB, confirm: true }))).toBe("STALE");
    expect(await reason(setProviderSwitch(env, bearer(owner.idToken), { slot: "nope", value: "none", expect: NB, confirm: true }))).toBe("UNKNOWN_SWITCH");
    const [audit] = await audits();
    expect(audit.actor).toEqual({ via: "web", uid: owner.uid });

    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    expect(slotView(await getProviderSwitches(env, bearer(owner.idToken)), "image-fallback").value).toBe("none");

    // Log lines carry a uid hash at most: never the email or the uid.
    const logged = JSON.stringify(env.logs);
    expect(logged).not.toContain(owner.email);
    expect(logged).not.toContain(owner.uid);
    expect(env.logs.find((l) => l.message === "provider_profile_changed")?.data).toMatchObject({ via: "web", uidHash: uidHash(owner.uid) });
  });

  test("a switch needs a recent sign-in; revoked tokens and disabled users are refused", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const later = makeEnv({ now: () => Date.now() + 31 * 60_000 });
    expect((await getProviderSwitches(later, bearer(owner.idToken))).slots[2].value).toBe(FL);
    expect(await reason(setProviderSwitch(later, bearer(owner.idToken), { slot: "image", value: "off", expect: FL, confirm: true }))).toBe("RECENT_SIGN_IN");

    await new Promise((r) => setTimeout(r, 1100));
    await auth.revokeRefreshTokens(owner.uid);
    expect(await reason(getProviderSwitches(makeEnv(), bearer(owner.idToken)))).toBe("SIGN_IN");

    const second = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [second.email] });
    await auth.updateUser(second.uid, { disabled: true });
    expect(await reason(getProviderSwitches(makeEnv(), bearer(second.idToken)))).toBe("SIGN_IN");
    expect(await runtime()).toBeUndefined();
  });

  test("a Google sign-in whose email is not verified is refused, listed by uid or by email", async () => {
    const env = makeEnv();
    const owner = await googleUser(newEmail("unverified"), false);
    const token = await auth.verifyIdToken(owner.idToken);
    expect(token.firebase.sign_in_provider).toBe("google.com");
    expect(token.email_verified).toBe(false);
    await db.doc(OWNER_DOC).set({ uids: [owner.uid], emails: [owner.email] });
    expect(await reason(getProviderSwitches(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    expect(await reason(setProviderSwitch(env, bearer(owner.idToken), { slot: "image", value: "off", expect: FL, confirm: true }))).toBe("NOT_OWNER");
    // Refused before anything else: Google's name stays on the user (no write for a non-owner).
    expect((await auth.getUser(owner.uid)).displayName).toBe("Some Name");
    expect(await runtime()).toBeUndefined();
  });

  test("an account whose own email is another sign-in's: only its Google identity's email counts", async () => {
    const env = makeEnv();
    const appleEmail = newEmail("apple");
    const googleEmail = newEmail("google");
    // Made with Apple (verified email A), then Google (email B) linked to it.
    const apple = await idpSignIn("apple.com", { sub: randomUUID(), email: appleEmail, email_verified: true });
    const googleSub = randomUUID();
    await idpSignIn("google.com", { sub: googleSub, email: googleEmail, email_verified: true }, apple.idToken);
    const session = await idpSignIn("google.com", { sub: googleSub, email: googleEmail, email_verified: true });
    expect(session.uid).toBe(apple.uid);
    const token = await auth.verifyIdToken(session.idToken);
    expect(token.firebase.sign_in_provider).toBe("google.com");
    // The scenario: the account's (and the token's) email is the Apple one, and verified.
    expect(token.email).toBe(appleEmail);
    expect(token.email_verified).toBe(true);
    expect((await auth.getUser(apple.uid)).email).toBe(appleEmail);

    await db.doc(OWNER_DOC).set({ emails: [appleEmail] });
    expect(await reason(getProviderSwitches(env, bearer(session.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({ emails: [googleEmail.toUpperCase()] });
    expect((await getProviderSwitches(env, bearer(session.idToken))).slots[2].value).toBe(FL);
    // The Apple session itself is not a Google sign-in, whatever the list says.
    await db.doc(OWNER_DOC).set({ uids: [apple.uid], emails: [googleEmail, appleEmail] });
    expect(await reason(getProviderSwitches(env, bearer(apple.idToken)))).toBe("NOT_OWNER");
  });

  test("unlink and relink: the Google identity linked now decides, never the email left behind", async () => {
    const env = makeEnv();
    const first = await googleUser(newEmail("first"));
    // Google account G1 unlinked: the user keeps G1's email but has no Google identity.
    await auth.updateUser(first.uid, { providersToUnlink: ["google.com"] });
    expect((await auth.getUser(first.uid)).email).toBe(first.email);
    expect((await auth.getUser(first.uid)).providerData.map((p) => p.providerId)).not.toContain("google.com");
    await db.doc(OWNER_DOC).set({ uids: [first.uid], emails: [first.email] });
    // G1's session token is still valid (not revoked), but the user has no Google identity now.
    expect(await reason(getProviderSwitches(env, bearer(first.idToken)))).toBe("NOT_OWNER");

    // Google account G2 linked: the account email is still G1's.
    const secondEmail = newEmail("second");
    const secondSub = randomUUID();
    await auth.updateUser(first.uid, { providerToLink: { providerId: "google.com", uid: secondSub, email: secondEmail } });
    const session = await idpSignIn("google.com", { sub: secondSub, email: secondEmail, email_verified: true });
    expect(session.uid).toBe(first.uid);
    expect((await auth.verifyIdToken(session.idToken)).email).toBe(first.email);
    await db.doc(OWNER_DOC).set({ emails: [first.email] });
    expect(await reason(getProviderSwitches(env, bearer(session.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({ emails: [secondEmail] });
    expect((await getProviderSwitches(env, bearer(session.idToken))).slots[2].value).toBe(FL);
    // Listed by uid, the account passes with any Google session of it, since it has one again.
    await db.doc(OWNER_DOC).set({ uids: [first.uid] });
    expect((await getProviderSwitches(env, bearer(session.idToken))).slots[2].value).toBe(FL);
  });

  test("an empty allowlist is nobody", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [], emails: [] });
    expect(await reason(getProviderSwitches(makeEnv(), bearer(owner.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({});
    expect(await reason(getProviderSwitches(makeEnv(), bearer(owner.idToken)))).toBe("NOT_OWNER");
  });

  test("parseOwnerDoc and the console's origins", () => {
    expect(parseOwnerDoc({ emails: ["A@Example.com"] })).toEqual({ uids: [], emails: ["a@example.com"] });
    expect(parseOwnerDoc(null)).toBeNull();
    expect(parseOwnerDoc({ uids: "x" })).toBeNull();
    expect(parseOwnerDoc({ uids: Array.from({ length: 11 }, (_, i) => `u${i}`) })).toBeNull();
    expect(parseOwnerDoc({ emails: [""] })).toBeNull();
    expect(ownerConsoleOrigins("bestbefore-f9ee1", false)).toEqual(["https://bestbefore-f9ee1.firebaseapp.com", "https://bestbefore-f9ee1.web.app"]);
    expect(ownerConsoleOrigins("demo-foodtracker", true)).toContain("http://127.0.0.1:5050");
    expect(ownerConsoleOrigins("", false)).toBe(false);
    expect(ownerConsoleOrigins("x.evil.com", false)).toBe(false);
  });
});

// --- The callables' gate (src/core/ownerCall.ts, used by src/index.ts) ------------------------

describe("OwnerCallGate", () => {
  // At the start of a minute, so no test crosses a throttle window.
  const NOW = Math.floor(Date.now() / 60_000) * 60_000 + 1_000;
  const gateEnv = () => {
    const env = makeEnv({ now: () => NOW });
    return { env, gate: new OwnerCallGate(env) };
  };
  const req = (ip: string, auth?: OwnerCallRequest["auth"], authorization?: string): OwnerCallRequest => ({
    ...(auth ? { auth } : {}),
    rawRequest: { headers: { "x-forwarded-for": ip, ...(authorization ? { authorization } : {}) }, socket: { remoteAddress: "10.0.0.1" } },
  });
  const anyone = (n: number | string) => ({ uid: `u-${n}`, token: { email: `x${n}@example.com`, firebase: { sign_in_provider: "anonymous" } } });
  const ok = async () => "ok";

  test("a call without a token is refused with SIGN_IN, logged, and not counted", async () => {
    const { env, gate } = gateEnv();
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE * 3; i++) {
      expect(await reason(gate.run("getProviderSwitches", req("203.0.113.7"), ok))).toBe("SIGN_IN");
    }
    // Neither the address nor the instance ceiling was used up.
    expect(await gate.run("getProviderSwitches", req("203.0.113.7", anyone(1)), ok)).toBe("ok");
    const refused = env.logs.filter((l) => l.message === "owner_call_refused");
    expect(refused.length).toBe(10);
    expect(refused[0].data).toEqual({ function: "getProviderSwitches", reason: "SIGN_IN", suppressedSinceLast: 0 });
  });

  test("per address below the instance ceiling; the ceiling holds across addresses", async () => {
    const { env, gate } = gateEnv();
    expect(OWNER_PER_ADDRESS_PER_MINUTE).toBeLessThan(OWNER_OTHERS_PER_MINUTE);
    for (let i = 0; i < OWNER_PER_ADDRESS_PER_MINUTE; i++) expect(await gate.run("f", req("198.51.100.1", anyone(i)), ok)).toBe("ok");
    expect(await reason(gate.run("f", req("198.51.100.1", anyone("next")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // An IPv6 /64 is one address.
    for (let i = 0; i < OWNER_PER_ADDRESS_PER_MINUTE; i++) expect(await gate.run("f", req(`2001:db8:1:2::${i + 1}`, anyone(`v6-${i}`)), ok)).toBe("ok");
    expect(await reason(gate.run("f", req("2001:db8:1:2::ff", anyone("v6-next")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // Other addresses, until the instance ceiling (the refused calls above counted toward it).
    let admitted = 2 * OWNER_PER_ADDRESS_PER_MINUTE;
    let counted = admitted + 2;
    for (let i = 0; counted < OWNER_OTHERS_PER_MINUTE; i++, counted++, admitted++) {
      expect(await gate.run("f", req(`192.0.2.${i + 1}`, anyone(`c${i}`)), ok)).toBe("ok");
    }
    expect(await reason(gate.run("f", req("192.0.2.200", anyone("late")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    expect(admitted).toBeLessThanOrEqual(OWNER_OTHERS_PER_MINUTE);
    expect(env.logs.filter((l) => l.message === "owner_call_refused").map((l) => l.data?.reason)).toEqual(["RATE_LIMITED", "RATE_LIMITED", "RATE_LIMITED"]);
  });

  test("nobody else's calls starve a listed owner", async () => {
    const { gate } = gateEnv();
    const byEmail = newEmail("owner");
    await db.doc(OWNER_DOC).set({ uids: ["owner-uid"], emails: [byEmail] });
    // Others use up the instance ceiling.
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE; i++) await gate.run("f", req(`192.0.2.${i + 1}`, anyone(i)), ok);
    expect(await reason(gate.run("f", req("192.0.2.250", anyone("late")), ok))).toBe("resource-exhausted:RATE_LIMITED");
    // Listed by uid, from an address that is also used up.
    expect(await gate.run("f", req("192.0.2.1", { uid: "owner-uid", token: { firebase: { sign_in_provider: "google.com" } } }), ok)).toBe("ok");
    // Listed by email, a Google session (the bucket only: the core decides who is an owner).
    expect(await gate.run("f", req("192.0.2.1", { uid: "email-owner", token: { email: byEmail, firebase: { sign_in_provider: "google.com" } } }), ok)).toBe("ok");
    // The same email on a session that is not Google gets no owner bucket.
    expect(await reason(gate.run("f", req("192.0.2.251", { uid: "apple-user", token: { email: byEmail, firebase: { sign_in_provider: "apple.com" } } }), ok))).toBe("resource-exhausted:RATE_LIMITED");
  });

  test("a uid the core let in keeps the owner's bucket on this instance", async () => {
    const { gate } = gateEnv();
    const owner = { uid: "let-in", token: { firebase: { sign_in_provider: "google.com" } } };
    expect(await gate.run("f", req("192.0.2.9", owner), ok)).toBe("ok");
    for (let i = 0; i < OWNER_OTHERS_PER_MINUTE; i++) await gate.run("f", req(`198.51.100.${i + 1}`, anyone(i)), ok).catch(() => undefined);
    expect(await gate.run("f", req("192.0.2.9", owner), ok)).toBe("ok");
  });

  test("with the real core: the owner gets through, refusals are logged by reason, no log carries a uid or email", async () => {
    const { env, gate } = gateEnv();
    const owner = await googleUser(newEmail("owner"));
    const other = await googleUser(newEmail("other"));
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const call = (u: { uid: string; idToken: string; email: string }, ip: string) =>
      gate.run("getProviderSwitches", req(ip, { uid: u.uid, token: { email: u.email, firebase: { sign_in_provider: "google.com" } } }, bearer(u.idToken)),
        (authorization) => getProviderSwitches(env, authorization));
    expect((await call(owner, "192.0.2.1")).slots[2].value).toBe(FL);
    expect(await reason(call(other, "192.0.2.2"))).toBe("NOT_OWNER");
    expect(await reason(gate.run("setProviderSwitch", req("192.0.2.2", { uid: other.uid }, "Bearer garbage"),
      (authorization) => setProviderSwitch(env, authorization, {})))).toBe("SIGN_IN");
    const refused = env.logs.filter((l) => l.message === "owner_call_refused").map((l) => l.data);
    // Past the throttle every refusal is logged, with the caller's uid hash (never the uid).
    expect(refused).toEqual([
      { function: "getProviderSwitches", reason: "NOT_OWNER", uidHash: uidHash(other.uid) },
      { function: "setProviderSwitch", reason: "SIGN_IN", uidHash: uidHash(other.uid) },
    ]);
    const logged = JSON.stringify(env.logs);
    for (const s of [owner.uid, owner.email, other.uid, other.email]) expect(logged).not.toContain(s);
  });

  test("src/index.ts binds both callables through the gate, one instance each, no App Check", () => {
    const index = readFileSync(join(__dirname, "../src/index.ts"), "utf8");
    expect(index).toContain("return guarded(name, () => ownerGate().run(name, request, (authorization) => fn(ownerEnv(), authorization)));");
    expect(index).toMatch(/const ownerCallable = \{\s*enforceAppCheck: false,\s*invoker: "public",\s*cors: ownerConsoleOrigins\(projectId\(\), process\.env\.FUNCTIONS_EMULATOR === "true"\),\s*timeoutSeconds: 30,\s*maxInstances: 1,\s*concurrency: 1,\s*\} as const;/);
    expect(index).toMatch(/export const getProviderSwitches = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("getProviderSwitches", request,/);
    expect(index).toMatch(/export const setProviderSwitch = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("setProviderSwitch", request,/);
    // Nothing of the caller's is logged there.
    const section = index.slice(index.indexOf("// --- The owner console"), index.indexOf("function decodePubSub"));
    expect(section).not.toMatch(/logger\.(info|warn|error)\(/);
  });
});

// --- The admin command ------------------------------------------------------------------------

describe("npm run admin -- providers", () => {
  function cliEnv(over: Partial<OwnerEnv> = {}): ImageEnv & { lines: string[] } {
    const lines: string[] = [];
    return { ...makeEnv(over), print: (l: string) => void lines.push(l), lines };
  }

  test("parses list and set, refuses the rest, and says what replaced use", () => {
    expect(parseProviderArgs(["providers", "list"])).toEqual({ command: "list", project: "demo-foodtracker" });
    expect(parseProviderArgs(["image", "list"])).toEqual({ command: "list", project: "demo-foodtracker" });
    expect(parseProviderArgs(["providers", "set", "image", "off", "--apply", "--project", "p"])).toEqual({ command: "set", slot: "image", value: "off", apply: true, project: "p" });
    expect(parseProviderArgs(["providers", "set", "text-fallback", "none"])).toMatchObject({ apply: false });
    expect(() => parseProviderArgs(["providers"])).toThrow(AdminRefusal);
    expect(() => parseProviderArgs(["providers", "set", "image"])).toThrow(AdminRefusal);
    expect(() => parseProviderArgs(["providers", "list", "--apply"])).toThrow(AdminRefusal);
    expect(() => parseProviderArgs(["providers", "set", "image", "off", "--force"])).toThrow(AdminRefusal);
    expect(() => parseProviderArgs(["providers", "use", "flash-lite"])).toThrow(/replaced by four switches/);
  });

  test("list shows the four switches, each option's readiness and what is missing", async () => {
    const env = cliEnv();
    await runProviders(parseProviderArgs(["providers", "list"]), env);
    const text = env.lines.join("\n");
    expect(text).toContain("Last change: none recorded");
    expect(text).toContain(`Text model (text): ${GEMINI}`);
    expect(text).toContain("Text fallback (text-fallback): none");
    expect(text).toContain(`Picture model (image): ${FL}`);
    expect(text).toContain(`Picture fallback (image-fallback): ${NB}`);
    expect(text).toMatch(new RegExp(`\\* ${GEMINI.replace(/[./]/g, "\\$&")}\\s+in force`));
    expect(text).toMatch(/openai\/gpt-6\.1-terra\s+NOT READY/);
    expect(text).toContain("privacy policy");
    expect(text).toMatch(/vertex\/gemini-nano-banana-2\.1\s+blocked/);
    expect(text).toContain("providers set <text|text-fallback|image|image-fallback> <value>");
  });

  test("set: a dry run writes nothing, --apply writes, a refusal is an AdminRefusal", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const dry = cliEnv();
    await runProviders(parseProviderArgs(["providers", "set", "image-fallback", "none"]), dry);
    expect(dry.lines.join("\n")).toContain("Dry run, nothing changed. --apply would write to config/runtime:");
    expect(dry.lines).toContain('  - image > fallbackModel = ""');
    expect(dry.lines.join("\n")).toContain("To switch: npm run admin -- providers set image-fallback none --apply");
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);

    const wet = cliEnv();
    await runProviders(parseProviderArgs(["providers", "set", "image-fallback", "none", "--apply"]), wet);
    expect(wet.lines.join("\n")).toMatch(/Done, written to config\/runtime:[\s\S]*Audit record providerProfileChanges\//);
    expect(((await runtime())!.image as Record<string, unknown>).fallbackModel).toBe("");
    expect(await audits()).toHaveLength(1);

    const again = cliEnv();
    await runProviders(parseProviderArgs(["providers", "set", "image-fallback", "none", "--apply"]), again);
    expect(again.lines).toContain("Already in force: nothing to write.");
    expect(await reason(runProviders(parseProviderArgs(["providers", "set", "text", OPENAI_TEXT, "--apply"]), cliEnv()))).toMatch(/^admin:Refused \(NOT_READY\)/);
    expect(await reason(runProviders(parseProviderArgs(["providers", "set", "image", "nope"]), cliEnv()))).toMatch(/^admin:Refused \(UNKNOWN_OPTION\)/);
    expect(await reason(runProviders(parseProviderArgs(["providers", "set", "pictures", "off"]), cliEnv()))).toMatch(/^admin:Refused \(UNKNOWN_SWITCH\)/);
    expect(await reason(runProviders(parseProviderArgs(["providers", "set", "text-fallback", GEMINI]), cliEnv()))).toMatch(/^admin:Refused \(SAME_AS_PRIMARY\)/);
    expect(await audits()).toHaveLength(1);
  });
});

// --- What the board and the console page show -------------------------------------------------

describe("the board and the console page", () => {
  const NOW = Date.parse("2026-11-15T13:45:00Z");
  const boardOf = (data: Record<string, unknown>) => buildBoard({
    project: "demo-foodtracker",
    generatedAtMs: NOW,
    days: 30,
    config: parseRuntimeConfig(data),
    stats: new Map(boardStatsDays(NOW, 30).map((d) => [d, undefined])),
    purchases: [],
    ledger: { negativeBalanceTotal: 0, checkedAtMs: null },
    holders: { accounts: 0, recipes: 0 },
    reads: 40,
  });

  test("the board shows the four switches read-only and links to the console", () => {
    const b = boardOf({});
    const html = renderBoardHtml(b);
    expect(html).toContain(`<code>${FL}</code>, fallback <code>${NB}</code>; recipe text <code>${GEMINI}</code>, fallback none`);
    expect(html).toContain('href="https://demo-foodtracker.firebaseapp.com/"');
    expect(html).not.toMatch(/<form|<select|fetch\(/);
    expect(boardJson(b).pictures).toEqual({ provider: "vertex", model: "gemini-3.1-flash-lite-image", fallback: NB, offered: true });
    expect(boardJson(b).text).toEqual({ model: GEMINI, fallback: "none" });
    expect(boardSummary(boardOf({ image: { provider: "off" } })).join("\n")).toContain(`Provider switches: text ${GEMINI}, fallback none; pictures off; offered to the apps: no.`);
    expect(boardJson(boardOf({ image: { model: "gemini-3-pro-image" } })).pictures).toMatchObject({ model: "gemini-3-pro-image", offered: false });
  });

  test("the static page holds no option, model, address or data, and writes text only", () => {
    const dir = join(__dirname, "../../owner-console");
    const html = readFileSync(join(dir, "index.html"), "utf8");
    const js = readFileSync(join(dir, "console.js"), "utf8");
    for (const slot of SLOTS) {
      for (const o of catalogue(slot)) {
        expect(html + js).not.toContain(o.label);
        if (o.provider) expect(html + js).not.toContain(o.value);
      }
    }
    expect(html + js).not.toContain("@");
    expect(js).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|localStorage|sessionStorage/);
    expect(js).toContain("inMemoryPersistence");
    // It calls the four callables by these names, and the switch sends exactly what the core expects.
    for (const name of ["getProviderSwitches", "setProviderSwitch", "getOwnerSettings", "setOwnerSettings"]) expect(js).toContain(`call("${name}"`);
    expect(js).toContain("{ slot: s.id, value: o.value, expect: s.value, confirm: true }");
  });

  test("the SDK is served from the site, as recorded in its SOURCE.txt", () => {
    const dir = join(__dirname, "../../owner-console");
    const js = readFileSync(join(dir, "console.js"), "utf8");
    const imports = [...js.matchAll(/from "([^"]+)"/g)].map((m) => m[1]);
    expect(imports).toEqual(["/vendor/firebasejs/13.4.0/firebase-app.js", "/vendor/firebasejs/13.4.0/firebase-auth.js"]);
    const vendor = join(dir, "vendor/firebasejs/13.4.0");
    const note = readFileSync(join(vendor, "SOURCE.txt"), "utf8");
    const sha = (f: string) => createHash("sha256").update(readFileSync(join(vendor, f))).digest("hex");
    expect(note).toContain(`${sha("firebase-app.js")} (served here unchanged)`);
    expect(note).toContain(`${sha("firebase-auth.js")} as served here`);
    // Nothing is loaded from elsewhere but the one Google sign-in script the SDK adds itself.
    const auth = readFileSync(join(vendor, "firebase-auth.js"), "utf8");
    expect(auth).not.toContain("www.gstatic.com");
    expect(auth.startsWith('import{') && auth.includes('from"./firebase-app.js"')).toBe(true);
  });

  test("Hosting's headers cover the console's own files only, never Hosting's reserved /__/ paths", () => {
    const root = join(__dirname, "../..");
    const hosting = JSON.parse(readFileSync(join(root, "firebase.json"), "utf8")).hosting;
    expect(hosting.public).toBe("owner-console");
    expect(hosting.headers).toHaveLength(1);
    const rule = hosting.headers[0];
    expect(rule.source).toBeUndefined();
    const re = new RegExp(rule.regex);
    for (const p of ["/", "/index.html", "/console.js", "/console.css", "/vendor/firebasejs/13.4.0/firebase-app.js", "/vendor/firebasejs/13.4.0/firebase-auth.js"]) {
      expect(re.test(p), p).toBe(true);
    }
    for (const p of ["/__/auth/handler", "/__/auth/iframe", "/__/auth/handler.js", "/__/firebase/init.json", "/__/auth/iframe?apiKey=x", "/x/index.html", "/index.html/x"]) {
      expect(re.test(p), p).toBe(false);
    }
    const csp = rule.headers.find((h: { key: string }) => h.key === "Content-Security-Policy").value as string;
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toMatch(/script-src 'self' https:\/\/apis\.google\.com;/);
    expect(csp).not.toContain("gstatic");
    expect(csp).not.toContain("unsafe-inline");
    expect(csp).not.toContain("127.0.0.1");

    // firebase.local.json (the Hosting emulator only) is the same but for the emulators in the CSP.
    const local = JSON.parse(readFileSync(join(root, "firebase.local.json"), "utf8"));
    expect(Object.keys(local).sort()).toEqual(["emulators", "hosting"]);
    const localCsp = local.hosting.headers[0].headers.find((h: { key: string }) => h.key === "Content-Security-Policy").value;
    expect(localCsp).toBe(csp
      .replace("connect-src 'self'", "connect-src 'self' http://127.0.0.1:9099 http://127.0.0.1:5001")
      .replace("frame-src ", "frame-src http://127.0.0.1:9099 "));
    const withoutCsp = (h: typeof hosting) => JSON.stringify({ ...h, headers: h.headers.map((r: typeof rule) => ({ ...r, headers: r.headers.filter((x: { key: string }) => x.key !== "Content-Security-Policy") })) });
    expect(withoutCsp(local.hosting)).toBe(withoutCsp(hosting));
    expect(local.emulators.hosting.port).toBe(5050);
    expect(ownerConsoleOrigins("demo-foodtracker", true)).toContain(`http://127.0.0.1:${local.emulators.hosting.port}`);
  });
});
