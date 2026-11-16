# Diagnostic logs (2.0.2-beta.logs.1)

Open **Settings → Diagnostics and logs**. Tap **Start diagnostic log**, reproduce the failure, then tap **Stop log capture** and **Save report to Downloads**. The notification also has a stop action. Debug mode separately reveals the advanced simulated-call controls; it does not disable phone-number redaction.

Reports are saved under `Download/ShizuCallRecorder/Logs/diagnostic-<timestamp>.log`. **Save to another location** uses the Android document picker. Both export actions report success or failure. Stopping preserves previous evidence, and exports remain available after stopping.

Working files live in the app's `noBackupFilesDir/diagnostics` directory, whose absolute path is displayed in Settings. `app.log` and `shizuku.log` each retain two rotated files, capped at 2 MiB per file (12 MiB total). These working files survive cache clearing and process restarts, but not app data clearing or uninstall. Exported Downloads reports remain until the user deletes them.

The user-started foreground service samples Shizuku's `dumpsys audio`, `dumpsys media.audio_flinger`, and the latest 300 filtered logcat lines, with a 20-second delay between collections. Samples include wall time, elapsed time, shell UID, command exit status, truncation and timeout markers. Each command has a 3-second capture timeout and 24 KiB output limit. This is sampled diagnosis, not a continuous system trace. AudioRecord, AudioFlinger, AudioPolicyManager, MediaCodec, ACodec and AndroidRuntime tags are selected; other system tags are excluded.

If Shizuku is unavailable or permission is missing, app event logging continues and the panel shows the failure. The service retries after 20 seconds. The last successful collection time is displayed separately. A process kill ends the foreground session; start a new session manually. Command failures and truncation remain in the report. OEM restrictions can still deny individual commands.

The app log uses a bounded queue of 512 entries. Queue overflow is counted in the exported header. A failed disk write is surfaced in Settings and the next event retries. Export waits for queued writes (up to 5 seconds), then serializes file reads with rotation. Crashes write the fatal trace directly before delegating to Android's original exception handler; abrupt termination cannot guarantee a final log entry.

Phone numbers are redacted, including exception text persisted to files. Other private data can remain in system output, such as device paths and application names. Review reports before sharing. No report is automatically uploaded. AIDL diagnostic commands are fixed and read-only; no caller-supplied shell text is executed.

Implementation references: [foreground service launch](https://developer.android.com/develop/background-work/services/fgs/launch), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [MediaStore downloads](https://developer.android.com/training/data-storage/shared/media).

Physical-device acceptance: reproduce carrier, WhatsApp and Messenger calls on the HONOR X9C; verify the notification stop action, Shizuku loss/recovery, exported report contents and behavior with the screen locked. Automated tests do not prove those OEM behaviors.
