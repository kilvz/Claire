package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Tapping the button hands off to a service in another process ({@link SpillLaunchService}), which opens the launch page of the app named by {@link #EXTRA_TARGET} with NEW_TASK:
 * the same way an app opens another app through a background process (service, SDK, redirect); the initiating process has no page on Mika's display, so the system places it on the home screen by default.
 * Used to reproduce "opens onto the user's home screen". (When opened from an application context in the same process, Android places it on the display of the topmost page of that process, so nothing spills over.)
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class SpillTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";
    /** When supplied, adds another button: opens this dialog-styled page through the background service as well. */
    public static final String EXTRA_DIALOG = "dialog";
    /** When supplied, adds another button: opens this full-screen account sign-in page through the background service (the test package is an account authenticator). */
    public static final String EXTRA_AUTH = "auth";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        String target = extra != null ? extra : "com.android.settings";
        String dialog = getIntent().getStringExtra(EXTRA_DIALOG);
        String auth = getIntent().getStringExtra(EXTRA_AUTH);
        Button button = new Button(this);
        button.setText("open elsewhere");
        button.setTextSize(32f);
        button.setOnClickListener(v ->
            startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_TARGET, target)));
        if (dialog == null && auth == null) {
            setContentView(button);
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(button);
        if (dialog != null) {
            Button dialogButton = new Button(this);
            dialogButton.setText("open dialog");
            dialogButton.setTextSize(32f);
            dialogButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_DIALOG, dialog)));
            root.addView(dialogButton);
        }
        if (auth != null) {
            Button authButton = new Button(this);
            authButton.setText("open auth");
            authButton.setTextSize(32f);
            authButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_AUTH, auth)));
            root.addView(authButton);
        }
        setContentView(root);
    }
}
