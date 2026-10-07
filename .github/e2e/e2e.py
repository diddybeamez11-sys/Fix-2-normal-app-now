#!/usr/bin/env python3
"""
Temporary end-to-end check (NOT part of the product).

Black-box test of the UNMODIFIED release APK on an emulator, driving the real UI the way the user does:
install -> pick the target app -> press Connect -> the floating icon must appear -> tap the icon -> the
menu must open -> Disconnect -> Connect again. Every state is captured (screenshot, window list, logcat).

Runs on the host inside android-emulator-runner (adb is on PATH, a device is booted).
"""
import base64
import glob
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

WS = os.environ.get("GITHUB_WORKSPACE", ".")
OUT = os.path.join(WS, "diag-out")
os.makedirs(OUT, exist_ok=True)
PKG = "dev.sora.protohax"
TARGET = "com.android.settings"
LOG = open(os.path.join(OUT, "10-e2e-log.txt"), "w")
RESULTS = []


def log(msg):
    print(msg, flush=True)
    LOG.write(str(msg) + "\n")
    LOG.flush()


def adb(*args, timeout=90, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if binary:
        return r.stdout
    return (r.stdout + r.stderr).decode("utf-8", "replace")


def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    log("%s  %s %s" % ("PASS" if ok else "FAIL", name, ("- " + detail) if detail else ""))


def screenshot(tag):
    try:
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        open(os.path.join(OUT, "shot-%s.png" % tag), "wb").write(png)
        from PIL import Image
        im = Image.open(io.BytesIO(png)).convert("RGB")
        w = 400
        im = im.resize((w, int(im.height * w / im.width)))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=50)
        open(os.path.join(OUT, "5%d-shot-%s.txt" % (len(glob.glob(OUT + "/5?-shot-*.txt")), tag)), "w").write(
            base64.b64encode(buf.getvalue()).decode())
        log("screenshot %s: %d bytes png -> %d bytes jpeg" % (tag, len(png), len(buf.getvalue())))
    except Exception as e:  # noqa
        log("screenshot %s failed: %r" % (tag, e))


def ui_nodes():
    """all nodes of the current uiautomator hierarchy: (text, content-desc, clickable, bounds, class)"""
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        xml = sh("cat /sdcard/ui.xml")
        if "<hierarchy" in xml:
            break
        time.sleep(2)
    else:
        return []
    nodes = []
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml[xml.index("<hierarchy"):])
    except Exception as e:  # noqa
        log("ui dump parse error: %r" % e)
        return []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            nodes.append((n.get("text", ""), n.get("content-desc", ""), n.get("clickable", ""),
                          tuple(int(x) for x in m.groups()), n.get("class", "")))
    return nodes


def find_node(label, nodes):
    for text, desc, clickable, b, cls in nodes:
        if label.lower() in (text.lower(), desc.lower()):
            return b
    return None


def tap(b):
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    sh("input tap %d %d" % (x, y))
    return x, y


def windows():
    """summary of every window of the app: title, type, flags, frame, ready"""
    raw = sh("dumpsys window windows", timeout=120)
    out = []
    for blk in re.split(r"\n  Window #", raw):
        head = re.match(r"\d+ Window\{[0-9a-f]+ u\d+ ([^}]*)\}", blk)
        if not head or PKG not in head.group(1) and "package=" + PKG not in blk[:600]:
            continue
        ty = re.search(r"ty=(\w+)", blk)
        fl = re.search(r" fl=([^\n}]*?)(?: pfl=| \w+=|\}|\n)", blk)
        fr = re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", blk)
        ready = re.search(r"isReadyForDisplay\(\)=(\w+)", blk)
        vis = re.search(r"mViewVisibility=(0x\w+)", blk)
        out.append({
            "title": head.group(1), "type": ty.group(1) if ty else "?", "flags": (fl.group(1).strip() if fl else "?"),
            "frame": tuple(int(x) for x in fr.groups()) if fr else None,
            "ready": ready.group(1) if ready else "?", "vis": vis.group(1) if vis else "?",
        })
    return out


def show_windows(tag):
    ws = windows()
    lines = ["== app windows (%s) ==" % tag]
    for w in ws:
        lines.append("  %-28s type=%-22s ready=%-5s vis=%s frame=%s flags=%s" % (
            w["title"][:28], w["type"], w["ready"], w["vis"], w["frame"], w["flags"][:70]))
    log("\n".join(lines))
    return ws


def logcat_lines(pattern):
    txt = adb("logcat", "-d", "-v", "threadtime", timeout=120)
    return [l for l in txt.splitlines() if re.search(pattern, l)]


def wait_for_log(pattern, seconds):
    end = time.time() + seconds
    while time.time() < end:
        hits = logcat_lines(pattern)
        if hits:
            return hits
        time.sleep(2)
    return []


def vpn_up():
    ip = sh("ip -o addr show")
    return [l for l in ip.splitlines() if "tun" in l]


def service_state():
    s = sh("dumpsys activity services %s" % PKG)
    return ("AppService" in s, "isForeground=true" in s)


def main():
    apk = sorted(glob.glob(os.path.join(WS, "apk", "*.apk")))[0]
    log("apk: %s" % apk)
    log(sh("getprop ro.build.version.sdk; getprop ro.build.type; getprop ro.product.cpu.abi"))

    # ---- 0. root (only needed to preselect the target app in the app's private preferences) --------
    adb("root", timeout=60)
    adb("wait-for-device", timeout=120)
    time.sleep(3)
    uid_line = sh("id")
    rooted = "uid=0" in uid_line
    check("adb root available (to preselect the target app)", rooted, uid_line.strip())
    sh("logcat -G 16M")
    adb("logcat", "-c")

    # ---- 1. install + consents ----------------------------------------------------------------------
    out = adb("install", "-r", "-g", apk, timeout=300)
    check("APK installs", "Success" in out, out.strip()[-80:])
    sh("appops set %s SYSTEM_ALERT_WINDOW allow" % PKG)
    sh("appops set %s ACTIVATE_VPN allow" % PKG)
    log("appops: " + sh("appops get %s SYSTEM_ALERT_WINDOW; appops get %s ACTIVATE_VPN" % (PKG, PKG)).strip().replace("\n", " | "))

    # ---- 2. first start creates the data dir; then preselect the target application --------------------
    sh("am start -W -n %s/.ui.activities.MainActivity" % PKG)
    time.sleep(6)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    if rooted:
        uid = sh("stat -c %%u /data/data/%s" % PKG).strip()
        prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                 "    <string name=\"TARGET_PACKAGE\">%s</string>\n</map>\n" % TARGET)
        open("/tmp/ProtoHax_Caches.xml", "w").write(prefs)
        adb("push", "/tmp/ProtoHax_Caches.xml", "/data/local/tmp/ProtoHax_Caches.xml")
        ctx = sh("ls -Zd /data/data/%s" % PKG).split()[0]          # incl. the per-app MCS categories
        sh("mkdir -p /data/data/%s/shared_prefs && cp /data/local/tmp/ProtoHax_Caches.xml /data/data/%s/shared_prefs/ProtoHax_Caches.xml"
           " && chown -R %s:%s /data/data/%s/shared_prefs && chmod 771 /data/data/%s/shared_prefs"
           " && chmod 660 /data/data/%s/shared_prefs/ProtoHax_Caches.xml && chcon -R %s /data/data/%s/shared_prefs"
           % (PKG, PKG, uid, uid, PKG, PKG, PKG, ctx, PKG))
        log("prefs now: " + sh("cat /data/data/%s/shared_prefs/ProtoHax_Caches.xml" % PKG).strip().replace("\n", " "))
        log("labels: " + sh("ls -lZ /data/data/%s/shared_prefs" % PKG).strip().replace("\n", " | "))

    # ---- 3. launch the real UI and press Connect -------------------------------------------------------
    sh("am start -W -n %s/.ui.activities.MainActivity" % PKG)
    time.sleep(8)
    nodes = ui_nodes()
    texts = [t or d for t, d, c, b, k in nodes if (t or d)]
    log("dashboard texts: %s" % texts[:40])
    screenshot("1-dashboard")
    b = find_node("Connect", nodes)
    check("dashboard shows the Connect button", b is not None, "bounds=%s" % (b,))
    check("dashboard shows the preselected application (not 'No application selected')",
          not any("No application selected" in t for t in texts))
    if b is None:
        return finish()
    log("tap Connect at %s" % (tap(b),))

    # ---- 4. the service must come up: VPN, netstack, overlay, relay ------------------------------------
    hits = wait_for_log(r"overlay GUI created|start overlay|establish VPN failed|Failed to start|no target application", 45)
    log("service log hits: %s" % [h[-110:] for h in hits][:6])
    time.sleep(6)
    check("log: netstack started", bool(logcat_lines(r"netstack started")))
    check("log: overlay GUI created", bool(logcat_lines(r"overlay GUI created")))
    relay = logcat_lines(r"relay started|start relay|announce")
    log("relay log: %s" % [h[-120:] for h in relay][:5])
    check("log: relay started", any("relay started" in h for h in relay))
    check("no crash so far", not logcat_lines(r"FATAL EXCEPTION|AndroidRuntime: Process: " + re.escape(PKG)))
    has_service, is_fg = service_state()
    check("AppService is running in the foreground", has_service and is_fg, "running=%s foreground=%s" % (has_service, is_fg))
    tun = vpn_up()
    check("VPN tunnel (tun) is established", bool(tun), str(tun)[:120])
    ws = show_windows("after Connect")
    overlay = [w for w in ws if w["type"] == "APPLICATION_OVERLAY"]
    check("overlay windows exist", len(overlay) >= 1, "%d overlay window(s)" % len(overlay))
    screenshot("2-after-connect")

    # ---- 5. tap the floating icon -> the menu must open -------------------------------------------------
    icon = [w for w in overlay if w["frame"] and w["frame"][2] - w["frame"][0] < 400 and w["frame"][3] - w["frame"][1] < 400]
    icon.sort(key=lambda w: (w["frame"][2] - w["frame"][0]) * (w["frame"][3] - w["frame"][1]))
    if icon:
        f = icon[0]["frame"]
        spot = ((f[0] + f[2]) // 2, (f[1] + f[3]) // 2)
    else:
        spot = (40, 140)
    check("floating icon window found", bool(icon), "frame=%s" % (icon[0]["frame"] if icon else None))
    log("tap floating icon at %s" % (spot,))
    sh("input tap %d %d" % spot)
    time.sleep(4)
    ws2 = show_windows("after tapping the icon")
    screenshot("3-menu-open")
    menu_nodes = ui_nodes()
    menu_texts = [t or d for t, dsc, c, b2, k in menu_nodes for d in [dsc] if (t or d)]
    log("texts visible after tapping the icon (%d): %s" % (len(menu_texts), menu_texts[:60]))
    full = [w for w in ws2 if w["frame"] and (w["frame"][2] - w["frame"][0]) > 800 and w["type"] == "APPLICATION_OVERLAY"]
    check("a full-screen overlay (the menu) is visible after the tap",
          any(w["ready"] == "true" for w in full), "%d full-screen overlay window(s)" % len(full))
    cat_hits = [t for t in menu_texts if t.lower() in ("combat", "movement", "visual", "misc", "world", "player", "fly", "killaura", "speed")]
    check("the menu lists modules / categories", len(cat_hits) > 0 or len(menu_texts) > 8, "matched=%s" % cat_hits[:8])
    crash = logcat_lines(r"FATAL EXCEPTION|AndroidRuntime: Process: " + re.escape(PKG))
    check("no crash after opening the menu", not crash, str(crash[:2])[:200])

    # tap the icon again -> toggles the menu closed
    sh("input tap %d %d" % spot)
    time.sleep(3)
    screenshot("4-menu-closed")
    ws3 = show_windows("after tapping the icon again")

    # ---- 6. Disconnect, then Connect again (teardown / second session) --------------------------------------
    sh("am start -W -n %s/.ui.activities.MainActivity" % PKG)
    time.sleep(4)
    nodes = ui_nodes()
    b = find_node("Disconnect", nodes)
    check("dashboard offers Disconnect while connected", b is not None, "bounds=%s" % (b,))
    if b is not None:
        tap(b)
        time.sleep(7)
        ws4 = show_windows("after Disconnect")
        left = [w for w in ws4 if w["type"] == "APPLICATION_OVERLAY"]
        check("overlay windows are removed after Disconnect", len(left) == 0, "%d left" % len(left))
        check("VPN tunnel is gone after Disconnect", not vpn_up(), str(vpn_up())[:100])
        screenshot("5-after-disconnect")
        nodes = ui_nodes()
        b = find_node("Connect", nodes)
        check("dashboard offers Connect again", b is not None)
        if b is not None:
            tap(b)
            time.sleep(14)
            ws5 = show_windows("after the second Connect")
            ov = [w for w in ws5 if w["type"] == "APPLICATION_OVERLAY"]
            small = [w for w in ov if w["frame"] and w["frame"][2] - w["frame"][0] < 400]
            check("second session: exactly one floating icon", len(small) == 1, "%d icon window(s)" % len(small))
            check("second session: VPN tunnel is up", bool(vpn_up()))
            screenshot("6-second-session")
            check("no crash in the second session", not logcat_lines(r"FATAL EXCEPTION|AndroidRuntime: Process: " + re.escape(PKG)))
    finish()


def finish():
    txt = adb("logcat", "-d", "-v", "threadtime", timeout=120)
    open(os.path.join(OUT, "logcat_full.log"), "w").write(txt)
    keep = [l for l in txt.splitlines() if re.search(
        r"AndroidRuntime|FATAL|ProtoHax|dev\.sora\.protohax|System\.err|NoClassDef|ExceptionInInit|VerifyError|Rejecting class|libmitm|gojni|WindowManager.*protohax", l)]
    open(os.path.join(OUT, "11-e2e-logcat-filtered.txt"), "w").write("\n".join(keep[-600:]))
    ok = all(r[1] for r in RESULTS)
    lines = ["E2E RESULT: %s  (%d checks, %d failed)" % ("ALL PASS" if ok else "FAILURES", len(RESULTS), sum(1 for r in RESULTS if not r[1]))]
    lines += ["  %s  %s  %s" % ("PASS" if r[1] else "FAIL", r[0], r[2]) for r in RESULTS]
    open(os.path.join(OUT, "00-summary.txt"), "w").write("\n".join(lines) + "\n")
    log("\n".join(lines))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa
        import traceback
        log("DRIVER ERROR: %r\n%s" % (e, traceback.format_exc()))
        finish()
