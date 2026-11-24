package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.Locale;

/**
 * Wheel picker test page: two stock number pickers (0–59, two-digit display, wrapping), both starting at 0.
 * "minutes" can be typed into directly; "locked" blocks the input field inside it (it cannot take focus, so typed text is not accepted), and can only be scrolled one step at a time.
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class WheelPickerTestActivity extends Activity {
    public static final String MINUTES = "分";
    public static final String LOCKED = "锁住";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addPicker(row, MINUTES, false);
        addPicker(row, LOCKED, true);
        setContentView(row);
    }

    private void addPicker(LinearLayout row, String label, boolean block) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(20f);
        col.addView(title);
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(59);
        picker.setValue(0);
        picker.setFormatter(v -> String.format(Locale.ROOT, "%02d", v));
        picker.setContentDescription(label);
        if (block) picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        col.addView(picker);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
