# Tea Timer

A Java Android app for personal use, package `com.sengsational.teatimer`.
Requires Android 8.0 (API 26) or newer.

Tap the **Tea Timer launcher icon** to start a 3-minute timer immediately.
No app screen opens after first-run access is granted. A silent notification
says the timer is running, with a tea-cup icon on the left side of the status bar.
The icon disappears when you cancel the timer or after the brief completion alert.
The running notification uses Default importance with sound and vibration off,
so Android does not hide it with low-importance silent notifications. Swipe away that notification (or use
its Cancel action) to cancel. Long-press the launcher icon to start a **3 minute**
or **5 minute** timer directly. Starting another timer replaces the previous one.

At expiry Android plays the standard notification tone once, using alarm audio
attributes. A brief “Your tea is ready” notification disappears automatically
after four seconds, also limiting the duration of unusually long selected tones.
No activity opens when the alarm fires. There is no countdown screen, settings
screen, foreground service, polling loop, or background work after completion.
Android may retain a cached process; this is normal and consumes no ongoing work.

## First run

Android requires notification access and Do Not Disturb policy access. The app
explains these requests and opens the corresponding system screens. Android 12
may also require Alarms & reminders access. Android 13+ grants exact alarm access
automatically through `USE_EXACT_ALARM`, appropriate for this timer app.

The Tea bell notification channel requests permission to bypass **priority**
Do Not Disturb; the app does not change the global DND filter. Android's **Total
silence** mode cannot be bypassed by this channel. If it is active when starting,
the app directs you to system sound settings to allow alarms/priority
interruptions. Changing DND modes or disabling channels after starting can still
silence a pending bell. Keep alarm volume above zero. User channel overrides are
respected; disabled or silent channels must be enabled in Android settings.

## Implementation

- `MainActivity.java`: transient launcher entry, shortcuts, first-run system access.
- `TimerController.java`: elapsed-realtime exact wakeup alarm and notifications.
- `TimerReceiver.java`: private target for immutable alarm/cancel PendingIntents.
- Per-timer tokens reject stale alarm and notification callbacks.
- Android handles the sound and notification timeout after the receiver returns.
- No network, storage, foreground-service, or battery-optimization exemption.
- Reboot, force-stop, or uninstall cancels a timer; timers are not restored.

## Validation

Build: `./gradlew :app:assembleDebug :app:lintDebug`.

On a connected test device, complete the app's first-run access prompts, then run
`./gradlew :app:connectedDebugAndroidTest`. The tests exercise real short alarms,
notification cancellation, replacement, stale callbacks, automatic
bell-notification cleanup, the default launcher, and both launcher shortcuts. The expiry test plays a brief sound if
the device permits it.

Manual checks: grant first-run access; tap the launcher icon and check no screen opens;
swipe the notification and verify no bell; use each long-press shortcut; let a
timer expire with the screen off; check the bell with priority DND enabled.
Verify sound audibly on your own phone; instrumentation cannot hear the speaker.

Android references: [exact alarms](https://developer.android.com/develop/background-work/services/alarms),
[notification DND exceptions](https://developer.android.com/reference/android/app/NotificationChannel#setBypassDnd(boolean)),
[automatic notification cleanup](https://developer.android.com/reference/android/app/Notification.Builder#setTimeoutAfter(long)).
