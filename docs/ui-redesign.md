# UI preview 2.0.2-ui.1

Version code 23 introduces a four-page workspace in place of the single settings list:

| Page | Controls |
| --- | --- |
| Home | Setup snapshot, incoming/outgoing automatic recording, recordings-folder shortcut, saved audio profile |
| Calls | Detection, storage and file naming, separate incoming/outgoing rules, contact exclusions, VoIP diagnostics |
| Audio | Capture source, codec, saved bitrate, channel separation and playback capture |
| Settings | Appearance and language, Shizuku management, debug tools, app information, licenses and support |

Navigation uses a side rail when the window is at least 720 dp wide and 560 dp high. Smaller or shorter windows use a bottom bar so that all destinations remain reachable in landscape. Each page retains its own scroll state. Android Back returns to Home from another page. The content width is capped on larger windows.

The palette uses navy and teal with separate light and dark surfaces. Dynamic wallpaper colors remain available in appearance settings. An explicitly saved dynamic-color preference is preserved; the default for an unset preference is now the app palette. Text, controls, first-launch setup, permission cards, dialogs, the floating recording control and adaptive launcher icon share the updated styling. Arabic layouts use mirrored directional actions and transitions.

The Home card checks configuration and Shizuku access when shown, resumed or refreshed. It is a setup snapshot, not a live recording indicator or proof that both sides of a call are captured. The recordings shortcut opens the configured document-provider folder; an unavailable provider or permission leads back to the folder picker.

Existing incoming/outgoing policies, contact filters and audio capture/finalization are retained. The disclaimer still requires reading/scrolling or accessibility support, acceptance, and its countdown before continuing. VoIP diagnostic permission names and the device test guide are expandable. Audio dropdowns show the saved codec/bitrate even when a device capability probe rejects the selection, rather than displaying another saved value.

## Verification

Run `gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with the project's Java 17 / Gradle 9.8.2 setup. Set `SKIP_SIGNING=true` when production signing secrets are unavailable.

`RecorderInterfaceTest` exercises navigation and persisted quick switches, Arabic dark layouts, enlarged Arabic text, tablet navigation, initial consent gating, and contact search/selection. It renders PNG previews using Robolectric native graphics at simulated API 34 under `app/build/reports/ui/`. Contact-picker images use fictional test entries. These previews do not validate physical-device system bars, keyboard behavior, or call audio.

The local preview APK uses the same Android debug certificate as beta 2 and can update a matching installation. It is a UI preview, not a new stable release.
