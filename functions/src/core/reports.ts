/**
 * "Report this recipe" (`reportRecipe`, CONTRACT.md `reportRecipe`, README "Reports"): a
 * signed-in person flags a recipe or menu written by the server (from a pack, with or without
 * pictures) as offensive, unsafe, a wrong picture, or something else, with an optional short
 * note. Only server results: a recipe from the phone's own model has no report action, and no
 * recipe text is ever accepted from a client. The report names one of the caller's generations;
 * the server takes the snapshot from it while it is still kept (7 days), else stores the report
 * without one (a saved recipe can be reported later). Kept `config/runtime.reports.keepDays`
 * (TTL), counted on stats/{day} for the owner's board, logged as `recipe_reported` for the
 * owner's alert.
 *
 * A report is never sent to a model, never changes a balance and promises nothing. Idempotent
 * per `reportId`.
 */
import { createHash } from "node:crypto";
import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { fail, isUuid, type Menu, type Recipe } from "../contract";
import {
  generationImageRef,
  generationRef,
  reportableRef,
  isClosed,
  statsRef,
  uidHash,
  userRef,
  utcDay,
  type GenerationDoc,
  type GenerationImageDoc,
} from "../model";
import type { Deps } from "../ports";

export const REPORT_REASONS = ["offensive", "unsafe", "picture", "other"] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];

/** Bounds of a report (CONTRACT.md `reportRecipe`). */
export const REPORT_LIMITS = {
  noteChars: 500,
  noteRawChars: 2000,
} as const;

export interface ReportCaller {
  uid: string;
}

export interface ReportResponse {
  status: "RECEIVED";
}

/** The server's own copy of what was reported (never pictures: those are separate docs). */
export type ReportSnapshot = { recipe: Recipe } | { menu: Menu };

export interface ReportDoc {
  /** The client's id (the document id is reportDocId). */
  reportId: string;
  reason: ReportReason;
  note: string | null;
  /** The generation reported, one of the reporter's. */
  requestId: string;
  /** Null when the generation was no longer kept (or held no result) when the report came. */
  kind: "recipe" | "menu" | null;
  /** The reporter; nulled when that account is deleted. */
  uid: string | null;
  platform: "android" | "ios";
  appVersion: string;
  language: string;
  snapshot: ReportSnapshot | null;
  /** Pictures copied beside the report (reportPictures/{main | step-<i>}). */
  pictures: number;
  imageLevel: string | null;
  /** When the generation was made, when it was still kept. */
  generatedAt: Timestamp | null;
  createdAt: Timestamp;
  expiresAt: Timestamp;
}

/**
 * A report's document id: a hash of the reporter's uid and the client's reportId, so ids are
 * scoped to the account (another account's report with the same reportId is another document)
 * and the document name carries no uid after the account's deletion.
 */
export const reportDocId = (uid: string, reportId: string) => createHash("sha256").update(`foodtracker-report:${uid}\n${reportId.toLowerCase()}`).digest("hex").slice(0, 40);
export const reportRef = (deps: Pick<Deps, "db">, uid: string, reportId: string) =>
  deps.db.collection("reports").doc(reportDocId(uid, reportId)) as DocumentReference<ReportDoc>;

function invalid(message: string): never {
  fail("invalid-argument", "INVALID_REQUEST", message);
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const FORBIDDEN = /[\p{Cc}\p{Cf}\p{Co}\p{Cn}\p{Cs}\p{Zl}\p{Zp}]/gu;
const SPACES = /\s+/gu;

/** NFC, control and format characters removed, whitespace runs to one space, cut to `max` code points. */
export function cleanReportText(v: string, max: number): string {
  const s = v.normalize("NFC").replace(FORBIDDEN, " ").replace(SPACES, " ").trim();
  return Array.from(s).slice(0, max).join("");
}

export interface ValidReport {
  reportId: string;
  reason: ReportReason;
  note: string | null;
  requestId: string;
  platform: "android" | "ios";
  appVersion: string;
  language: string;
}

/** Only these fields are read; a recipe or menu sent along is refused (the server never takes recipe text). */
export function validateReport(v: unknown): ValidReport {
  if (!isObj(v)) invalid("request must be an object");
  if (typeof v.reportId !== "string" || !isUuid(v.reportId)) invalid("reportId must be a UUID");
  if (!(REPORT_REASONS as readonly unknown[]).includes(v.reason)) invalid(`reason must be one of ${REPORT_REASONS.join(", ")}`);
  if (typeof v.requestId !== "string" || !isUuid(v.requestId)) invalid("requestId must be the generation's UUID");
  if (v.recipe !== undefined || v.menu !== undefined || v.text !== undefined) invalid("a report carries no recipe text");
  let note: string | null = null;
  if (v.note !== undefined && v.note !== null) {
    if (typeof v.note !== "string" || v.note.length > REPORT_LIMITS.noteRawChars) invalid("note must be a short string");
    note = cleanReportText(v.note, REPORT_LIMITS.noteChars) || null;
  }
  const app = isObj(v.app) ? v.app : {};
  if (app.platform !== "android" && app.platform !== "ios") invalid("app.platform must be android or ios");
  if (typeof app.version !== "string" || !/^[0-9A-Za-z.+-]{1,32}$/.test(app.version)) invalid("app.version is malformed");
  if (typeof app.language !== "string" || !/^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?$/.test(app.language)) invalid("app.language is malformed");
  return {
    reportId: v.reportId,
    reason: v.reason as ReportReason,
    note,
    requestId: v.requestId,
    platform: app.platform,
    appVersion: app.version,
    language: app.language,
  };
}

/** A stable pseudonym of a report id for logs (the id itself is the document's name). */
const reportHash = (id: string) => createHash("sha256").update(`foodtracker-report:${id}`).digest("hex").slice(0, 16);

/** Refusals the transaction decides after recording a once-a-day mark. */
type ReportRefusal = { refused: true; firstToday: boolean; reports: number };

/**
 * The order of checks: the account (document, open), its own daily limit, that the generation is
 * the caller's (still kept, or its `reportable` record), and only then the service-wide daily cap,
 * so neither made-up ids nor another account's reports can use up the day for everyone. The first
 * refusal at the cap each UTC day records `stats/{day}.reportCapReachedAt` and logs
 * `report_day_limit` (error) once.
 */
export async function reportRecipe(deps: Deps, caller: ReportCaller, data: unknown): Promise<ReportResponse> {
  const config = await deps.config();
  const r = validateReport(data);
  const now = deps.now();
  const day = utcDay(now);
  const result = await deps.db.runTransaction(async (tx): Promise<{ duplicate: true } | { duplicate: false; doc: ReportDoc } | ReportRefusal> => {
    const ref = reportRef(deps, caller.uid, r.reportId);
    const uRef = userRef(deps.db, caller.uid);
    const gRef = generationRef(deps.db, caller.uid, r.requestId);
    const [existing, uSnap, gSnap, rSnap] = await Promise.all([tx.get(ref), tx.get(uRef), tx.get(gRef), tx.get(reportableRef(deps.db, caller.uid, r.requestId))]);
    const prior = existing.data();
    if (prior) {
      // A retry of the same report: already received, nothing written again. The same reportId
      // for another result is a client bug.
      if (prior.requestId !== r.requestId) invalid("this reportId was used for another result");
      return { duplicate: true };
    }
    const user = uSnap.data();
    // Every result from a pack belongs to an account with a document; without one there is nothing to report.
    if (!user) fail("not-found", "UNKNOWN_REQUEST", "No result of this account to report");
    if (isClosed(user)) fail("failed-precondition", "ACCOUNT_CLOSED", "This account was deleted or merged");
    const window = user.reportWindow?.day === day ? user.reportWindow.count : 0;
    if (window >= config.reports.perUserPerDay) fail("resource-exhausted", "REPORT_LIMIT", "Too many reports today");
    // Only the caller's own results: a generation still kept, or the record of one delivered.
    const gen = gSnap.data() as GenerationDoc | undefined;
    if (!gen && !rSnap.exists) fail("not-found", "UNKNOWN_REQUEST", "No result of this account with this requestId");

    const sRef = statsRef(deps.db, day);
    const stats = (await tx.get(sRef)).data() ?? {};
    const today = (stats.reports as number | undefined) ?? 0;
    if (today >= config.reports.perDay) {
      if (stats.reportCapReachedAt !== undefined) return { refused: true, firstToday: false, reports: today };
      tx.set(sRef, { reportCapReachedAt: now }, { merge: true });
      return { refused: true, firstToday: true, reports: today };
    }

    // The snapshot: the generation's own result while it is kept; after that, none.
    const snapshot: ReportSnapshot | null = gen?.recipe ? { recipe: gen.recipe } : gen?.menu ? { menu: gen.menu } : null;
    // The generation's pictures, copied beside the report (one document each).
    const pictureRefs = gen
      ? [...(gen.imageStored ? [generationImageRef(deps.db, caller.uid, r.requestId)] : []), ...(gen.stepImagesStored ?? []).map((i) => generationImageRef(deps.db, caller.uid, r.requestId, i))]
      : [];
    const pictures = pictureRefs.length ? await tx.getAll(...pictureRefs) : [];
    const expiresAt = Timestamp.fromMillis(now + config.reports.keepDays * 86_400_000);
    const doc: ReportDoc = {
      reportId: r.reportId,
      reason: r.reason,
      note: r.note,
      requestId: r.requestId,
      kind: snapshot ? ("menu" in snapshot ? "menu" : "recipe") : (rSnap.data()?.kind ?? null),
      uid: caller.uid,
      platform: r.platform,
      appVersion: r.appVersion,
      language: r.language,
      snapshot,
      pictures: pictures.filter((p) => p.exists).length,
      imageLevel: gen?.imageLevel ?? null,
      generatedAt: gen?.createdAt ?? rSnap.data()?.at ?? null,
      createdAt: Timestamp.fromMillis(now),
      expiresAt,
    };
    tx.create(ref, doc);
    for (const p of pictures) {
      const d = p.data() as GenerationImageDoc | undefined;
      if (d) tx.set(ref.collection("reportPictures").doc(p.id), { ...d, expiresAt });
    }
    tx.set(sRef, { reports: FieldValue.increment(1), reportReasons: { [r.reason]: FieldValue.increment(1) } }, { merge: true });
    tx.update(uRef, { reportWindow: { day, count: window + 1 } });
    return { duplicate: false, doc };
  });
  if ("refused" in result) {
    if (result.firstToday) deps.logger.error("report_day_limit", { day, perDay: config.reports.perDay, reports: result.reports });
    fail("resource-exhausted", "REPORT_LIMIT", "Too many reports today");
  }
  if (!result.duplicate) {
    // For the owner's alert: never the note, the snapshot or the uid.
    deps.logger.warn("recipe_reported", {
      report: reportHash(r.reportId),
      reason: r.reason,
      kind: result.doc.kind,
      snapshot: result.doc.snapshot !== null,
      hasNote: r.note !== null,
      pictures: result.doc.pictures,
      imageLevel: result.doc.imageLevel,
      platform: r.platform,
      appVersion: r.appVersion,
      language: r.language,
      uidHash: uidHash(caller.uid),
    });
  }
  return { status: "RECEIVED" };
}

/** deleteAccount: the account's reports stay (the owner may still need them) without its uid. */
export async function detachReports(deps: Pick<Deps, "db">, uid: string): Promise<number> {
  const snap = await deps.db.collection("reports").where("uid", "==", uid).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = deps.db.batch();
    for (const d of snap.docs.slice(i, i + 400)) batch.update(d.ref, { uid: null });
    await batch.commit();
  }
  return snap.size;
}
