package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test page for screen-reader and input verification:
 * - The "result" text is marked as not important for accessibility (like a calculator's display area), and pressing "7" only changes it;
 * - Tapping the "empty input box" only moves the input focus;
 * - The "hot word example" is a search box that only shows a hint;
 * - After input, the "swapping box" is replaced with a new input control (some apps rebuild the input view whenever the content changes);
 * - The "fake search box" is just a piece of clickable text; only after tapping does the real input box appear and take focus (this is how the search bar on many home screens works).
 * Pages in the test APK run in the test package's own process and do not bundle the Kotlin standard library, so they are written in Java.
 */
public class FieldsTestActivity extends Activity {
    private LinearLayout holder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView result = new TextView(this);
        result.setText("0");
        result.setTextSize(32f);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(result);
        Button seven = new Button(this);
        seven.setText("7");
        useResourceId(seven, "send_code");
        seven.setOnClickListener(v -> result.append("7"));
        root.addView(seven);
        EditText empty = new EditText(this);
        empty.setHint("空输入框");
        useResourceId(empty, "verification_code");
        empty.setContentDescription(null);
        root.addView(empty);
        EditText hinted = new EditText(this);
        hinted.setHint("热词示例");
        root.addView(hinted);
        holder = new LinearLayout(this);
        root.addView(holder);
        holder.addView(swapping(""), fullWidth());
        EditText real = new EditText(this);
        real.setHint("真正的输入框");
        real.setVisibility(View.GONE);
        TextView fake = new TextView(this);
        fake.setText("假搜索框");
        fake.setTextSize(24f);
        fake.setOnClickListener(v -> {
            real.setVisibility(View.VISIBLE);
            real.requestFocus();
        });
        root.addView(fake);
        root.addView(real);
        setContentView(root);
    }

    private void useResourceId(View view, String id) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setViewIdResourceName(getPackageName() + ":id/" + id);
            }
        });
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private EditText swapping(CharSequence initial) {
        EditText field = new EditText(this);
        field.setHint("会换的框");
        field.setText(initial);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String text = s == null ? "" : s.toString();
                if (text.isEmpty()) return;
                field.post(() -> {
                    holder.removeAllViews();
                    EditText next = swapping(text);
                    holder.addView(next, fullWidth());
                    next.requestFocus();
                });
            }
        });
        return field;
    }
}
