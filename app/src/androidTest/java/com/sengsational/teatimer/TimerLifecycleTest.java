package com.sengsational.teatimer;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BooleanSupplier;

/** Exercises real system alarms and the actual notification delete PendingIntent. */
@RunWith(AndroidJUnit4.class)
public class TimerLifecycleTest {
    private Context context;
    private NotificationManager notifications;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        notifications = context.getSystemService(NotificationManager.class);
        assertTrue("Grant Tea Timer notification access before running these tests",
                TimerController.notificationsAllowed(context));
        assertTrue("Grant Tea Timer exact alarm access before running these tests",
                TimerController.exactAlarmsAllowed(context));
        cleanup();
    }

    @After
    public void cleanup() {
        if (context != null) {
            onMain(() -> {
                TimerController.cancel(context, TimerController.activeToken(context));
                notifications.cancel(TimerController.BELL_NOTIFICATION);
            });
        }
    }

    @Test
    public void dismissingNotificationCancelsTimerAndPreventsBell() throws Exception {
        onMain(() -> TimerController.start(context, 1_500L));
        await(() -> notification(TimerController.RUNNING_NOTIFICATION) != null, 3_000);
        Notification running = notification(TimerController.RUNNING_NOTIFICATION);
        assertNotNull(running);
        assertEquals(0, running.flags & Notification.FLAG_ONGOING_EVENT);
        assertNotNull(running.deleteIntent);
        String token = TimerController.activeToken(context);
        running.deleteIntent.send();
        await(() -> TimerController.activeToken(context) == null, 3_000);
        assertNull(notification(TimerController.RUNNING_NOTIFICATION));
        // Deliver an expiry that was already queued before the swipe.
        onMain(() -> TimerController.expire(context, token));
        SystemClock.sleep(1_700);
        assertNull(notification(TimerController.BELL_NOTIFICATION));
    }

    @Test
    public void replacingTimerIgnoresOldSwipeAndOldExpiry() {
        onMain(() -> TimerController.start(context, 60_000L));
        await(() -> notification(TimerController.RUNNING_NOTIFICATION) != null, 3_000);
        String oldToken = TimerController.activeToken(context);
        Notification oldNotification = notification(TimerController.RUNNING_NOTIFICATION);
        assertNotNull(oldNotification);
        PendingIntent oldDelete = oldNotification.deleteIntent;
        onMain(() -> TimerController.start(context, 300_000L));
        String newToken = TimerController.activeToken(context);
        assertNotEquals(oldToken, newToken);
        try {
            oldDelete.send();
        } catch (PendingIntent.CanceledException expected) {
            // Replacing the timer invalidates the old notification's PendingIntent.
        }
        onMain(() -> {
            TimerController.cancel(context, oldToken);
            TimerController.expire(context, oldToken);
        });
        assertEquals(newToken, TimerController.activeToken(context));
        assertNotNull(notification(TimerController.RUNNING_NOTIFICATION));
        assertNull(notification(TimerController.BELL_NOTIFICATION));
    }

    @Test
    public void exactAlarmExpiresOnceAndAutomaticallyRemovesBellNotification() {
        onMain(() -> TimerController.start(context, 1_000L));
        String token = TimerController.activeToken(context);
        await(() -> notification(TimerController.BELL_NOTIFICATION) != null, 8_000);
        assertNull(TimerController.activeToken(context));
        assertNull(notification(TimerController.RUNNING_NOTIFICATION));
        Notification bell = notification(TimerController.BELL_NOTIFICATION);
        assertNotNull(bell);
        assertEquals(Notification.CATEGORY_ALARM, bell.category);
        assertEquals(TimerController.BELL_TIMEOUT_MS, bell.getTimeoutAfter());
        onMain(() -> TimerController.expire(context, token));
        await(() -> notification(TimerController.BELL_NOTIFICATION) == null, 8_000);
    }

    @Test
    public void nonpositiveDurationDoesNotCancelExistingTimer() {
        onMain(() -> TimerController.start(context, 180_000L));
        String token = TimerController.activeToken(context);
        onMain(() -> {
            try {
                TimerController.start(context, 0);
                fail("A zero duration should be rejected");
            } catch (IllegalArgumentException expected) {
                assertEquals(token, TimerController.activeToken(context));
            }
        });
    }

    private Notification notification(int id) {
        for (StatusBarNotification notification : notifications.getActiveNotifications()) {
            if (notification.getId() == id) {
                return notification.getNotification();
            }
        }
        return null;
    }

    private static void onMain(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private static void await(BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
        }
        assertTrue("Timed out waiting for the expected timer state", condition.getAsBoolean());
    }
}
