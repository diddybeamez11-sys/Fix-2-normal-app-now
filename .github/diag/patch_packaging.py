#!/usr/bin/env python3
"""
Temporary diagnostic helper (NOT part of the product).

Applies a CANDIDATE change to app/build.gradle's packagingOptions in the CI workspace only:
  p0  drop the blanket '/META-INF/**' exclusion completely
  p1  replace it with an explicit list of noise files, keeping META-INF/services/** in the APK
"""
import sys

mode = sys.argv[1]
path = "app/build.gradle"
s = open(path).read()
line = "excludes += '/META-INF/**'"
assert line in s, "blanket META-INF exclusion not found"

if mode == "p0":
    new = "// candidate p0: no META-INF excludes at all"
else:
    pats = [
        "/META-INF/MANIFEST.MF", "/META-INF/*.SF", "/META-INF/*.RSA", "/META-INF/*.DSA", "/META-INF/*.EC",
        "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST",
        "/META-INF/AL2.0", "/META-INF/LGPL2.1", "/META-INF/maven/**", "/META-INF/versions/**",
        "/META-INF/proguard/**", "/META-INF/native-image/**", "/META-INF/*.kotlin_module",
        "/META-INF/*.version", "/META-INF/io.netty.versions.properties", "/META-INF/*.md", "/META-INF/*.txt",
    ]
    new = "// candidate p1: explicit noise excludes, META-INF/services/** is kept\n" + "\n".join(
        "            excludes += '%s'" % p for p in pats)

s = s.replace(line, new, 1)
open(path, "w").write(s)
print(s[s.index("packagingOptions"):][:1500])
