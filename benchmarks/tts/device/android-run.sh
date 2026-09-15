#!/bin/sh
set -eu
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the physical test device}"
: "${NARRATIFY_ANDROID_APK:?set NARRATIFY_ANDROID_APK}"
: "${NARRATIFY_ANDROID_TEST_APK:?set NARRATIFY_ANDROID_TEST_APK}"
: "${NARRATIFY_ANDROID_PACKAGE:?set NARRATIFY_ANDROID_PACKAGE}"
: "${NARRATIFY_ANDROID_RUNNER:?set NARRATIFY_ANDROID_RUNNER}"
: "${NARRATIFY_RESULT_PATH:?set NARRATIFY_RESULT_PATH}"
adb -s "$ANDROID_SERIAL" get-state
adb -s "$ANDROID_SERIAL" install -r "$NARRATIFY_ANDROID_APK"
adb -s "$ANDROID_SERIAL" install -r "$NARRATIFY_ANDROID_TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e narratifyOfflineRequired true \
  -e narratifyResultPath /sdcard/Download/narratify-tts-result.json \
  "$NARRATIFY_ANDROID_PACKAGE/$NARRATIFY_ANDROID_RUNNER"
adb -s "$ANDROID_SERIAL" pull /sdcard/Download/narratify-tts-result.json "$NARRATIFY_RESULT_PATH"
python3 "$(dirname "$0")/../validate_result.py" "$NARRATIFY_RESULT_PATH"
