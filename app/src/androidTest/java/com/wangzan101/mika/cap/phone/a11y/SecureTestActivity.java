package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Page with the anti-screenshot flag: it is drawn black on the virtual display, but the accessibility tree remains readable.
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class SecureTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText("secure page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
