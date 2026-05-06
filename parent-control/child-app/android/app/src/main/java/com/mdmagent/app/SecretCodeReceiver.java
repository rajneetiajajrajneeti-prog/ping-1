package com.mdmagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Listens for *#*#8888#*#* in the dialer — opens the hidden app */
public class SecretCodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launch);
    }
}
