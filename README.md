<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper (Groq fork)

Push-to-talk dictation for Android.

This is a fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) with cloud transcription and cleanup switched from OpenAI to [Groq](https://groq.com): transcription uses `whisper-large-v3` and cleanup uses `llama-3.3-70b-versatile`, both via Groq's OpenAI-compatible API. Everything else (local on-device transcription, the overlay, accessibility insertion) works the same as upstream.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with Groq Whisper
- **Optional cleanup** with Groq to fix punctuation and grammar

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl) the original author.

## What's changed in this fork

- **Transcription**: `whisper-1` (OpenAI) → `whisper-large-v3` (Groq), via `https://api.groq.com/openai/v1/audio/transcriptions`
- **Cleanup**: `gpt-4o-mini` (OpenAI) → `llama-3.3-70b-versatile` (Groq), via `https://api.groq.com/openai/v1/chat/completions`
- **API key**: the settings screen now asks for a Groq key (`gsk_...`) instead of an OpenAI key (`sk-...`)
- **CI**: added a [GitHub Actions workflow](.github/workflows/build-apk.yml) that builds the debug APK on every push to `main` and publishes it to the [`dictate` release](https://github.com/EdiBianco/phone-whisper/releases/tag/dictate)

Local on-device transcription is untouched — it never called OpenAI in the first place.

## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest debug APK from the [`dictate` release](https://github.com/EdiBianco/phone-whisper/releases/tag/dictate) on this fork. A [GitHub Actions workflow](.github/workflows/build-apk.yml) rebuilds and republishes the APK to that release automatically on every push to `main`, so the link always points to the current build.

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/EdiBianco/phone-whisper.git && cd phone-whisper
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

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your [Groq API key](https://console.groq.com/keys)

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to Groq's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to Groq's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to Groq using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
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

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

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

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.
