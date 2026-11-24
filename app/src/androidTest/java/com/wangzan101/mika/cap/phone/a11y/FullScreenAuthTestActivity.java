package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen sign-in/verification page: no dialog theme, no password field, and no excludeFromRecents,
 * so in shape it looks like an ordinary app page. Only the fact that its package is an account authenticator without a launcher icon identifies it as something the user must handle personally.
 */
public class FullScreenAuthTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Sign in to continue");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("This account page needs you to confirm on this phone.");
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
