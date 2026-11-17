// The owner console (functions/README.md "The owner console"). Static and harmless on its own:
// every fact it shows and every change it makes goes through the callables getProviderProfiles
// and setProviderProfile, which accept only the owner's Google sign-in (config/owner). Nothing is
// kept in the browser: the sign-in lives in memory only (inMemoryPersistence), so each visit
// signs in afresh, and Google's name and photo are never read. Text goes in with textContent only.
import { initializeApp } from "https://www.gstatic.com/firebasejs/13.4.0/firebase-app.js";
import {
  GoogleAuthProvider,
  connectAuthEmulator,
  getAuth,
  inMemoryPersistence,
  setPersistence,
  signInWithPopup,
  signOut,
} from "https://www.gstatic.com/firebasejs/13.4.0/firebase-auth.js";

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
  if (!signedIn) $("console").hidden = true;
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
  return `${p.provider} / ${p.model}, fallback ${p.fallbackModel || "none"}`;
}

function render() {
  const a = view.active;
  $("active").textContent = a.label + (a.profile === "custom" ? "" : ` (${a.profile})`);
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
  $("profile-summary").textContent = p ? `${p.summary} Models: ${models(p)}.` : "";
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
  } catch (e) {
    $("console").hidden = true;
    if (e.reason === "NOT_OWNER") say("This Google account may not use the owner console.", true);
    else if (e.reason === "SIGN_IN" || e.reason === "UNAUTHENTICATED") say("Sign in with Google.", true);
    else say(`Could not load: ${e.message}`, true);
  }
}

async function signIn() {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  await signInWithPopup(auth, provider);
  show(true);
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

async function start() {
  const cfg = await (await fetch("/__/firebase/init.json", { credentials: "omit" })).json();
  projectId = cfg.projectId;
  const app = initializeApp({ apiKey: cfg.apiKey, authDomain: cfg.authDomain, projectId: cfg.projectId, appId: cfg.appId });
  auth = getAuth(app);
  if (local) connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  await setPersistence(auth, inMemoryPersistence);
  show(false);
  $("sign-in").addEventListener("click", () => signIn().then(load).catch((e) => say(`Sign-in failed: ${e.code ?? e.message}`, true)));
  $("sign-out").addEventListener("click", () => signOut(auth).then(() => { view = null; show(false); say("Signed out."); }));
  $("profile").addEventListener("change", describe);
  $("switch").addEventListener("click", () => {
    const p = selected();
    $("confirm-text").textContent = `From ${view.active.label} to ${p.label} (${models(p)}).`;
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
