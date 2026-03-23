#!/usr/bin/env bash
set -euo pipefail

# Requires:
# - Android Studio / Android SDK
# - Java 17
# - Gradle 8.7+
# - SDK packages: platforms;android-34 and build-tools;34.0.0

gradle assembleDebug

echo
echo "APK path: app/build/outputs/apk/debug/"
