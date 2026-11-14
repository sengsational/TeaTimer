package com.sengsational.teatimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import java.util.UUID;

/** The system owns the countdown and the one-shot sound; no resident service is needed. */
final class TimerController {
    static final String ACTION_EXPIRE = "com.sengsational.teatimer.EXPIRE";
    static final String ACTION_CANCEL = "com.sengsational.teatimer.CANCEL";
    // A new ID is required: Android cannot raise the existing LOW channel's importance.
    static final String RUNNING_CHANNEL = "timer_running_status";
    static final String BELL_CHANNEL = "tea_bell";
    static final int RUNNING_NOTIFICATION = 1;
    static final int BELL_NOTIFICATION = 2;
    static final long BELL_TIMEOUT_MS = 4_000L;
    private static final String PREFERENCES = "timer";
    private static final String TOKEN = "token";

    private TimerController() {}

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel running = new NotificationChannel(RUNNING_CHANNEL,
                context.getString(R.string.running_channel), NotificationManager.IMPORTANCE_DEFAULT);
        running.setDescription(context.getString(R.string.running_channel_description));
        running.setSound(null, null);
        running.enableVibration(false);
        running.setShowBadge(false);
        manager.createNotificationChannel(running);

        NotificationChannel bell = new NotificationChannel(BELL_CHANNEL,
                context.getString(R.string.bell_channel), NotificationManager.IMPORTANCE_HIGH);
        bell.setDescription(context.getString(R.string.bell_channel_description));
        bell.setSound(defaultSound(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        bell.enableVibration(false);
        bell.setShowBadge(false);
        // This is a channel exception, so other apps retain their normal DND behavior.
        bell.setBypassDnd(manager.isNotificationPolicyAccessGranted());
        manager.createNotificationChannel(bell);
    }

    private static Uri defaultSound(Context context) {
        if (RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_NOTIFICATION) != null) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_ALARM) != null) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        }
        return RingtoneManager.getValidRingtoneUri(context);
    }

    static boolean notificationsAllowed(Context context) {
        return (Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean exactAlarmsAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static String activeToken(Context context) {
        return preferences(context).getString(TOKEN, null);
    }

    /** Starting a timer replaces the previous timer, including any bell still playing. */
    static void start(Context context, long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Timer duration must be positive");
        }
        if (!notificationsAllowed(context) || !exactAlarmsAllowed(context)) {
            throw new SecurityException("Notification and exact alarm access are required");
        }
        createChannels(context);
        cancel(context, activeToken(context));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(BELL_NOTIFICATION);

        String token = UUID.randomUUID().toString();
        if (!preferences(context).edit().putString(TOKEN, token).commit()) {
            throw new IllegalStateException("Could not save the timer");
        }
        try {
            PendingIntent cancel = pendingIntent(context, ACTION_CANCEL, token,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            long minutes = Math.max(1, durationMs / 60_000L);
            Notification notification = new Notification.Builder(context, RUNNING_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_tea)
                    .setContentTitle(context.getString(R.string.timer_running))
                    .setContentText(context.getString(R.string.timer_running_description, minutes))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setOngoing(false)
                    .setDeleteIntent(cancel)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_stat_tea),
                            context.getString(R.string.cancel), cancel).build())
                    .build();
            manager.notify(RUNNING_NOTIFICATION, notification);
            context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + durationMs,
                    pendingIntent(context, ACTION_EXPIRE, token, PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException exception) {
            cancel(context, token);
            throw exception;
        }
    }

    static void cancel(Context context, String token) {
        if (token == null || !token.equals(activeToken(context))) {
            return;
        }
        clear(context, token);
        context.getSystemService(NotificationManager.class).cancel(RUNNING_NOTIFICATION);
    }

    static void expire(Context context, String token) {
        // A queued alarm or swipe from an older timer must never affect a newer one.
        if (token == null || !token.equals(activeToken(context))) {
            return;
        }
        clear(context, token);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(RUNNING_NOTIFICATION);
        if (!notificationsAllowed(context)) {
            return;
        }
        // Android plays this sound once, even after the receiver/process has finished.
        // Timeout removes the notification and caps long user-selected tones at four seconds.
        Notification bell = new Notification.Builder(context, BELL_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tea)
                .setContentTitle(context.getString(R.string.tea_ready))
                .setCategory(Notification.CATEGORY_ALARM)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setTimeoutAfter(BELL_TIMEOUT_MS)
                .build();
        manager.notify(BELL_NOTIFICATION, bell);
    }

    // Persist completion before returning from a short-lived receiver.
    @SuppressLint("ApplySharedPref")
    private static void clear(Context context, String token) {
        preferences(context).edit().remove(TOKEN).commit();
        PendingIntent alarm = pendingIntent(context, ACTION_EXPIRE, token, PendingIntent.FLAG_NO_CREATE);
        if (alarm != null) {
            context.getSystemService(AlarmManager.class).cancel(alarm);
            alarm.cancel();
        }
        PendingIntent dismiss = pendingIntent(context, ACTION_CANCEL, token, PendingIntent.FLAG_NO_CREATE);
        if (dismiss != null) {
            dismiss.cancel();
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static PendingIntent pendingIntent(Context context, String action, String token, int flags) {
        Intent intent = new Intent(context, TimerReceiver.class)
                .setAction(action)
                .setData(Uri.fromParts("teatimer", token, null));
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }
}
