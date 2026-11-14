/**
 * Validation of the model's answer before anything is stored or returned. Pure: no I/O.
 *
 * The model answers in an internal shape (see adapters/prompt.ts): pantry ingredients carry a
 * `pantryId` ("p1".."pN", or "none" for a staple) instead of a self-declared `fromPantry` flag,
 * and a menu day's `uses` is a list of pantry ids. This module turns that into the public Recipe /
 * Menu (CONTRACT.md), so `fromPantry` and `uses` are always the server's reading of the ids and
 * `uses` holds the user's own item names, never model text.
 *
 * Text direction plays no part: every check works on code points in logical order, and the answer
 * is stored and returned as JSON strings, so right-to-left item names need nothing special.
 *
 * Repairs (not failures): Unicode NFC, tabs and line breaks to spaces, runs of spaces collapsed,
 * emoji removed, leading "1." / "-" bullets removed from steps, `servings` and `difficulty` set to
 * what was requested. Everything else that is wrong makes the whole answer invalid (an
 * OutputRule), which generation settles as a content failure.
 */
import { coursesFor, menuDates, type Course, type GenerateRequest, type Menu, type MenuDay, type Recipe, type RecipeIngredient } from "../contract";
import {
  ALLERGEN_ALIASES,
  ALLERGEN_GROUPS,
  DIET_RULES,
  NON_FOOD,
  SOLICITATION,
  STAPLE_FILLERS,
  STAPLE_WORDS,
  compileTerms,
  foldText,
  matchesAny,
  type CompiledTerm,
  type DietKey,
  type FoldedText,
} from "./foodTerms";
import { fold, scriptOf } from "./inputSafety";

export type OutputRule =
  | "SHAPE"
  | "LENGTH"
  | "STEPS"
  | "CHARACTERS"
  | "MARKUP"
  | "URL"
  | "CONTACT"
  | "PHONE"
  | "LEAK"
  | "SCRIPT"
  | "LANGUAGE"
  | "REPETITION"
  | "PANTRY_REF"
  | "NO_PANTRY"
  | "DAY_NO_PANTRY"
  | "STAPLES_ONLY"
  | "DUPLICATE_DISH"
  | "NON_FOOD"
  | "SOLICITATION"
  | "ALLERGEN"
  | "DIET";

/**
 * Rules a model following its instructions on honest input should not break; a violation points
 * at injected text and counts against the account's daily rejection limit. SHAPE, LENGTH and STEPS
 * are formatting slips and do not count (they are still content failures).
 */
export const SUSPICIOUS_OUTPUT_RULES: ReadonlySet<OutputRule> = new Set<OutputRule>([
  "CHARACTERS", "MARKUP", "URL", "CONTACT", "PHONE", "LEAK", "SCRIPT", "LANGUAGE", "REPETITION",
  "PANTRY_REF", "NON_FOOD", "SOLICITATION", "ALLERGEN", "DIET",
]);

/**
 * The quality gate: an answer that is clean but is not what was paid for (a recipe or a menu day
 * that uses no pantry item, a dish made only of staples, the same dish twice in a menu). Checked
 * last, after every other rule, so it only ever fires on an otherwise acceptable answer. Settled
 * as a "quality" failure: the service did not deliver, so it is refunded in full, uses none of the
 * account's content allowance and is not a prompt rejection; its model spend is booked as usual
 * and it counts towards infraRefundsPerDay (generation.ts settle).
 */
export const QUALITY_OUTPUT_RULES: ReadonlySet<OutputRule> = new Set<OutputRule>([
  "NO_PANTRY", "DAY_NO_PANTRY", "STAPLES_ONLY", "DUPLICATE_DISH",
]);

/**
 * A refusal by the quality gate (QUALITY_OUTPUT_RULES) carries the otherwise valid answer as
 * `value`, so the output judge can still look at it (generation.ts); no other refusal does.
 */
export type OutputCheck<T> = { ok: true; value: T } | { ok: false; rule: OutputRule; value?: T };

export interface OutputContext {
  req: GenerateRequest;
  /** The per-request marker in the system instruction; it must never appear in the answer. */
  canary: string;
  /** The system instruction actually sent, for the verbatim-leak check. */
  systemInstruction: string;
}

export const LIMITS = {
  title: 100,
  summary: 400,
  amount: 40,
  ingredientName: 80,
  step: 600,
  stepMin: 3,
  tip: 300,
  ingredients: 40,
  minutes: 24 * 60,
} as const;

/** Steps per recipe by detail level: the prompt's target, with slack for an honest overrun. */
export const STEP_BOUNDS: Readonly<Record<string, { min: number; max: number }>> = {
  brief: { min: 1, max: 7 },
  standard: { min: 2, max: 10 },
  stepByStep: { min: 2, max: 18 },
};

export const pantryIdsFor = (n: number): string[] => Array.from({ length: n }, (_, i) => `p${i + 1}`);

class Invalid extends Error {
  constructor(readonly rule: OutputRule) {
    super(rule);
  }
}
const bad = (rule: OutputRule): never => {
  throw new Invalid(rule);
};

// --- Text repair and scanning ------------------------------------------------------------------

const EMOJI = /[\p{Extended_Pictographic}\u{1F3FB}-\u{1F3FF}\u{1F1E6}-\u{1F1FF}]|\uFE0E|\uFE0F|\u20E3|\u200D(?=\p{Extended_Pictographic})/gu;
const WHITESPACE = /[\t\n\r\u00A0\u2000-\u200A\u202F\u205F\u3000 ]+/g;
const FORBIDDEN_CLASS = /[\p{Cc}\p{Cf}\p{Co}\p{Cn}\p{Cs}\p{Zl}\p{Zp}]/u;

function clean(s: string): string {
  return s.normalize("NFC").replace(EMOJI, "").replace(WHITESPACE, " ").trim();
}

const MARKUP: readonly RegExp[] = [
  /<\s*[/!?]?\s*[a-z]/i, // HTML/XML tags, comments, processing instructions
  /&(?:[a-z][a-z0-9]{1,30}|#\d{1,7}|#x[0-9a-f]{1,6});/i, // character entities
  /\[[^\]]*\]\s*\(/, // markdown link or image
  /`|\*\*|__|~~|[{}\\|]/, // code, bold, strike, braces, escapes, tables
  /(?:^|\s)#{1,6}\s/, // headings
  /(?:^|\s)>\s/, // quotes
  /\$\{|\{\{|%[0-9a-f]{2}/i, // template and percent-encoded fragments
];
const URL_PATTERNS: readonly RegExp[] = [
  /\b[a-z][a-z0-9+.-]{1,20}:\/\//i,
  /\b(?:mailto|tel|sms|callto|javascript|vbscript|data|intent|market|itms-apps|itms-services|whatsapp|tg|viber|skype|geo|file):/i,
  /\bwww\d{0,3}\./i,
  /(?:^|[^\p{L}\p{N}])[\p{L}\p{N}][\p{L}\p{N}-]*\.(?:com|net|org|info|biz|io|co|app|dev|xyz|site|online|shop|store|top|link|click|me|tv|ly|gl|to|cc|ru|su|ua|uk|no|se|dk|fi|de|at|ch|fr|es|it|pl|nl|be|eu|us|ca|au|in|cn|jp|kr|br|ws|gg|im|ai|sh|gd|onion|\u0440\u0444)(?![\p{L}\p{N}])/iu,
];
const PHONE = /\+?\d[\d \-()./]{5,}\d/g;
const REPEATED_CHAR = /(.)\1{9,}/u;

const NON_FOOD_TERMS = compileTerms(NON_FOOD);
const SOLICITATION_TERMS = compileTerms(SOLICITATION);
/** Names of the prompt's own parts; they never belong in a recipe. */
const PROMPT_WORDS = compileTerms(["pantry_data", "begin_pantry_data", "end_pantry_data", "system instruction", "systeminstruks", "response schema", "pantryid"]);

const LEAK_NGRAM = 8;

function ngrams(words: string[], n: number): Set<string> {
  const out = new Set<string>();
  for (let i = 0; i + n <= words.length; i++) out.add(words.slice(i, i + n).join(" "));
  return out;
}

interface Scanner {
  ctx: OutputContext;
  leakNgrams: Set<string>;
  canary: string;
  /** Scripts a letter of the answer may be in (see allowedScripts). */
  allowed: ReadonlySet<string>;
  /** The request's item and allergen names, folded, one per line: what other-script text may quote. */
  quotable: string;
  /** The requested language's script. */
  wanted: string;
  /** Letters in the wanted script, and letters in any script (Common and Inherited not counted). */
  wantedLetters: number;
  letters: number;
}

/** Scripts that are written together, so an item name in one may be quoted in another. */
const SCRIPT_FAMILIES: ReadonlyArray<readonly string[]> = [
  ["Han", "Hiragana", "Katakana"],
  ["Hangul", "Han"],
];

/**
 * The scripts an answer may use: Latin and Cyrillic (the scripts of the ten recipe languages; a
 * Norwegian user can own "Сметана", a Russian recipe can say "°C"), plus every script a letter of
 * the request's own item and allergen names is in, so a Greek or Chinese item name can be quoted
 * in an English recipe. Japanese and Korean bring the scripts written alongside them. An answer in
 * a script none of those uses is refused (SCRIPT), and so is any run of letters outside Latin and
 * Cyrillic that is not part of one of those names (scan): the answer may quote the user's items in
 * their own script, but not write anything else in it, since no word list reads other languages.
 */
export function allowedScripts(req: GenerateRequest): Set<string> {
  const out = new Set<string>(["Latin", "Cyrillic"]);
  for (const name of [...req.ingredients.map((i) => i.name), ...req.allergens]) {
    for (const ch of name) {
      if (!/\p{L}/u.test(ch)) continue;
      const s = scriptOf(ch);
      out.add(s);
      for (const family of SCRIPT_FAMILIES) if (family.includes(s)) family.forEach((f) => out.add(f));
    }
  }
  return out;
}

function scan(sc: Scanner, s: string): void {
  if (s.toUpperCase().includes(sc.canary)) bad("LEAK");
  if (FORBIDDEN_CLASS.test(s)) bad("CHARACTERS");
  if (MARKUP.some((r) => r.test(s))) bad("MARKUP");
  if (URL_PATTERNS.some((r) => r.test(s))) bad("URL");
  if (s.includes("@") || s.includes("＠")) bad("CONTACT");
  for (const m of s.matchAll(PHONE)) {
    if ((m[0].match(/\d/g) ?? []).length >= 8) bad("PHONE");
  }
  if (REPEATED_CHAR.test(s)) bad("REPETITION");
  const folded = foldText(s);
  if (matchesAny(folded, PROMPT_WORDS) || s.toLowerCase().includes("pantry_data")) bad("LEAK");
  for (const g of ngrams(folded.words, LEAK_NGRAM)) if (sc.leakNgrams.has(g)) bad("LEAK");
  let run = 1;
  for (let i = 1; i < folded.words.length; i++) {
    run = folded.words[i] === folded.words[i - 1] ? run + 1 : 1;
    if (run >= 4) bad("REPETITION");
  }
  if (matchesAny(folded, NON_FOOD_TERMS)) bad("NON_FOOD");
  if (matchesAny(folded, SOLICITATION_TERMS)) bad("SOLICITATION");
  let quote = "";
  const endRun = () => {
    if (quote && !sc.quotable.includes(fold(quote))) bad("SCRIPT");
    quote = "";
  };
  for (const ch of s) {
    const letter = /\p{L}/u.test(ch);
    const script = letter || /\p{M}/u.test(ch) ? scriptOf(ch) : "";
    // A run of letters (with their marks) outside the recipe languages' scripts must be part of
    // one of the request's own names: quoting "Φέτα" or "豆腐" is fine, free text is not, since no
    // word list reads it. Common and Inherited letters and marks continue a run that is open.
    if (script !== "" && script !== "Latin" && script !== "Cyrillic" && (quote !== "" || (script !== "Common" && script !== "Inherited"))) {
      quote += ch;
    } else {
      endRun();
    }
    if (!letter || script === "Common" || script === "Inherited") continue;
    if (!sc.allowed.has(script)) bad("SCRIPT");
    sc.letters++;
    if (script === sc.wanted) sc.wantedLetters++;
  }
  endRun();
}

function text(sc: Scanner, v: unknown, max: number, min = 1): string {
  if (typeof v !== "string") bad("SHAPE");
  const s = clean(v as string);
  if (s.length < min || s.length > max) bad("LENGTH");
  if (s.length > 0) scan(sc, s);
  return s;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

// --- Allergens and diet --------------------------------------------------------------------------

/** Free-from wording ("gluten-free", "glutenfri", "sans lactose") is removed before matching. */
const FREE_SUFFIX = /^\S{3,}(?:fri|frei|freie|freier|frie|fria|fritt|free)$/u;
const FREE_WORD = new Set(["free", "fri", "frei", "freie", "frie", "fria", "fritt"]);
const WITHOUT = new Set(["sin", "sans", "senza", "bez", "без", "utan", "uten", "ohne", "without", "no"]);

export function withoutFreeFrom(t: FoldedText): FoldedText & { freeOf: string[] } {
  const words: string[] = [];
  const freeOf: string[] = [];
  for (let i = 0; i < t.words.length; i++) {
    const w = t.words[i];
    if (FREE_WORD.has(t.words[i + 1] ?? "")) {
      freeOf.push(w);
      i++;
      continue;
    }
    if (WITHOUT.has(w)) {
      if (t.words[i + 1]) freeOf.push(t.words[i + 1]);
      i++;
      continue;
    }
    if (FREE_SUFFIX.test(w)) {
      freeOf.push(w);
      continue;
    }
    words.push(w);
  }
  return { words, flat: ` ${words.join(" ")} `, freeOf };
}

interface Guard {
  terms: CompiledTerm[];
  exempt: CompiledTerm[];
  freeStems?: string[];
}

function guardOf(terms: readonly string[], exempt: readonly string[] = [], freeStems: readonly string[] = []): Guard {
  return { terms: compileTerms(terms), exempt: compileTerms(exempt), freeStems: freeStems.map(fold) };
}

function violates(name: FoldedText, guards: readonly Guard[]): boolean {
  const t = withoutFreeFrom(name);
  return guards.some((g) => {
    if (g.exempt.length && matchesAny(t, g.exempt)) return false;
    if (g.freeStems?.some((s) => t.freeOf.some((f) => f.startsWith(s)))) return false;
    return matchesAny(t, g.terms);
  });
}

const GROUP_TERMS = Object.fromEntries(
  Object.entries(ALLERGEN_GROUPS).map(([k, g]) => [k, { terms: compileTerms(g.terms), exempt: compileTerms(g.exempt ?? []) }]),
) as Record<string, Guard>;
const ALIAS_TERMS = Object.fromEntries(Object.entries(ALLERGEN_ALIASES).map(([k, a]) => [k, compileTerms(a)])) as Record<string, CompiledTerm[]>;
const DIET_GUARDS = Object.fromEntries(
  Object.entries(DIET_RULES).map(([k, groups]) => [k, groups.map((g) => guardOf(g.terms, g.exempt, g.freeStems))]),
) as Record<DietKey, Guard[]>;

/** A word in a script whose words are a character or two (Chinese, Japanese, Korean, Thai...). */
const DENSE = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Script=Thai}\p{Script=Lao}\p{Script=Khmer}\p{Script=Myanmar}\p{Script=Yi}]/u;

/**
 * The guards for a user's allergen list: each name itself (every word of 3+ letters, as a word
 * start, and the whole name), plus every group of the table whose terms or aliases the name
 * matches, so "nøtter" also catches "walnuts" and "milk" also catches "smør". The table covers
 * the ten recipe languages only: an allergen in another script ("花生", "γάλα") is matched
 * literally, on the folded text (a word of a script written without spaces, or of Hangul, as a
 * substring of any length, since such a word is one or two characters and sits inside compounds).
 */
export function allergenGuards(allergens: readonly string[]): Guard[] {
  const guards: Guard[] = [];
  for (const a of allergens) {
    const t = foldText(a);
    const groups = Object.keys(GROUP_TERMS).filter((k) => matchesAny(t, GROUP_TERMS[k].terms) || matchesAny(t, ALIAS_TERMS[k] ?? []));
    const literal = [
      ...t.words.filter((w) => DENSE.test(w)).map((w) => `*${w}`),
      ...t.words.filter((w) => !DENSE.test(w) && Array.from(w).length >= 3),
      ...(t.words.length > 1 ? [`*${t.words.join(" ")}`] : []),
    ];
    const exempt = groups.flatMap((k) => GROUP_TERMS[k].exempt);
    if (literal.length) guards.push({ terms: compileTerms(literal), exempt });
    for (const k of groups) guards.push(GROUP_TERMS[k]);
  }
  return guards;
}

// --- Staples (quality gate) ----------------------------------------------------------------------

const STAPLE_TERMS = compileTerms(STAPLE_WORDS);
const FILLER_WORDS = new Set(STAPLE_FILLERS.map(fold));

/**
 * Whether a name is only a staple ("freshly ground black pepper", "olivenolje", "4 cups water"):
 * every word (split at apostrophes too, "d'oliva") a staple word, a filler or a number, and at
 * least one a staple word.
 */
export function isStapleName(name: string): boolean {
  let staple = false;
  for (const w of foldText(name).words.flatMap((x) => x.split("'")).filter(Boolean)) {
    if (/^\p{N}+$/u.test(w) || FILLER_WORDS.has(w)) continue;
    if (!matchesAny({ words: [w], flat: ` ${w} ` }, STAPLE_TERMS)) return false;
    staple = true;
  }
  return staple;
}

// --- Recipe and menu ---------------------------------------------------------------------------

interface Checker {
  sc: Scanner;
  req: GenerateRequest;
  idToName: Map<string, string>;
  allergen: Guard[];
  diet: Guard[];
  pantryUses: number;
  /** Quality gate findings, reported only after every other rule passed (see gate). */
  staplesOnly: boolean;
  emptyDay: boolean;
  titles: string[];
}

function ingredient(ch: Checker, raw: unknown): RecipeIngredient {
  if (!isObject(raw)) bad("SHAPE");
  const r = raw as Record<string, unknown>;
  const amount = text(ch.sc, r.amount, LIMITS.amount, 0);
  const name = text(ch.sc, r.name, LIMITS.ingredientName);
  const id = r.pantryId;
  if (typeof id !== "string") bad("SHAPE");
  if (id !== "none" && !ch.idToName.has(id as string)) bad("PANTRY_REF");
  const fromPantry = id !== "none";
  const names = [foldText(name)];
  if (fromPantry) {
    ch.pantryUses++;
    names.push(foldText(ch.idToName.get(id as string)!));
  }
  if (names.some((n) => violates(n, ch.allergen))) bad("ALLERGEN");
  if (names.some((n) => violates(n, ch.diet))) bad("DIET");
  if (!fromPantry) return { amount, name, fromPantry };
  return { amount, name, fromPantry, pantryIndex: Number((id as string).slice(1)) - 1 };
}

/** An ingredient is a staple when its name is one and, for a pantry item, the item's own name too. */
function isStaple(ch: Checker, ing: RecipeIngredient): boolean {
  if (!isStapleName(ing.name)) return false;
  return ing.pantryIndex === undefined || isStapleName(ch.req.ingredients[ing.pantryIndex].name);
}

function recipe(ch: Checker, raw: unknown): Recipe {
  if (!isObject(raw)) bad("SHAPE");
  const r = raw as Record<string, unknown>;
  const title = text(ch.sc, r.title, LIMITS.title);
  const summary = text(ch.sc, r.summary, LIMITS.summary);
  const minutes = r.minutes;
  if (typeof minutes !== "number" || !Number.isInteger(minutes) || minutes < 1 || minutes > LIMITS.minutes) bad("SHAPE");
  if (!Array.isArray(r.ingredients) || r.ingredients.length === 0) bad("SHAPE");
  if ((r.ingredients as unknown[]).length > LIMITS.ingredients) bad("LENGTH");
  const ingredients = (r.ingredients as unknown[]).map((i) => ingredient(ch, i));
  if (ingredients.every((ing) => isStaple(ch, ing))) ch.staplesOnly = true;
  const bounds = STEP_BOUNDS[ch.req.config.detail];
  if (!Array.isArray(r.steps)) bad("SHAPE");
  const rawSteps = r.steps as unknown[];
  if (rawSteps.length < bounds.min || rawSteps.length > bounds.max) bad("STEPS");
  const steps = rawSteps.map((s) => {
    if (typeof s !== "string") bad("SHAPE");
    const stripped = clean(s as string).replace(/^(?:\d{1,2}[.):]|[-*•–])\s+/u, "");
    return text(ch.sc, stripped, LIMITS.step, LIMITS.stepMin);
  });
  if (new Set(steps.map((s) => fold(s))).size < steps.length) bad("REPETITION");
  ch.titles.push(foldText(title).words.join(" "));
  let tip: string | undefined;
  if (r.tip !== undefined && r.tip !== null && r.tip !== "") {
    tip = text(ch.sc, r.tip, LIMITS.tip, 0) || undefined;
  }
  return {
    title,
    summary,
    minutes: minutes as number,
    servings: ch.req.config.servings,
    difficulty: ch.req.config.difficulty,
    ingredients,
    steps,
    ...(tip ? { tip } : {}),
  };
}

function checker(ctx: OutputContext): Checker {
  const ids = pantryIdsFor(ctx.req.ingredients.length);
  const diet = ctx.req.config.diet === "none" ? [] : DIET_GUARDS[ctx.req.config.diet as DietKey];
  return {
    sc: {
      ctx,
      leakNgrams: ngrams(foldText(ctx.systemInstruction).words, LEAK_NGRAM),
      canary: ctx.canary.toUpperCase(),
      allowed: allowedScripts(ctx.req),
      quotable: [...ctx.req.ingredients.map((i) => i.name), ...ctx.req.allergens].map(fold).join("\n"),
      wanted: ctx.req.config.language === "ru" || ctx.req.config.language === "uk" ? "Cyrillic" : "Latin",
      wantedLetters: 0,
      letters: 0,
    },
    req: ctx.req,
    idToName: new Map(ids.map((id, i) => [id, ctx.req.ingredients[i].name])),
    allergen: allergenGuards(ctx.req.allergens),
    diet,
    pantryUses: 0,
    staplesOnly: false,
    emptyDay: false,
    titles: [],
  };
}

/**
 * The answer must be mostly in the requested language's script. Pantry names in other scripts
 * may be quoted (a Norwegian user can own "Сметана", an English one "Φέτα" or "豆腐"), hence a
 * majority of all letters, not all.
 */
function checkLanguage(ch: Checker): void {
  const { wantedLetters, letters } = ch.sc;
  if (letters === 0) bad("LANGUAGE");
  if (wantedLetters / letters < 0.6) bad("LANGUAGE");
}

/**
 * The quality gate, after every other rule (the language check included), so an answer that also
 * breaks a content rule is always settled as that content failure, never as a refunded quality one.
 */
function gate(ch: Checker): OutputRule | null {
  if (ch.pantryUses === 0) return "NO_PANTRY";
  if (ch.emptyDay) return "DAY_NO_PANTRY";
  if (ch.staplesOnly) return "STAPLES_ONLY";
  if (new Set(ch.titles).size < ch.titles.length) return "DUPLICATE_DISH";
  return null;
}

/** Runs every content rule, then the quality gate on an answer that passed them all. */
function run<T>(ctx: OutputContext, f: (ch: Checker) => T): OutputCheck<T> {
  const ch = checker(ctx);
  let value: T;
  try {
    value = f(ch);
    checkLanguage(ch);
  } catch (e) {
    if (e instanceof Invalid) return { ok: false, rule: e.rule };
    throw e;
  }
  const rule = gate(ch);
  return rule ? { ok: false, rule, value } : { ok: true, value };
}

export function checkRecipeOutput(raw: unknown, ctx: OutputContext): OutputCheck<Recipe> {
  return run(ctx, (ch) => recipe(ch, raw));
}

/** A menu must have exactly the 3 expected days, each with exactly the requested courses, in order. */
export function checkMenuOutput(raw: unknown, ctx: OutputContext): OutputCheck<Menu> {
  return run(ctx, (ch) => {
    const req = ctx.req;
    if (!isObject(raw) || !Array.isArray(raw.days) || raw.days.length !== 3) bad("SHAPE");
    const dates = menuDates(req.today);
    const wanted = coursesFor(req.courses!);
    const days: MenuDay[] = [];
    for (let i = 0; i < 3; i++) {
      const d = (raw as { days: unknown[] }).days[i];
      if (!isObject(d) || d.date !== dates[i]) bad("SHAPE");
      const day = d as Record<string, unknown>;
      if (!Array.isArray(day.uses) || day.uses.length > req.ingredients.length) bad("SHAPE");
      const uses: string[] = [];
      for (const u of day.uses as unknown[]) {
        if (typeof u !== "string" || !ch.idToName.has(u)) bad("PANTRY_REF");
        const name = ch.idToName.get(u as string)!;
        if (!uses.includes(name)) uses.push(name);
      }
      if (!Array.isArray(day.courses) || day.courses.length !== wanted.length) bad("SHAPE");
      const byCourse = new Map<Course, Recipe>();
      const usesBefore = ch.pantryUses;
      for (const c of day.courses as unknown[]) {
        if (!isObject(c) || !(wanted as unknown[]).includes(c.course) || byCourse.has(c.course as Course)) bad("SHAPE");
        const r = recipe(ch, (c as Record<string, unknown>).recipe);
        byCourse.set((c as Record<string, unknown>).course as Course, r);
      }
      // Judged from the ingredients' pantry ids, not from the model's own `uses` list.
      if (ch.pantryUses === usesBefore) ch.emptyDay = true;
      days.push({ date: dates[i], uses, courses: wanted.map((course) => ({ course, recipe: byCourse.get(course)! })) });
    }
    return { days };
  });
}

/**
 * Whether the request can pass the quality gate at all, decided before anything is reserved or
 * any model is called. A pantry item is usable unless its own name breaks the allergens or the
 * diet (the same guards the answer is checked with, so the model could not use it anyway). A
 * recipe needs one usable item; a menu needs, on each of its 3 days, one usable item it may be
 * planned on that day: no date, already past its date at request time (those may go on any day),
 * or a date on or after that day. Anything thinner is left to the model and its few extras.
 */
export function hasEnoughItems(req: GenerateRequest): boolean {
  const allergen = allergenGuards(req.allergens);
  const diet = req.config.diet === "none" ? [] : DIET_GUARDS[req.config.diet as DietKey];
  const usable = req.ingredients.filter((i) => {
    const name = foldText(i.name);
    return !violates(name, allergen) && !violates(name, diet);
  });
  if (req.kind === "recipe") return usable.length > 0;
  return menuDates(req.today).every((day) =>
    usable.some((i) => i.expiresOn === null || i.expiresOn < req.today || i.expiresOn >= day));
}

export function checkOutput(raw: unknown, ctx: OutputContext): OutputCheck<{ recipe?: Recipe; menu?: Menu }> {
  if (ctx.req.kind === "menu") {
    const m = checkMenuOutput(raw, ctx);
    if (m.ok) return { ok: true, value: { menu: m.value } };
    return m.value ? { ok: false, rule: m.rule, value: { menu: m.value } } : { ok: false, rule: m.rule };
  }
  const r = checkRecipeOutput(raw, ctx);
  if (r.ok) return { ok: true, value: { recipe: r.value } };
  return r.value ? { ok: false, rule: r.rule, value: { recipe: r.value } } : { ok: false, rule: r.rule };
}
