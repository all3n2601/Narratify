#!/bin/sh
set -eu
: "${NARRATIFY_XCODE_PROJECT:?set NARRATIFY_XCODE_PROJECT}"
: "${NARRATIFY_IOS_SCHEME:?set NARRATIFY_IOS_SCHEME}"
: "${NARRATIFY_IOS_DESTINATION:?set an explicit physical-device destination}"
: "${NARRATIFY_RESULT_PATH:?set NARRATIFY_RESULT_PATH written by the test target}"
case "$NARRATIFY_IOS_DESTINATION" in
  *Simulator*|*simulator*) echo "A physical iOS device is required" >&2; exit 2 ;;
esac
rm -f "$NARRATIFY_RESULT_PATH"
NARRATIFY_OFFLINE_REQUIRED=1 NARRATIFY_RESULT_PATH="$NARRATIFY_RESULT_PATH" \
  xcodebuild test \
  -project "$NARRATIFY_XCODE_PROJECT" \
  -scheme "$NARRATIFY_IOS_SCHEME" \
  -destination "$NARRATIFY_IOS_DESTINATION"
test -f "$NARRATIFY_RESULT_PATH"
python3 "$(dirname "$0")/../validate_result.py" "$NARRATIFY_RESULT_PATH"
