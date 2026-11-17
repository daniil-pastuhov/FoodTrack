/**
 * Every provider's picture becomes the same delivered shape: a JPEG of at most
 * IMAGE.maxEdge pixels on its longer side, sRGB, no metadata (EXIF, XMP, ICC, C2PA manifests are
 * dropped: nothing a provider embedded reaches the apps), re-encoded at IMAGE.jpegQuality.
 *
 * Uses sharp (libvips), loaded on first use only so that no other function pays for it at cold
 * start. sharp ships prebuilt binaries for linux-x64 (glibc), which Cloud Functions 2nd gen runs
 * on; no install script or build step is needed. Its own cache is off, it uses one thread, and
 * pictures are re-encoded one after another (normalizeImage's queue), so the 256 MiB instance
 * holds one 1K picture (about 4 MB decoded) at a time.
 */
import { IMAGE } from "../constants";

export interface NormalizedImage {
  mimeType: "image/jpeg";
  data: Buffer;
  width: number;
  height: number;
}

/** A picture that could not be read or re-encoded (a fixed message, never the bytes). */
export class ImageNormalizeError extends Error {}

type Sharp = typeof import("sharp");
let sharpModule: Promise<Sharp> | undefined;

function loadSharp(): Promise<Sharp> {
  sharpModule ??= import("sharp").then((m) => {
    const sharp = ((m as unknown as { default?: Sharp }).default ?? m) as Sharp;
    sharp.cache(false);
    sharp.concurrency(1);
    return sharp;
  });
  return sharpModule;
}

/** The formats a provider may answer in; anything else is refused without decoding. */
function sniff(bytes: Buffer): "jpeg" | "png" | "webp" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "png";
  if (bytes.length >= 12 && bytes.toString("latin1", 0, 4) === "RIFF" && bytes.toString("latin1", 8, 12) === "WEBP") return "webp";
  return null;
}

/**
 * One picture is decoded at a time per instance: the provider calls run in parallel, their
 * re-encoding queues here, so a recipe with three pictures never holds three decoded pictures in
 * the 256 MiB instance at once.
 */
let queue: Promise<unknown> = Promise.resolve();

export function normalizeImage(bytes: Buffer): Promise<NormalizedImage> {
  const run = queue.then(() => normalizeOne(bytes));
  queue = run.catch(() => undefined);
  return run;
}

async function normalizeOne(bytes: Buffer): Promise<NormalizedImage> {
  if (bytes.length === 0 || bytes.length > IMAGE.maxInputBytes) throw new ImageNormalizeError("image size out of bounds");
  const format = sniff(bytes);
  if (!format) throw new ImageNormalizeError("image format not accepted");
  const sharp = await loadSharp();
  try {
    const { data, info } = await sharp(bytes, { limitInputPixels: IMAGE.maxInputPixels, failOn: "error", animated: false })
      .rotate()
      .resize({ width: IMAGE.maxEdge, height: IMAGE.maxEdge, fit: "inside", withoutEnlargement: true })
      .toColourspace("srgb")
      .flatten({ background: "#ffffff" })
      .jpeg({ quality: IMAGE.jpegQuality, chromaSubsampling: "4:2:0" })
      .toBuffer({ resolveWithObject: true });
    if (data.length > IMAGE.maxOutputBytes) throw new ImageNormalizeError("image too large after re-encoding");
    return { mimeType: "image/jpeg", data, width: info.width, height: info.height };
  } catch (e) {
    if (e instanceof ImageNormalizeError) throw e;
    throw new ImageNormalizeError("image could not be decoded");
  }
}
