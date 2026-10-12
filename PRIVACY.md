# Privacy Policy for OpenWhispr

OpenWhispr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

OpenWhispr supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device. If no local model is available, OpenWhispr shows a message instead of sending the audio to the cloud. If optional cleanup or voice commands are enabled, the transcribed text (not the audio) is sent to Groq's chat API.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to Groq's transcription API to generate text.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to Groq's chat API to improve punctuation, capitalization, and clarity.

## API keys

If you use cloud features, your Groq API key is stored locally on your device in app storage and used to authenticate requests sent directly to Groq.

I do not operate a relay server for these requests.

## Accessibility Service

OpenWhispr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

OpenWhispr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Clipboard

To insert text, OpenWhispr places the dictated text on the clipboard and pastes it; if insertion fails, the text stays there for you to paste. It is marked as sensitive, so Android 13+ hides it in the clipboard preview and keyboards that honour this flag keep it out of their clipboard suggestions.

## Update checks

By default, OpenWhispr only contacts GitHub (`api.github.com`) to look for a new version when you tap "Check for updates". If you turn on "Check for updates automatically", it also checks when the app opens, at most once every 12 hours. These requests carry no personal data, but like any web request they reveal your IP address to GitHub.

## Data collection

I do not run a backend for OpenWhispr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as Groq, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, open an issue at: https://github.com/EdiBianco/OpenWhispr
