# Pocket Tally

Pocket Tally is a private, offline-first Android counter built as a standalone module in the Blackline mobile-tools repository.

## Product promise

Count anything quickly without losing context or hunting for small controls. Pocket Tally supports touch, physical volume buttons, and a home-screen widget while the data remains on the device.

## Version 1.0 feature set

- Multiple independently configured counters
- Templates for attendance, inventory, laps, repetitions, traffic, rows, scores, and general counting
- Large distraction-free focus mode
- Volume-up to increment and volume-down to decrement while the app is in the foreground
- Configurable step size and custom positive or negative changes
- Optional goals with progress feedback
- Per-counter negative-value control
- Undo and redo
- Local event history with seven-day activity visualization
- CSV activity export
- Complete JSON backup and restore
- Shareable counter summaries
- Home-screen widget with increment and decrement actions
- Haptic, sound, keep-awake, and reset-confirmation preferences
- High-contrast, scalable, accessibility-conscious UI
- No account, network permission, analytics SDK, advertising SDK, or cloud dependency

## Build

The repository uses Java 17, Android Gradle Plugin 8.12.2, and Gradle 8.13.

```bash
gradle :pocket_tally:lintDebug :pocket_tally:testDebugUnitTest :pocket_tally:assembleDebug
gradle :pocket_tally:bundleRelease
```

GitHub Actions uploads a debug APK, an unsigned release AAB, and the Android lint report. Production Play uploads should use a protected upload keystore and a signed release bundle.

## Play configuration

- Package ID: `online.pcguys.pockettally`
- Minimum Android: 8.0 / API 26
- Target Android: 16 / API 36
- Version code: 1
- Version name: 1.0.0

The package ID becomes permanent after the first Google Play upload. Change it before that upload if a different final application ID is required.

## Data safety baseline

Pocket Tally does not collect or share user data. Counter data is stored in app-private `SharedPreferences`. Files are written only after the user explicitly chooses an export destination. Backups are read only after the user explicitly selects a file through Android's system document picker.

Physical volume buttons are intercepted only while Pocket Tally is the foreground activity. No Accessibility Service or background key-capture permission is used.
