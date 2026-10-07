#!/usr/bin/env bash
# Temporary diagnostic helper (NOT part of the product).
# Collects every static analysis of ONE finished build variant into $OUT/NN-*.txt
set +e
: "${OUT:?}" "${NAME:?}" "${RUNNER_TEMP:?}"
A="python3 .github/diag/analyze.py"
APK=app/build/outputs/apk/release/app-release.apk
MAPDIR=app/build/outputs/mapping/release
PRE="$RUNNER_TEMP/prestrip.jar"
FW="$ANDROID_HOME/platforms/android-34/android.jar"
GCACHE=~/.gradle/caches/modules-2/files-2.1

echo "variant=$NAME"
ls -la "$MAPDIR" "$(dirname "$APK")" 2>&1 | head -30

# ---- 10/11/12: jar audits --------------------------------------------------
$A jar "$PRE"                          > "$OUT/10-jar-prestrip.txt" 2>&1
$A jar app/libs/ProtoHax-1.4.0.jar     > "$OUT/11-jar-final.txt"    2>&1
$A reflect app/libs/ProtoHax-1.4.0.jar > "$OUT/12-reflect.txt"      2>&1

# ---- 13: does the stripped jar link against the Kotlin stdlib versions that exist? -------------
{
  for STD in $(find "$GCACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-[0-9]*.jar' ! -name '*sources*' 2>/dev/null | sort -V); do
    echo "################ $STD"
    $A kotlin app/libs/ProtoHax-1.4.0.jar "$STD" 2>&1 | head -120
  done
} > "$OUT/13-kotlin-compat.txt" 2>&1

# ---- 20/21: R8 mapping + effective configuration -----------------------------------------------
if [ -f "$MAPDIR/mapping.txt" ]; then
  $A mapping "$MAPDIR/mapping.txt" M2.g > "$OUT/20-mapping.txt" 2>&1
else
  echo "no mapping.txt produced" > "$OUT/20-mapping.txt"
fi
{
  echo "--- R8 side files ---"; ls -la "$MAPDIR"
  echo; echo "--- sections merged into configuration.txt (origin of every rule block) ---"
  grep -n -E "^# The proguard configuration file for the following section is" "$MAPDIR/configuration.txt" 2>/dev/null \
    | sed -E 's#/home/runner/.gradle/caches/[^ ]*/transformed/##' | head -90
  echo; echo "--- notable directives in the merged configuration ---"
  grep -n -E "^-(dontwarn|dontshrink|dontobfuscate|dontoptimize|ignorewarnings|adapt|keepattributes|repackageclasses|keeppackagenames|flattenpackagehierarchy|keep(classmembers|names|classmembernames)? .*(netty|kotlinx|luaj|cloudburst|msftauth|go\.|libmitm))" \
    "$MAPDIR/configuration.txt" 2>/dev/null | head -60
} > "$OUT/21-r8-config.txt" 2>&1

# ---- 30/31/32: the final APK -------------------------------------------------------------------
{
  ls -la "$APK"
  echo; echo "--- entries by top-level dir ---"; unzip -Z1 "$APK" | cut -d/ -f1 | sort | uniq -c | sort -rn | head -20
  echo; echo "--- dex / native / manifest / resources ---"; unzip -l "$APK" | grep -E "classes[0-9]*\.dex|lib/|resources.arsc|AndroidManifest" 
  echo; echo "--- assets/ ---"; unzip -Z1 "$APK" | grep -E "^assets/" | head -80
  echo; echo "--- META-INF/ in the APK (the app excludes /META-INF/** in build.gradle) ---"; unzip -Z1 "$APK" | grep -E "^META-INF/" | head -60
  echo; echo "--- any services file? ---"; unzip -Z1 "$APK" | grep -E "META-INF/services" || echo "(none: ServiceLoader finds nothing at runtime)"
} > "$OUT/30-apk.txt" 2>&1
$A dex "$APK" "$FW" "$MAPDIR/mapping.txt" > "$OUT/31-dex.txt" 2>&1

BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
mkdir -p "$RUNNER_TEMP/dex" && unzip -o -q "$APK" 'classes*.dex' -d "$RUNNER_TEMP/dex"
{
  echo "dexdump: $BT/dexdump  (target descriptor LM2/g;)"
  for f in "$RUNNER_TEMP"/dex/classes*.dex; do
    echo "##### $(basename "$f")"
    "$BT/dexdump" -d "$f" 2>/dev/null | awk -v pat="descriptor  : 'LM2/g;'" '
      /^Class #/ { if (hit) printf "%s", buf; buf=$0 "\n"; hit=0; next }
      { buf = buf $0 "\n"; if (index($0, pat) > 0) hit=1 }
      END { if (hit) printf "%s", buf }'
  done
} > "$OUT/32-dex-M2g.txt" 2>&1

# ---- 41: same classes on a plain JVM (no R8, no Android) ------------------------------------------
STD=$(find "$GCACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-1.9.2*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)
COR=$(find "$GCACHE/org.jetbrains.kotlinx" -name 'kotlinx-coroutines-core-jvm-1.7.2.jar' 2>/dev/null | head -1)
{
  echo "classpath: $PRE : $STD : $COR"
  java -cp "$PRE:$STD:$COR" .github/diag/JvmSmoke.java 2>&1 | head -300
} > "$OUT/41-jvm-smoke.txt" 2>&1

# ---- 40: what R8 hides behind '-dontwarn **' (plain variant only; this re-runs R8 so it is LAST) ---------
if [ "$NAME" = "base-plain" ]; then
  cp app/proguard-rules.pro "$RUNNER_TEMP/rules.bak"
  sed -i '/^-dontwarn \*\*[[:space:]]*$/d' app/proguard-rules.pro
  ./gradlew app:minifyReleaseWithR8 --no-daemon --no-configuration-cache --stacktrace > "$OUT/r8-nodontwarn.full.log" 2>&1
  echo "gradle exit: $?" > "$OUT/40-r8-missing.txt"
  cp "$RUNNER_TEMP/rules.bak" app/proguard-rules.pro
  {
    echo "== missing_rules.txt (R8's suggested -dontwarn rules = classes it could not find) =="
    cat "$MAPDIR/missing_rules.txt" 2>/dev/null || echo "(not generated)"
    echo; echo "== 'Missing class' diagnostics in the gradle log (unique) =="
    grep -E "Missing class|Missing method" "$OUT/r8-nodontwarn.full.log" | sed -E 's/\(referenced from.*//' | sort | uniq -c | sort -rn | head -150
    echo; echo "== tail of the gradle log =="; tail -40 "$OUT/r8-nodontwarn.full.log"
  } >> "$OUT/40-r8-missing.txt" 2>&1
fi

# ---- 00: short summary (also shipped as annotations) ----------------------------------------------------
{
  echo "variant=$NAME"
  grep -E "kotlin/ classes inside jar|assets/ entries|files$|has v844|Bedrock_v\* refs|MinecraftRelay.class:" "$OUT/10-jar-prestrip.txt" | head -10
  grep -E "services file|none: ServiceLoader|META-INF/services" "$OUT/30-apk.txt" | head -4
  echo
  sed -n '1,45p' "$OUT/20-mapping.txt"
} > "$OUT/00-summary.txt" 2>&1
echo "collect done"
