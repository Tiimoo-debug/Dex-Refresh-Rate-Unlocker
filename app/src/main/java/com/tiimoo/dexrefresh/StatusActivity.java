package com.tiimoo.dexrefresh;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.ui.Root;

import java.lang.reflect.Method;

/**
 * On-device control panel.
 *
 * <p>Everything this module can do was previously a shell command typed into
 * Termux, and that friction cost real time: a silent setprop, a command that
 * only fires when the property value changes, a cache-busting timestamp parsed
 * as an argument, and reports buried under sixty log lines a second. All of
 * that is handled here instead.
 *
 * <p>Runs in the module's own process, where the Xposed classes do not exist,
 * so nothing here may touch {@code de.robv.android.xposed.*} — not even
 * indirectly through ProbeLog. Commands reach the hook by way of {@code su}
 * setting the property the module polls.
 */
public class StatusActivity extends Activity {

    private TextView status;
    private TextView output;
    private EditText customSpec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(heading("DeX Refresh Probe " + BuildConfig.VERSION_NAME));

        status = mono("(reading state…)");
        root.addView(status);
        root.addView(button("Refresh status", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatus();
            }
        }));

        root.addView(heading("Unlock"));
        root.addView(body("Auto works out which votes hold each display below "
                + "the fastest mode it advertises, and drops exactly those. It "
                + "re-checks continuously, so redocks and reboots keep working.\n\n"
                + "On a dock, DisplayPort often runs two lanes instead of four, "
                + "so the link has a real ceiling below what the monitor "
                + "advertises. Removing the cap there can leave no picture at "
                + "all. Use the 120 Hz option for docks, or turn the unlock "
                + "off. If a display vanishes just after a change, the module "
                + "restores the caps by itself."));
        root.addView(button("Enable auto (persists across reboots)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        run("setprop " + Cfg.PROP_PERSIST_UNLOCK + " auto",
                                "Auto unlock enabled.");
                    }
                }));
        root.addView(button("Enable auto, capped at 120 Hz (safer on docks)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        run("setprop " + Cfg.PROP_PERSIST_UNLOCK + " auto:120",
                                "Auto unlock enabled, ceiling 120 Hz.");
                    }
                }));
        root.addView(button("Turn unlock off", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("setprop " + Cfg.PROP_PERSIST_UNLOCK + " '' ; setprop "
                                + Cfg.PROP_CMD + " \"unlock off " + stamp() + "\"",
                        "Unlock disabled.");
            }
        }));

        customSpec = new EditText(this);
        customSpec.setHint("or a custom spec, e.g. -1:19,*:10");
        customSpec.setInputType(InputType.TYPE_CLASS_TEXT);
        customSpec.setSingleLine(true);
        root.addView(customSpec);
        root.addView(button("Apply custom spec", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String spec = customSpec.getText().toString().trim();
                if (spec.isEmpty()) {
                    toast("Enter a spec first, or use Auto.");
                    return;
                }
                run("setprop " + Cfg.PROP_CMD + " \"unlock " + spec + " "
                                + stamp() + "\"", "Applied: " + spec);
            }
        }));

        root.addView(heading("Diagnose"));
        root.addView(button("Why is it not at maximum?", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runThenShowReport("setprop " + Cfg.PROP_CMD + " \"why " + stamp() + "\"");
            }
        }));
        root.addView(button("Full snapshot", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runThenShowReport("setprop " + Cfg.PROP_CMD
                        + " \"snapshot panel " + stamp() + "\"");
            }
        }));
        root.addView(button("Class / field scan", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runThenShowReport("setprop " + Cfg.PROP_CMD + " \"scout " + stamp() + "\"");
            }
        }));
        root.addView(button("Verbose logging on (for diagnosis)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        run("setprop " + Cfg.PROP_VERBOSE + " 1", "Verbose logging on.");
                    }
                }));
        root.addView(button("Verbose logging off", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("setprop " + Cfg.PROP_VERBOSE + " 0", "Verbose logging off.");
            }
        }));
        root.addView(button("Show latest reports", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReport();
            }
        }));

        root.addView(heading("Compare before and after"));
        root.addView(body("Several things on this phone change the refresh "
                + "rate and none of them have been told apart: Samsung's own "
                + "policy, DispUnlock, MultiStar's higher-resolutions toggle, "
                + "and this module. Each has a switch. Capture, flip one "
                + "switch, capture again, and the comparison names exactly "
                + "what that switch did \u2014 no reboot and no shell."));
        root.addView(button("1. Capture \u2018before\u2019", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("setprop " + Cfg.PROP_CMD + " \"capture before " + stamp() + "\"",
                        "Captured 'before'. Now flip one switch \u2014 one only "
                                + "\u2014 and capture 'after'.");
            }
        }));
        root.addView(button("2. Capture \u2018after\u2019", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("setprop " + Cfg.PROP_CMD + " \"capture after " + stamp() + "\"",
                        "Captured 'after'. Now compare.");
            }
        }));
        root.addView(button("3. Compare them", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runThenShowReport("setprop " + Cfg.PROP_CMD
                        + " \"diff before after " + stamp() + "\"");
            }
        }));

        root.addView(heading("Who files a vote?"));
        root.addView(body("Another display module (DispUnlock) has been active "
                + "for every measurement so far, so a vote in the table is not "
                + "necessarily Samsung's. Trace a priority, then re-plug the "
                + "display, and the caller stack names whoever filed it."));
        root.addView(button("Trace priority 5 (the 120 Hz render floor)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        run("setprop " + Cfg.PROP_CMD + " \"trace 5 " + stamp() + "\"",
                                "Tracing priority 5. Re-plug the display, then "
                                        + "read the reports.");
                    }
                }));
        root.addView(button("Stop tracing", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("setprop " + Cfg.PROP_CMD + " \"trace off " + stamp() + "\"",
                        "Tracing off.");
            }
        }));

        root.addView(heading("Display connection"));
        root.addView(body("Plug or unplug the dock, then tap this. It shows "
                + "whether Android saw a display device appear at all — which "
                + "separates a link or dock problem from anything this module "
                + "or DeX is doing."));
        root.addView(button("Show display connect/disconnect events",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setOutput("Reading\u2026");
                        Root.run("logcat -d -s " + Cfg.TAG_REPORT
                                        + ":V | grep DISPLAY-EVENT | tail -n 40",
                                new Root.Callback() {
                                    @Override
                                    public void onResult(boolean ok, String out) {
                                        setOutput(out.trim().isEmpty()
                                                ? "No display events in the log.\n\n"
                                                        + "If you just plugged the dock and "
                                                        + "nothing appears here, Android never "
                                                        + "saw a display - the link never came "
                                                        + "up, so no software setting is "
                                                        + "involved."
                                                : out);
                                    }
                                });
                    }
                }));

        root.addView(heading("Output"));
        output = mono("");
        root.addView(output);
        root.addView(button("Copy output", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(output.getText().toString());
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        refreshStatus();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * The module only acts when the command property's value *changes*, so
     * every command carries a unique suffix.
     *
     * <p>Generated here rather than in the shell: Android's date applet does
     * not reliably support sub-second formats, and a plain seconds value would
     * repeat if two buttons were tapped within the same second, silently doing
     * nothing the second time. The module strips a trailing all-digit token
     * before parsing, so this never reaches a command's arguments.
     */
    private static String stamp() {
        return Long.toString(System.nanoTime());
    }

    private void run(String command, final String successMessage) {
        setOutput("Running…");
        Root.run(command, new Root.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                if (ok) {
                    setOutput(successMessage);
                    // The hook polls every couple of seconds; give it a moment
                    // before reading back the state it publishes.
                    status.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatus();
                        }
                    }, 3000L);
                } else {
                    setOutput("Failed.\n\n" + out);
                }
            }
        });
    }

    private void runThenShowReport(String command) {
        setOutput("Running…");
        // One root session: fire the command, wait for the poller, then read
        // back only the report tag. Deliberately does NOT clear the log buffer
        // - that would also destroy the STATE history, which is the part worth
        // keeping. Reports are low volume on their own tag, so a tail is clean.
        Root.run(command + "; sleep 5; logcat -d -s " + Cfg.TAG_REPORT
                        + ":V | tail -n 200",
                new Root.Callback() {
                    @Override
                    public void onResult(boolean ok, String out) {
                        setOutput(out.trim().isEmpty()
                                ? "No report was produced. Is the module enabled for "
                                        + "System Framework in LSPosed, and rebooted?"
                                : out);
                    }
                });
    }

    private void showReport() {
        setOutput("Reading…");
        Root.run("logcat -d -s " + Cfg.TAG_REPORT + ":V | tail -n 200",
                new Root.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                setOutput(out.trim().isEmpty() ? "No reports in the log buffer yet." : out);
            }
        });
    }

    private void refreshStatus() {
        String state = systemProperty(Cfg.PROP_STATE);
        String persisted = systemProperty(Cfg.PROP_PERSIST_UNLOCK);
        StringBuilder sb = new StringBuilder();
        if (state == null || state.isEmpty()) {
            sb.append("Module state: not detected.\n")
                    .append("Either it is not enabled for System Framework in\n")
                    .append("LSPosed (then rebooted), or this firmware refuses\n")
                    .append("the status property. Check LSPosed → Logs.");
        } else {
            sb.append("unlock=").append(firstToken(state)).append('\n');
            String rest = state.substring(firstToken(state).length()).trim();
            if (!rest.isEmpty()) {
                sb.append("displays (active/max Hz):\n  ").append(rest.replace(" ", "\n  "));
            }
        }
        if (persisted != null && !persisted.isEmpty()) {
            sb.append("\npersisted: ").append(persisted);
        }
        status.setText(sb.toString());
    }

    private static String firstToken(String s) {
        int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
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

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(Cfg.TAG, text));
            toast("Copied");
        } catch (Throwable t) {
            toast("Copy failed: " + t);
        }
    }

    private void setOutput(String text) {
        output.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        b.setLayoutParams(rowParams(dp(8)));
        return b;
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(rowParams(dp(20)));
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLayoutParams(rowParams(dp(6)));
        return tv;
    }

    private TextView mono(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setBackgroundColor(Color.argb(28, 128, 128, 128));
        int p = dp(8);
        tv.setPadding(p, p, p, p);
        tv.setLayoutParams(rowParams(dp(6)));
        return tv;
    }

    private LinearLayout.LayoutParams rowParams(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
