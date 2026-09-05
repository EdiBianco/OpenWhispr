<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="OpenWhispr Logo">
</p>

# OpenWhispr

Free, open-source, on-device push-to-talk dictation for Android — a free alternative to [Wispr Flow](https://wisprflow.ai).

Speak naturally into any app and OpenWhispr turns your raw speech into clear, polished text: filler words removed, punctuation and formatting fixed automatically, then inserted straight into whatever field you're already typing in. Tap the floating button, speak, tap again — done.

It's completely free to run. Cloud transcription and cleanup use your own [Groq](https://groq.com) API key, and Groq's free tier is generous enough for everyday dictation without paying anything. Prefer to keep everything on-device? Local transcription needs no API key or internet connection at all.

This is a fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper), originally built around OpenAI. This fork switches cloud transcription and cleanup to Groq and adds a round of reliability and UX work on top — see below for the full list. If you find the original project useful, consider [sponsoring the original author](https://github.com/sponsors/kafkasl).

It supports:

- **Local on-device transcription** with sherpa-onnx — no API key, no internet required
- **Cloud transcription** with Groq Whisper — free API key, fast, no local model download
- **Optional AI cleanup** with Groq — removes filler words, fixes punctuation and grammar, formats emails

## What's changed in this fork

- **Transcription**: `whisper-1` (OpenAI) → `whisper-large-v3` (Groq), via `https://api.groq.com/openai/v1/audio/transcriptions`
- **Cleanup**: `gpt-4o-mini` (OpenAI) → `llama-3.3-70b-versatile` (Groq), via `https://api.groq.com/openai/v1/chat/completions`
- **API key**: the settings screen now asks for a free [Groq API key](https://console.groq.com/keys) (`gsk_...`) instead of an OpenAI key (`sk-...`), with a direct link to get one
- **CI**: a [GitHub Actions workflow](.github/workflows/build-apk.yml) builds the debug APK on every push to `main` and publishes it to a version-tagged [GitHub Release](https://github.com/EdiBianco/OpenWhispr/releases)
- **Overlay visibility**: the mic overlay shows only while a text field is focused, fading in/out, using three redundant signals (accessibility focus events, a periodic focus poll, and system keyboard visibility) so it still shows up in apps with non-standard text composers (e.g. WhatsApp, Telegram)
- **Stability**: hardened against crashes and killed background services, with a toggle to pause dictation without touching the Accessibility permission
- **Battery**: detects when Android might shut the background service down to save power and offers a one-tap fix, so the overlay stays available
- **Natural language cleanup**: a stricter cleanup prompt that handles self-corrections and preserves your intent instead of acting on it as a command, with room to add your own custom instructions on top

Local on-device transcription is untouched — it never called OpenAI in the first place.

## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest debug APK from the [Releases page](https://github.com/EdiBianco/OpenWhispr/releases) on this fork. A [GitHub Actions workflow](.github/workflows/build-apk.yml) builds and publishes a new version-tagged release automatically on every push to `main`.

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/EdiBianco/OpenWhispr.git && cd OpenWhispr
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **OpenWhispr**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your free [Groq API key](https://console.groq.com/keys) — the app links straight to that page when you tap to set the key
5. When prompted, allow OpenWhispr to run **unrestricted by battery optimization** — otherwise Android may shut the background service down and the overlay will disappear until you reopen the app

Once setup is done, the floating button is ready.

## Keeping the background service alive

Android is aggressive about killing background services to save battery, and an Accessibility Service is no exception. OpenWhispr does a few things to stay running:

- Runs as a **foreground service** with a persistent, silent, minimum-priority notification — the standard way to keep a background service alive when the app is swiped away in the recent-apps screen
- Prompts you to **exempt the app from battery optimization** (`Settings → Battery optimization` in the app, or the OS dialog it opens) the first time it detects the Accessibility Service is on but the exemption isn't granted
- Defensive error handling around accessibility events and local-model loading, so a single bad event or model can't crash the whole service process and force you to clear app storage and re-grant permissions

A **"Background service"** switch in the app lets you pause dictation (hide the overlay, stop reacting to taps) without revoking the Accessibility permission — handy if you want to quiet it temporarily instead of walking through Android's accessibility settings.

Some phone manufacturers (Samsung, Xiaomi, OnePlus, and others) layer their own battery/app-sleep managers on top of stock Android and may still kill the service even after you grant the exemption above. If the overlay keeps disappearing, check your phone's own battery/app management settings for an "autostart" or "keep in background" option for OpenWhispr.

## Why does it need Accessibility?

OpenWhispr uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

OpenWhispr supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to Groq's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to Groq's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to Groq using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.edib.openwhispr/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

OpenWhispr works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, OpenWhispr falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use OpenWhispr in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own Groq API key

## Support the project

OpenWhispr itself is free — if the underlying project it's forked from saves you time, you can sponsor the original author on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.
