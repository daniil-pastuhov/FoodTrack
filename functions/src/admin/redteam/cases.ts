/**
 * The red-team case file (test/redteam/cases.json, plus an optional git-ignored
 * test/redteam/cases.local.json the owner may keep real slurs or other local cases in): every item
 * of test/injection-prompts.md as a request and the checks its expectation becomes. Pure loading
 * and validation; no network.
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import type { OutputRule } from "../../core/outputSafety";
import type { InputRule } from "../../core/inputSafety";

export type Severity = "breach" | "fail" | "warn";

interface AssertionBase {
  /**
   * What a violation means. `breach` (default): FAIL when the server delivered the answer, CAUGHT
   * when it refused it. `fail`: FAIL whatever the server did (something that must never happen,
   * such as an id outside the schema's enum). `warn`: NEEDS HUMAN READ when delivered.
   */
  severity?: Severity;
  /** Shown in the report: the check is a heuristic, not one of the server's rules. */
  heuristic?: boolean;
  note?: string;
}

export type Assertion = AssertionBase & (
  | { type: "forbid"; patterns: string[] }
  | { type: "forbidUnless"; patterns: string[]; unless: string }
  | { type: "language"; language: string }
  | { type: "maxSteps"; max: number }
  | { type: "maxStepChars"; max: number }
  | { type: "maxMinutes"; max: number }
  | { type: "idsNotUsed"; ids: string[] }
  | { type: "idsUsed"; ids: string[] }
  | { type: "minPantryItems"; min: number }
  | { type: "day1Uses"; ids: string[]; all?: boolean }
  | { type: "everyDayUses"; ids: string[] }
  | { type: "datesRespected" }
  | { type: "idsInEnum" }
  | { type: "staplesNotPantry" }
  | { type: "usesHonest" }
  | { type: "maxExtras"; max: number }
  | { type: "usage"; maxOutputTokens?: number; maxThinkingTokens?: number; belowReserved?: boolean }
);

export const ASSERTION_TYPES = [
  "forbid", "forbidUnless", "language", "maxSteps", "maxStepChars", "maxMinutes", "idsNotUsed", "idsUsed",
  "minPantryItems", "day1Uses", "everyDayUses", "datesRespected", "idsInEnum", "staplesNotPantry", "usesHonest",
  "maxExtras", "usage",
] as const satisfies readonly Assertion["type"][];

/** What the server says before any model call: accepted, or the refusal's rule or reason. */
export type InputVerdict = "accepted" | "NOT_ENOUGH_ITEMS" | "ALLERGEN_CONSENT_REQUIRED" | "INVALID_REQUEST" | InputRule;

export const INPUT_VERDICTS: readonly InputVerdict[] = [
  "accepted", "NOT_ENOUGH_ITEMS", "ALLERGEN_CONSENT_REQUIRED", "INVALID_REQUEST",
  "TYPE", "EMPTY", "TOO_LONG", "CHARACTERS", "WORDS", "WORD_LENGTH", "MARKS", "MIXED_SCRIPT", "ENCODED",
  "REPEATED", "SPACED_LETTERS", "INSTRUCTION",
];

/** Every output rule of core/outputSafety.ts (the server's verdict on an answer). */
export const OUTPUT_RULES = [
  "SHAPE", "LENGTH", "STEPS", "CHARACTERS", "MARKUP", "URL", "CONTACT", "PHONE", "LEAK", "SCRIPT", "LANGUAGE",
  "REPETITION", "PANTRY_REF", "NO_PANTRY", "DAY_NO_PANTRY", "STAPLES_ONLY", "DUPLICATE_DISH", "NON_FOOD",
  "SOLICITATION", "ALLERGEN", "DIET",
] as const satisfies readonly OutputRule[];

/**
 * Verdicts from the generator and judge rather than the output rules (adapters/gemini.ts): the
 * model's safety system, a finish reason, an unusable answer, the token ceiling, a judge verdict.
 */
export const GENERATOR_VERDICTS = [
  "PROMPT_BLOCKED", "FINISH_SAFETY", "FINISH_PROHIBITED_CONTENT", "FINISH_BLOCKLIST", "FINISH_SPII",
  "FINISH_RECITATION", "FINISH_LANGUAGE", "FINISH_MAX_TOKENS", "EMPTY", "NOT_JSON", "TOKEN_CEILING",
  "JUDGE_OFF_TOPIC", "JUDGE_UNSAFE_FOOD", "JUDGE_HARMFUL",
] as const;

/** Verdicts that are an error of the run, not a verdict on the answer. */
export const ERROR_VERDICTS = ["API_ERROR", "TIMEOUT", "GENERATOR_ERROR", "JUDGE_FAILED"] as const;

/** The model's own safety refusals, for cases that accept any of them. */
export const SAFETY_VERDICTS = ["PROMPT_BLOCKED", "FINISH_SAFETY", "FINISH_PROHIBITED_CONTENT", "FINISH_BLOCKLIST", "FINISH_SPII"];
export const QUALITY_VERDICTS = ["NO_PANTRY", "DAY_NO_PANTRY", "STAPLES_ONLY", "DUPLICATE_DISH"];

export interface HumanRead {
  /** What to read for, in words. */
  note: string;
  /** A regex (case-insensitive): every text field matching it is quoted. */
  quote?: string;
  /** Text fields quoted in full: title, summary, tip, ingredient, step. */
  fields?: string[];
}

export interface RedTeamCase {
  /** The checklist number (with a letter for one of several requests under one item). */
  id: string;
  section: string;
  title: string;
  /** The checklist's expectation, in its words. */
  checklist: string;
  /** `manual`: needs the deployed function and an account; never automated. */
  mode?: "auto" | "manual";
  manualSteps?: string[];
  /** An honest request: any refusal not listed in `expect.output` is a false positive (FAIL). */
  honest?: boolean;
  /** Run the output judge for this case, as `config/runtime.outputJudge` would. */
  judge?: "shadow" | "enforce";
  /** A generateRecipe body without `requestId`; merged over `requestFile` when both are given. */
  request?: Record<string, unknown>;
  /** A JSON request file, relative to the case file. */
  requestFile?: string;
  expect: {
    input: InputVerdict;
    /** Server verdicts that meet the expectation: "ok", output rules, generator verdicts, or "*". */
    output?: string[];
    assertions?: Assertion[];
    humanRead?: HumanRead;
  };
}

export interface CaseFile {
  version: 1;
  /** Shared fragments, ignored by the runner (the case file's own notes). */
  notes?: string[];
  cases: RedTeamCase[];
}

export interface LoadedCase extends RedTeamCase {
  /** The request as it will be validated: requestFile merged with request. */
  raw?: Record<string, unknown>;
  /** Which file the case came from. */
  source: string;
}

const KNOWN_OUTPUT = new Set<string>(["ok", "*", ...OUTPUT_RULES, ...GENERATOR_VERDICTS]);

function problem(source: string, id: string, message: string): never {
  throw new Error(`${source}: case ${id}: ${message}`);
}

/** Checks one case's structure and the codes it names. Throws with the case id. */
export function validateCase(c: RedTeamCase, source: string): void {
  const id = typeof c.id === "string" ? c.id : "?";
  if (typeof c.id !== "string" || !/^[0-9]{1,3}[a-z]?$/.test(c.id)) problem(source, id, "id must be a checklist number, optionally with a letter");
  if (typeof c.section !== "string" || !/^[A-Z]$/.test(c.section)) problem(source, id, "section must be one capital letter");
  if (typeof c.title !== "string" || !c.title) problem(source, id, "title is required");
  if (typeof c.checklist !== "string" || !c.checklist) problem(source, id, "checklist (the expectation in words) is required");
  if (c.mode !== undefined && c.mode !== "auto" && c.mode !== "manual") problem(source, id, "mode must be auto or manual");
  if (c.mode === "manual") {
    if (!Array.isArray(c.manualSteps) || c.manualSteps.length === 0) problem(source, id, "a manual case needs manualSteps");
    return;
  }
  if (!c.request && !c.requestFile) problem(source, id, "request or requestFile is required");
  if (c.judge !== undefined && c.judge !== "shadow" && c.judge !== "enforce") problem(source, id, "judge must be shadow or enforce");
  if (!c.expect || !(INPUT_VERDICTS as readonly string[]).includes(c.expect.input)) problem(source, id, "expect.input must be an input verdict");
  for (const o of c.expect.output ?? []) if (!KNOWN_OUTPUT.has(o)) problem(source, id, `unknown output verdict ${o}`);
  if (c.expect.input === "accepted" && !(c.expect.output ?? []).length) problem(source, id, "an accepted case needs expect.output");
  for (const a of c.expect.assertions ?? []) {
    if (!(ASSERTION_TYPES as readonly string[]).includes(a.type)) problem(source, id, `unknown assertion ${String(a.type)}`);
    if (a.severity !== undefined && !["breach", "fail", "warn"].includes(a.severity)) problem(source, id, `bad severity ${a.severity}`);
    const patterns = a.type === "forbid" || a.type === "forbidUnless" ? a.patterns : [];
    if ((a.type === "forbid" || a.type === "forbidUnless") && (!Array.isArray(patterns) || patterns.length === 0)) {
      problem(source, id, `${a.type} needs patterns`);
    }
    for (const p of [...patterns, ...(a.type === "forbidUnless" ? [a.unless] : [])]) {
      try {
        new RegExp(p, "iu");
      } catch {
        problem(source, id, `bad regex ${p}`);
      }
    }
  }
  if (c.expect.humanRead?.quote) {
    try {
      new RegExp(c.expect.humanRead.quote, "iu");
    } catch {
      problem(source, id, `bad humanRead regex ${c.expect.humanRead.quote}`);
    }
  }
}

function loadFile(path: string): LoadedCase[] {
  const file = JSON.parse(readFileSync(path, "utf8")) as CaseFile;
  if (file.version !== 1 || !Array.isArray(file.cases)) throw new Error(`${path}: not a version 1 case file`);
  const base = dirname(path);
  return file.cases.map((c) => {
    validateCase(c, path);
    if (c.mode === "manual") return { ...c, source: path };
    const fromFile = c.requestFile ? (JSON.parse(readFileSync(resolve(base, c.requestFile), "utf8")) as Record<string, unknown>) : {};
    return { ...c, raw: { ...fromFile, ...(c.request ?? {}) }, source: path };
  });
}

/** The cases of `path` and, when it exists, of the local file beside it. Ids must be unique across both. */
export function loadCases(path: string, localPath?: string): LoadedCase[] {
  const cases = loadFile(path);
  if (localPath && existsSync(localPath)) cases.push(...loadFile(localPath));
  const seen = new Set<string>();
  for (const c of cases) {
    if (seen.has(c.id)) throw new Error(`${c.source}: duplicate case id ${c.id}`);
    seen.add(c.id);
  }
  return cases;
}

/** Cases selected by `--only` ids and `--section` letters (both optional; both apply). */
export function selectCases(cases: readonly LoadedCase[], only?: string[], sections?: string[]): LoadedCase[] {
  const unknown = (only ?? []).filter((id) => !cases.some((c) => c.id === id));
  if (unknown.length) throw new Error(`unknown case ids: ${unknown.join(", ")}`);
  return cases.filter((c) => (!only?.length || only.includes(c.id)) && (!sections?.length || sections.includes(c.section)));
}
