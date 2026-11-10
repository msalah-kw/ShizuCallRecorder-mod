# Beta 2: VoIP detection and stability

This fork's `2.0.1-beta.2` APK uses version code 22. It supports updates from beta 1 when signed with the same certificate. The distributed beta APK uses the existing Android debug certificate, not a production signing key.

## Setup

1. Complete the usual Shizuku, call detection, notification, storage, and battery setup.
2. Enable third-party call recording and **Allow capture via the Playback API** in audio settings. Playback capture requires Android 14 or newer.
3. In **VoIP compatibility**, select **Check again** with Shizuku running and authorized. The check verifies shell permissions for audio output capture, voice communication capture, and audio routing. Passing these checks does not prove that both sides are audible on a particular device.
4. If WhatsApp, WhatsApp Business, or Messenger calls do not appear through Telecom, enable **WhatsApp / Messenger notification fallback**, then select **Allow notification access**. Enable it before placing the test call. Notification access is optional and separate from permission to display the recorder's own notifications.
5. Enable automatic recording for the desired directions. If a fallback notification has no verified direction, **both incoming and outgoing** automatic recording must be enabled. Notifications explicitly marked incoming or screening are not treated as answered calls. Legacy notifications without a call type require the ongoing flag. An ongoing call notification and communication audio mode are both required.

The listener checks only the three supported package names, call category, call type, ongoing flag, and notification key. It does not read message text, contact names, people, or notification actions. It cannot identify the other party from this metadata. Enabled contact exclusions keep unidentified VoIP calls in standby; the recording notification still allows a manual start. Anonymous incoming-call rules also apply. Carrier call filtering retains its existing behavior.

Telecom and the fallback share one recording owner. A second source cannot start a duplicate session or stop another source's session. The fallback waits one second for Telecom and tolerates two seconds without an ongoing notification or communication audio mode before stopping its own session. These delays can omit the beginning of a call or retain a short tail. Simultaneous calls, call transfer between apps, and long audio-mode changes remain unsupported.

## Device test checklist

The intended test device is **HONOR X9C (BRP-NX1), Android 16, Shizuku, without root**. This device has not been tested by the development environment.

- Allow background activity and remove battery optimization for the recorder and Shizuku. On HONOR/MagicOS, check manual app launch/background settings too.
- Test incoming, outgoing, rejected, missed, and back-to-back calls separately for carrier, Wi-Fi Calling, WhatsApp, and Messenger.
- Repeat answered calls with the screen locked, earpiece, speaker, wired headset where available, and Bluetooth. Switch routes during a longer call.
- Play every resulting file and confirm both speakers, complete duration, no silence, and successful saving. A notification or a nonempty file alone is insufficient proof.
- Confirm messages, voice notes, and unanswered calls do not produce recordings.
- Test Shizuku stopping, storage becoming unavailable, and enabling contact exclusions. Check that failures are visible and a subsequent valid call still works.

Export diagnostic logs from the existing debug settings after reproducing a problem. Exports now include VoIP prerequisites, notification access, audio mode, and output device type IDs. Detection logs identify the source package without reading notification content. Existing logs may contain caller metadata, especially in debug mode; inspect a report before sharing it.

## Automated validation

Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with Java 17, Gradle 9.8.2, and the Android SDK configured. Set `SKIP_SIGNING=true` when production signing secrets are unavailable. The new validation workflow runs these checks on pull requests and pushes to `main`. It builds a debug APK; it does not publish or supply the local beta signing key.

Automated tests cover notification detection lifecycle, duplicate ownership, recording policy, encoder initialization cleanup, permission setup, and earlier save/finalization regressions. They simulate Android services and codec failures with Robolectric. They do not validate a device's audio routing or actual call audio.
