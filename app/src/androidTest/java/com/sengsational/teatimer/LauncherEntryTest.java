package com.sengsational.teatimer;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class LauncherEntryTest {
    private Context context;
    private NotificationManager notifications;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        notifications = context.getSystemService(NotificationManager.class);
        assertTrue("Complete first-run notification access", TimerController.notificationsAllowed(context));
        assertTrue("Complete first-run Do Not Disturb access",
                notifications.isNotificationPolicyAccessGranted());
        assertNotEquals("Allow alarms or priority interruptions before running launcher tests",
                NotificationManager.INTERRUPTION_FILTER_NONE,
                notifications.getCurrentInterruptionFilter());
        cleanup();
    }

    @After
    public void cleanup() {
        if (context != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TimerController.cancel(context, TimerController.activeToken(context));
                notifications.cancel(TimerController.BELL_NOTIFICATION);
            });
        }
    }

    @Test
    public void defaultLauncherStartsThreeMinutesAndCloses() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        startAndVerify(launch, 3);
    }

    @Test
    public void threeMinuteLauncherShortcutStartsAndCloses() {
        startAndVerify(shortcutIntent("tea_three_minutes", MainActivity.START_THREE), 3);
    }

    @Test
    public void fiveMinuteLauncherShortcutStartsAndCloses() {
        startAndVerify(shortcutIntent("tea_five_minutes", MainActivity.START_FIVE), 5);
    }

    private Intent shortcutIntent(String id, String action) {
        List<ShortcutInfo> shortcuts = context.getSystemService(ShortcutManager.class).getManifestShortcuts();
        assertEquals(2, shortcuts.size());
        for (ShortcutInfo shortcut : shortcuts) {
            if (id.equals(shortcut.getId())) {
                Intent intent = shortcut.getIntent();
                assertNotNull(intent);
                assertEquals(action, intent.getAction());
                assertTrue(shortcut.isEnabled());
                return intent;
            }
        }
        throw new AssertionError("Missing launcher shortcut: " + id);
    }

    private void startAndVerify(Intent intent, int minutes) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        Notification running = null;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            for (StatusBarNotification notification : notifications.getActiveNotifications()) {
                if (notification.getId() == TimerController.RUNNING_NOTIFICATION) {
                    running = notification.getNotification();
                }
            }
            if (running != null && TimerController.activeToken(context) != null) {
                break;
            }
            android.os.SystemClock.sleep(50);
        }
        assertNotNull("Launching should start a timer", running);
        assertEquals(context.getString(R.string.timer_running_description, (long) minutes),
                running.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue("The bell channel must have its DND exception enabled",
                notifications.getNotificationChannel(TimerController.BELL_CHANNEL).canBypassDnd());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertTrue("No Tea Timer activity should remain visible",
                        ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(Stage.RESUMED).isEmpty()));
    }
}
