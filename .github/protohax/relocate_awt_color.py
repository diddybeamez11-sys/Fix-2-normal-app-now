#!/usr/bin/env python3
"""
Relocate every reference to java.awt.Color in the shaded ProtoHax jar to dev.sora.relay.compat.Color.

Android has no java.awt, yet the Cloudburst protocol classes bundled into ProtoHax use java.awt.Color on
mandatory paths (SerializedSkin.<clinit>, player list / biome definition serializers, skin read/write ...).
dev.sora.relay.compat.Color (.github/protohax/Color.java, compiled into the jar by the ProtoHax build)
provides the members they use with the JDK class' exact semantics.

Why not Shadow's relocate? ProtoHax pins Shadow 8.0.0, whose ASM (9.4) cannot read the Java 21 class files
ProtoHax is compiled to ("Unsupported class file major version 65"); Java 21 support arrived in Shadow 8.3.0.

How: only CONSTANT_Utf8 entries are rewritten (class names, field/method descriptors, generic signatures).
No constant is added or removed, so every constant-pool index - and therefore every other part of the class
file - stays valid, and nothing has to understand the bytecode.

usage: relocate_awt_color.py <ProtoHax jar>        (rewrites the jar in place)
"""
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

OLD = b"java/awt/Color"
NEW = b"dev/sora/relay/compat/Color"
# whole identifier only: never touch e.g. java/awt/ColorModel
TOKEN = re.compile(re.escape(OLD) + rb"(?![A-Za-z0-9_$])")


def rewrite_class(data):
    """returns the rewritten class file, or None if it does not reference java.awt.Color"""
    if OLD not in data:
        return None
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    (count,) = struct.unpack_from(">H", data, 8)
    out = bytearray(data[:10])
    pos = 10
    index = 1
    changed = 0
    while index < count:
        tag = data[pos]
        if tag == 1:  # CONSTANT_Utf8
            (length,) = struct.unpack_from(">H", data, pos + 1)
            raw = data[pos + 3: pos + 3 + length]
            new, n = TOKEN.subn(NEW, raw)
            if n:
                if len(new) > 0xFFFF:
                    raise ValueError("constant too long after relocation")
                changed += n
            out += b"\x01" + struct.pack(">H", len(new)) + new
            pos += 3 + length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            out += data[pos:pos + 5]
            pos += 5
        elif tag in (5, 6):  # long / double take two constant-pool slots
            out += data[pos:pos + 9]
            pos += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            out += data[pos:pos + 3]
            pos += 3
        elif tag == 15:
            out += data[pos:pos + 4]
            pos += 4
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        index += 1
    out += data[pos:]
    return bytes(out) if changed else None


def main():
    path = sys.argv[1]
    fd, tmp = tempfile.mkstemp(suffix=".jar", dir=os.path.dirname(os.path.abspath(path)))
    os.close(fd)
    rewritten = 0
    try:
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w") as dst:
            for info in src.infolist():
                data = src.read(info.filename)
                if info.filename.endswith(".class") and not info.filename.startswith("META-INF/"):
                    new = rewrite_class(data)
                    if new is not None:
                        data = new
                        rewritten += 1
                dst.writestr(info, data, compress_type=info.compress_type)
        shutil.move(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    print("relocated java.awt.Color -> dev.sora.relay.compat.Color in %d classes of %s" % (rewritten, os.path.basename(path)))
    if rewritten == 0:
        sys.exit("no class referenced java.awt.Color - the bundled protocol library changed, review this step")


if __name__ == "__main__":
    main()
