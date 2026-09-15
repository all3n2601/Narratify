# Device execution

`android-run.sh` and `ios-run.sh` are strict orchestration shells for native test
targets. They do not install models, fetch dependencies, or synthesize results.
The platform test target must embed the same corpus/config and emit a result JSON
matching `../schemas/result.schema.json`.

Android environment: `ANDROID_SERIAL`, `NARRATIFY_ANDROID_APK`,
`NARRATIFY_ANDROID_TEST_APK`, `NARRATIFY_ANDROID_PACKAGE`, and
`NARRATIFY_ANDROID_RUNNER`. iOS environment: `NARRATIFY_XCODE_PROJECT`,
`NARRATIFY_IOS_SCHEME`, and `NARRATIFY_IOS_DESTINATION` (an explicit device
destination, not a simulator). Both scripts require `NARRATIFY_RESULT_PATH`.

Airplane mode and screen-off thermal conditions must be set and independently
recorded by the operator. The scripts will not silently change device radios.
