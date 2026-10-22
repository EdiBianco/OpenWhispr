# Changelog

Short, human "what's new" notes per release -- the same text shown in the
in-app update dialog when a new version is detected. Add a new `## X.Y.Z`
section at the top before bumping `versionName` in `app/build.gradle.kts`.

Entries here are user-facing only: fixes, bugs, improvements, and features.
No internal/process notes (CI changes, release cleanup, repo housekeeping,
etc.) -- if it wouldn't mean anything to someone who just installed the app,
it doesn't belong here.

## 4.1.0
- Cloud transcription: choose the Groq Whisper model under Dictation > Cloud model. Whisper Large v3 (most accurate, the default), Whisper Large v3 Turbo (faster, cheaper), or Distil Whisper Large v3 (English only, fastest)

## 4.0.0
- Release builds are now signed with a private key. Before updating, make a backup in Settings > Backup. Then uninstall the 3.x app and install this version: Android won't update an app signed with a different key, and this is the one-time switch. Restore your backup on first launch

## 3.23.0
- Upgrade path to OpenWispr 4: when 4.0.0 is available, the app saves it to Downloads and walks you through backing up, uninstalling version 3, installing 4.0.0, and restoring your backup
- The Status tab shows when the accessibility service is switched on but not running, and a notification reminds you to turn it back on

## 3.22.0
- Backup: export your settings and API keys to a file you save on your phone, protected by a passphrase you choose. Pick everything or individual items
- Restore from a backup, from Settings or on first launch of a fresh install. Pick everything or individual items; the rest of your setup is left as it is

## 3.21.0
- Choose where the floating button sits: a new height slider and a left/right switch in Settings. A circle previews the spot while you move the slider, and the button always stays fully on screen
- The status note next to the button now appears on its right when the button is on the left

## 3.20.0
- Voice commands now work at the end of a dictation: say your text, then your trigger phrase and the action (for example "…the meeting moved to five. Whisper Command, translate to Italian"). Your trigger phrase is the break between the text and the action, with or without a pause, and it works with a custom trigger phrase too. The result is inserted like normal dictation
- Turkish app interface

## 3.12.2
- Fixed the floating button sometimes staying invisible after switching apps
- The floating button no longer flickers when focus changes quickly between apps

## 3.12.1
- Experimental multiple-language support: the app interface is now available in Italian, Spanish, Russian, Japanese, Portuguese (Brasil), and Chinese (Simplified). Translations are machine-drafted and still being reviewed
- Language picker in Settings shows each language in its own name

## 3.12.0
- New "Preferred languages" setting in the Dictation tab. Default is Auto-detect. Pick one language to force it, or several to tell the transcriber which languages to expect
- Local mode: when your languages aren't covered by the active model, the app offers to switch to a downloaded model, download one, or use cloud transcription
- New local models: Whisper Base and Whisper Small (multilingual)
- Downloaded local models that aren't in use can be deleted from the model list

## 3.11.5
- Silent recordings are no longer sent for transcription, so the app no longer inserts made-up text such as "Thank you." when you didn't speak

## 3.11.4
- Fixed the floating button not appearing in some apps (such as the Claude app's Code tab) even with the keyboard open: it now also shows whenever a text field is ready for typing or the keyboard is on screen
- The floating button shows the microphone while recording and transcribing, and the app logo when idle

## 3.11.3
- The floating button shows the microphone while recording and transcribing again, and the app logo when idle
- The button no longer stays on screen in every app: the 3.11.2 keyboard detection is reverted

## 3.11.2
- Fixed the floating button not appearing in some apps (such as the Claude app's Code tab) even with the keyboard open: it now also shows whenever a text field is ready for typing or the keyboard is on screen

## 3.11.1
- Fixed a possible crash right after downloading a speech model -- the model is now downloaded but not used until you tap it

## 3.11.0
- Cleanup no longer acts on what you dictate (like drafting the email you described) -- it keeps your words, and falls back to exactly what you said if the result doesn't match
- A short animated splash screen when the app starts (Android 12 and newer)
- Recording overlay redesigned: the logo bars turn soft red and move with your voice, instead of a blinking microphone
- Setting descriptions no longer run into their switches
- Cleaner settings screen: rounded sections, clearer switch descriptions, and a status line that tells you exactly what's missing (tap it to jump to the fix)
- Checking for updates when the app opens is now optional and off by default -- turn it on in Settings, or tap "Check for updates" any time
- The update dialog always shows what's new, with a link to the full release notes on GitHub
- Updates are checked against GitHub's published checksum before installing, and aren't installed if it doesn't match
- In local mode, audio never goes to the cloud -- if no local model is ready, you're told why instead
- Fixed a crash when the on-device speech engine couldn't be loaded
- Dictated text placed on the clipboard is marked as sensitive, so Android 13+ hides its preview
- "Copied to clipboard" now only appears when the text couldn't be inserted
- Text from the field you're dictating into is no longer written to the system log

## 3.10.0
- Refreshed the app's look with Material 3 Expressive: a richer color palette and more expressive buttons, switches, and tabs
- Smoother, springier animations across the settings screen

## 3.9.0
- Help walkthrough for Android 13+'s "Restricted settings" block, shown before opening Accessibility settings if it's not enabled yet
- The update dialog now shows what's new in the new version, not just the version number
