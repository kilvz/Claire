package com.wangzan101.mika.cap.phone.a11y;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.Display;

/**
 * Opens an app's launch page from a separate process, then stops as soon as it has opened it.
 * Components in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class SpillLaunchService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String auth = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_AUTH) : null;
        String dialog = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_DIALOG) : null;
        String component = auth != null ? auth : dialog;
        if (component != null) {
            // Dialog-styled or full-screen sign-in page: lands on the user's home screen. The test package itself has a page on Mika's display,
            // so when no display is specified the system puts it there too; when a background process has no page on the home screen, the system places it on the home screen by default, so we specify the home screen the same way here.
            Intent launch = new Intent().setComponent(new ComponentName(getPackageName(), component))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String target = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_TARGET) : null;
        Intent launch = target != null ? getPackageManager().getLaunchIntentForPackage(target) : null;
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
