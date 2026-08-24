#!/usr/bin/env bash
set -Eeuo pipefail

./gradlew --no-daemon --stacktrace \
  -PshareguardReleasePrivacyEvidence=true \
  :app:assembleDebug :app:assembleDebugAndroidTest

app_apk=$(find app/build/outputs/apk/debug -type f -name 'app-debug.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -type f -name '*-androidTest.apk' -print -quit)
test -n "${app_apk}"
test -n "${test_apk}"
adb install --no-streaming -r "${app_apk}"
adb install --no-streaming -r "${test_apk}"

runner='app.shareguard.canonical.debug.test/androidx.test.runner.AndroidJUnitRunner'
process_probe='app.shareguard.canonical.ProcessDeathPersistenceInstrumentedTest'
reboot_probe='app.shareguard.canonical.DestructiveDeviceLifecycleInstrumentedTest'
mkdir -p app/build/outputs/destructive-lifecycle

adb shell am instrument -w -r \
  -e class "${reboot_probe}#seedBeforeReboot" \
  "${runner}" | tee app/build/outputs/destructive-lifecycle/reboot-seed.txt
grep -Eq '^OK \(1 test\)$' app/build/outputs/destructive-lifecycle/reboot-seed.txt

boot_before=$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
adb reboot
adb wait-for-device
for attempt in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  if [[ "${attempt}" == "120" ]]; then
    echo "The emulator did not complete its reboot." >&2
    exit 1
  fi
  sleep 1
done
boot_after=$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
test -n "${boot_before}"
test -n "${boot_after}"
test "${boot_before}" != "${boot_after}"

adb shell am instrument -w -r \
  -e class "${reboot_probe}#verifyAfterReboot" \
  "${runner}" | tee app/build/outputs/destructive-lifecycle/reboot-verify.txt
grep -Eq '^OK \(1 test\)$' app/build/outputs/destructive-lifecycle/reboot-verify.txt

set +e
adb shell am instrument -w -r \
  -e class "${process_probe}#dieImmediatelyAfterMetadataCommit" \
  "${runner}" | tee app/build/outputs/destructive-lifecycle/process-death-seed.txt
process_seed_status=${PIPESTATUS[0]}
set -e
if grep -Eq '^OK \(1 test\)$' app/build/outputs/destructive-lifecycle/process-death-seed.txt; then
  echo "The process-death seed returned normally instead of dying at the checkpoint." >&2
  exit 1
fi
printf 'instrumentation_exit=%s\n' "${process_seed_status}" \
  >> app/build/outputs/destructive-lifecycle/process-death-seed.txt

adb shell am instrument -w -r \
  -e class "${process_probe}#freshProcessQuarantinesTheInterruptedCommitAndPurgesItsTransientSource" \
  "${runner}" | tee app/build/outputs/destructive-lifecycle/process-death-verify.txt
grep -Eq '^OK \(1 test\)$' app/build/outputs/destructive-lifecycle/process-death-verify.txt
