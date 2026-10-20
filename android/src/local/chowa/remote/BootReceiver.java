package local.chowa.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts {@link RemoteService} on BOOT_COMPLETED and after package updates. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            RemoteService.start(context);
        }
    }
}
