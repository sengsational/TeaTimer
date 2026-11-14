package com.sengsational.teatimer;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** A transient launcher entry point; only first-run access prompts need an app screen. */
public final class MainActivity extends AppCompatActivity {
    static final String START_THREE = "com.sengsational.teatimer.START_THREE";
    static final String START_FIVE = "com.sengsational.teatimer.START_FIVE";
    private static final int NOTIFICATION_PERMISSION = 10;
    private static final int NOTIFICATION_SETTINGS = 11;
    private static final int EXACT_ALARM_SETTINGS = 12;
    private static final int DND_SETTINGS = 13;
    private static final int RUNNING_CHANNEL_SETTINGS = 14;
    private static final int BELL_CHANNEL_SETTINGS = 15;
    private static final int SOUND_SETTINGS = 16;
    private int pendingMinutes;
    private boolean requestInFlight;
    private boolean dialogShowing;
    private boolean setupShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (savedInstanceState != null) {
            pendingMinutes = savedInstanceState.getInt("minutes");
            requestInFlight = savedInstanceState.getBoolean("request");
        } else {
            pendingMinutes = START_FIVE.equals(getIntent().getAction()) ? 5 : 3;
        }
        if (pendingMinutes == 0) {
            showSetup();
        }
    }

    private void showSetup() {
        if (setupShown) {
            return;
        }
        setupShown = true;
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setBackgroundResource(R.color.page_background);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        findViewById(R.id.start_timer).setOnClickListener(view -> {
            if (!requestInFlight && !dialogShowing) {
                pendingMinutes = 3;
                continueStart();
            }
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (pendingMinutes > 0 && !requestInFlight && !dialogShowing) {
            continueStart();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("minutes", pendingMinutes);
        state.putBoolean("request", requestInFlight);
        super.onSaveInstanceState(state);
    }

    private void continueStart() {
        if (pendingMinutes == 0 || requestInFlight || dialogShowing) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            boolean askedBefore = getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false);
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                showChoice(R.string.notification_access_title, R.string.notification_access_message,
                        this::requestNotificationPermission);
            } else if (askedBefore) {
                explainSettings(R.string.notification_access_title, R.string.notification_denied_message,
                        appSettings(), NOTIFICATION_SETTINGS);
            } else {
                requestNotificationPermission();
            }
            return;
        }
        if (!TimerController.notificationsAllowed(this)) {
            explainSettings(R.string.notification_access_title, R.string.notification_denied_message,
                    appSettings(), NOTIFICATION_SETTINGS);
            return;
        }
        if (!TimerController.exactAlarmsAllowed(this)) {
            explainSettings(R.string.exact_alarm_title, R.string.exact_alarm_message,
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())), EXACT_ALARM_SETTINGS);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.isNotificationPolicyAccessGranted()) {
            explainSettings(R.string.dnd_title, R.string.dnd_message,
                    new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), DND_SETTINGS);
            return;
        }
        // Create the bell channel after policy access, so its DND exception can be enabled.
        TimerController.createChannels(this);
        if (!runningChannelEnabled()) {
            explainSettings(R.string.channel_title, R.string.running_channel_message,
                    channelSettings(TimerController.RUNNING_CHANNEL), RUNNING_CHANNEL_SETTINGS);
            return;
        }
        if (!bellChannelEnabled()) {
            explainSettings(R.string.channel_title, R.string.bell_channel_message,
                    channelSettings(TimerController.BELL_CHANNEL), BELL_CHANNEL_SETTINGS);
            return;
        }
        if (manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE) {
            explainSettings(R.string.total_silence_title, R.string.total_silence_message,
                    new Intent(Settings.ACTION_SOUND_SETTINGS), SOUND_SETTINGS);
            return;
        }
        try {
            TimerController.start(this, pendingMinutes * 60_000L);
            pendingMinutes = 0;
            finishAndRemoveTask();
        } catch (RuntimeException exception) {
            Log.e("TeaTimer", "Unable to start timer", exception);
            pendingMinutes = 0;
            showMessage(R.string.start_failed);
        }
    }

    private void requestNotificationPermission() {
        showSetup();
        if (Build.VERSION.SDK_INT >= 33) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestInFlight = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION) {
            requestInFlight = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                continueStart();
            } else {
                pendingMinutes = 0;
                showChoice(R.string.notification_access_title, R.string.notification_denied_message,
                        () -> openSettings(appSettings(), NOTIFICATION_SETTINGS));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        requestInFlight = false;
        if (pendingMinutes == 0) {
            return;
        }
        boolean granted;
        switch (requestCode) {
            case NOTIFICATION_SETTINGS:
                granted = TimerController.notificationsAllowed(this);
                break;
            case EXACT_ALARM_SETTINGS:
                granted = TimerController.exactAlarmsAllowed(this);
                break;
            case DND_SETTINGS:
                granted = getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
                break;
            case RUNNING_CHANNEL_SETTINGS:
                granted = runningChannelEnabled();
                break;
            case BELL_CHANNEL_SETTINGS:
                granted = bellChannelEnabled();
                break;
            case SOUND_SETTINGS:
                granted = getSystemService(NotificationManager.class).getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_NONE;
                break;
            default:
                return;
        }
        if (granted) {
            continueStart();
        } else {
            pendingMinutes = 0;
            showMessage(R.string.access_not_granted);
        }
    }

    private boolean runningChannelEnabled() {
        NotificationChannel channel = getSystemService(NotificationManager.class)
                .getNotificationChannel(TimerController.RUNNING_CHANNEL);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
    }

    private boolean bellChannelEnabled() {
        NotificationChannel channel = getSystemService(NotificationManager.class)
                .getNotificationChannel(TimerController.BELL_CHANNEL);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                && channel.getSound() != null && channel.canBypassDnd();
    }

    private Intent appSettings() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private Intent channelSettings(String channelId) {
        return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
    }

    private void explainSettings(int title, int message, Intent intent, int requestCode) {
        showChoice(title, message, () -> openSettings(intent, requestCode));
    }

    @SuppressWarnings("deprecation")
    private void openSettings(Intent intent, int requestCode) {
        requestInFlight = true;
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException | SecurityException exception) {
            requestInFlight = false;
            pendingMinutes = 0;
            showMessage(R.string.settings_unavailable);
        }
    }

    private void showChoice(int title, int message, Runnable positive) {
        showSetup();
        dialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.continue_label, (ignored, which) -> positive.run())
                .setNegativeButton(R.string.cancel, (ignored, which) -> pendingMinutes = 0)
                .setOnCancelListener(ignored -> pendingMinutes = 0)
                .create();
        dialog.setOnDismissListener(ignored -> dialogShowing = false);
        dialog.show();
    }

    private void showMessage(int message) {
        showSetup();
        dialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(ignored -> dialogShowing = false);
        dialog.show();
    }
}
