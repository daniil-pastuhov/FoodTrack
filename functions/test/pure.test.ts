/** Pure functions: output parsing, the prompt, adapter mappings. No emulator needed, but run with the rest. */
import { generateKeyPairSync, verify } from "node:crypto";
import { clientSecret } from "../src/adapters/appleRevoke";
import { thinkingConfigFor } from "../src/adapters/gemini";
import { parseProductPurchaseV2 } from "../src/adapters/play";
import { buildPrompt, responseSchema } from "../src/adapters/prompt";
import { validateGenerateRequest } from "../src/contract";
import { checkMenuOutput, checkRecipeOutput } from "../src/core/outputSafety";
import { sampleMenu, sampleRecipe } from "./helpers";

const today = new Date().toISOString().slice(0, 10);
const baseRequest = {
  requestId: "0b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b",
  kind: "menu",
  courses: 3,
  config: { difficulty: "medium", detail: "stepByStep", servings: 4, maxMinutes: null, mealType: "dinner", diet: "vegan", language: "nb" },
  ingredients: [{ name: "Tomat", expiresOn: today, quantity: 3 }],
  allergens: ["sesame"],
  today,
};

describe("output parsing", () => {
  const recipeReq = validateGenerateRequest({ ...baseRequest, kind: "recipe", courses: undefined, config: { ...baseRequest.config, language: "en", diet: "none" } }, Date.now());
  const ctx = { req: recipeReq, canary: "LRD-TEST", systemInstruction: "" };
  test("a recipe needs every required field", () => {
    expect(checkRecipeOutput(sampleRecipe(), ctx).ok).toBe(true);
    expect(checkRecipeOutput({ ...sampleRecipe(), steps: [] }, ctx)).toEqual({ ok: false, rule: "STEPS" });
    expect(checkRecipeOutput({ ...sampleRecipe(), ingredients: [{ amount: "1", name: "x" }] }, ctx)).toEqual({ ok: false, rule: "SHAPE" });
    const tipped = checkRecipeOutput({ ...sampleRecipe(), tip: "Serve warm." }, ctx);
    expect(tipped.ok && tipped.value.tip).toBe("Serve warm.");
  });

  test("a menu needs the 3 expected dates and exactly the requested courses, returned in course order", () => {
    const req = validateGenerateRequest({ ...baseRequest, config: { ...baseRequest.config, language: "en", diet: "none" } }, Date.now());
    const mctx = { req, canary: "LRD-TEST", systemInstruction: "" };
    const menu = sampleMenu(req);
    menu.days[0].courses.reverse();
    const ok = checkMenuOutput(menu, mctx);
    expect(ok.ok && ok.value.days[0].courses.map((c) => c.course)).toEqual(["starter", "main", "dessert"]);
    expect(ok.ok && ok.value.days[0].uses).toEqual(["Tomat"]);
    const dup = sampleMenu(req);
    dup.days[1].courses[2] = { ...dup.days[1].courses[1] };
    expect(checkMenuOutput(dup, mctx).ok).toBe(false);
    const wrongDate = sampleMenu(req);
    wrongDate.days[0].date = "1999-01-01";
    expect(checkMenuOutput(wrongDate, mctx).ok).toBe(false);
    expect(checkMenuOutput(sampleMenu(req), { ...mctx, req: { ...req, courses: 2 } }).ok).toBe(false);
  });
});

describe("prompt", () => {
  test("pantry names are only in the data block; the configuration is spelled out in the system instruction", () => {
    const req = validateGenerateRequest(baseRequest, Date.now());
    const prompt = buildPrompt(req, "LRD-0123456789ABCDEF");
    expect(prompt.systemInstruction).toContain("Norwegian Bokmål");
    expect(prompt.systemInstruction).toContain("vegan");
    expect(prompt.systemInstruction).toContain("starter, main, dessert");
    expect(prompt.systemInstruction).toContain("LRD-0123456789ABCDEF");
    expect(prompt.systemInstruction).not.toContain("Tomat");
    expect(prompt.systemInstruction).not.toContain("sesame");
    expect(prompt.userText).toBe(
      `BEGIN_PANTRY_DATA\n{"pantry":[{"id":"p1","name":"Tomat","expiresOn":"${today}","quantity":3}],"allergens":["sesame"]}\nEND_PANTRY_DATA`,
    );
    expect(prompt.userText).not.toContain("LRD-");
    const schema = responseSchema(req) as { properties: { days: { minItems: number } } };
    expect(schema.properties.days.minItems).toBe(3);
  });
});
describe("Gemini thinking config", () => {
  test("gemini-2.x gets budget 0, newer models a thinking level", () => {
    expect(thinkingConfigFor("gemini-2.5-flash", "LOW")).toEqual({ thinkingBudget: 0 });
    expect(thinkingConfigFor("gemini-3.8-flash", "LOW")).toEqual({ thinkingLevel: "LOW" });
    expect(thinkingConfigFor("gemini-3.5-flash", "MINIMAL")).toEqual({ thinkingLevel: "MINIMAL" });
    expect(thinkingConfigFor("gemini-3.8-flash", "bogus")).toEqual({ thinkingLevel: "LOW" });
  });
});

describe("Play productsv2 mapping", () => {
  test("maps line item, quantity, state and test purchases", () => {
    expect(parseProductPurchaseV2({
      productLineItem: [{ productId: "recipes_50", productOfferDetails: { quantity: 2, refundableQuantity: 1, consumptionState: "CONSUMPTION_STATE_YET_TO_BE_CONSUMED" } }],
      purchaseStateContext: { purchaseState: "PURCHASED" },
      testPurchaseContext: { fopType: "TEST" },
      orderId: "GPA.1",
    })).toEqual({
      productId: "recipes_50", quantity: 2, purchaseState: "PURCHASED", consumed: false, isTest: true, orderId: "GPA.1", refundableQuantity: 1, obfuscatedExternalAccountId: null,
    });
    expect(parseProductPurchaseV2({ productLineItem: [] })).toBeNull();
    expect(parseProductPurchaseV2({ productLineItem: [{ productId: "recipes_10" }] })?.quantity).toBe(1);
  });
});

describe("Sign in with Apple client secret", () => {
  test("is an ES256 JWT with Apple's audience", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const secret = clientSecret(
      { teamId: "TEAM123456", keyId: "KEY1234567", clientId: "com.foodbest.ios", privateKey: privateKey.export({ type: "pkcs8", format: "pem" }).toString() },
      1_700_000_000,
    );
    const [h, p, s] = secret.split(".");
    expect(JSON.parse(Buffer.from(h, "base64url").toString())).toEqual({ alg: "ES256", kid: "KEY1234567" });
    expect(JSON.parse(Buffer.from(p, "base64url").toString())).toMatchObject({
      iss: "TEAM123456", sub: "com.foodbest.ios", aud: "https://appleid.apple.com", exp: 1_700_000_300,
    });
    expect(verify("sha256", Buffer.from(`${h}.${p}`), { key: publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s, "base64url"))).toBe(true);
  });
});
