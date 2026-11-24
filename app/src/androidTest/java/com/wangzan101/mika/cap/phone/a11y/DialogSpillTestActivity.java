package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dialog-styled page: when opened from a background service it lands on the user's home screen, reproducing sign-in verification, account confirmation and similar dialogs that need the user to tap confirm.
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class DialogSpillTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Verify it's you");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Are you trying to sign in? Tap Confirm on this phone to continue.");
        body.setTextSize(16f);
        body.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(body);

        Button confirm = new Button(this);
        confirm.setText("Confirm");
        confirm.setOnClickListener(v -> finish());
        root.addView(confirm);

        setContentView(root);
    }
}
