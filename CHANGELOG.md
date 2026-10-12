# Changelog

Short, human "what's new" notes per release -- the same text shown in the
in-app update dialog when a new version is detected. Add a new `## X.Y.Z`
section at the top before bumping `versionName` in `app/build.gradle.kts`.

Entries here are user-facing only: fixes, bugs, improvements, and features.
No internal/process notes (CI changes, release cleanup, repo housekeeping,
etc.) -- if it wouldn't mean anything to someone who just installed the app,
it doesn't belong here.

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
