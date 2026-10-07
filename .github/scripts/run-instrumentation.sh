#!/usr/bin/env bash
set -uo pipefail

# Pass includeTestAbi (modern x86_64 emulator) or includeLegacyTestAbi (API 19 x86 emulator).
ABI_PROPERTY="${1:-includeTestAbi}"
MAX_ATTEMPTS=2

collect_diagnostics() {
  adb logcat -d -v threadtime > instrumentation-logcat.txt 2>/dev/null || true
  adb shell run-as com.nukacast.app.debug \
    cat files/last-java-crash.txt > application-crash.txt 2>/dev/null || true
}

# The emulator runner starts the device, but adb can still be wedged. Waiting for
# sys.boot_completed avoids running a test APK against a device that is not ready.
wait_for_boot() {
  adb start-server >/dev/null 2>&1 || true
  timeout 180 adb wait-for-device >/dev/null 2>&1 || return 1
  for _ in $(seq 1 60); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      return 0
    fi
    sleep 5
  done
  return 1
}

# Number of executed test cases in the JUnit reports of the last attempt.
executed_tests() {
  grep -ho "<testcase" app/build/outputs/androidTest-results/*/*/*.xml 2>/dev/null | wc -l | tr -d ' '
}

status=1
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "==> instrumentation attempt ${attempt}/${MAX_ATTEMPTS} (${ABI_PROPERTY})"
  rm -rf app/build/outputs/androidTest-results
  adb logcat -c >/dev/null 2>&1 || true
  if [ "$attempt" -gt 1 ]; then
    # A wedged adb server, failed console or dead emulator is retried once; a real
    # test failure executes test cases and is never retried.
    adb kill-server >/dev/null 2>&1 || true
    sleep 15
    adb start-server >/dev/null 2>&1 || true
  fi
  if ! wait_for_boot; then
    echo "warning: emulator did not report sys.boot_completed=1"
  fi
  ./gradlew :app:connectedDebugAndroidTest \
    -P"${ABI_PROPERTY}=true" \
    --stacktrace \
    --console=plain 2>&1 | tee "gradle-instrumentation-${attempt}.log"
  status="${PIPESTATUS[0]}"
  collect_diagnostics
  if [ "$status" -eq 0 ]; then
    break
  fi
  if [ "$(executed_tests)" -eq 0 ] && [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
    echo "No test case executed, which points at the emulator or adb instead of the app."
    continue
  fi
  break
done

if [ "$status" -ne 0 ]; then
  tail -n 400 instrumentation-logcat.txt
  if [ -s application-crash.txt ]; then
    printf '%s\n' 'Application crash report:'
    cat application-crash.txt
  fi
fi

exit "$status"
