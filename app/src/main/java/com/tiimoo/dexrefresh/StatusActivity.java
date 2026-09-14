package com.tiimoo.dexrefresh;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tiimoo.dexrefresh.core.Cfg;

import java.lang.reflect.Method;

/**
 * On-device cheat sheet.
 *
 * <p>Runs in the module's own process, where the Xposed classes do not exist,
 * so nothing here may touch {@code de.robv.android.xposed.*} - not even
 * indirectly through {@code ProbeLog}.
 */
public class StatusActivity extends Activity {

    private static final String SNAPSHOT_CMD =
            "su -c 'am broadcast -a " + Cfg.ACTION_SNAPSHOT + " --es label LABEL'";
    private static final String SCOUT_CMD =
            "su -c 'am broadcast -a " + Cfg.ACTION_SCOUT + " --ez scan true'";
    private static final String LOGCAT_CMD =
            "su -c 'logcat -s " + Cfg.TAG + ":V'";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(heading("DeX Refresh Probe " + BuildConfig.VERSION_NAME));
        root.addView(body("Observation only. This build makes no changes to the "
                + "refresh rate; it records how the system chooses one."));
        root.addView(heading("Status"));
        root.addView(body(statusText()));
        root.addView(heading("1. Capture a baseline (phone idle, no DeX)"));
        root.addView(mono(SNAPSHOT_CMD.replace("LABEL", "dex-off")));
        root.addView(heading("2. Start DeX, then capture again"));
        root.addView(mono(SNAPSHOT_CMD.replace("LABEL", "dex-on")));
        root.addView(heading("3. Read the log"));
        root.addView(mono(LOGCAT_CMD));
        root.addView(body("The LSPosed app shows the same output under "
                + "Logs → Modules, if you would rather not use a shell."));
        root.addView(heading("Optional: class/field discovery"));
        root.addView(mono(SCOUT_CMD));
        root.addView(body("Diff the two snapshots. The vote that appears or "
                + "tightens between them is what caps the panel."));

        root.addView(copyButton("Copy snapshot commands",
                SNAPSHOT_CMD.replace("LABEL", "dex-off") + "\n"
                        + SNAPSHOT_CMD.replace("LABEL", "dex-on") + "\n"
                        + LOGCAT_CMD));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String statusText() {
        String value = systemProperty(Cfg.PROP_ACTIVE);
        if (value == null) {
            return "Could not read " + Cfg.PROP_ACTIVE + ".\n"
                    + "This says nothing either way — confirm in LSPosed's log instead.";
        }
        if (value.isEmpty()) {
            return "Not detected as loaded.\n"
                    + "Either the module is not enabled for 'System Framework' in "
                    + "LSPosed (and rebooted), or this firmware refuses the status "
                    + "property. Check LSPosed → Logs before assuming the worst.";
        }
        return "Hook reported itself active in system_server at unix time " + value + ".";
    }

    /** Read a system property without linking against the hidden API. */
    private static String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Button copyButton(String label, final String payload) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(Cfg.TAG, payload));
                    Toast.makeText(StatusActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(StatusActivity.this, "Copy failed: " + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView mono(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setBackgroundColor(Color.argb(28, 128, 128, 128));
        int p = dp(8);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
