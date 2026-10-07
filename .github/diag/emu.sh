#!/usr/bin/env bash
# Temporary diagnostic helper (NOT part of the product).
# Runs INSIDE android-emulator-runner (adb is available and a device is booted).
set +e
WS="${GITHUB_WORKSPACE:-.}"
OUT="$WS/diag-out"
mkdir -p "$OUT"
APK=$(ls "$WS"/apk/*.apk | head -1)
echo "APK=$APK"

{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
} > "$OUT/60-emu-device.txt"

adb logcat -c
adb install -r -g "$APK" 2>&1 | tee "$OUT/61-install.txt"
adb shell appops set dev.sora.protohax SYSTEM_ALERT_WINDOW allow
adb shell am start -W -n dev.sora.protohax/.ui.activities.MainActivity 2>&1 | tee -a "$OUT/61-install.txt"

# wait for the probe to finish (it logs "END probe"), at most ~3 minutes
for i in $(seq 1 60); do
  if adb logcat -d -s DIAG:I 2>/dev/null | grep -q "END probe"; then echo "probe finished after ~$((i*3))s"; break; fi
  sleep 3
done
sleep 5

adb logcat -d -v threadtime > "$OUT/logcat_full.log"
# the probe's own output
grep -E " DIAG " "$OUT/logcat_full.log" | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ //' > "$OUT/62-probe-raw.txt"
# everything that smells like a runtime/class-loading problem
grep -E "AndroidRuntime|FATAL|ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|VerifyError|Rejecting class|Could not initialize|ClassNotFoundException|ProtoHax|System.err|SLF4J" \
  "$OUT/logcat_full.log" | grep -v -E "ProtoHax.*(trace|debug)" | head -400 > "$OUT/63-runtime-errors-raw.txt"
echo "done"
