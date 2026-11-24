package com.wangzan101.mika.a11y;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Rich-text editing area (contenteditable) inside a web page; many apps build their note and comment boxes this way.
 * The editor only reacts to the browser's input event: the text above shows how many input events were received and the current content, which the test uses to tell whether the editor really received the input.
 * The test APK runs on its own in a separate process and does not bundle the Kotlin standard library, so this page is written in Java.
 */
public class WebEditorTestActivity extends Activity {
    private TextView status;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("inputs=0 text=");
        status.setTextSize(20f);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "bridge");
        web.loadDataWithBaseURL(null,
            "<html><body><div id=\"ed\" contenteditable=\"true\" aria-label=\"正文\" style=\"min-height:200px;border:1px solid #888\"></div>"
                + "<script>var n=0;document.getElementById('ed').addEventListener('input',function(e){n++;bridge.report(n,e.target.innerText);});</script>"
                + "</body></html>",
            "text/html", "utf-8", null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(status);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800));
        setContentView(root);
    }

    private class Bridge {
        @JavascriptInterface
        public void report(int n, String text) {
            runOnUiThread(() -> status.setText("inputs=" + n + " text=" + text.trim()));
        }
    }
}
