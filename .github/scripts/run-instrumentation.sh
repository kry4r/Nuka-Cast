#!/usr/bin/env bash
set -uo pipefail

# Pass includeTestAbi (modern x86_64 emulator) or includeLegacyTestAbi (API 19 x86 emulator).
ABI_PROPERTY="${1:-includeTestAbi}"

adb logcat -c
set +e
./gradlew :app:connectedDebugAndroidTest \
  -P"${ABI_PROPERTY}=true" \
  --stacktrace \
  --console=plain
test_status=$?
set -e

adb logcat -d -v threadtime > instrumentation-logcat.txt || true
adb shell run-as com.nukacast.app.debug \
  cat files/last-java-crash.txt > application-crash.txt 2>/dev/null || true

if [ "$test_status" -ne 0 ]; then
  tail -n 400 instrumentation-logcat.txt
  if [ -s application-crash.txt ]; then
    printf '%s\n' 'Application crash report:'
    cat application-crash.txt
  fi
fi

exit "$test_status"
