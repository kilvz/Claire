package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Ordinary page, used to simulate another app taking the foreground.
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class PlainTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("plain page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
