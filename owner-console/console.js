// The owner console (functions/README.md "The owner console"). Static and harmless on its own:
// every fact it shows and every change it makes goes through the callables getProviderProfiles,
// setProviderProfile, getOwnerSettings and setOwnerSettings, which accept only the owner's Google
// sign-in (config/owner). Nothing is
// kept in the browser: the sign-in lives in memory only (inMemoryPersistence), so each visit
// signs in afresh, and Google's name and photo are never read. Text goes in with textContent only.
// The Firebase JS SDK is served from this site (vendor/, pinned; vendor/firebasejs/13.4.0/SOURCE.txt).
import { initializeApp } from "/vendor/firebasejs/13.4.0/firebase-app.js";
import {
  GoogleAuthProvider,
  connectAuthEmulator,
  getAdditionalUserInfo,
  getAuth,
  inMemoryPersistence,
  setPersistence,
  signInWithPopup,
  signOut,
} from "/vendor/firebasejs/13.4.0/firebase-auth.js";

const REGION = "europe-west1";
const $ = (id) => document.getElementById(id);
const local = location.hostname === "127.0.0.1" || location.hostname === "localhost";

let auth;
let projectId = "";
let view = null;

function say(text, error = false) {
  $("message").textContent = text;
  $("message").className = error ? "error" : "";
}

function show(signedIn) {
  $("signed-out").hidden = signedIn;
  $("account").hidden = !signedIn;
  if (!signedIn) {
    $("console").hidden = true;
    $("settings").hidden = true;
  }
}

async function call(name, data) {
  const user = auth.currentUser;
  if (!user) throw Object.assign(new Error("Sign in with Google"), { reason: "SIGN_IN" });
  const url = local
    ? `http://127.0.0.1:5001/${projectId}/${REGION}/${name}`
    : `https://${REGION}-${projectId}.cloudfunctions.net/${name}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await user.getIdToken()}` },
    body: JSON.stringify({ data: data ?? {} }),
    credentials: "omit",
    referrerPolicy: "no-referrer",
  });
  let body = null;
  try {
    body = await res.json();
  } catch {
    // An answer that is not JSON is a failure below.
  }
  if (!res.ok || !body || body.error) {
    const e = new Error(body?.error?.message ?? `The server answered ${res.status}`);
    e.reason = body?.error?.details?.reason ?? body?.error?.status ?? "FAILED";
    throw e;
  }
  return body.result;
}

function models(p) {
  if (p.provider === "off") return "none (pictures off)";
  const fb = p.fallbackModel ? `${p.fallbackProvider ? `${p.fallbackProvider} / ` : ""}${p.fallbackModel}` : "none";
  return `${p.provider} / ${p.model}, fallback ${fb}`;
}

// The recipe text's part (older servers send none of these fields: then nothing is shown).
function textModels(p) {
  if (!p.textProvider) return "";
  const fb = p.textFallbackModel ? `${p.textFallbackProvider} / ${p.textFallbackModel}` : "none";
  return `${p.textProvider} / ${p.textModel}, fallback ${fb}`;
}

function render() {
  const a = view.active;
  $("active").textContent = a.label + (a.profile === "custom" ? "" : ` (${a.profile})`);
  $("active-text").textContent = textModels(a) || "not reported by this server";
  $("active-models").textContent = `${models(a)}; step pictures at most ${a.maxStepImages}`;
  $("active-on").textContent = a.picturesOn ? "Yes" : "No";
  const c = view.lastChange;
  $("last-change").textContent = c ? `${new Date(c.at).toLocaleString()} (${c.via === "web" ? "this console" : "admin command"}): ${c.from} → ${c.to}` : "None recorded";
  const select = $("profile");
  const keep = select.value;
  select.replaceChildren(...view.profiles.map((p) => {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = p.label + (p.id === a.profile ? " (in force)" : p.ready ? "" : " (not ready)");
    return o;
  }));
  select.value = view.profiles.some((p) => p.id === keep) ? keep : (view.profiles.find((p) => p.id === a.profile)?.id ?? view.profiles[0]?.id ?? "");
  describe();
  $("console").hidden = false;
}

function selected() {
  return view?.profiles.find((p) => p.id === $("profile").value);
}

function describe() {
  const p = selected();
  const text = p ? textModels(p) : "";
  $("profile-summary").textContent = p ? `${p.summary} Text: ${text || "unchanged"}. Pictures: ${models(p)}.` : "";
  $("profile-missing").replaceChildren(...(p?.missing ?? []).map((m) => {
    const li = document.createElement("li");
    li.textContent = m;
    return li;
  }));
  $("switch").disabled = !p || !p.ready || p.id === view.active.profile;
}

async function load() {
  say("Loading…");
  try {
    view = await call("getProviderProfiles");
    say("");
    render();
    await loadSettings();
  } catch (e) {
    $("console").hidden = true;
    $("settings").hidden = true;
    if (e.reason === "NOT_OWNER") say("This Google account may not use the owner console.", true);
    else if (e.reason === "SIGN_IN" || e.reason === "UNAUTHENTICATED") say("Sign in with Google.", true);
    else say(`Could not load: ${e.message}`, true);
  }
}

async function signIn() {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  const result = await signInWithPopup(auth, provider);
  // A Google account with no Firebase user yet just got one. Left in place, it would stop that
  // Google account being linked in the app later (credential already in use), and no owner can be
  // such a user anyway (config/owner names an existing account), so it is removed at once.
  // The local emulators keep theirs.
  if (getAdditionalUserInfo(result)?.isNewUser && !local) {
    try {
      await result.user.delete();
    } catch {
      await signOut(auth);
      throw Object.assign(new Error("new-account-not-removed"), { code: "new-account-not-removed" });
    }
    show(false);
    throw Object.assign(new Error("no-app-account"), { code: "no-app-account" });
  }
  show(true);
}

function signInFailed(e) {
  if (e.code === "no-app-account") {
    say("This Google account has no account with the app, so it may not use the owner console. Nothing was kept.", true);
  } else if (e.code === "new-account-not-removed") {
    say("This Google account has no account with the app, and the one this sign-in made could not be removed; delete it in the Firebase console (Authentication > Users).", true);
  } else {
    say(`Sign-in failed: ${e.code ?? e.message}`, true);
  }
}

async function doSwitch() {
  const p = selected();
  if (!p) return;
  const body = { profile: p.id, expect: view.active.profile, confirm: true };
  say("Switching…");
  try {
    view = await call("setProviderProfile", body);
  } catch (e) {
    if (e.reason !== "RECENT_SIGN_IN") throw e;
    say("Sign in again to confirm the switch.");
    await signIn();
    view = await call("setProviderProfile", body);
  }
  render();
  say(`Switched to ${p.label}.`);
}

// --- Settings (getOwnerSettings / setOwnerSettings) -------------------------------------------
// Everything below is drawn from what the server returns: sections, labels, help, controls,
// bounds, values and defaults. A new setting on the server needs no change here.

let settingsView = null;
let pendingPlan = null;
/** Per setting id: { get(): value | undefined (unreadable), reset: boolean }. */
const inputs = new Map();
let fieldSeq = 0;

function el(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else e[k] = v;
  }
  for (const c of children) if (c !== null && c !== undefined) e.append(c);
  return e;
}

const isUsd = (c) => c.kind === "number" && c.unit === "usd";
const usdText = (c, n) => `USD ${(n / 1e6).toFixed(c.step < 10000 ? 3 : 2)}`;
const same = (a, b) => JSON.stringify(sortKeys(a)) === JSON.stringify(sortKeys(b));
function sortKeys(v) {
  if (Array.isArray(v)) return v.map(sortKeys);
  if (v && typeof v === "object") return Object.fromEntries(Object.keys(v).sort().map((k) => [k, sortKeys(v[k])]));
  return v ?? null;
}

function fmt(c, v) {
  if (v === null || v === undefined) return (c && c.emptyMeans) || "none";
  if (!c) return typeof v === "string" ? v : JSON.stringify(v);
  switch (c.kind) {
    case "toggle":
      return v ? "On" : "Off";
    case "number":
      if (isUsd(c)) return usdText(c, v);
      return v === 0 && c.offAtZero ? `0 (${c.offAtZero})` : String(v);
    case "select":
      return c.options.find((o) => o.value === v)?.label ?? String(v);
    case "multi":
      return v.length === 0 ? "none" : v.map((x) => c.options.find((o) => o.value === x)?.label ?? x).join("; ");
    case "table":
      return c.rows.map((r) => `${r.label}: ${c.cols.map((k) => v[r.value]?.[k.value]).join(" / ")}`).join("; ");
    default:
      return JSON.stringify(v);
  }
}

function settingById(id) {
  for (const s of settingsView?.sections ?? []) for (const x of s.settings) if (x.id === id) return x;
  return null;
}

/** The input for one setting, and how to read it back (undefined: not a valid entry). */
function controlFor(s, fid) {
  const c = s.control;
  if (c.kind === "toggle") {
    const box = el("input", { type: "checkbox", id: fid, checked: s.value === true, disabled: Boolean(s.blocked) });
    return { node: el("p", { class: "toggle" }, box, el("span", { text: " On" })), get: () => box.checked };
  }
  if (c.kind === "number") {
    const k = isUsd(c) ? 1e6 : 1;
    const box = el("input", {
      type: "number", id: fid, inputMode: c.integer && !isUsd(c) ? "numeric" : "decimal",
      min: String(c.min / k), max: String(c.max / k), step: String(c.step / k),
      value: s.value === null ? "" : String(s.value / k), disabled: Boolean(s.blocked),
    });
    if (c.emptyMeans) box.placeholder = `empty: ${c.emptyMeans}`;
    return {
      node: box,
      get: () => {
        const t = box.value.trim();
        if (t === "") return null;
        const n = Number(t);
        if (!Number.isFinite(n)) return undefined;
        return isUsd(c) ? Math.round(n * 1e6) : n;
      },
    };
  }
  if (c.kind === "select") {
    const sel = el("select", { id: fid, disabled: Boolean(s.blocked) }, ...c.options.map((o) => el("option", { value: o.value, text: o.label })));
    sel.value = s.value;
    return { node: sel, get: () => sel.value };
  }
  if (c.kind === "multi") {
    const boxes = c.options.map((o, i) => {
      const box = el("input", { type: "checkbox", id: `${fid}-${i}`, value: o.value, checked: (s.value ?? []).includes(o.value), disabled: Boolean(s.blocked) });
      return { box, row: el("li", {}, box, el("label", { htmlFor: `${fid}-${i}`, class: "inline", text: `${o.label}` })) };
    });
    return {
      node: el("ul", { class: "multi", id: fid }, ...boxes.map((b) => b.row)),
      get: () => boxes.filter((b) => b.box.checked).map((b) => b.box.value),
    };
  }
  if (c.kind === "table") {
    const cells = [];
    const head = el("tr", {}, el("th", { text: "" }), ...c.cols.map((k) => el("th", { scope: "col", text: k.label })));
    const rows = c.rows.map((r) => el("tr", {}, el("th", { scope: "row", text: r.label }), ...c.cols.map((k) => {
      const fixed = c.fixed.some((f) => f.row === r.value && f.col === k.value);
      const box = el("input", { type: "number", inputMode: "numeric", min: String(c.min), max: String(c.max), step: "1", value: String(s.value?.[r.value]?.[k.value] ?? ""), disabled: fixed || Boolean(s.blocked) });
      box.setAttribute("aria-label", `${r.label}, ${k.label}${fixed ? " (set above)" : ""}`);
      cells.push({ r: r.value, k: k.value, box });
      return el("td", {}, box);
    })));
    return {
      node: el("div", { class: "table-wrap" }, el("table", { id: fid }, el("thead", {}, head), el("tbody", {}, ...rows))),
      get: () => {
        const out = {};
        for (const { r, k, box } of cells) {
          const n = Number(box.value);
          if (box.value.trim() === "" || !Number.isInteger(n)) return undefined;
          (out[r] ??= {})[k] = n;
        }
        return out;
      },
    };
  }
  return { node: el("p", { class: "note", text: "This server sent a kind of setting this page doesn't know; use the admin command." }), get: () => s.value };
}

function viaText(via) {
  return via === "web" ? "this console" : "admin command";
}

function settingNode(s) {
  const fid = `setting-${++fieldSeq}`;
  const ctl = controlFor(s, fid);
  const entry = { get: ctl.get, reset: false };
  inputs.set(s.id, entry);
  const meta = [`Default: ${fmt(s.control, s.default)}`];
  meta.push(s.overridden ? "set here" : "default in force");
  if (s.changed) meta.push("changed from default");
  if (s.lastChange) meta.push(`last changed ${new Date(s.lastChange.at).toLocaleString()} (${viaText(s.lastChange.via)})`);
  const node = el("div", { class: "setting" + (s.changed ? " changed" : "") },
    el(s.control.kind === "multi" || s.control.kind === "table" ? "p" : "label", { class: "name", text: s.label + (s.sensitive ? " (sensitive)" : ""), ...(s.control.kind === "multi" || s.control.kind === "table" ? {} : { htmlFor: fid }) }),
    el("p", { class: "note", text: s.help }),
    ctl.node,
    el("p", { class: "meta", text: meta.join(" · ") }),
    s.blocked ? el("p", { class: "warn", text: s.blocked }) : null,
  );
  if (s.overridden && !s.blocked) {
    const btn = el("button", { type: "button", class: "small", text: "Reset to default" });
    btn.addEventListener("click", () => {
      entry.reset = !entry.reset;
      btn.textContent = entry.reset ? "Will reset on Save (undo)" : "Reset to default";
      node.classList.toggle("resetting", entry.reset);
    });
    node.append(btn);
  }
  return node;
}

function renderSettings() {
  const v = settingsView;
  inputs.clear();
  const parts = [];
  if (v.invalid.length) parts.push(el("p", { class: "warn", text: `Invalid as stored, so their safe values are in force: ${v.invalid.join(", ")}. Fix or delete them in the Firestore console.` }));
  if (v.lastChange) parts.push(el("p", { class: "note", text: `Last settings change: ${new Date(v.lastChange.at).toLocaleString()} (${viaText(v.lastChange.via)}).` }));
  for (const sec of v.sections) {
    const save = el("button", { type: "button", class: "primary", text: `Save ${sec.title}…` });
    save.addEventListener("click", () => saveSection(sec).catch(saveFailed));
    parts.push(el("section", { class: "card" }, el("h3", { text: sec.title }), el("p", { class: "note", text: sec.help }), ...sec.settings.map(settingNode), save));
  }
  if (v.readOnly.length) {
    const dl = el("dl", { class: "facts" });
    for (const r of v.readOnly) {
      dl.append(el("dt", { text: r.label }), el("dd", {}, el("span", { text: fmt(null, r.value) + (same(r.value, r.default) ? "" : `  (default ${fmt(null, r.default)})`) }), el("p", { class: "note", text: r.reason })));
    }
    parts.push(el("section", { class: "card" }, el("h3", { text: "Shown here, changed elsewhere" }), dl));
  }
  $("settings-sections").replaceChildren(...parts);
  $("settings").hidden = false;
}

function sectionChanges(sec) {
  const changes = [];
  for (const s of sec.settings) {
    const entry = inputs.get(s.id);
    if (!entry || s.blocked) continue;
    if (entry.reset) {
      changes.push({ id: s.id, reset: true, expect: s.stored });
      continue;
    }
    const value = entry.get();
    if (value === undefined) throw Object.assign(new Error(`${s.label}: not a valid entry.`), { reason: "LOCAL" });
    if (same(value, s.value)) continue;
    if (value === null) changes.push({ id: s.id, reset: true, expect: s.stored });
    else changes.push({ id: s.id, value, expect: s.stored });
  }
  return changes;
}

async function callWrite(name, body) {
  try {
    return await call(name, body);
  } catch (e) {
    if (e.reason !== "RECENT_SIGN_IN") throw e;
    say("Sign in again to confirm.");
    await signIn();
    return call(name, body);
  }
}

async function saveSection(sec) {
  const changes = sectionChanges(sec);
  if (changes.length === 0) {
    say(`Nothing to save in ${sec.title}.`);
    return;
  }
  say("Checking…");
  const answer = await callWrite("setOwnerSettings", { changes, dryRun: true });
  const plan = answer.plan;
  if (plan.changes.length === 0) {
    say(`Nothing to save in ${sec.title}: already so.`);
    return;
  }
  say("");
  pendingPlan = { changes, plan };
  const ctlOf = (id) => settingById(id)?.control ?? null;
  $("settings-confirm-list").replaceChildren(...plan.changes.map((c) => el("li", {
    class: c.sensitive ? "sensitive" : "",
    text: `${c.label}: ${fmt(ctlOf(c.id), c.from)} → ${fmt(ctlOf(c.id), c.to)}${c.reset ? " (reset to default)" : ""}${c.sensitive ? " · sensitive" : ""}`,
  })));
  $("settings-confirm-side").textContent = plan.sideEffects.length
    ? `Also changes with it: ${plan.sideEffects.map((s) => `${s.label}: ${fmt(ctlOf(s.id), s.from)} → ${fmt(ctlOf(s.id), s.to)}`).join("; ")}.`
    : "";
  const sensitive = plan.sensitive.length > 0;
  $("settings-confirm-sensitive").hidden = !sensitive;
  $("settings-typed").value = "";
  if (sensitive) {
    $("settings-confirm-sensitive-text").textContent = `${plan.changes.filter((c) => c.sensitive).map((c) => c.label).join(", ")}: sensitive (${plan.changes.filter((c) => c.sensitive).map((c) => settingById(c.id)?.sensitiveWhy).filter(Boolean).join("; ")}).`;
    $("settings-typed-label").textContent = `Type ${settingsView.confirmWord} to save${plan.changes.length > 1 ? " all of these" : ""}`;
  }
  $("settings-confirm-ok").disabled = sensitive;
  $("settings-confirm").showModal();
}

async function confirmSave() {
  const p = pendingPlan;
  if (!p) return;
  const sensitive = p.plan.sensitive;
  $("settings-confirm").close();
  say("Saving…");
  settingsView = await callWrite("setOwnerSettings", { changes: p.changes, confirm: true, sensitive, typed: sensitive.length ? $("settings-typed").value.trim() : "" });
  pendingPlan = null;
  renderSettings();
  say("Saved. New requests use it within 30 seconds.");
}

function saveFailed(e) {
  say(e.reason === "LOCAL" ? e.message : `Not saved: ${e.message}`, true);
  if (e.reason === "STALE") loadSettings();
}

async function loadSettings() {
  try {
    settingsView = await call("getOwnerSettings");
    renderSettings();
  } catch (e) {
    $("settings").hidden = true;
    if (e.reason !== "NOT_OWNER" && e.reason !== "SIGN_IN") say(`Settings could not load: ${e.message}`, true);
  }
}

async function start() {
  const cfg = await (await fetch("/__/firebase/init.json", { credentials: "omit" })).json();
  projectId = cfg.projectId;
  const app = initializeApp({ apiKey: cfg.apiKey, authDomain: cfg.authDomain, projectId: cfg.projectId, appId: cfg.appId });
  auth = getAuth(app);
  if (local) connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  await setPersistence(auth, inMemoryPersistence);
  show(false);
  $("sign-in").addEventListener("click", () => signIn().then(load).catch(signInFailed));
  $("sign-out").addEventListener("click", () => signOut(auth).then(() => { view = null; settingsView = null; show(false); say("Signed out."); }));
  $("settings-typed").addEventListener("input", () => {
    $("settings-confirm-ok").disabled = $("settings-typed").value.trim() !== settingsView?.confirmWord;
  });
  $("settings-confirm-cancel").addEventListener("click", () => {
    pendingPlan = null;
    $("settings-confirm").close();
  });
  $("settings-confirm-ok").addEventListener("click", () => confirmSave().catch(saveFailed));
  $("profile").addEventListener("change", describe);
  $("switch").addEventListener("click", () => {
    const p = selected();
    const text = textModels(p);
    $("confirm-text").textContent = `From ${view.active.label} to ${p.label} (${text ? `text ${text}; ` : ""}pictures ${models(p)}).`;
    $("confirm").showModal();
  });
  $("confirm-cancel").addEventListener("click", () => $("confirm").close());
  $("confirm-ok").addEventListener("click", () => {
    $("confirm").close();
    doSwitch().catch((e) => {
      say(e.reason === "STALE" ? "The setup was changed meanwhile; reloaded, check and try again." : `Not switched: ${e.message}`, true);
      if (e.reason === "STALE") load();
    });
  });
}

start().catch(() => say("This page could not start (the Firebase web app may not be registered yet).", true));
