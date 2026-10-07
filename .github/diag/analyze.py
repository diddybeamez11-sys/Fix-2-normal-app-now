#!/usr/bin/env python3
"""
Temporary diagnostic helper (NOT part of the product).

Sub-commands (all print plain text to stdout, never raise to the caller):
  jar      <jar>                         content audit of a (shaded) jar
  reflect  <jar>                         reflection / dynamic-loading hot-spots (R8 sensitive code)
  kotlin   <jar> <kotlin-stdlib.jar>     does the jar link against the given Kotlin stdlib?
  mapping  <mapping.txt> [needle ...]    R8 mapping lookups (obfuscated -> original and back)
  dex      <apk|dex> [android.jar]       dangling type references / broken supertypes in final dex
  annotate <mapping.txt> <textfile>      add [original name] after every obfuscated class name
"""
import collections
import os
import re
import struct
import sys
import zipfile

# ----------------------------------------------------------------------------
# class-file parsing
# ----------------------------------------------------------------------------

def parse_class(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("bad magic")
    _minor, major, n = struct.unpack_from(">HHH", data, 4)
    pos = 10
    cp = [None] * n
    i = 1
    while i < n:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, pos)
            pos += 2
            cp[i] = (1, data[pos:pos + ln].decode("utf-8", "replace"))
            pos += ln
        elif tag in (3, 4):
            cp[i] = (tag,)
            pos += 4
        elif tag in (5, 6):
            cp[i] = (tag,)
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == 15:
            cp[i] = (tag,) + struct.unpack_from(">BH", data, pos)
            pos += 3
        else:
            raise ValueError("bad constant pool tag %d" % tag)
        i += 1
    access, this_i, super_i, ic = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    ifaces = list(struct.unpack_from(">%dH" % ic, data, pos))
    pos += 2 * ic

    def members():
        nonlocal pos
        (cnt,) = struct.unpack_from(">H", data, pos)
        pos += 2
        out = []
        for _ in range(cnt):
            acc, ni, di, ac = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            for _ in range(ac):
                pos += 2
                (ln,) = struct.unpack_from(">I", data, pos)
                pos += 4 + ln
            out.append((acc, cp[ni][1], cp[di][1]))
        return out

    fields = members()
    methods = members()

    def cname(idx):
        return cp[cp[idx][1]][1]

    return {
        "major": major,
        "cp": cp,
        "access": access,
        "this": cname(this_i),
        "super": cname(super_i) if super_i else None,
        "ifaces": [cname(x) for x in ifaces],
        "fields": fields,
        "methods": methods,
    }


def class_refs(ci):
    """(set of referenced class names, list of (owner, name, desc, kind) member refs, set of string constants)"""
    cp = ci["cp"]
    classes = set()
    members = []
    strings = set()
    for e in cp:
        if not e:
            continue
        if e[0] == 7:
            classes.add(cp[e[1]][1])
        elif e[0] in (9, 10, 11):
            owner = cp[cp[e[1]][1]][1]
            nat = cp[e[2]]
            members.append((owner, cp[nat[1]][1], cp[nat[2]][1], e[0]))
        elif e[0] == 8:
            strings.add(cp[e[1]][1])
    return classes, members, strings


def iter_classes(zf, skip_versions=True):
    for n in zf.namelist():
        if not n.endswith(".class"):
            continue
        if skip_versions and n.startswith("META-INF/versions/"):
            continue
        yield n


# ----------------------------------------------------------------------------
# jar audit
# ----------------------------------------------------------------------------

def cmd_jar(path):
    z = zipfile.ZipFile(path)
    names = z.namelist()
    classes = [n for n in names if n.endswith(".class")]
    print("file: %s  size=%d bytes" % (os.path.basename(path), os.path.getsize(path)))
    print("entries=%d  classes=%d" % (len(names), len(classes)))

    c = collections.Counter()
    for n in classes:
        if n.startswith("META-INF/"):
            c["META-INF/versions"] += 1
            continue
        c["/".join(n.split("/")[:3])] += 1
    print("\n-- top packages (3 segments) --")
    for k, v in c.most_common(40):
        print("%6d  %s" % (v, k))

    kot = [n for n in classes if n.startswith("kotlin/")]
    print("\n-- kotlin/ classes inside jar: %d" % len(kot))
    if kot:
        kc = collections.Counter("/".join(n.split("/")[:2]) for n in kot)
        print("   ", dict(kc.most_common(12)))

    print("\n-- assets/ entries --")
    a = [n for n in names if n.startswith("assets/") and not n.endswith("/")]
    print("   %d files" % len(a))
    for n in a[:60]:
        print("   ", n)
    idx = [n for n in a if n.endswith("index.json")]
    for n in idx:
        print("   %s => %s" % (n, z.read(n).decode("utf-8", "replace").strip()[:200]))

    print("\n-- META-INF service / rule files --")
    for n in names:
        if n.startswith("META-INF/services/") and not n.endswith("/"):
            print("   svc:", n, "=>", z.read(n).decode("utf-8", "replace").strip().replace("\n", " | ")[:160])
    for n in names:
        if n.startswith(("META-INF/proguard/", "META-INF/com.android.tools/")) and not n.endswith("/"):
            print("   rule:", n)
    mv = collections.Counter(n.split("/")[2] for n in classes if n.startswith("META-INF/versions/"))
    print("   multi-release classes:", dict(mv))

    print("\n-- class file versions (outside META-INF/versions) --")
    mj = collections.Counter()
    for n in classes:
        if n.startswith("META-INF/"):
            continue
        d = z.read(n)
        mj[struct.unpack_from(">H", d, 6)[0]] += 1
    print("   ", dict(sorted(mj.items())), "(52=Java8, 55=Java11, 61=Java17, 65=Java21)")

    print("\n-- protocol codecs --")
    vers = sorted(
        int(m.group(1))
        for n in classes
        for m in [re.match(r"org/cloudburstmc/protocol/bedrock/codec/v(\d+)/Bedrock_v\1\.class$", n)]
        if m
    )
    print("   %d codec classes, min=%s max=%s" % (len(vers), vers[:1], vers[-1:]))
    print("   has v844:", 844 in vers, " has v2193:", 2193 in vers)
    for k in (
        "dev/sora/relay/MinecraftRelay.class",
        "dev/sora/relay/game/GameSession.class",
        "dev/sora/relay/session/listener/RelayListenerAutoCodec.class",
        "org/cloudburstmc/protocol/bedrock/codec/compat/BedrockCompat.class",
    ):
        print("   %-70s %s" % (k, "present" if k in names else "MISSING"))
    for k in ("dev/sora/relay/MinecraftRelay.class", "dev/sora/relay/game/GameSession.class"):
        if k in names:
            ci = parse_class(z.read(k))
            _cl, _mem, strs = class_refs(ci)
            vs = sorted(s for s in strs if re.fullmatch(r"1\.\d+\.\d+(\.\d+)?", s))
            bed = sorted(x for x in _cl if "Bedrock_v" in x)
            print("   %s: version strings=%s  Bedrock_v* refs=%s" % (k.split("/")[-1], vs, [b.split("/")[-1] for b in bed]))


# ----------------------------------------------------------------------------
# reflection scan
# ----------------------------------------------------------------------------

REFLECT_APIS = {
    ("java/lang/Class", "forName"): "Class.forName",
    ("java/lang/Class", "getDeclaredField"): "getDeclaredField",
    ("java/lang/Class", "getField"): "getField",
    ("java/lang/Class", "getDeclaredMethod"): "getDeclaredMethod",
    ("java/lang/Class", "getMethod"): "getMethod",
    ("java/lang/Class", "getDeclaredConstructor"): "getDeclaredConstructor",
    ("java/lang/Class", "getConstructor"): "getConstructor",
    ("java/lang/Class", "newInstance"): "Class.newInstance",
    ("java/util/ServiceLoader", "load"): "ServiceLoader.load",
    ("java/util/concurrent/atomic/AtomicReferenceFieldUpdater", "newUpdater"): "AtomicRefFieldUpdater",
    ("java/util/concurrent/atomic/AtomicIntegerFieldUpdater", "newUpdater"): "AtomicIntFieldUpdater",
    ("java/util/concurrent/atomic/AtomicLongFieldUpdater", "newUpdater"): "AtomicLongFieldUpdater",
    ("sun/misc/Unsafe", "objectFieldOffset"): "Unsafe.objectFieldOffset",
    ("java/lang/reflect/Proxy", "newProxyInstance"): "Proxy.newProxyInstance",
    ("java/lang/invoke/MethodHandles$Lookup", "findVarHandle"): "findVarHandle",
    ("java/lang/invoke/MethodHandles$Lookup", "findVirtual"): "MH.findVirtual",
    ("java/lang/invoke/MethodHandles$Lookup", "findStatic"): "MH.findStatic",
    ("java/lang/invoke/MethodHandles$Lookup", "findGetter"): "MH.findGetter",
    ("java/lang/invoke/MethodHandles$Lookup", "findConstructor"): "MH.findConstructor",
    ("java/lang/Enum", "valueOf"): "Enum.valueOf(Class,String)",
}

KEPT_PREFIXES = (  # packages kept by proguard-rules.pro at the time of writing
    "io/netty/", "kotlinx/", "org/luaj/", "org/cloudburstmc/netty/", "coelho/msftauth/api/",
)


def pkg3(n):
    return "/".join(n.split("/")[:3])


def cmd_reflect(path):
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    jar_classes = {n[:-6] for n in names if n.endswith(".class") and not n.startswith("META-INF/")}
    by_pkg = collections.defaultdict(lambda: collections.Counter())
    samples = {}
    lit = collections.defaultdict(list)  # referencing class -> [string literal naming a class of this jar]
    pat = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
    errors = 0
    for n in iter_classes(z):
        try:
            ci = parse_class(z.read(n))
        except Exception:
            errors += 1
            continue
        _cl, mem, strs = class_refs(ci)
        for owner, name, _d, _k in mem:
            api = REFLECT_APIS.get((owner, name))
            if api:
                by_pkg[pkg3(n)][api] += 1
                samples.setdefault((pkg3(n), api), n[:-6])
        for s in strs:
            if pat.match(s) and s.replace(".", "/") in jar_classes and "/" not in s:
                lit[n[:-6]].append(s)
    print("scanned jar: %s (parse errors: %d)" % (os.path.basename(path), errors))
    print("\n-- reflection / dynamic-loading API use by package (R8 hot-spots) --")
    rows = sorted(by_pkg.items(), key=lambda kv: -sum(kv[1].values()))
    for p, c in rows[:60]:
        kept = " [KEPT by rules]" if p.startswith(KEPT_PREFIXES) else ""
        print("%-38s %s%s" % (p, dict(c), kept))
    print("\n-- string constants that name a class of this jar (breaks when the class is renamed) --")
    shown = 0
    for k, v in sorted(lit.items()):
        if k.startswith(KEPT_PREFIXES):
            continue
        tgt = [x for x in v if not x.replace(".", "/").startswith(KEPT_PREFIXES)]
        if tgt:
            print("%s -> %s" % (k, sorted(set(tgt))[:6]))
            shown += 1
            if shown >= 120:
                print("... (truncated)")
                break
    if shown == 0:
        print("(none)")


# ----------------------------------------------------------------------------
# Kotlin stdlib link check
# ----------------------------------------------------------------------------

def cmd_kotlin(jar_path, stdlib_path):
    zs = zipfile.ZipFile(stdlib_path)
    std = {}
    for n in zs.namelist():
        if n.endswith(".class") and n.startswith("kotlin/"):
            try:
                ci = parse_class(zs.read(n))
            except Exception:
                continue
            std[ci["this"]] = ci
    print("stdlib: %s -> %d kotlin/* classes" % (os.path.basename(stdlib_path), len(std)))

    def resolves(owner, name, desc, seen=None):
        ci = std.get(owner)
        if not ci:
            return owner == "java/lang/Object" or None
        for _a, mn, md in ci["methods"] + ci["fields"]:
            if mn == name and md == desc:
                return True
        seen = seen or set()
        for sup in [ci["super"]] + ci["ifaces"]:
            if sup and sup not in seen:
                seen.add(sup)
                if sup.startswith("kotlin/") or sup in std:
                    if resolves(sup, name, desc, seen):
                        return True
        return False

    z = zipfile.ZipFile(jar_path)
    missing_cls = collections.defaultdict(set)
    missing_mem = collections.defaultdict(set)
    total_refs = 0
    for n in iter_classes(z):
        if n.startswith("kotlin/"):
            continue
        try:
            ci = parse_class(z.read(n))
        except Exception:
            continue
        cl, mem, _s = class_refs(ci)
        for c in cl:
            if c.startswith("kotlin/") and c not in std:
                missing_cls[c].add(n[:-6])
        for owner, name, desc, _k in mem:
            if owner.startswith("kotlin/"):
                total_refs += 1
                if owner in std and not resolves(owner, name, desc):
                    missing_mem["%s.%s%s" % (owner, name, desc)].add(n[:-6])
    print("kotlin member refs checked: %d" % total_refs)
    print("\n-- kotlin classes referenced but NOT in stdlib: %d --" % len(missing_cls))
    for c, who in sorted(missing_cls.items())[:60]:
        print("  %s   <- %s" % (c, sorted(who)[:2]))
    print("\n-- kotlin members referenced but NOT in stdlib: %d --" % len(missing_mem))
    for c, who in sorted(missing_mem.items())[:60]:
        print("  %s   <- %s" % (c, sorted(who)[:2]))


# ----------------------------------------------------------------------------
# R8 mapping
# ----------------------------------------------------------------------------

def load_mapping(path, want_members_for=()):
    obf2orig, orig2obf, members = {}, {}, {}
    cur = None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if line[:1] not in (" ", "\t", "\n"):
                m = re.match(r"^(\S+) -> (\S+):\s*$", line)
                if m:
                    o, ob = m.group(1), m.group(2)
                    obf2orig[ob] = o
                    orig2obf[o] = ob
                    cur = ob if ob in want_members_for else None
                    if cur:
                        members[cur] = []
                continue
            if cur:
                members[cur].append(line.strip())
    return obf2orig, orig2obf, members


def cmd_mapping(path, needles):
    needles = list(needles) or ["M2.g"]
    o2o, orig2obf, members = load_mapping(path, want_members_for=set(needles))
    print("mapping: %s  classes=%d" % (os.path.basename(path), len(o2o)))
    renamed = sum(1 for o, b in orig2obf.items() if o != b)
    print("renamed=%d  kept-as-is=%d" % (renamed, len(orig2obf) - renamed))
    for nd in needles:
        print("\n== %s ==" % nd)
        if nd in o2o:
            print("original class: %s" % o2o[nd])
            for m in members.get(nd, [])[:60]:
                print("    ", m)
        else:
            print("(no class with this obfuscated name)")
    pk = needles[0].rsplit(".", 1)[0] if "." in needles[0] else needles[0]
    sib = sorted((ob, o) for ob, o in o2o.items() if ob.startswith(pk + "."))
    print("\n== all classes in obfuscated package '%s' (%d) ==" % (pk, len(sib)))
    for ob, o in sib[:120]:
        print("  %-14s %s" % (ob, o))

    keys = [
        r"^dev\.sora\.protohax\.relay\.MinecraftRelay(\$.*)?$",
        r"^dev\.sora\.relay\.MinecraftRelay(\$.*)?$",
        r"^dev\.sora\.relay\.game\.GameSession$",
        r"^dev\.sora\.relay\.game\.entity\.EntityLocalPlayer$",
        r"^dev\.sora\.relay\.game\.world\.Level$",
        r"^dev\.sora\.relay\.game\.management\.BlobCacheManager$",
        r"^dev\.sora\.relay\.cheat\.module\.ModuleManager$",
        r"^dev\.sora\.relay\.game\.registry\.(Item|Block)Mapping\$Provider$",
        r"^dev\.sora\.relay\.game\.registry\.MappingProvider$",
        r"^dev\.sora\.relay\.session\.listener\.RelayListenerAutoCodec(\$.*)?$",
        r"^org\.cloudburstmc\.protocol\.bedrock\.codec\.v(844|2193)\.Bedrock_v\d+$",
        r"^org\.cloudburstmc\.protocol\.bedrock\.codec\.compat\.BedrockCompat$",
        r"^dev\.sora\.protohax\.relay\.service\.AppService$",
        r"^dev\.sora\.protohax\.MyApplication(\$.*)?$",
        r"^dev\.sora\.protohax\.ui\.overlay\.OverlayManager$",
    ]
    print("\n== key classes: original -> obfuscated ==")
    for pat in keys:
        rx = re.compile(pat)
        for o, ob in sorted(orig2obf.items()):
            if rx.match(o):
                print("  %-85s -> %s" % (o, ob))

    stats = collections.defaultdict(lambda: [0, 0])
    for o, ob in orig2obf.items():
        for pfx in ("dev.sora.protohax", "dev.sora.relay", "org.cloudburstmc.protocol", "org.cloudburstmc.netty",
                    "io.netty", "kotlin.", "kotlinx.", "com.google.common", "org.bouncycastle", "org.jose4j",
                    "org.apache.http", "okhttp3", "it.unimi", "com.google.gson", "coelho.msftauth", "org.luaj",
                    "libmitm", "go."):
            if o.startswith(pfx):
                stats[pfx][0] += 1
                if o == ob:
                    stats[pfx][1] += 1
    print("\n== per-package: total classes / kept-with-original-name ==")
    for k, (t, kept) in sorted(stats.items()):
        print("  %-28s total=%-6d kept=%d" % (k, t, kept))


def cmd_annotate(mapping_path, text_path):
    o2o, _, _ = load_mapping(mapping_path)
    txt = open(text_path, "r", encoding="utf-8", errors="replace").read()
    tok = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$<>]+)+")

    def sub(m):
        t = m.group(0)
        parts = t.split(".")
        for k in range(len(parts), 0, -1):
            cand = ".".join(parts[:k])
            if cand in o2o and o2o[cand] != cand:
                rest = ".".join(parts[k:])
                return "%s{=%s%s}" % (t, o2o[cand], ("." + rest) if rest else "")
        return t

    sys.stdout.write(tok.sub(sub, txt))


# ----------------------------------------------------------------------------
# DEX analysis
# ----------------------------------------------------------------------------

def uleb(b, p):
    r = 0
    s = 0
    while True:
        x = b[p]
        p += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, p
        s += 7


def parse_dex(b):
    if b[:4] != b"dex\n":
        raise ValueError("not a dex")
    (n_str, o_str, n_type, o_type, n_proto, _op, n_field, _of, n_meth, _om, n_cls, o_cls) = struct.unpack_from("<12I", b, 0x38)

    def string(i):
        (off,) = struct.unpack_from("<I", b, o_str + 4 * i)
        _l, p = uleb(b, off)
        e = b.index(b"\x00", p)
        return b[p:e].decode("utf-8", "replace")

    types = [string(struct.unpack_from("<I", b, o_type + 4 * i)[0]) for i in range(n_type)]
    classes = []
    for i in range(n_cls):
        cidx, acc, sidx, ioff, _src, _ann, _dat, _sv = struct.unpack_from("<8I", b, o_cls + 32 * i)
        ifs = []
        if ioff:
            (sz,) = struct.unpack_from("<I", b, ioff)
            ifs = [types[x] for x in struct.unpack_from("<%dH" % sz, b, ioff + 4)]
        classes.append((types[cidx], acc, types[sidx] if sidx != 0xFFFFFFFF else None, ifs))
    return {"n_str": n_str, "n_type": n_type, "n_proto": n_proto, "n_field": n_field, "n_meth": n_meth,
            "n_cls": n_cls, "types": types, "classes": classes, "size": len(b)}


FW_PREFIXES = ("Ljava/", "Ljavax/", "Ldalvik/", "Landroid/", "Lorg/w3c/", "Lorg/xml/", "Lorg/json/", "Lorg/xmlpull/",
               "Lsun/", "Ljdk/", "Llibcore/", "Lcom/android/", "Lcom/sun/", "Ljunit/", "Lorg/apache/harmony/",
               "Lorg/ietf/", "Lorg/apache/http/")


def cmd_dex(path, android_jar=None, mapping=None):
    dexes = []
    if path.endswith(".dex"):
        dexes.append((os.path.basename(path), open(path, "rb").read()))
    else:
        z = zipfile.ZipFile(path)
        for n in sorted(z.namelist()):
            if re.fullmatch(r"classes\d*\.dex", n):
                dexes.append((n, z.read(n)))
    fw = set()
    if android_jar and os.path.exists(android_jar):
        for n in zipfile.ZipFile(android_jar).namelist():
            if n.endswith(".class"):
                fw.add("L" + n[:-6] + ";")
    o2o = {}
    if mapping and os.path.exists(mapping):
        o2o = load_mapping(mapping)[0]

    defined = {}
    referenced = set()
    tot = collections.Counter()
    for name, b in dexes:
        d = parse_dex(b)
        print("%-14s size=%-9d classes=%-6d types=%-6d methods=%-6d fields=%-6d strings=%d"
              % (name, d["size"], d["n_cls"], d["n_type"], d["n_meth"], d["n_field"], d["n_str"]))
        for k in ("n_cls", "n_type", "n_meth", "n_field"):
            tot[k] += d[k]
        for t in d["types"]:
            referenced.add(t)
        for (t, acc, sup, ifs) in d["classes"]:
            defined[t] = (sup, ifs, name)
    print("TOTAL: classes=%d  methods=%d  fields=%d" % (tot["n_cls"], tot["n_meth"], tot["n_field"]))

    def elem(t):
        return t.lstrip("[")

    prim = set("VZBSCIJFD")
    dangling = set()
    for t in referenced:
        e = elem(t)
        if len(e) == 1 and e in prim:
            continue
        if e in defined:
            continue
        dangling.add(e)

    def is_fw(t):
        return t in fw or t.startswith(FW_PREFIXES)

    jdk_missing = sorted(t for t in dangling if t.startswith(("Ljava/", "Ljavax/")) and fw and t not in fw)
    other = sorted(t for t in dangling if not is_fw(t))
    print("\ntype refs: %d distinct; dangling(not defined in dex): %d; of which platform-ish: %d; NOT platform: %d"
          % (len(referenced), len(dangling), len(dangling) - len(other), len(other)))

    print("\n== dangling types that are NOT platform classes (would give NoClassDefFoundError if executed): %d ==" % len(other))
    grp = collections.Counter()
    for t in other:
        grp[".".join(t[1:].rstrip(";").split("/")[:3])] += 1
    for k, v in grp.most_common(60):
        print("  %5d  %s" % (v, k))
    print("-- full list (first 400) --")
    for t in other[:400]:
        print("  ", t[1:-1].replace("/", "."))

    print("\n== java/javax classes referenced but absent from android.jar (JDK-only): %d ==" % len(jdk_missing))
    for t in jdk_missing[:150]:
        print("  ", t[1:-1].replace("/", "."))

    print("\n== classes whose SUPERCLASS or INTERFACE is neither in the dex nor a platform class ==")
    bad = 0
    for t, (sup, ifs, dn) in sorted(defined.items()):
        miss = [x for x in ([sup] if sup else []) + ifs if x not in defined and not is_fw(x)]
        if miss:
            bad += 1
            if bad <= 80:
                print("  %s (%s) extends/implements missing %s   orig=%s" % (
                    t[1:-1].replace("/", "."), dn, [m[1:-1].replace("/", ".") for m in miss],
                    o2o.get(t[1:-1].replace("/", "."), "?")))
    print("  total broken-supertype classes: %d" % bad)


# ----------------------------------------------------------------------------

def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[1], argv[2:]
    try:
        if cmd == "jar":
            cmd_jar(*args)
        elif cmd == "reflect":
            cmd_reflect(*args)
        elif cmd == "kotlin":
            cmd_kotlin(*args)
        elif cmd == "mapping":
            cmd_mapping(args[0], args[1:])
        elif cmd == "annotate":
            cmd_annotate(*args)
        elif cmd == "dex":
            cmd_dex(args[0], args[1] if len(args) > 1 else None, args[2] if len(args) > 2 else None)
        else:
            print(__doc__)
            return 2
    except Exception as e:  # never break the workflow
        import traceback
        print("ANALYZER ERROR in %s: %r" % (cmd, e))
        traceback.print_exc(file=sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
