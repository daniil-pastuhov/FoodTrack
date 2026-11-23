// The owner console (functions/README.md "The owner console"). Static and harmless on its own:
// every fact it shows and every change it makes goes through the callables getProviderSwitches,
// setProviderSwitch, getOwnerSettings and setOwnerSettings, which accept only the owner's Google
// sign-in (config/owner). Nothing is
// kept in the browser: the sign-in lives in memory only (inMemoryPersistence), so each visit
// signs in afresh, and Google's name and photo are never read. Text goes in with textContent only.
// The Firebase JS SDK is served from this site (vendor/, pinned; vendor/firebasejs/13.4.0/SOURCE.txt).
//
// The page is a dashboard: a status strip of what is in force now, then one card per group of
// settings, all drawn from what the server sends (its groups, settings, figures and status), so a
// new setting lands in its card without a change here. Unsaved changes show on their card and in
// the dock at the bottom; every save, from a card or from the dock, is the same check (dry run),
// confirmation and save bound to that check.
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
/** What to do again after a fresh sign-in the server asked for (RECENT_SIGN_IN); run only on a click. */
let retry = null;

function say(text, error = false) {
  $("message").textContent = text;
  $("message").className = error ? "error" : "";
  if (!retry) $("reauth").hidden = true;
  dock();
}

// The server wants a newer sign-in. Google's sign-in is never opened by itself: the page says so
// and waits for a click on "Sign in again", which then repeats what was refused.
function askSignInAgain(text, again) {
  retry = again;
  say(text, true);
  $("reauth").hidden = false;
  dock();
}

function show(signedIn) {
  $("signed-out").hidden = signedIn;
  $("account").hidden = !signedIn;
  if (!signedIn) {
    $("load-failed").hidden = true;
    $("loading").hidden = true;
    $("console").hidden = true;
    $("settings").hidden = true;
    $("status-strip").hidden = true;
    $("board-link").hidden = true;
    clearCards();
  }
  dock();
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

// --- Small DOM helpers ----------------------------------------------------------------------

function el(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k === "role" || k.startsWith("aria-") || k.startsWith("data-")) e.setAttribute(k, v);
    else e[k] = v;
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) e.append(c);
  return e;
}

/** A state mark: its shape differs by tone (check, triangle, octagon), so colour never carries it alone. */
function dot(tone) {
  return el("span", { class: `dot ${tone}`, "aria-hidden": "true" });
}

/** A value such as "USD 0.92 of USD 5.00": the first part large, "of …" quieter beside it. */
function valueNode(value) {
  const at = value.indexOf(" of ");
  if (at <= 0) return el("span", { class: "t-main", text: value });
  return el("span", {}, el("span", { class: "t-main", text: value.slice(0, at) }), el("span", { class: "t-of", text: value.slice(at) }));
}

/** A tile of the status strip: label, value, a meter, a line (or labelled lines). */
function tile({ label, value, tone = "neutral", note = "", meter = null, wide = false, lines = null }) {
  const m = meter && meter.max > 0
    ? el("meter", { min: 0, max: meter.max, value: Math.min(meter.value, meter.max), low: meter.max * 0.8, high: meter.max * 0.999, optimum: 0, "aria-label": label })
    : null;
  return el("div", { class: `tile ${tone}${wide ? " wide" : ""}` },
    el("div", { class: "t-label" }, tone !== "neutral" ? dot(tone) : null, el("span", { text: label })),
    el("div", { class: "t-value" }, valueNode(value)),
    m,
    note ? el("p", { class: "t-note", text: note }) : null,
    lines ? el("dl", { class: "t-lines" }, ...lines.flatMap(([k, v]) => [el("dt", { text: k }), el("dd", { text: v })])) : null);
}

// --- Providers (getProviderSwitches / setProviderSwitch) -------------------------------------
// Four switches, each on its own: the recipe text's model, its fallback, the picture model, its
// fallback. Every option, its price and its readiness come from the server.

/** The option chosen (not yet switched) on each switch, by switch id. */
const chosen = new Map();

const slotOf = (id) => view?.slots.find((s) => s.id === id);
const optionOf = (s, value) => s?.options.find((o) => o.value === value);

/** "model (provider/model)" for a value, or the option's own label for none / off. */
function valueText(s, value) {
  const o = optionOf(s, value);
  if (!o) return `${value} (set by hand)`;
  return o.label === value || !value.includes("/") ? o.label : `${o.label} (${value})`;
}

function inForceNote(s) {
  if (s.custom) return "Set by hand: no option matches. Any option below replaces it.";
  if (s.inert) return "Pictures are off: kept for when they are back, asked by nobody now.";
  return "";
}

function renderSwitch(s) {
  const pick = optionOf(s, chosen.get(s.id))?.value ?? s.value;
  chosen.set(s.id, pick);
  const list = el("ul", { class: "profile-list" }, ...s.options.map((o, i) => {
    const id = `opt-${s.id}-${i}`;
    const radio = el("input", { type: "radio", name: `opt-${s.id}`, id, value: o.value, checked: o.value === pick });
    radio.addEventListener("change", () => {
      chosen.set(s.id, o.value);
      describeSwitch(s.id);
    });
    const state = o.current ? ["active", "In force"] : !o.ready ? ["crit", "Not ready"] : o.blocked ? ["warn", "Not now"] : ["good", "Ready"];
    const sub = [o.value.includes("/") ? o.value : "", o.providerLabel, o.price].filter(Boolean).join(" · ");
    const why = !o.current && !o.ready ? o.missing : !o.current && o.blocked ? [o.blocked] : [];
    return el("li", { class: "profile" + (o.value === pick ? " checked" : "") + (o.current ? " in-force" : "") + (o.current || (o.ready && !o.blocked) ? "" : " not-ready") },
      radio,
      el("div", { class: "p-body" },
        el("label", { htmlFor: id, text: o.label }),
        el("span", { class: `p-state ${state[0]}` }, state[0] === "active" ? null : dot(state[0]), el("span", { text: state[1] })),
        sub ? el("p", { class: "p-models", text: sub }) : null,
        why.length ? el("ul", { class: "p-missing" }, ...why.map((m) => el("li", { text: m }))) : null));
  }));
  const note = inForceNote(s);
  const button = el("button", { type: "button", class: "primary", text: "Switch…" });
  button.addEventListener("click", () => confirmSwitch(s.id));
  return el("fieldset", { class: "switch-block", "data-slot": s.id },
    el("legend", { text: s.label }),
    el("p", { class: "eyebrow", text: "In force" }),
    el("p", { class: "inforce-name mono", text: valueText(s, s.value) }),
    note ? el("p", { class: "note", text: note }) : null,
    list,
    el("div", { class: "switch-foot" }, el("p", { class: "note switch-summary" }), button));
}

/** The chosen option's line and whether its switch can be pressed. */
function describeSwitch(id) {
  const s = slotOf(id);
  const block = document.querySelector(`fieldset[data-slot="${id}"]`);
  if (!s || !block) return;
  const o = optionOf(s, chosen.get(id));
  for (const li of block.querySelectorAll("li.profile")) li.classList.toggle("checked", li.querySelector("input")?.checked === true);
  const summary = block.querySelector(".switch-summary");
  const button = block.querySelector("button");
  if (!o || o.current) {
    summary.textContent = "Choose another option to switch.";
    button.disabled = true;
  } else if (!o.ready) {
    summary.textContent = `${o.label} is not ready.`;
    button.disabled = true;
  } else if (o.blocked) {
    summary.textContent = `${o.label}: ${o.blocked}.`;
    button.disabled = true;
  } else {
    summary.textContent = `From ${valueText(s, s.value)} to ${valueText(s, o.value)}.`;
    button.disabled = false;
  }
}

function render() {
  $("active-on").textContent = view.picturesOn ? "Yes" : "No";
  const c = view.lastChange;
  $("last-change").textContent = c
    ? `${new Date(c.at).toLocaleString()} (${c.via === "web" ? "this console" : "admin command"}): ${c.slot === "profile" ? "profile" : (slotOf(c.slot)?.label ?? c.slot)} ${c.from} → ${c.to}`
    : "None recorded";
  $("switches").replaceChildren(...view.slots.map(renderSwitch));
  for (const s of view.slots) describeSwitch(s.id);
  $("console").hidden = false;
  renderStatus();
}

let pendingSwitch = null;

function confirmSwitch(id) {
  const s = slotOf(id);
  const o = optionOf(s, chosen.get(id));
  if (!s || !o || o.current || !o.ready || o.blocked) return;
  pendingSwitch = { body: { slot: s.id, value: o.value, expect: s.value, confirm: true }, label: s.label, to: valueText(s, o.value) };
  $("confirm-title").textContent = `Switch the ${s.label.toLowerCase()}?`;
  $("confirm-text").textContent = `From ${valueText(s, s.value)} to ${valueText(s, o.value)}. The other three switches stay as they are.`;
  $("confirm").showModal();
}

async function doSwitch() {
  const p = pendingSwitch;
  pendingSwitch = null;
  if (!p) return;
  say("Switching…");
  try {
    view = await call("setProviderSwitch", p.body);
  } catch (e) {
    if (e.reason !== "RECENT_SIGN_IN") throw e;
    askSignInAgain(`Not switched: ${e.message}. Sign in again, then the switch is sent once more.`, () => sendSwitch(p));
    return;
  }
  chosen.delete(p.body.slot);
  render();
  say(`${p.label} switched to ${p.to}.`);
}

async function sendSwitch(p) {
  say("Switching…");
  view = await call("setProviderSwitch", p.body);
  chosen.delete(p.body.slot);
  render();
  say(`${p.label} switched to ${p.to}.`);
}

/** The status strip: what the settings say is in force, then the four provider switches. */
function renderStatus() {
  // What the settings say first, in the server's order; the provider setup (a wide tile) last.
  const tiles = [...(settingsView?.status ?? [])];
  if (view) {
    const lines = view.slots.map((s) => [s.label, valueText(s, s.value) + (s.inert ? " (inert)" : "")]);
    const inForce = view.slots.map((s) => [s, optionOf(s, s.value)]);
    const missing = inForce.flatMap(([s, o]) => (o && !o.ready ? o.missing.map((m) => `${s.label}: ${m}`) : []));
    const custom = inForce.filter(([, o]) => !o).map(([s]) => s.label);
    const pictures = view.picturesOn ? "pictures offered" : "pictures not offered";
    const value = `${valueText(view.slots[0], view.slots[0].value)}; ${pictures}`;
    if (missing.length) tiles.push({ label: "Provider switches", wide: true, value, tone: "crit", note: `Not ready: ${missing.join("; ")}.`, lines });
    else if (custom.length) tiles.push({ label: "Provider switches", wide: true, value, tone: "warn", note: `Set by hand, no option: ${custom.join(", ")}; readiness unknown.`, lines });
    else tiles.push({ label: "Provider switches", wide: true, value, tone: "good", note: "Ready.", lines });
  }
  $("status").replaceChildren(...tiles.map(tile));
  // The one line to read first: what needs attention (the server's own tones), or that nothing does.
  const attention = tiles.filter((t) => t.tone === "crit" || t.tone === "warn");
  const worst = attention.some((t) => t.tone === "crit") ? "crit" : attention.length ? "warn" : "good";
  $("status-summary").className = `summary ${worst}`;
  $("status-summary").replaceChildren(dot(worst), el("span", {
    text: attention.length ? `Needs attention: ${attention.map((t) => `${t.label}, ${t.value}`).join(" · ")}` : "Nothing needs attention",
  }));
  $("status-strip").hidden = tiles.length === 0;
}

/** The page's own panel for a load that failed (the dock is for what happens after). */
function loadFailed(title, text) {
  $("load-failed-title").textContent = title;
  $("load-failed-text").textContent = text;
  $("load-failed").hidden = false;
}

async function load() {
  say("Loading…");
  $("load-failed").hidden = true;
  $("loading").hidden = false;
  try {
    view = await call("getProviderSwitches");
    say("");
    render();
    await loadSettings();
  } catch (e) {
    $("console").hidden = true;
    $("settings").hidden = true;
    $("status-strip").hidden = true;
    clearCards();
    say("");
    if (e.reason === "NOT_OWNER") loadFailed("Not the owner's account", "This Google account may not use the owner console. Sign out and sign in with the owner's account.");
    else if (e.reason === "SIGN_IN" || e.reason === "UNAUTHENTICATED") loadFailed("Signed out", "Sign in with Google.");
    else loadFailed("Could not load", `The server answered: ${e.message}`);
  } finally {
    $("loading").hidden = true;
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

// --- Settings (getOwnerSettings / setOwnerSettings) -------------------------------------------
// Everything below is drawn from what the server returns: groups, labels, help, controls,
// bounds, values, defaults, figures and status. A new setting on the server needs no change here.

let settingsView = null;
let pendingPlan = null;
/** Per setting id: the control's reader, its card, and whether a reset is asked. */
const inputs = new Map();
/** Per group id: its card's parts. */
const cards = new Map();
let fieldSeq = 0;

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
    case "list":
      return v.length === 0 ? "none" : v.join(", ");
    case "table":
      return c.rows.map((r) => `${r.label}: ${c.cols.map((k) => v[r.value]?.[k.value]).join(" / ")}`).join("; ");
    default:
      return JSON.stringify(v);
  }
}

/** A short form for the dock's review (a long list or table is summarised). */
function short(c, v) {
  const t = fmt(c, v);
  return t.length > 90 ? `${t.slice(0, 87)}…` : t;
}

function settingById(id) {
  for (const s of settingsView?.sections ?? []) for (const x of s.settings) if (x.id === id) return x;
  return null;
}

/**
 * The input for one setting: its node, how to read it back (undefined: not a valid entry), and
 * how to keep and restore what was typed (state / restore) across a redraw after another save.
 */
function controlFor(s, fid) {
  const c = s.control;
  if (c.kind === "toggle") {
    const box = el("input", { type: "checkbox", id: fid, checked: s.value === true, disabled: Boolean(s.blocked), role: "switch" });
    const word = el("span", { class: "switch-word", text: s.value === true ? "On" : "Off", "aria-hidden": "true" });
    box.addEventListener("change", () => { word.textContent = box.checked ? "On" : "Off"; });
    return {
      node: el("span", { class: "switch" }, box, word),
      get: () => box.checked,
      state: () => box.checked,
      restore: (v) => { box.checked = v; word.textContent = v ? "On" : "Off"; },
    };
  }
  if (c.kind === "number") {
    const k = isUsd(c) ? 1e6 : 1;
    // The bounds the page may use now (the server's envelope), else the control's own.
    const min = s.web?.min ?? c.min;
    const max = s.web?.max ?? c.max;
    const box = el("input", {
      type: "number", id: fid, inputMode: c.integer && !isUsd(c) ? "numeric" : "decimal",
      min: String(min / k), max: String(max / k), step: String(c.step / k),
      value: s.value === null ? "" : String(s.value / k), disabled: Boolean(s.blocked),
    });
    if (c.emptyMeans) box.placeholder = `empty: ${c.emptyMeans}`;
    // The unit inside the field, right after the figure (USD, recipes, days).
    const unitText = isUsd(c) ? "USD" : c.unit === "recipes" || c.unit === "days" ? c.unit : "";
    const unit = unitText ? el("span", { class: "unit", text: unitText, "aria-hidden": "true" }) : null;
    return {
      node: el("span", { class: `field${box.disabled ? " disabled" : ""}` }, box, unit),
      get: () => {
        const t = box.value.trim();
        if (t === "") return null;
        const n = Number(t);
        if (!Number.isFinite(n)) return undefined;
        return isUsd(c) ? Math.round(n * 1e6) : n;
      },
      state: () => box.value,
      restore: (v) => { box.value = v; },
    };
  }
  if (c.kind === "select") {
    const sel = el("select", { id: fid, disabled: Boolean(s.blocked) }, ...c.options.map((o) => el("option", { value: o.value, text: o.label })));
    sel.value = s.value;
    return { node: sel, get: () => sel.value, state: () => sel.value, restore: (v) => { sel.value = v; } };
  }
  if (c.kind === "multi") {
    const boxes = c.options.map((o, i) => {
      const box = el("input", { type: "checkbox", id: `${fid}-${i}`, value: o.value, checked: (s.value ?? []).includes(o.value), disabled: Boolean(s.blocked) });
      return { box, row: el("li", {}, box, el("label", { htmlFor: `${fid}-${i}`, text: o.label })) };
    });
    return {
      node: el("ul", { class: "multi", id: fid, "aria-labelledby": `${fid}-name` }, ...boxes.map((b) => b.row)),
      get: () => boxes.filter((b) => b.box.checked).map((b) => b.box.value),
      state: () => boxes.map((b) => b.box.checked),
      restore: (v) => boxes.forEach((b, i) => { b.box.checked = v[i]; }),
    };
  }
  if (c.kind === "list") {
    // One entry per line; the server trims, lower-cases and checks each (and refuses a bad one).
    const box = el("textarea", { id: fid, rows: String(Math.min(Math.max((s.value ?? []).length + 1, 3), 10)), spellcheck: false, autocomplete: "off", disabled: Boolean(s.blocked) });
    box.value = (s.value ?? []).join("\n");
    box.placeholder = c.item === "email" ? "one email per line" : "one per line";
    box.setAttribute("aria-describedby", `${fid}-name`);
    return {
      node: box,
      get: () => {
        const list = box.value.split(/[\n,]/).map((x) => x.trim().toLowerCase()).filter(Boolean);
        return list.length > c.max ? undefined : list;
      },
      state: () => box.value,
      restore: (v) => { box.value = v; },
    };
  }
  if (c.kind === "table") {
    const cells = [];
    const head = el("tr", {}, el("th", { text: "" }), ...c.cols.map((k) => el("th", { scope: "col", text: k.label })));
    const rows = c.rows.map((r) => el("tr", {}, el("th", { scope: "row", text: r.label }), ...c.cols.map((k) => {
      const fixed = c.fixed.some((f) => f.row === r.value && f.col === k.value);
      const b = s.web?.cells?.[r.value]?.[k.value] ?? { min: c.min, max: c.max };
      const box = el("input", { type: "number", inputMode: "numeric", min: String(b.min), max: String(b.max), step: "1", value: String(s.value?.[r.value]?.[k.value] ?? ""), disabled: fixed || Boolean(s.blocked) });
      box.setAttribute("aria-label", `${r.label}, ${k.label}${fixed ? " (set above)" : ""}`);
      cells.push({ r: r.value, k: k.value, box });
      return el("td", {}, box);
    })));
    return {
      node: el("div", { class: "table-wrap" }, el("table", { id: fid, "aria-labelledby": `${fid}-name` }, el("thead", {}, head), el("tbody", {}, ...rows))),
      get: () => {
        const out = {};
        for (const { r, k, box } of cells) {
          const n = Number(box.value);
          if (box.value.trim() === "" || !Number.isInteger(n)) return undefined;
          (out[r] ??= {})[k] = n;
        }
        return out;
      },
      state: () => cells.map((x) => x.box.value),
      restore: (v) => cells.forEach((x, i) => { x.box.value = v[i]; }),
    };
  }
  return { node: el("p", { class: "note", text: "This server sent a kind of setting this page doesn't know; use the admin command." }), get: () => s.value, state: () => null, restore: () => {} };
}

function commandText(s) {
  return s.command + (local ? "" : ` --project ${projectId}`);
}

function viaText(via) {
  return via === "web" ? "this console" : "admin command";
}

/** What one setting would change to now: {change} (none when unchanged), or {invalid}. */
function pendingOf(s) {
  const entry = inputs.get(s.id);
  if (!entry || s.blocked) return {};
  if (entry.reset) return { change: { id: s.id, reset: true, expect: s.stored }, to: s.default };
  const value = entry.get();
  if (value === undefined) return { invalid: true };
  if (same(value, s.value)) return {};
  if (value === null) return { change: { id: s.id, reset: true, expect: s.stored }, to: s.default };
  return { change: { id: s.id, value, expect: s.stored }, to: value };
}

function settingNode(s, sec) {
  const fid = `setting-${++fieldSeq}`;
  const ctl = controlFor(s, fid);
  const block = s.control.kind === "multi" || s.control.kind === "table" || s.control.kind === "list";
  const meta = el("p", { class: "s-meta" });
  const node = el("div", { class: "setting" + (block ? " block" : "") + (s.changed ? " changed" : "") });
  const entry = { get: ctl.get, state: ctl.state, restore: ctl.restore, reset: false, sec: sec.id, node, meta, setting: s };
  inputs.set(s.id, entry);
  // Two quiet marks: "sensitive" (a lock) and "changed" from the default (a dot; the default is on the line below).
  const chips = el("span", { class: "s-chips" },
    s.changed ? el("span", { class: "chip changed", text: "changed" }) : null,
    s.sensitive ? el("span", { class: "chip sens", text: "sensitive" }) : null);
  const name = block
    ? el("p", { class: "s-name", id: `${fid}-name` }, el("span", { text: s.label }), chips)
    : el("label", { class: "s-name", htmlFor: fid, id: `${fid}-name` }, el("span", { text: s.label }), chips);
  const facts = [
    el("p", { text: s.help }),
    el("p", { text: `Default: ${fmt(s.control, s.default)} · ${s.overridden ? "set here" : "default in force"}${s.lastChange ? ` · last changed ${new Date(s.lastChange.at).toLocaleString()} (${viaText(s.lastChange.via)})` : ""}` }),
    s.web ? el("p", { text: `On this page: ${s.web.text}.` }) : null,
    s.web ? el("p", {}, el("span", { text: "Larger changes: use the command " }), el("code", { text: commandText(s) })) : null,
  ];
  const more = el("details", { class: "s-more" }, el("summary", { text: "About" }), el("div", { class: "s-about" }, ...facts));
  const tools = el("div", { class: "s-tools" }, more);
  node.append(...[
    el("div", { class: "s-head" }, name),
    el("div", { class: "s-ctl" }, ctl.node),
    meta,
    tools,
    s.blocked ? el("p", { class: "s-warn", text: s.blocked }) : null,
  ].filter(Boolean));
  if (s.overridden && !s.blocked) {
    const btn = el("button", { type: "button", class: "link", text: "Reset to default" });
    btn.addEventListener("click", () => {
      entry.reset = !entry.reset;
      btn.textContent = entry.reset ? "Will reset on Save (undo)" : "Reset to default";
      node.classList.toggle("resetting", entry.reset);
      refreshPending();
    });
    entry.resetButton = btn;
    more.before(btn);
  }
  node.addEventListener("input", refreshPending);
  node.addEventListener("change", refreshPending);
  return node;
}

/** The read-only fields that belong in one card, folded away. */
function readOnlyNode(rows) {
  if (!rows.length) return null;
  const dl = el("dl", { class: "facts" });
  for (const r of rows) {
    dl.append(el("dt", { text: r.label }), el("dd", {},
      el("span", { text: fmt(null, r.value) + (same(r.value, r.default) ? "" : `  (default ${fmt(null, r.default)})`) }),
      el("p", { class: "note", text: r.reason })));
  }
  return el("details", { class: "ro" }, el("summary", { text: `Shown here, changed elsewhere (${rows.length})` }), dl);
}

/**
 * A worked line the server sends as "min(A, B) = C. Rest." set as a small formula block: one term
 * a line, the result last. Only the layout is the page's; the figures and words are the server's.
 * Anything else stays a plain note.
 */
function formulaNode(note) {
  const m = /^min\((.+?), (.+)\) = ([^,;]+?)\.(?:\s+(.*))?$/s.exec(note);
  if (!m) return null;
  const row = (op, text, cls = "") => el("div", { class: `fx-row${cls}` }, el("span", { class: "fx-op", text: op }), el("span", { class: "fx-term", text }));
  return el("div", { class: "f-formula" },
    el("div", { class: "fx" },
      row("min(", m[1]),
      row("", m[2] + " )"),
      row("=", m[3], " fx-result")),
    m[4] ? el("p", { class: "f-note", text: m[4] }) : null);
}

function figuresNode(facts) {
  if (!facts?.length) return null;
  return el("div", { class: "figures" }, ...facts.map((f) => {
    const formula = f.note ? formulaNode(f.note) : null;
    return el("div", { class: "figure" + (formula ? " formula" : "") },
      el("div", { class: "f-label", text: f.label }),
      el("div", { class: "f-value", text: f.value }),
      formula ?? (f.note ? el("p", { class: "f-note", text: f.note }) : null));
  }));
}

/** One change as "Group › Setting  before → after", each part its own span, the sensitive ones marked. */
function changeItem({ where, label, from, to, note = "", sensitive = false, invalid = false }) {
  return el("li", { class: (sensitive ? "sensitive" : "") + (invalid ? " invalid" : "") },
    el("span", { class: "c-what" }, where ? el("span", { class: "c-where", text: `${where} › ` }) : null, el("span", { class: "c-label", text: label }),
      sensitive ? el("span", { class: "chip sens", text: "sensitive" }) : null),
    invalid
      ? el("span", { class: "c-diff" }, el("span", { class: "c-bad", text: "not a valid entry" }))
      : el("span", { class: "c-diff" },
        el("span", { class: "c-from", text: from }),
        el("span", { class: "c-arrow", text: " → " }),
        el("span", { class: "c-to", text: to }),
        note ? el("span", { class: "c-note", text: ` ${note}` }) : null));
}

// --- The weekly prompt review (a section's `reviews` from getOwnerSettings; core/promptReview.ts) --
// Everything here is model text or figures from the server: set as text, never as HTML.

const REVIEW_STATUS = {
  done: "Done",
  not_enough_data: "Not enough data",
  skipped: "Skipped",
  failed: "Failed",
  running: "Running",
};
const REVIEW_TARGET = { recipe: "Recipe prompt", menu: "Menu prompt", judge: "Judge prompt" };
const reviewUsd = (micro) => `USD ${(micro / 1e6).toFixed(micro > 0 && micro < 10000 ? 4 : 3)}`;

function reviewProblem(p) {
  const textBlock = (label, text, cls) => el("div", { class: `r-text ${cls}` },
    el("span", { class: "r-k", text: label }),
    el("pre", { class: "r-pre", text: text || "(empty)" }));
  return el("li", { class: "r-problem" },
    el("div", { class: "r-head" },
      el("span", { class: "r-name", text: p.title }),
      el("span", { class: "r-count", text: `${p.count} ${p.count === 1 ? "answer" : "answers"}` })),
    el("div", { class: "s-chips" },
      el("span", { class: "chip", text: p.issue.replaceAll("_", " ") }),
      el("span", { class: "chip", text: REVIEW_TARGET[p.target] ?? p.target }),
      el("span", { class: "chip", text: `${p.confidence} confidence` })),
    p.cause ? el("p", { class: "r-cause", text: p.cause }) : null,
    el("div", { class: "r-diff" }, textBlock("Before", p.before, "r-before"), textBlock("After", p.after, "r-after")),
    p.beforeFound ? null : el("p", { class: "r-warn", text: "This “before” is not in the current prompt word for word: check it before applying." }),
    p.exampleIds.length ? el("p", { class: "r-meta mono", text: `Examples: ${p.exampleIds.join(", ")}` }) : null);
}

function reviewList(title, help, list) {
  return el("div", { class: "r-group" },
    el("h3", { class: "r-title" }, el("span", { text: title }), el("span", { class: "badge r-n", text: String(list.length) })),
    el("p", { class: "help", text: help }),
    list.length ? el("ol", { class: "r-problems" }, ...list.map(reviewProblem)) : el("p", { class: "note", text: "None this week." }));
}

function reviewFigures(r) {
  const figure = (label, value, note = "") => el("div", { class: "figure" },
    el("div", { class: "f-label", text: label }), el("div", { class: "f-value", text: value }), note ? el("p", { class: "f-note", text: note }) : null);
  const tokens = r.usage ? `${r.usage.inputTokens.toLocaleString()} in, ${(r.usage.outputTokens + r.usage.thinkingTokens).toLocaleString()} out` : "";
  return el("div", { class: "figures" },
    figure("Week", r.week, r.finishedAt ? `Run ${new Date(r.finishedAt).toLocaleString()}` : ""),
    figure("Answers analysed", r.answers === null ? "–" : r.answers.toLocaleString(), r.sampled !== null ? `${r.sampled} sampled for the model` : ""),
    figure("Cost of the run", reviewUsd(r.costMicroUsd), tokens ? `${r.model ?? ""} · ${tokens} tokens` : (r.model ?? "")),
    figure("Status", REVIEW_STATUS[r.status] ?? r.status, r.status !== "done" && r.reason ? r.reason.replaceAll("_", " ").toLowerCase() : ""));
}

function reviewReport(r) {
  return el("div", { class: "r-report" },
    reviewFigures(r),
    r.summary ? el("p", { class: "r-summary", text: r.summary }) : null,
    reviewList("Prompt problems", "The recipe writer's prompt causes or allows these.", r.promptProblems),
    reviewList("Judge problems", "The second check is wrong here (e.g. its safety filter on ordinary cooking).", r.judgeProblems));
}

function reviewNode(reviews) {
  const box = el("div", { class: "review" });
  if (reviews === null || reviews === undefined) {
    box.append(el("p", { class: "note", text: "The latest report could not be read." }));
    return box;
  }
  if (!reviews.length) {
    box.append(el("p", { class: "note", text: "No report yet: the first runs on a Monday at 03:41 UTC." }));
    return box;
  }
  const [latest, earlier] = reviews;
  if (latest.status === "done") {
    box.append(reviewReport(latest));
  } else {
    const why = latest.status === "not_enough_data"
      ? `only ${latest.answers ?? 0} answers, so no model was asked and nothing was spent`
      : latest.status === "skipped" ? `${(latest.reason ?? "").replaceAll("_", " ").toLowerCase()}, nothing was spent`
        : latest.status === "failed" ? `${(latest.reason ?? "").replaceAll("_", " ").toLowerCase()}; ${reviewUsd(latest.costMicroUsd)} spent` : "in progress";
    box.append(el("p", { class: "r-latest" }, dot(latest.status === "failed" ? "warn" : "neutral"),
      el("span", { text: `${latest.week}: ${REVIEW_STATUS[latest.status] ?? latest.status} (${why}).` })));
    if (earlier) box.append(el("p", { class: "eyebrow r-earlier", text: `Latest report, ${earlier.week}` }), reviewReport(earlier));
  }
  if (latest.email) box.append(el("p", { class: "note r-email", text: latest.email }));
  box.append(el("p", { class: "note r-foot", text: "Advice only: nothing was changed. Prompts are code; a change goes through review, the red-team run and a deploy. Full text: npm run admin -- judge review." }));
  return box;
}

// --- The weekly app report (a section's `weekly` from getOwnerSettings; core/weeklyReport.ts) -----
// Preformatted figures from the server: set as text, never as HTML.

function weeklyNode(weekly) {
  const box = el("div", { class: "weekly" });
  if (weekly === null || weekly === undefined) {
    box.append(el("p", { class: "note", text: "The weekly report could not be read." }));
    return box;
  }
  const { latest, history } = weekly;
  if (!latest) {
    box.append(el("p", { class: "note", text: "No report yet: the first is made on a Monday at 03:41 UTC, for the week before." }));
    return box;
  }
  const made = latest.generatedAt ? ` · made ${new Date(latest.generatedAt).toLocaleString()}` : "";
  box.append(el("p", { class: "eyebrow w-week", text: `${latest.week} · ${latest.span} (UTC)${latest.previousWeek ? ` · changes against ${latest.previousWeek}` : " · the first report"}${made}` }));
  box.append(el("div", { class: "figures w-figures" }, ...latest.rows.map((r) => el("div", { class: "figure" },
    el("div", { class: "f-label", text: r.label }),
    el("div", { class: "f-value w-value" }, el("span", { text: r.value }), r.change ? el("span", { class: "w-change", text: r.change }) : null),
    r.note ? el("p", { class: "f-note", text: r.note }) : null))));
  for (const c of latest.caveats) box.append(el("p", { class: "r-warn w-caveat", text: c }));
  if (latest.email) box.append(el("p", { class: "note w-email", text: latest.email }));
  if (history.length > 1) {
    const head = ["Week", "Net revenue", "Model spend", "Margin", "Purchases", "Requests", "Free used"];
    const cell = (t) => el("td", { text: t });
    box.append(el("p", { class: "eyebrow w-earlier", text: "Last weeks" }),
      el("div", { class: "table-wrap w-history" }, el("table", {},
        el("thead", {}, el("tr", {}, ...head.map((h) => el("th", { scope: "col", text: h })))),
        el("tbody", {}, ...history.map((h) => el("tr", {},
          el("th", { scope: "row", text: h.week }), cell(h.revenue), cell(h.spend), cell(h.margin), cell(h.purchases), cell(h.requests), cell(h.freeUsed)))))));
  }
  box.append(el("p", { class: "note r-foot", text: "Estimates: revenue is the net per recipe set under Spending limits, not a store payout; spend is the server's token estimate, not the Cloud bill. Emailed to the owner on Mondays, once. Full text: npm run admin -- report weekly." }));
  return box;
}

function clearCards() {
  for (const c of cards.values()) c.node.remove();
  cards.clear();
  inputs.clear();
  $("providers-readonly").replaceChildren();
  refreshPending();
}

function cardNode(sec, readOnly) {
  const badge = el("span", { class: "badge", hidden: true });
  const save = el("button", { type: "button", class: "primary", text: "Save…", disabled: true });
  save.setAttribute("aria-label", `Save ${sec.title}`);
  const discard = el("button", { type: "button", text: "Discard", hidden: true });
  const hid = `h-sec-${sec.id}`;
  const node = el("section", { class: "card" + (sec.wide ? " wide" : ""), "aria-labelledby": hid },
    el("header", { class: "card-head" },
      el("div", { class: "card-title" }, el("h2", { id: hid, text: sec.title }), el("p", { class: "help", text: sec.help })),
      badge),
    figuresNode(sec.facts),
    "reviews" in sec ? reviewNode(sec.reviews) : null,
    "weekly" in sec ? weeklyNode(sec.weekly) : null,
    el("div", { class: "rows" }, ...sec.settings.map((s) => settingNode(s, sec))),
    readOnlyNode(readOnly),
    el("div", { class: "card-foot" }, discard, save));
  save.addEventListener("click", () => saveScope({ title: sec.title, sections: [sec] }).catch(saveFailed));
  discard.addEventListener("click", () => {
    const fresh = cardNode(sec, readOnly);
    node.replaceWith(fresh.node);
    cards.set(sec.id, fresh);
    refreshPending();
  });
  return { node, badge, save, discard, sec };
}

function renderSettings(carry = new Map()) {
  const v = settingsView;
  for (const c of cards.values()) c.node.remove();
  cards.clear();
  inputs.clear();
  $("settings-invalid").hidden = v.invalid.length === 0;
  $("settings-invalid").textContent = v.invalid.length ? `Invalid as stored, so their safe values are in force: ${v.invalid.join(", ")}. Fix or delete them in the Firestore console.` : "";
  $("settings-last").textContent = v.lastChange ? `Last settings change: ${new Date(v.lastChange.at).toLocaleString()} (${viaText(v.lastChange.via)}).` : "";
  const known = new Set(v.sections.map((s) => s.id));
  const roOf = (id) => v.readOnly.filter((r) => (r.section ?? "other") === id);
  const grid = $("cards");
  for (const sec of v.sections) {
    const card = cardNode(sec, roOf(sec.id));
    cards.set(sec.id, card);
    grid.append(card.node);
  }
  // Read-only fields of no group on this page (or from an older server, which sends none) in a last card.
  const rest = v.readOnly.filter((r) => r.section !== "providers" && !known.has(r.section));
  if (rest.length) {
    const node = el("section", { class: "card", "aria-labelledby": "h-sec-other" },
      el("header", { class: "card-head" }, el("div", { class: "card-title" }, el("h2", { id: "h-sec-other", text: "Other, changed elsewhere" }),
        el("p", { class: "help", text: "Shown here with the value in force; changed by hand or by a code change, not on this page." }))),
      readOnlyNode(rest));
    cards.set("other", { node, badge: null, save: null, discard: null, sec: null });
    grid.append(node);
  }
  $("providers-readonly").replaceChildren(...[readOnlyNode(roOf("providers"))].filter(Boolean));
  // What was typed in other cards before a save, unless the server's value moved meanwhile.
  for (const [id, kept] of carry) {
    const entry = inputs.get(id);
    if (!entry || !same(entry.setting.stored, kept.stored) || entry.setting.blocked) continue;
    entry.restore(kept.state);
    if (kept.reset && entry.resetButton) entry.resetButton.click();
  }
  const link = $("board-link");
  link.hidden = !v.boardUrl;
  if (v.boardUrl) link.href = v.boardUrl;
  $("settings").hidden = false;
  renderStatus();
  refreshPending();
}

/** Recounts the unsaved changes: per card (badge, buttons, border), per setting, and in the dock. */
function refreshPending() {
  let total = 0;
  let invalid = 0;
  const review = [];
  for (const card of cards.values()) {
    if (!card.sec) continue;
    let n = 0;
    for (const s of card.sec.settings) {
      const entry = inputs.get(s.id);
      if (!entry) continue;
      const p = pendingOf(s);
      const dirty = Boolean(p.change || p.invalid);
      entry.node.classList.toggle("dirty", dirty);
      entry.meta.textContent = p.invalid
        ? "Not a valid entry yet."
        : p.change
          ? `Unsaved: ${fmt(s.control, s.value)} → ${fmt(s.control, p.to)}${p.change.reset ? " (reset to default)" : ""}`
          : s.changed ? `Default: ${fmt(s.control, s.default)}` : "";
      if (dirty) {
        n++;
        review.push(p.invalid
          ? { where: card.sec.title, label: s.label, invalid: true }
          : { where: card.sec.title, label: s.label, from: short(s.control, s.value), to: short(s.control, p.to), note: p.change.reset ? "(reset)" : "", sensitive: s.sensitive });
      }
      if (p.invalid) invalid++;
    }
    total += n;
    card.badge.hidden = n === 0;
    card.badge.textContent = `${n} unsaved`;
    card.save.disabled = n === 0;
    card.discard.hidden = n === 0;
    card.node.classList.toggle("dirty", n > 0);
  }
  $("pending").hidden = total === 0;
  $("pending").textContent = total === 0 ? "" : `${total} unsaved ${total === 1 ? "change" : "changes"}${invalid ? ` (${invalid} not valid yet)` : ""}`;
  $("pending-actions").hidden = total === 0;
  $("pending-list").replaceChildren(...review.map(changeItem));
  if (total === 0) setReview(false);
  dock();
}

function setReview(open) {
  $("pending-review").hidden = !open;
  $("review-all").setAttribute("aria-expanded", String(open));
  $("review-all").textContent = open ? "Hide review" : "Review";
}

/** The dock shows while there is something to say or to save. */
function dock() {
  $("dock").hidden = !$("message").textContent && $("reauth").hidden && $("pending").hidden;
}

function scopeChanges(scope) {
  const changes = [];
  for (const sec of scope.sections) {
    for (const s of sec.settings) {
      const p = pendingOf(s);
      if (p.invalid) throw Object.assign(new Error(`${s.label}: not a valid entry.`), { reason: "LOCAL" });
      if (p.change) changes.push(p.change);
    }
  }
  return changes;
}

async function saveScope(scope) {
  const changes = scopeChanges(scope);
  if (changes.length === 0) {
    say(`Nothing to save in ${scope.title}.`);
    return;
  }
  say("Checking…");
  let answer;
  try {
    answer = await call("setOwnerSettings", { changes, dryRun: true });
  } catch (e) {
    if (e.reason !== "RECENT_SIGN_IN") throw e;
    askSignInAgain(`Not checked: ${e.message}. Sign in again, then the check runs once more.`, () => saveScope(scope).catch(saveFailed));
    return;
  }
  const plan = answer.plan;
  if (plan.changes.length === 0) {
    say(`Nothing to save in ${scope.title}: already so.`);
    return;
  }
  say("");
  pendingPlan = { changes, plan };
  const ctlOf = (id) => settingById(id)?.control ?? null;
  $("settings-confirm-list").replaceChildren(...plan.changes.map((c) => changeItem({
    label: c.label, from: fmt(ctlOf(c.id), c.from), to: fmt(ctlOf(c.id), c.to), note: c.reset ? "(reset to default)" : "", sensitive: c.sensitive,
  })));
  $("settings-confirm-side").textContent = plan.sideEffects.length
    ? `Also changes with it: ${plan.sideEffects.map((s) => `${s.label}: ${fmt(ctlOf(s.id), s.from)} → ${fmt(ctlOf(s.id), s.to)}`).join("; ")}.`
    : "";
  const sensitive = plan.sensitive.length > 0;
  $("settings-confirm-sensitive").hidden = !sensitive;
  $("settings-confirm-signin").textContent = sensitive
    ? `A sensitive save needs a sign-in from the last ${settingsView.sensitiveSignInMinutes} minutes; if yours is older, the server says so and you sign in again.`
    : "";
  $("settings-typed").value = "";
  if (sensitive) {
    const why = [...new Set(plan.changes.filter((c) => c.sensitive).map((c) => settingById(c.id)?.sensitiveWhy).filter(Boolean))];
    $("settings-confirm-sensitive-text").textContent = `${plan.changes.filter((c) => c.sensitive).map((c) => c.label).join(", ")}: sensitive (${why.join("; ")}).`;
    $("settings-typed-label").textContent = `Type ${settingsView.confirmWord} to save${plan.changes.length > 1 ? " all of these" : ""}`;
  }
  $("settings-confirm-ok").disabled = sensitive;
  $("settings-confirm-ok").classList.toggle("danger", sensitive);
  $("settings-confirm-ok").classList.toggle("primary", !sensitive);
  $("settings-confirm").showModal();
}

async function confirmSave() {
  const p = pendingPlan;
  if (!p) return;
  const sensitive = p.plan.sensitive;
  $("settings-confirm").close();
  say("Saving…");
  // The token binds the save to the check the dialog showed: a different plan now is refused as STALE.
  const body = { changes: p.changes, confirm: true, token: p.plan.token, sensitive, typed: sensitive.length ? $("settings-typed").value.trim() : "" };
  await sendSave(body);
}

async function sendSave(body) {
  say("Saving…");
  // What is typed elsewhere stays: everything not in this save is carried over the redraw.
  const saved = new Set(body.changes.map((c) => c.id));
  const carry = new Map();
  for (const [id, entry] of inputs) {
    if (saved.has(id) || !entry.node.classList.contains("dirty")) continue;
    carry.set(id, { stored: entry.setting.stored, state: entry.state(), reset: entry.reset });
  }
  try {
    settingsView = await call("setOwnerSettings", body);
  } catch (e) {
    if (e.reason !== "RECENT_SIGN_IN") throw e;
    askSignInAgain(`Not saved: ${e.message}. Sign in again, then the same save is sent once more.`, () => sendSave(body).catch(saveFailed));
    return;
  }
  pendingPlan = null;
  renderSettings(carry);
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
  $("reauth-button").addEventListener("click", () => {
    const again = retry;
    retry = null;
    $("reauth").hidden = true;
    dock();
    signIn().then(() => again?.()).catch((e) => (e.reason ? say(`Not done: ${e.message}`, true) : signInFailed(e)));
  });
  $("sign-out").addEventListener("click", () => signOut(auth).then(() => { view = null; settingsView = null; retry = null; chosen.clear(); show(false); say("Signed out."); }));
  $("settings-typed").addEventListener("input", () => {
    $("settings-confirm-ok").disabled = $("settings-typed").value.trim() !== settingsView?.confirmWord;
  });
  $("settings-confirm-cancel").addEventListener("click", () => {
    pendingPlan = null;
    $("settings-confirm").close();
  });
  $("settings-confirm-ok").addEventListener("click", () => confirmSave().catch(saveFailed));
  $("review-all").addEventListener("click", () => setReview($("pending-review").hidden));
  $("discard-all").addEventListener("click", () => {
    renderSettings();
    say("Unsaved changes discarded.");
  });
  // Every group with changes, in one check, one confirmation and one save.
  $("save-all").addEventListener("click", () => saveScope({ title: "any group", sections: settingsView?.sections ?? [] }).catch(saveFailed));
  $("confirm-cancel").addEventListener("click", () => {
    pendingSwitch = null;
    $("confirm").close();
  });
  $("confirm-ok").addEventListener("click", () => {
    $("confirm").close();
    doSwitch().catch((e) => {
      say(e.reason === "STALE" ? "That switch was changed meanwhile; reloaded, check and try again." : `Not switched: ${e.message}`, true);
      if (e.reason === "STALE") load();
    });
  });
  // Leaving with unsaved changes asks first (the browser's own question).
  addEventListener("beforeunload", (e) => {
    if (!$("pending").hidden) e.preventDefault();
  });
}

start().catch(() => say("This page could not start (the Firebase web app may not be registered yet).", true));
