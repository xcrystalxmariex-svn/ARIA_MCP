package online.svndev.mcpbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Stable manifest entry point for Termux RUN_COMMAND results.
 * Termux sends the mutable PendingIntent to this receiver, which then forwards
 * the result to the app's currently registered Activity/service receivers.
 */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Intent forwarded = new Intent(TermuxBridge.ACTION_APP_RESULT);
        forwarded.setPackage(context.getPackageName());
        forwarded.putExtras(intent);
        context.sendBroadcast(forwarded);
    }
}
