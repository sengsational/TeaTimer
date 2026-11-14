package com.sengsational.teatimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private target for system-held immutable alarm and notification PendingIntents. */
public final class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) {
            return;
        }
        String token = intent.getData().getSchemeSpecificPart();
        if (TimerController.ACTION_CANCEL.equals(intent.getAction())) {
            TimerController.cancel(context, token);
        } else if (TimerController.ACTION_EXPIRE.equals(intent.getAction())) {
            TimerController.expire(context, token);
        }
    }
}
