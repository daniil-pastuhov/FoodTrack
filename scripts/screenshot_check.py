#!/usr/bin/env python3
"""Takes every app screenshot on every capture device, one device at a time, and compares them
with a local baseline through reg-cli (a visual-regression tool: HTML report plus JSON).

    scripts/screenshot_check.py [--platform android|ios|all] [--devices a,b] [--langs en,ru]
                                [--approve] [--approve-last] [--no-build] [--no-capture]
                                [--keep-device] [--ignore-running] [--threshold-rate R]

One command, unattended. For each device in turn it boots it, runs the store capture script
(scripts/android_screenshots.sh or scripts/ios_screenshots.sh, which hold the demo data and the
six screens) for every language asked, and shuts it down before the next one starts: never two
emulators or simulators at once. Each app is built once per platform (--no-build reuses the last
build). Then reg-cli compares build/screenshot-check/actual/ with the baseline in
build/screenshot-check/expected/.

Everything the tools print goes to build/screenshot-check/run.log; stdout is a short summary
(shots and time per device, then changed / new / deleted / unchanged with the changed files and
their diff ratio), and build/screenshot-check/summary.json holds the same. Exit code: 0 no
differences (or a baseline approved), 1 differences, 2 the run itself failed or there is no
baseline yet.

Baseline: only framed store images are committed (store-listing/), so the baseline is local and
git-ignored. --approve takes a run and makes it the baseline for the devices and languages it
covered; --approve-last does the same with the last run's captures, without capturing again.
The demo data is relative to today (scripts/demo_backup.py), and the scan's pack date and the
menu's days are dates, so compare with a baseline taken the same day: the summary says when it
was not.
"""
import argparse
import datetime
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANGS = ["en", "ru", "nb", "es", "de", "fr", "sv", "da"]
REG_CLI = "reg-cli@0.18.16"
# reg-cli's own knobs: pixelmatch's per-pixel colour threshold, anti-aliased pixels not counted,
# and the share of differing pixels a screenshot may have and still pass.
MATCHING_THRESHOLD = 0.1
THRESHOLD_RATE = 0.0
LIST_CAP = 15
ANDROID_PORT = 5580
BOOT_TIMEOUT = 300
CAPTURE_TIMEOUT = 45 * 60
DERIVED = ROOT / "build" / "ios-screenshots-derived-data"

# The devices the store capture scripts shoot on (their headers list the sets). key -> settings.
# Android: android_screenshots.sh takes only a phone (Play's phone set). iOS: the 6.9" and 6.3"
# iPhones, the iPad and the iPhone Duo's two displays; the Duo boots folded, so its outer display
# is unattended, while the inner one needs Xcode's Device Hub to unfold it (scripts/duo_pose.sh),
# so it runs only when named in --devices.
DEVICES = {
    "android-phone": dict(platform="android", avd="Larder_Shots_Phone", default=True),
    "ios-iphone-6.9": dict(platform="ios", name="iPhone 17 Pro Max", default=True),
    "ios-iphone-6.3": dict(platform="ios", name="iPhone 17 Pro", default=True),
    "ios-ipad-13": dict(platform="ios", name="iPad Pro 13-inch (M5)", default=True),
    "ios-duo-outer": dict(platform="ios", name="iPhone Duo", screen="1", default=True),
    "ios-duo-inner": dict(platform="ios", name="iPhone Duo", screen="3", pose="Open", default=False),
}


class RunFailed(Exception):
    pass


# ---- pure parts (tested in scripts/tests/test_screenshot_check.py) ----

def csv_list(value):
    return [v for v in (s.strip() for s in value.split(",")) if v] if value else []


def parse_args(argv):
    p = argparse.ArgumentParser(prog="screenshot_check.py", description=__doc__.split("\n\n")[0])
    p.add_argument("--platform", choices=["android", "ios", "all"], default="all")
    p.add_argument("--devices", type=csv_list, default=[], help="comma-separated: " + ", ".join(DEVICES))
    p.add_argument("--langs", type=csv_list, default=list(LANGS), help="comma-separated, default all eight")
    p.add_argument("--approve", action="store_true", help="make this run the baseline")
    p.add_argument("--approve-last", action="store_true", help="make the last run the baseline; captures nothing")
    p.add_argument("--no-build", action="store_true", help="reuse the last build")
    p.add_argument("--no-capture", action="store_true", help="compare the last run's captures again")
    p.add_argument("--keep-device", action="store_true", help="leave each device running")
    p.add_argument("--ignore-running", action="store_true",
                   help="start even when another emulator or simulator is running")
    p.add_argument("--threshold-rate", type=float, default=THRESHOLD_RATE,
                   help="share of differing pixels a screenshot may have and pass (default %(default)s)")
    p.add_argument("--out", type=Path, default=ROOT / "build" / "screenshot-check")
    args = p.parse_args(argv)
    bad = [l for l in args.langs if l not in LANGS]
    if bad:
        p.error("unknown language %s (one of %s)" % (", ".join(bad), " ".join(LANGS)))
    if not args.langs:
        p.error("--langs is empty")
    try:
        args.device_keys = select_devices(args.platform, args.devices)
    except ValueError as e:
        p.error(str(e))
    if args.approve_last and (args.approve or args.no_capture):
        p.error("--approve-last captures nothing; use it alone")
    return args


def select_devices(platform, names):
    """The device keys to run, in table order: those named, else the platform's defaults."""
    unknown = [n for n in names if n not in DEVICES]
    if unknown:
        raise ValueError("unknown device %s (one of %s)" % (", ".join(unknown), ", ".join(DEVICES)))
    keys = [k for k, d in DEVICES.items()
            if (k in names if names else d["default"]) and platform in ("all", d["platform"])]
    if not keys:
        raise ValueError("no device of platform %s among %s" % (platform, ", ".join(names)))
    return keys


def fmt_duration(seconds):
    seconds = int(round(seconds))
    return "%dm%02ds" % divmod(seconds, 60) if seconds >= 60 else "%ds" % seconds


def classify(reg):
    """reg-cli's JSON -> the four lists, sorted."""
    return {
        "changed": sorted(reg.get("failedItems", [])),
        "new": sorted(reg.get("newItems", [])),
        "deleted": sorted(reg.get("deletedItems", [])),
        "unchanged": sorted(reg.get("passedItems", [])),
    }


def result_code(groups, failed_devices):
    if failed_devices:
        return 2
    return 1 if groups["changed"] or groups["new"] or groups["deleted"] else 0


def capped(lines, cap=LIST_CAP):
    return lines if len(lines) <= cap else lines[:cap] + ["…and %d more" % (len(lines) - cap)]


def stale_baseline_notes(meta, pairs, today):
    """A line per device whose baseline for these languages was taken on another day."""
    notes = []
    for device in sorted({d for d, _ in pairs}):
        days = sorted({meta.get("%s/%s" % (device, lang), {}).get("date") for d, lang in pairs if d == device} - {None})
        old = [d for d in days if d != today]
        if old:
            notes.append("%s: baseline from %s; the scan and menu shots show today's date, so they differ "
                         "(approve a baseline today)" % (device, ", ".join(old)))
    return notes


def device_line(d):
    status = "%d shots" % d["shots"] if d["ok"] else "FAILED: %s" % d["error"]
    return "  %-16s %s  %s" % (d["key"], status, fmt_duration(d["seconds"]))


def verdict(code, approved=None, no_baseline=False):
    if code == 0:
        return "baseline approved" if approved is not None else "no differences"
    if code == 1:
        return "differences"
    return "no baseline" if no_baseline else "run failed"


def format_summary(groups, ratios, notes, paths, code, approved=None, no_baseline=False):
    # groups is None when nothing was compared (no baseline, or only approving).
    """The lines after the per-device ones: what changed, notes, where to look, the verdict."""
    out = []
    if groups is not None:
        out.append("changed %d · new %d · deleted %d · unchanged %d" % tuple(
            len(groups[k]) for k in ("changed", "new", "deleted", "unchanged")))
        changed = ["  ~ %s  %s" % (f, "%.3f%%" % (ratios[f] * 100) if ratios.get(f) is not None else "?")
                   for f in groups["changed"]]
        out += capped(changed)
        out += capped(["  + " + f for f in groups["new"]])
        out += capped(["  - " + f for f in groups["deleted"]])
    out += ["note: " + n for n in notes]
    if approved is not None:
        out.append("approved %d shots as the baseline" % approved)
    for label, path in paths.items():
        out.append("%s: %s" % (label, path))
    out.append("result: " + verdict(code, approved, no_baseline))
    return "\n".join(out)


def pairs_in(directory):
    """(device, lang) folders under an actual/expected tree."""
    if not directory.is_dir():
        return []
    return sorted((d.name, l.name) for d in directory.iterdir() if d.is_dir()
                  for l in d.iterdir() if l.is_dir() and any(l.glob("*.png")))


# ---- the run ----

class Runner:
    def __init__(self, args):
        self.args = args
        self.out = args.out
        self.log_path = self.out / "run.log"
        self.log = None
        self.booted = None  # (platform, id) of the device this run started, for cleanup
        self.emulator = None

    def say(self, line):
        print(line, flush=True)
        self.logline(line)

    def logline(self, line):
        self.log.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), line))
        self.log.flush()

    def run(self, cmd, env=None, timeout=None, check=True, capture=False):
        self.logline("$ " + " ".join(str(c) for c in cmd))
        r = subprocess.run([str(c) for c in cmd], env=env, timeout=timeout, cwd=ROOT,
                           stdout=subprocess.PIPE if capture else self.log, stderr=self.log,
                           stdin=subprocess.DEVNULL, text=True)
        if check and r.returncode != 0:
            raise RunFailed("%s exited %d (see the log)" % (Path(str(cmd[0])).name, r.returncode))
        return r.stdout if capture else r.returncode

    # -- devices --

    def adb(self):
        return shutil.which("adb") or str(sdk_root() / "platform-tools" / "adb")

    def running_devices(self):
        found = []
        try:
            for line in self.run([self.adb(), "devices"], capture=True, check=False).splitlines():
                if line.startswith("emulator-") and "\t" in line:
                    found.append(line.split("\t")[0])
        except FileNotFoundError:
            pass
        if shutil.which("xcrun"):
            data = json.loads(self.run(["xcrun", "simctl", "list", "devices", "booted", "-j"], capture=True))
            found += ["%s (%s)" % (d["name"], d["udid"]) for ds in data["devices"].values() for d in ds
                      if d.get("state") == "Booted"]
        return found

    def check_nothing_running(self):
        if self.args.ignore_running:
            return
        running = self.running_devices()
        if running:
            raise RunFailed("already running: %s; shut it down or pass --ignore-running" % ", ".join(running))

    def simulator_udid(self, name):
        # The same pick as ios_screenshots.sh: the first available simulator of that name.
        data = json.loads(self.run(["xcrun", "simctl", "list", "devices", "available", "-j"], capture=True))
        for runtime, devices in data["devices"].items():
            if "iOS" in runtime:
                for d in devices:
                    if d["name"] == name:
                        return d["udid"]
        raise RunFailed("no available simulator named " + name)

    def boot_android(self, avd):
        serial = "emulator-%d" % ANDROID_PORT
        emulator = shutil.which("emulator") or str(sdk_root() / "emulator" / "emulator")
        self.logline("starting %s on %s" % (avd, serial))
        self.emulator = subprocess.Popen(
            [emulator, "-avd", avd, "-port", str(ANDROID_PORT), "-no-window", "-no-audio",
             "-no-boot-anim", "-no-snapshot-save"],
            stdout=self.log, stderr=self.log, stdin=subprocess.DEVNULL, start_new_session=True)
        self.booted = ("android", serial)
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            if self.emulator.poll() is not None:
                raise RunFailed("the emulator exited while booting (see the log)")
            out = subprocess.run([self.adb(), "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                 capture_output=True, text=True, stdin=subprocess.DEVNULL)
            if out.stdout.strip() == "1":
                break
            time.sleep(3)
        else:
            raise RunFailed("%s did not boot in %ds" % (avd, BOOT_TIMEOUT))
        # Animations off, so a capture never lands mid-transition.
        for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.run([self.adb(), "-s", serial, "shell", "settings", "put", "global", key, "0"])
        return serial

    def shutdown(self):
        if not self.booted:
            return
        platform, ident = self.booted
        self.booted = None
        if self.args.keep_device:
            self.logline("keeping %s running" % ident)
            return
        self.logline("shutting down %s" % ident)
        if platform == "android":
            subprocess.run([self.adb(), "-s", ident, "emu", "kill"], stdout=self.log, stderr=self.log,
                           stdin=subprocess.DEVNULL)
            if self.emulator:
                try:
                    self.emulator.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    self.emulator.kill()
                    self.emulator.wait(timeout=30)
                self.emulator = None
        else:
            subprocess.run(["xcrun", "simctl", "shutdown", ident], stdout=self.log, stderr=self.log,
                           stdin=subprocess.DEVNULL)

    # -- capture --

    def build(self, platform, first_udid=None):
        start = time.time()
        if platform == "android":
            self.run([ROOT / "gradlew", "-q", ":androidApp:assembleDebug"], timeout=40 * 60)
        else:
            # The build ios_screenshots.sh makes, so its SKIP_BUILD=1 then installs it.
            self.run(["xcodebuild", "-quiet", "-project", ROOT / "iosApp" / "FoodTracker.xcodeproj",
                      "-scheme", "FoodTracker", "-configuration", "Debug", "-destination", "id=" + first_udid,
                      "-derivedDataPath", DERIVED, "build"], timeout=40 * 60)
        return time.time() - start

    def capture(self, key, built):
        spec = DEVICES[key]
        dest = self.out / "actual" / key
        env = dict(os.environ, LANGS=" ".join(self.args.langs))
        if spec["platform"] == "android":
            if not built:
                apk = ROOT / "androidApp" / "build" / "outputs" / "apk" / "debug" / "androidApp-debug.apk"
                if not apk.exists():
                    raise RunFailed("no debug APK to reuse; run without --no-build")
            env["ANDROID_SERIAL"] = self.boot_android(spec["avd"])
            cmd = [ROOT / "scripts" / "android_screenshots.sh", dest]
        else:
            udid = self.simulator_udid(spec["name"])
            env.update(UDID=udid, SKIP_BUILD="1", DEVICE_NAME=spec["name"])
            if spec.get("screen"):
                env["SCREEN"] = spec["screen"]
            self.booted = ("ios", udid)
            if spec.get("pose"):
                # Unfold first, and skip the region change's reboot, which folds it again.
                self.run(["xcrun", "simctl", "boot", udid], check=False)
                self.run(["xcrun", "simctl", "bootstatus", udid, "-b"])
                self.run([ROOT / "scripts" / "duo_pose.sh", spec["pose"], udid])
                env["KEEP_REGION"] = "1"
            cmd = [ROOT / "scripts" / "ios_screenshots.sh", dest]
        self.run(cmd, env=env, timeout=CAPTURE_TIMEOUT)
        missing = [l for l in self.args.langs if not any((dest / l).glob("*.png"))]
        if missing:
            raise RunFailed("no screenshots for " + ", ".join(missing))
        return sum(len(list((dest / l).glob("*.png"))) for l in self.args.langs)

    def capture_all(self):
        actual = self.out / "actual"
        shutil.rmtree(actual, ignore_errors=True)
        actual.mkdir(parents=True)
        devices, builds = [], {}
        for platform in ("android", "ios"):
            keys = [k for k in self.args.device_keys if DEVICES[k]["platform"] == platform]
            if not keys:
                continue
            built = False
            if not self.args.no_build:
                try:
                    udid = self.simulator_udid(DEVICES[keys[0]]["name"]) if platform == "ios" else None
                    builds[platform] = self.build(platform, udid)
                    built = True
                    self.say("  %-16s %s" % (platform + " build", fmt_duration(builds[platform])))
                except (RunFailed, subprocess.TimeoutExpired) as e:
                    for k in keys:
                        devices.append(dict(key=k, ok=False, shots=0, seconds=0, error="build: %s" % e))
                    self.say("  %s build failed: %s" % (platform, e))
                    continue
            for key in keys:
                start = time.time()
                try:
                    self.check_nothing_running()
                    shots = self.capture(key, built)
                    devices.append(dict(key=key, ok=True, shots=shots, seconds=0, error=None))
                except (RunFailed, subprocess.TimeoutExpired) as e:
                    devices.append(dict(key=key, ok=False, shots=0, seconds=0, error=str(e)))
                finally:
                    self.shutdown()
                devices[-1]["seconds"] = time.time() - start
                self.say(device_line(devices[-1]))
        return devices, builds

    # -- compare --

    def compare(self, pairs):
        """Runs reg-cli over these (device, lang) pairs; returns reg-cli's JSON."""
        expected_run = self.out / ".expected-run"
        diff, report, reg_json = self.out / "diff", self.out / "report", self.out / "reg.json"
        for path in (expected_run, diff, report):
            shutil.rmtree(path, ignore_errors=True)
        if reg_json.exists():
            reg_json.unlink()
        # Only the baseline of what ran, so a device or language left out isn't "deleted".
        for device, lang in pairs:
            src = self.out / "expected" / device / lang
            if src.is_dir():
                shutil.copytree(src, expected_run / device / lang)
        expected_run.mkdir(parents=True, exist_ok=True)
        report.mkdir(parents=True)
        cmd = ["npx", "--yes", REG_CLI, self.out / "actual", expected_run, diff,
               "-R", report / "index.html", "-J", reg_json, "-M", str(MATCHING_THRESHOLD), "-A",
               "-T", str(self.args.threshold_rate), "-C", "4"]
        self.run(cmd, check=False, timeout=30 * 60)
        if not reg_json.exists():
            raise RunFailed("reg-cli wrote no report (see the log)")
        return json.loads(reg_json.read_text())

    def ratios(self, changed):
        # reg-cli (pixelmatch) paints each differing pixel pure red on a faded grey copy, so the
        # red share of its diff image is the share of pixels that differ.
        from PIL import Image, ImageChops
        result = {}
        for item in changed:
            path = self.out / "diff" / (os.path.splitext(item)[0] + ".png")
            try:
                r, g, b = Image.open(path).convert("RGB").split()
                mask = ImageChops.multiply(ImageChops.multiply(
                    r.point(lambda v: 255 if v == 255 else 0), g.point(lambda v: 255 if v == 0 else 0)),
                    b.point(lambda v: 255 if v == 0 else 0))
                result[item] = mask.histogram()[255] / (mask.width * mask.height)
            except (OSError, ValueError):
                result[item] = None
        return result

    def approve(self, pairs):
        expected = self.out / "expected"
        meta_path = expected / "baseline.json"
        meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
        sha = self.run(["git", "rev-parse", "--short", "HEAD"], capture=True, check=False).strip()
        count = 0
        for device, lang in pairs:
            dest = expected / device / lang
            shutil.rmtree(dest, ignore_errors=True)
            shutil.copytree(self.out / "actual" / device / lang, dest)
            count += len(list(dest.glob("*.png")))
            meta["%s/%s" % (device, lang)] = dict(date=datetime.date.today().isoformat(), commit=sha)
        meta_path.write_text(json.dumps(meta, indent=2, sort_keys=True) + "\n")
        return count

    def main(self):
        self.out.mkdir(parents=True, exist_ok=True)
        self.log = open(self.log_path, "w")
        a = self.args
        devices, builds, groups, ratios, notes, approved = [], {}, None, {}, [], None
        no_baseline = False
        paths = {"log": self.log_path}
        try:
            if a.approve_last:
                pairs = pairs_in(self.out / "actual")
                if not pairs:
                    raise RunFailed("no last run under %s to approve" % (self.out / "actual"))
                approved = self.approve(pairs)
                code = 0
            else:
                if not a.no_capture:
                    print("screenshot-check: %s · %s" % (", ".join(a.device_keys), " ".join(a.langs)), flush=True)
                    devices, builds = self.capture_all()
                    ok = {d["key"] for d in devices if d["ok"]}
                    pairs = [(d, l) for d in a.device_keys if d in ok for l in a.langs]
                else:
                    pairs = [(d, l) for d, l in pairs_in(self.out / "actual")
                             if d in a.device_keys and l in a.langs]
                    if not pairs:
                        raise RunFailed("nothing captured under %s to compare" % (self.out / "actual"))
                failed = [d["key"] for d in devices if not d["ok"]]
                has_baseline = any((self.out / "expected" / d / l).is_dir() for d, l in pairs)
                if pairs and has_baseline:
                    reg = self.compare(pairs)
                    groups = classify(reg)
                    ratios = self.ratios(groups["changed"])
                    paths = {"report": self.out / "report" / "index.html", "summary": self.out / "summary.json",
                             "log": self.log_path}
                    meta_path = self.out / "expected" / "baseline.json"
                    meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
                    notes = stale_baseline_notes(meta, pairs, datetime.date.today().isoformat())
                if not has_baseline and pairs and not a.approve:
                    no_baseline = True
                    shots = sum(len(list((self.out / "actual" / d / l).glob("*.png"))) for d, l in pairs)
                    notes.append("no baseline for these devices and languages yet, so nothing was compared; "
                                 "%d shots are in %s: run with --approve-last to make them the baseline"
                                 % (shots, rel(self.out / "actual")))
                    code = 2
                else:
                    code = result_code(groups or classify({}), failed)
                if a.approve and pairs:
                    approved = self.approve(pairs)
                    code = 2 if failed else 0
        except (RunFailed, subprocess.TimeoutExpired) as e:
            notes.append("run failed: %s" % e)
            code = 2
        except KeyboardInterrupt:
            notes.append("interrupted")
            code = 2
        finally:
            self.shutdown()
        summary = format_summary(groups, ratios, notes, {k: rel(v) for k, v in paths.items()}, code, approved,
                                 no_baseline and code == 2)
        print(summary, flush=True)
        self.logline(summary)
        (self.out / "summary.json").write_text(json.dumps(dict(
            result=code, verdict=verdict(code, approved, no_baseline and code == 2), devices=devices, builds=builds, groups=groups,
            ratios={k: v for k, v in ratios.items()}, notes=notes, approved=approved,
            report=str(self.out / "report" / "index.html")), indent=2, ensure_ascii=False) + "\n")
        self.log.close()
        return code


def rel(path):
    try:
        return str(Path(path).resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def sdk_root():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    return Path.home() / "Library" / "Android" / "sdk"


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    # Ctrl-C and a kill both end in Runner.main's finally, which shuts the device down.
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    signal.signal(signal.SIGHUP, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    return Runner(args).main()


if __name__ == "__main__":
    sys.exit(main())
