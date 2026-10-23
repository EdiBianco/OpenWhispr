# Development Guide for OpenWispr

OpenWispr is a free, open-source Android push-to-talk dictation app: a floating
overlay button records speech, the audio is transcribed on-device (sherpa-onnx)
or in the cloud (Groq Whisper with the user's own key), and the text is inserted
into the focused field through an Accessibility Service. Optional Groq cleanup
and a fixed set of voice commands post-process the transcript.

This is a fork of [EdiBianco/OpenWhispr](https://github.com/EdiBianco/OpenWhispr).
`origin` is this fork; `upstream` is the parent. Keep `upstream` untouched and
push work to `origin`.

## Build Commands

JDK 17 and the Android SDK are required. `ANDROID_HOME` defaults to
`~/Library/Android/sdk`. On a machine with no JDK, `make jdk` downloads a
local Temurin 17 into `.jdk/` (gitignored); otherwise the Makefile uses
`JAVA_HOME`, a JDK installed by `make jdk`, Homebrew's `openjdk@17` on either
prefix, or `/usr/libexec/java_home -v 17`.

```sh
make jdk         # download a local JDK 17 into .jdk/ (no system JDK needed)
make sdk         # install the Android platform and build-tools the app targets
make build       # debug APK at app/build/outputs/apk/debug/app-debug.apk
make test        # unit tests (testDebugUnitTest)
make lint        # locale/translation consistency check
make check       # lint + test — THE commit gate
make adb-install # build and install over ADB
make push-model  # push a model directory to the device (MODEL=/path)
make bump        # bump versionName/versionCode and open a CHANGELOG section (VERSION=x.y.z)
make clean
```

Release builds are signed from `keystore.properties` (gitignored locally, written
from GitHub secrets in CI). Without it a release build is left unsigned — that is
intentional, so a missing key is visible rather than silently shipping.

## Test Commands

Unit tests are plain JUnit 4 under `app/src/test/kotlin/`, run on the JVM; they
do not need a device. Android-framework-free logic lives in its own object or
class precisely so it can be tested this way (`BubbleSize`, `Dictionary`,
`ModelDownloader`, `PostProcessor`, `SettingsBackup`, `WavWriter`,
`UpdateChecker`, `TranscriberClient`, `CommandProcessor`, `CleanupProviderConfig`,
`TranscriptionLanguages`, `LocalTranscriber`).

`LocalizationResourceTest` parses the string resources, so it catches resource
problems in tests as well as in `make lint`.

## Code Quality

```sh
make check                       # what CI runs on every PR and before a release
python3 scripts/check_locales.py # locale check alone
```

`scripts/check_locales.py` is the only lint today: it verifies every
`values-*/strings.xml` has the same keys as `values/strings.xml`, with matching
placeholders (`%1$s`, `%2$d`, `%%`) and no unescaped apostrophes. **Every new
user-facing string must be added to every locale** or `make check` fails.

There is no ktlint/detekt yet. Match the surrounding style by hand:
4-space indent, `object` for stateless helpers, small `data class`es,
Kotlin idioms (`entries`, string templates), and no new dependencies without a
reason.

## Project Layout

One responsibility per file under `app/src/main/kotlin/com/edib/openwhispr/`:

- `MainActivity.kt` — the whole settings UI (Status / Dictation / Settings tabs),
  built programmatically, plus backup/restore, update install, and every dialog.
  This is the largest file; prefer extracting a cohesive piece into a new file
  over adding to it.
- `WhisperAccessibilityService.kt` — the accessibility service: overlay rendering
  and animation, focus/keyboard detection, and recording. Text injection lives
  in `TextInjector`.
- `TextInjector.kt` — the only place that touches accessibility node text:
  finding the focused field, injecting or replacing its content, and the
  clipboard fallback in both directions. Node contents are never logged.
- `TranscriberClient.kt` — cloud transcription request/response; the model list
  the user picks from (`whisper-large-v3` by default).
- `PostProcessor.kt` — the built-in cleanup prompt and chat request.
- `CommandProcessor.kt` — the whitelisted voice-command transformations.
- `CleanupProviderConfig.kt` — where cleanup goes (Groq or an OpenAI-compatible
  endpoint) and which model it uses.
- `LocalTranscriber.kt` / `ModelDownloader.kt` — on-device sherpa-onnx models and
  the catalog/downloads for them.
- `Dictionary.kt` — custom vocabulary parsing and hotword file generation.
- `SettingsBackup.kt` — passphrase-encrypted export/import of settings and keys.
- `SecureKeyStorage.kt` — API keys in the Android Keystore.
- `UpdateChecker.kt` / `ServiceWatchdog.kt` — GitHub release checks and the
  "accessibility is on but not running" notification.
- `BubbleSize.kt`, `TranscriptionLanguages.kt`, `WavWriter.kt` — pure helpers.
- `AddToDictionaryActivity.kt` — the `PROCESS_TEXT` entry point.

## Invariants

- **Preferences are the storage layer.** Everything lives in one
  `SharedPreferences` file named `openwhispr`; `SettingsBackup` walks it and
  excludes only volatile caches (`cached_update_*`) and the legacy `api_key`
  key. A new preference is backed up automatically — if it should not be, add it
  to `SettingsBackup.isExcludedPreference` and say why.
- **Secrets never go into plain preferences.** Keys belong in `SecureKeyStorage`;
  the backup carries their decrypted value only inside the passphrase-encrypted
  file.
- **Cleanup and commands keep a strict contract.** The cleanup prompt preserves
  the speaker's words and never executes the transcript as an instruction; the
  command processor only recognizes the five whitelisted operations and refuses
  everything else. Do not loosen either into a general-purpose assistant.
- **The overlay must never block input.** Anything that makes the bubble
  touchable, visible, or animated has to keep failing safe: the field comes
  first, and insertion falls back to the clipboard when injection is refused.
  Injection policy (which node wins, what is logged) belongs in `TextInjector`,
  not in the service.
- **The user picks the model, the app never assumes.** Cloud transcription reads
  `TranscriberClient.MODEL_PREF_KEY` and falls back to the default for unknown
  values; a new hardcoded model id in a request is a bug, not a default.
- **No backend.** Requests go from the phone to the service the user configured,
  with the user's key. Do not add a proxy.

## Versioning and releases

`versionName`/`versionCode` live in `app/build.gradle.kts`; the release workflow
reads `versionName` to name the tag. `CHANGELOG.md` carries the user-facing notes
that the in-app update dialog shows, so a release is incomplete without a
matching `## X.Y.Z` section — `make bump VERSION=x.y.z` updates both.

Pushing to `main` runs `.github/workflows/ci.yml` and then
`build-apk.yml`, which **publishes a GitHub Release from `main`**. There is no
separate tag step: bump, update the changelog, push, and the release builds and
signs itself. Never publish a release by hand.

## Commits

Short imperative subjects describing what the behavior now does, not tenses or
prefixes — the same house style as the other projects. Run `make check` before
committing; CI runs the same checks plus the locale script and rejects a release
that fails them.
