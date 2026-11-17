/**
 * Validation of the model's answer before anything is stored or returned. Pure: no I/O.
 *
 * The model answers in an internal shape (see adapters/prompt.ts): pantry ingredients carry a
 * `pantryId` ("p1".."pN", "o1".."oN" for an optional item, "s1".."sN" for one of the person's own
 * staples, or "none" for anything else) instead of a self-declared `fromPantry` flag,
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
import { coursesFor, menuDates, menuDaysOf, type Course, type GenerateRequest, type Menu, type MenuDay, type Recipe, type RecipeIngredient, type StapleInput } from "../contract";
import {
  ALLERGEN_ALIASES,
  ALLERGEN_GROUPS,
  DIET_RULES,
  GLUTEN_FOODS_GROUP,
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
import { TASTE_CATALOGUE } from "../taste/catalogue";
import { IMAGE } from "../constants";

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
/** Ids of the optional items ("o1".."oN"), a separate series so the ticked ones keep theirs. */
export const optionalIdsFor = (n: number): string[] => Array.from({ length: n }, (_, i) => `o${i + 1}`);

/** Every id an answer may refer to, ticked items first, with the item's name. */
export function idNamesFor(req: GenerateRequest): Array<[string, string]> {
  const ids = pantryIdsFor(req.ingredients.length);
  const optional = optionalIdsFor(req.optionalIngredients.length);
  return [
    ...req.ingredients.map((i, n): [string, string] => [ids[n], i.name]),
    ...req.optionalIngredients.map((i, n): [string, string] => [optional[n], i.name]),
  ];
}

/** Ids of the usable staples ("s1".."sN", CONTRACT.md "Staples"), a third series apart from items. */
export const stapleIdsFor = (n: number): string[] => Array.from({ length: n }, (_, i) => `s${i + 1}`);

/**
 * The kept staples the prompt and the answer check use: those whose own name the request's diet
 * or allergens would not refuse ("Butter" under dairy-free, "Eggs" with an egg allergen are left
 * out quietly, never refused). Ids `s1..sN` follow this list's order.
 */
export function usableStaples(req: GenerateRequest): StapleInput[] {
  return req.staples.filter((s) => !breaksDietOrAllergens(s.name, req));
}

/** The request's item names, ticked and optional. */
const itemNames = (req: GenerateRequest): string[] => [...req.ingredients, ...req.optionalIngredients].map((i) => i.name);
/** The request's own names an answer may quote: items and usable staples. */
const ownNames = (req: GenerateRequest): string[] => [...itemNames(req), ...usableStaples(req).map((s) => s.name)];

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
const PROMPT_WORDS = compileTerms(["pantry_data", "begin_pantry_data", "end_pantry_data", "begin_staples_data", "end_staples_data", "system instruction", "systeminstruks", "response schema", "pantryid"]);

const LEAK_NGRAM = 8;

function ngrams(words: string[], n: number): Set<string> {
  const out = new Set<string>();
  for (let i = 0; i + n <= words.length; i++) out.add(words.slice(i, i + n).join(" "));
  return out;
}

/** What the leak check compares a text with: the request's canary and its system instruction's word runs. */
export interface LeakSource {
  canary: string;
  leakNgrams: ReadonlySet<string>;
}

export function leakSourceOf(systemInstruction: string, canary: string): LeakSource {
  return { canary: canary.toUpperCase(), leakNgrams: ngrams(foldText(withoutTasteLists(systemInstruction)).words, LEAK_NGRAM) };
}

/**
 * How the taste block's list lines start: every catalogue step's prompt label (adapters/prompt.ts
 * tasteLines). What follows a label is the server's words for the user's own choices
 * ("tomatoes; mushrooms; potatoes; leafy greens..."), which an honest answer may well repeat in
 * that order, so the leak check skips it; the label and every fixed sentence of the block stay in
 * the check.
 */
const TASTE_LIST_LABELS: readonly string[] = TASTE_CATALOGUE.steps.map((s) => s.prompt.label);

function withoutTasteLists(systemInstruction: string): string {
  const labels = TASTE_LIST_LABELS;
  return systemInstruction
    .split("\n")
    .map((line) => labels.find((l) => line.startsWith(`${l} `)) ?? line)
    .join("\n");
}

/**
 * The leak rule (LEAK): what of the prompt a text gives away, or null. The canary, a name of the
 * prompt's own parts (the data markers, "system instruction", "response schema"), or a run of
 * LEAK_NGRAM words of the system instruction. The red-team runner calls it on the raw answer.
 */
export function promptLeak(s: string, src: LeakSource): string | null {
  if (src.canary && s.toUpperCase().includes(src.canary)) return "the canary";
  const folded = foldText(s);
  if (matchesAny(folded, PROMPT_WORDS) || /pantry_data|staples_data/.test(s.toLowerCase())) return "a name of the prompt's parts";
  for (const g of ngrams(folded.words, LEAK_NGRAM)) if (src.leakNgrams.has(g)) return `"${g}" from the system instruction`;
  return null;
}

interface Scanner {
  ctx: OutputContext;
  leakNgrams: ReadonlySet<string>;
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
 * The scripts an answer may use: Latin and Cyrillic (the scripts of the recipe languages; a
 * Norwegian user can own "Сметана", a Russian recipe can say "°C"), plus every script a letter of
 * the request's own item and allergen names is in, so a Greek or Chinese item name can be quoted
 * in an English recipe. Japanese and Korean bring the scripts written alongside them. An answer in
 * a script none of those uses is refused (SCRIPT), and so is any run of letters outside Latin and
 * Cyrillic that is not part of one of those names (scan): the answer may quote the user's items in
 * their own script, but not write anything else in it, since no word list reads other languages.
 * The usable staples' names count as the request's own names here.
 */
export function allowedScripts(req: GenerateRequest): Set<string> {
  const out = new Set<string>(["Latin", "Cyrillic"]);
  for (const name of [...ownNames(req), ...req.allergens]) {
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
  if (promptLeak(s, sc)) bad("LEAK");
  const folded = foldText(s);
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

/**
 * Free-from wording ("gluten-free", "glutenfri", "sans lactose") is removed before matching. The
 * inflected Scandinavian forms (frie, fria, fritt, frit) count only inside a compound
 * ("glutenfrit"), never as a word of their own: standalone they are cooking words in another
 * recipe language (French "poisson frit" is fried fish, Spanish "leche fría" cold milk), and
 * reading them as "free of" would drop the food word before them.
 */
const FREE_SUFFIX = /^\S{3,}(?:fri|frei|freie|freier|frie|fria|fritt|frit|free)$/u;
const FREE_WORD = new Set(["free", "fri", "frei", "freie"]);
const WITHOUT = new Set(["sin", "sans", "senza", "bez", "без", "utan", "uten", "uden", "ohne", "without", "no"]);

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
const GLUTEN_FOODS_GUARD = guardOf(GLUTEN_FOODS_GROUP.terms, GLUTEN_FOODS_GROUP.exempt, GLUTEN_FOODS_GROUP.freeStems);
const DIET_GUARDS = Object.fromEntries(
  Object.entries(DIET_RULES).map(([k, groups]) => [k, groups.map((g) => guardOf(g.terms, g.exempt, g.freeStems))]),
) as Record<DietKey, Guard[]>;

/**
 * The guards of every diet the request selected: they all hold at once (vegetarian and
 * gluten-free is both), so an ingredient breaking any one of them breaks the request's diet. The
 * strictest combination wins by itself: pescatarian with vegetarian forbids fish, as vegetarian
 * does.
 */
export function dietGuards(req: GenerateRequest): Guard[] {
  return req.config.diets.flatMap((d) => DIET_GUARDS[d as DietKey]);
}

/**
 * Whether the answer checks would refuse an ingredient of this name under the request's diet or
 * allergens. Used by the prompt to leave such a favourite out of the taste block; the checks on
 * the answer are the same ones, unchanged.
 */
export function breaksDietOrAllergens(name: string, req: GenerateRequest): boolean {
  return violates(foldText(name), [...dietGuards(req), ...allergenGuards(req.allergens)]);
}

/** A word in a script whose words are a character or two (Chinese, Japanese, Korean, Thai...). */
const DENSE = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Script=Thai}\p{Script=Lao}\p{Script=Khmer}\p{Script=Myanmar}\p{Script=Yi}]/u;

/**
 * The guards for a user's allergen list: each name itself (every word of 3+ letters, as a word
 * start, and the whole name), plus every group of the table whose terms or aliases the name
 * matches, so "nøtter" also catches "walnuts" and "milk" also catches "smør" (and "gluten" the
 * foods of GLUTEN_FOODS_GROUP, "pasta" and "brød", unless free of it). The table covers
 * the recipe languages only: an allergen in another script ("花生", "γάλα") is matched
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
    // Bread, pasta and beer are gluten unless sold free of it, as for the glutenFree diet.
    if (groups.includes("gluten")) guards.push(GLUTEN_FOODS_GUARD);
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

/**
 * Whether a name has a staple word anywhere ("Filippo Berio Olive Oil", "Maldon Sea Salt 250g"):
 * a ticked item that may honestly be written as just that staple.
 */
export function hasStapleWord(name: string): boolean {
  return foldText(name).words.flatMap((x) => x.split("'")).filter(Boolean)
    .some((w) => matchesAny({ words: [w], flat: ` ${w} ` }, STAPLE_TERMS));
}

// --- The person's staples (stapleIndex) ------------------------------------------------------------

/** A name's words that say what it is: folded, split at apostrophes, without numbers and STAPLE_FILLERS. */
function significantWords(name: string): string[] {
  return foldText(name).words.flatMap((x) => x.split("'")).filter((w) => w && !/^\p{N}+$/u.test(w) && !FILLER_WORDS.has(w));
}

/**
 * Two folded words name the same thing: equal; one the other plus at most two letters, both at
 * least 3 long ("egg" / "eggs", "løk" / "løken"); or the longer ends with the shorter, at least 4
 * long (a compound's head: "havsalt" / "salt", "meersalz" / "salz", "olivenolje" / "olje").
 */
function sameWord(a: string, b: string): boolean {
  if (a === b) return true;
  const [short, long] = a.length <= b.length ? [a, b] : [b, a];
  if (Array.from(short).length >= 3 && long.startsWith(short) && Array.from(long).length - Array.from(short).length <= 2) return true;
  return Array.from(short).length >= 4 && long.endsWith(short);
}

/**
 * The multi-language groups of core/foodTerms.ts that name one food each, so that two names whose
 * every significant word is in the same group name the same staple across languages ("Eggs" /
 * "яйца" / "huevos"). Broader groups (milk: butter and cream; fish; tree nuts; soy: tofu and miso;
 * gluten; peanut: peanut butter) are left out, and so are the egg group's dishes and products
 * (mayonnaise, omelette, frittata), which are not the staple.
 */
const SAME_FOOD_GROUPS: readonly CompiledTerm[][] = (["egg", "sesame", "mustard", "celery", "lupin"] as const).map((k) =>
  compileTerms(ALLERGEN_GROUPS[k].terms.filter((t) => !/^(?:mayo|majon|майонез|omelet|frittat)/u.test(t))));
const EGG_EXEMPT = compileTerms(ALLERGEN_GROUPS.egg.exempt ?? []);

/**
 * Whether an ingredient the model labelled with a staple's id plausibly is that staple, so that it
 * may carry `stapleIndex` (the apps then show "Your staple" and leave it off the shopping list).
 * When in doubt it is not: the ingredient is then an ordinary one, shown without the mark and
 * offered for the shopping list. True when either
 * - every significant word of the staple's name has the same word (sameWord) in the ingredient's
 *   name ("Sea salt" for "Salt", "dark soy sauce" for "Soy sauce", but not "fish sauce"), or
 * - every significant word of both names is in the same one-food group of core/foodTerms.ts
 *   (SAME_FOOD_GROUPS: "яйца" for "Eggs").
 * A staple written in a language the tables cannot relate ("соль" for "Salt") is not matched.
 */
export function namesStaple(ingredientName: string, stapleName: string): boolean {
  const ing = significantWords(ingredientName);
  const st = significantWords(stapleName);
  if (ing.length === 0 || st.length === 0) return false;
  if (st.every((s) => ing.some((w) => sameWord(s, w)))) return true;
  const inGroup = (words: string[], terms: CompiledTerm[]) =>
    words.every((w) => {
      const t = { words: [w], flat: ` ${w} ` };
      return matchesAny(t, terms) && !matchesAny(t, EGG_EXEMPT);
    });
  return SAME_FOOD_GROUPS.some((terms) => inGroup(ing, terms) && inGroup(st, terms));
}

// --- Recipe and menu ---------------------------------------------------------------------------

interface Checker {
  sc: Scanner;
  req: GenerateRequest;
  idToName: Map<string, string>;
  /** The usable staples by id ("s1".."sN"); kept apart from idToName, so a day's `uses` cannot name one. */
  stapleIds: Map<string, StapleInput>;
  allergen: Guard[];
  diet: Guard[];
  /** Ingredients that are one of the ticked items: at least one is required (NO_PANTRY). An
   *  optional item counts as from the pantry but not here. */
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
  if ((id as string).startsWith("s")) {
    // One of the person's staples: never pantry use, so it neither counts for NO_PANTRY nor
    // carries pantryIndex; it says which staple it is as sent.
    const staple = ch.stapleIds.get(id as string);
    if (!staple) bad("PANTRY_REF");
    const names = [foldText(name), foldText(staple!.name)];
    if (names.some((n) => violates(n, ch.allergen))) bad("ALLERGEN");
    if (names.some((n) => violates(n, ch.diet))) bad("DIET");
    // Only an ingredient that plausibly is that staple says so; anything else the model labelled
    // with a staple's id ("Chicken thighs" as "Salt") is an ordinary ingredient, as with "none".
    const isStaple = namesStaple(name, staple!.name);
    return { amount, name, fromPantry: false, ...(isStaple ? { stapleIndex: staple!.sentIndex } : {}), ...(isStapleName(name) ? { staple: true as const } : {}) };
  }
  if (id !== "none" && !ch.idToName.has(id as string)) bad("PANTRY_REF");
  const itemName = id === "none" ? undefined : ch.idToName.get(id as string)!;
  const names = [foldText(name), ...(itemName === undefined ? [] : [foldText(itemName)])];
  if (names.some((n) => violates(n, ch.allergen))) bad("ALLERGEN");
  if (names.some((n) => violates(n, ch.diet))) bad("DIET");
  // An ingredient written as nothing but a staple ("water", "salt and pepper") is not the ticked
  // item it is labelled with unless that item's name has a staple word too (the user's own "Olive
  // oil", "Filippo Berio Olive Oil 500ml"): it is delivered as a staple and does not count as
  // using the item, so "water" labelled as the user's "Pasta" cannot carry a staples-only dish
  // past the quality gate. Judged from the words only, never by comparing names, so "макароны"
  // written for "Pasta" still counts.
  const staple = isStapleName(name);
  const fromPantry = itemName !== undefined && !(staple && !hasStapleWord(itemName));
  // A staple (water, oil, salt, pepper, sugar, spices) is marked, so a client can order its shopping
  // list and leave plain water out of it (CONTRACT.md "Recipe").
  if (!fromPantry) return { amount, name, fromPantry, ...(staple ? { staple: true as const } : {}) };
  const n = Number((id as string).slice(1)) - 1;
  if ((id as string).startsWith("p")) {
    ch.pantryUses++;
    return { amount, name, fromPantry, pantryIndex: n };
  }
  return { amount, name, fromPantry, optionalIndex: ch.req.optionalIngredients[n].sentIndex ?? n };
}

function recipe(ch: Checker, raw: unknown): Recipe {
  if (!isObject(raw)) bad("SHAPE");
  const r = raw as Record<string, unknown>;
  const title = text(ch.sc, r.title, LIMITS.title);
  const summary = text(ch.sc, r.summary, LIMITS.summary);
  const minutes = r.minutes;
  if (typeof minutes !== "number" || !Number.isInteger(minutes) || minutes < 1 || minutes > (ch.req.config.maxMinutes ?? LIMITS.minutes)) bad("SHAPE");
  if (!Array.isArray(r.ingredients) || r.ingredients.length === 0) bad("SHAPE");
  if ((r.ingredients as unknown[]).length > LIMITS.ingredients) bad("LENGTH");
  const ingredients = (r.ingredients as unknown[]).map((i) => ingredient(ch, i));
  // A pantry or optional ingredient with a staple-only name is one of the user's own staples (see ingredient).
  if (ingredients.every((ing) => isStapleName(ing.name))) ch.staplesOnly = true;
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
  const diet = dietGuards(ctx.req);
  const leak = leakSourceOf(ctx.systemInstruction, ctx.canary);
  return {
    sc: {
      ctx,
      leakNgrams: leak.leakNgrams,
      canary: leak.canary,
      allowed: allowedScripts(ctx.req),
      quotable: [...ownNames(ctx.req), ...ctx.req.allergens].map(fold).join("\n"),
      wanted: ctx.req.config.language === "ru" || ctx.req.config.language === "uk" ? "Cyrillic" : "Latin",
      wantedLetters: 0,
      letters: 0,
    },
    req: ctx.req,
    idToName: new Map(idNamesFor(ctx.req)),
    stapleIds: new Map((() => {
      const usable = usableStaples(ctx.req);
      const ids = stapleIdsFor(usable.length);
      return usable.map((s, n): [string, StapleInput] => [ids[n], s]);
    })()),
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

/** A menu must have exactly the requested days (menuDates), each with exactly the requested courses, in order. */
export function checkMenuOutput(raw: unknown, ctx: OutputContext): OutputCheck<Menu> {
  return run(ctx, (ch) => {
    const req = ctx.req;
    const dates = menuDates(req.today, menuDaysOf(req));
    if (!isObject(raw) || !Array.isArray(raw.days) || raw.days.length !== dates.length) bad("SHAPE");
    const wanted = coursesFor(req.courses!);
    const days: MenuDay[] = [];
    for (let i = 0; i < dates.length; i++) {
      const d = (raw as { days: unknown[] }).days[i];
      if (!isObject(d) || d.date !== dates[i]) bad("SHAPE");
      const day = d as Record<string, unknown>;
      if (!Array.isArray(day.uses) || day.uses.length > ch.idToName.size) bad("SHAPE");
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
 * any model is called. A pantry item is usable unless its own name breaks the allergens or any
 * selected diet (the same guards the answer is checked with, so the model could not use it
 * anyway). A recipe needs one usable item; a menu needs, on each of its days, one usable item it
 * may be planned on that day: no date, already past its date at request time (those may go on any
 * day), or a date on or after that day. Anything thinner is left to the model and its few extras.
 */
export function hasEnoughItems(req: GenerateRequest): boolean {
  const allergen = allergenGuards(req.allergens);
  const diet = dietGuards(req);
  const usable = req.ingredients.filter((i) => {
    const name = foldText(i.name);
    return !violates(name, allergen) && !violates(name, diet);
  });
  if (req.kind === "recipe") return usable.length > 0;
  return menuDates(req.today, menuDaysOf(req)).every((day) =>
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

// --- The picture's brief -----------------------------------------------------------------------

/** Why a brief was refused; the picture is then not made (the recipe is still delivered). */
export type BriefRule = OutputRule | "BRIEF_MISSING" | "BRIEF_CHARACTERS" | "BRIEF_TERMS";

/**
 * Words a brief may not hold, because the picture must show only food and tableware (no people,
 * no writing, no brands) and never anything unfit for a family cooking app. Matched as whole
 * words on the lower-cased brief, singular and plural.
 */
const BRIEF_BLOCKED = new Set([
  "person", "people", "man", "men", "woman", "women", "child", "children", "kid", "kids", "baby", "babies",
  "boy", "boys", "girl", "girls", "human", "humans", "face", "faces", "hand", "hands", "finger", "fingers",
  "arm", "arms", "body", "bodies", "chef", "chefs", "portrait", "selfie", "celebrity",
  "text", "texts", "word", "words", "letter", "letters", "lettering", "writing", "written", "caption", "captions",
  "label", "labels", "logo", "logos", "brand", "brands", "branded", "trademark", "sign", "signs", "signage",
  "watermark", "signature", "poster", "slogan", "headline", "font", "typography", "qr", "barcode",
  "packaging", "package", "wrapper", "screen", "photo", "photograph", "image", "picture", "drawing", "draw",
  "nude", "naked", "sexy", "erotic", "blood", "bloody", "gore", "corpse", "dead", "weapon", "weapons", "gun", "guns",
  "rifle", "bomb", "drug", "drugs", "cigarette", "cigarettes", "syringe", "flag", "flags", "swastika",
  "ignore", "instruction", "instructions", "prompt", "system",
]);

const BRIEF_CHARS = /^[A-Za-z][A-Za-z ,.'-]*$/;

/**
 * The text model's `imageBrief` (only on a recipe asked with a picture): a string of
 * IMAGE.briefMinChars to IMAGE.briefMaxChars characters after the usual repairs, plain ASCII
 * English letters, spaces and . , ' - only (so no name in another script, no markup, digits,
 * quotes or brackets can reach the picture prompt), every rule an answer's text is held to (no
 * URL, contact, canary or instruction leak, non-food, solicitation, repetition), and none of
 * BRIEF_BLOCKED. A brief that fails is never repaired: the picture is not made.
 */
export function checkImageBrief(raw: unknown, ctx: OutputContext): { ok: true; brief: string } | { ok: false; rule: BriefRule } {
  const v = typeof raw === "object" && raw !== null && !Array.isArray(raw) ? (raw as Record<string, unknown>).imageBrief : undefined;
  return checkBrief(v, ctx);
}

/**
 * The step briefs of a recipe asked with step pictures (`stepBriefs`, prompt.ts stepBriefLines):
 * each entry `{step, brief}` with `step` a step number from 1 to `stepCount` and `brief` held to
 * every rule of checkImageBrief. An entry that breaks a rule, repeats a step or comes after
 * `max` kept ones is left out (counted in `refused`); a missing or malformed list is no step
 * marked. Returns the kept ones with 0-based step indices, in the order written.
 */
export function checkStepBriefs(
  raw: unknown,
  ctx: OutputContext,
  stepCount: number,
  max: number,
): { steps: { step: number; brief: string }[]; refused: BriefRule[] } {
  const list = typeof raw === "object" && raw !== null && !Array.isArray(raw) ? (raw as Record<string, unknown>).stepBriefs : undefined;
  const steps: { step: number; brief: string }[] = [];
  const refused: BriefRule[] = [];
  if (!Array.isArray(list)) return { steps, refused };
  for (const entry of list.slice(0, 20)) {
    const e = typeof entry === "object" && entry !== null && !Array.isArray(entry) ? (entry as Record<string, unknown>) : {};
    const n = e.step;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > stepCount || steps.some((s) => s.step === n - 1)) {
      refused.push("SHAPE");
      continue;
    }
    if (steps.length >= max) {
      refused.push("LENGTH");
      continue;
    }
    const b = checkBrief(e.brief, ctx);
    if (b.ok) steps.push({ step: n - 1, brief: b.brief });
    else refused.push(b.rule);
  }
  return { steps, refused };
}

function checkBrief(v: unknown, ctx: OutputContext): { ok: true; brief: string } | { ok: false; rule: BriefRule } {
  if (typeof v !== "string") return { ok: false, rule: "BRIEF_MISSING" };
  const brief = clean(v);
  if (brief.length < IMAGE.briefMinChars || brief.length > IMAGE.briefMaxChars) return { ok: false, rule: "LENGTH" };
  if (!BRIEF_CHARS.test(brief)) return { ok: false, rule: "BRIEF_CHARACTERS" };
  try {
    scan(checker(ctx).sc, brief);
  } catch (e) {
    if (e instanceof Invalid) return { ok: false, rule: e.rule };
    throw e;
  }
  const words = brief.toLowerCase().split(/[^a-z']+/).filter(Boolean);
  if (words.some((w) => BRIEF_BLOCKED.has(w) || BRIEF_BLOCKED.has(w.replace(/'s$/, "")))) return { ok: false, rule: "BRIEF_TERMS" };
  return { ok: true, brief };
}
