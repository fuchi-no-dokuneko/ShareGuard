#!/usr/bin/env bash
set -Eeuo pipefail

./gradlew --no-daemon --stacktrace \
  -PshareguardReleasePrivacyEvidence=true \
  connectedDebugAndroidTest
