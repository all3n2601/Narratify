# Releasing Narratify

The [Release workflow](../.github/workflows/release.yml) builds Android and iOS artifacts, runs checks, and publishes GitHub releases from version tags. It does not upload to Google Play, TestFlight, or the App Store.

## Outputs

| File | Purpose |
| --- | --- |
| `Narratify-VERSION-android.apk` | Signed Android app for direct installation |
| `Narratify-VERSION-android.aab` | Signed Android App Bundle for a separate Google Play submission |
| `Narratify-VERSION-ios-simulator.zip` | Release-configuration `.app` for the iOS simulator; not installable on physical devices |
| `SHA256SUMS.txt` | SHA-256 digests of all three release assets |

Android/shared checks, database verification, benchmark checks, alignment checks, Android release lint, the recorded neural distribution approval, and native iOS tests must pass before publication. The pipeline verifies the APK signature before upload. Files are uploaded to a draft release first; the release becomes public only after all assets are uploaded.

These checks do not replace physical-device speech quality, battery, thermal, or app-store readiness reviews. Feature coverage remains documented in the main README.

## One-time Android signing setup

Create a release keystore using Android Studio's **Build → Generate Signed Bundle / APK** flow, or use an existing project release keystore. Back it up securely: updates to the directly distributed APK must use the same signing identity. Follow [Android's signing documentation](https://developer.android.com/studio/publish/app-signing).

Add these **repository secrets** under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The entire keystore encoded as base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Password for that key |

For example, on macOS, encode a keystore into an untracked temporary file, then upload it with GitHub CLI:

```sh
base64 -i /secure/path/narratify-release.jks -o /tmp/narratify-keystore-base64.txt
gh secret set ANDROID_KEYSTORE_BASE64 --repo all3n2601/Narratify < /tmp/narratify-keystore-base64.txt
rm /tmp/narratify-keystore-base64.txt

# Each command prompts for its value without putting it in command history.
gh secret set ANDROID_KEYSTORE_PASSWORD --repo all3n2601/Narratify
gh secret set ANDROID_KEY_ALIAS --repo all3n2601/Narratify
gh secret set ANDROID_KEY_PASSWORD --repo all3n2601/Narratify
```

The runner decodes the key into a private temporary file, disables the Gradle configuration cache for the signing build, and removes the key afterward. Credentials and keystores are never included in release artifacts. Tag releases fail with an actionable error if any signing secret is missing; a new temporary signing identity is never substituted.

## Validate without publishing

Open **Actions → Release → Run workflow**, select `main`, and enter a version such as `0.1.0-rc.1`. Or run:

```sh
gh workflow run release.yml --repo all3n2601/Narratify --ref main -f version=0.1.0-rc.1
```

Manual runs execute all build and test jobs and keep their downloadable workflow artifacts for 14 days. They never create a GitHub release. Without signing secrets, Android validation outputs are explicitly named `*-android-unsigned.apk` and `*-android-unsigned.aab`; the APK is not installable until signed. Supplying only some signing secrets fails validation rather than silently falling back.

## Publish a version

1. Merge the intended changes into `main` and check the verification result.
2. Run the manual release validation, including signing, and review the artifacts.
3. Create and push an annotated version tag on the intended commit:

```sh
git switch main
git pull --ff-only
git tag -a v0.1.0 -m 'Narratify 0.1.0'
git push origin v0.1.0
```

A tag must point to a commit in `main`'s history. Accepted versions are `vMAJOR.MINOR.PATCH` or a semantic prerelease such as `v0.1.0-rc.1`; leading zeroes and build-metadata suffixes are rejected. Prerelease tags produce GitHub prereleases and do not replace the latest stable release. Stable tags become the latest release, so publish stable versions in ascending order.

The tag controls Android's `versionName` and iOS's numeric marketing version. The Release workflow's increasing run number supplies Android's `versionCode` and iOS's build number. A rerun retains the same build number.

The pipeline uses the built-in GitHub token with release-write permission limited to the publication job; no personal access token is required. Release notes combine artifact instructions with GitHub's automatically generated change notes.

## Retry and recovery

If a build fails, fix the source and publish a new version tag, or rerun the same tag when the failure is environmental or missing credentials. If uploading fails after creating a draft, rerunning retries the draft upload. An already published release is never overwritten by a rerun: use a new version instead. Concurrent runs for the same ref are serialized.

## iOS device distribution

The current workflow packages a simulator app and requires no Apple signing credentials. To distribute an IPA or submit to TestFlight/App Store, add a separate signed archive/export workflow with your Apple Developer team, certificate, provisioning profile or automatic-signing authorization, and App Store Connect credentials. A simulator ZIP is not an IPA.

To install the simulator bundle locally:

```sh
unzip Narratify-0.1.0-ios-simulator.zip
xcrun simctl install booted Narratify.app
xcrun simctl launch booted app.narratify.ios
```
