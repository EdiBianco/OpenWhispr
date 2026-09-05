# Privacy Policy for OpenWhispr

OpenWhispr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

OpenWhispr supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to Groq's transcription API to generate text.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to Groq's chat API to improve punctuation, capitalization, and clarity.

## API keys

If you use cloud features, your Groq API key is stored locally on your device in app storage and used to authenticate requests sent directly to Groq.

I do not operate a relay server for these requests.

## Accessibility Service

OpenWhispr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

OpenWhispr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for OpenWhispr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as Groq, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, open an issue at: https://github.com/EdiBianco/OpenWhispr
