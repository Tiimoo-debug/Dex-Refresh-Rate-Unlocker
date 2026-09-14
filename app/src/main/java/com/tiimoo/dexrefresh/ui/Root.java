package com.tiimoo.dexrefresh.ui;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command as root, off the main thread.
 *
 * <p>The control panel lives in the module's own app process, which cannot set
 * system properties itself. On a rooted device the simplest reliable bridge is
 * to shell out through {@code su} — Magisk prompts once and then remembers.
 *
 * <p>Everything here is asynchronous by construction. {@code su} can block for
 * seconds waiting on a Magisk permission dialog, and doing that on the main
 * thread is a guaranteed ANR.
 */
public final class Root {

    private Root() {
    }

    public interface Callback {
        void onResult(boolean ok, String output);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Run {@code command} as root; the callback lands on the main thread. */
    public static void run(final String command, final Callback callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String output;
                try {
                    Result r = exec(command, 20_000L);
                    ok = r.exitCode == 0;
                    output = r.output;
                } catch (Throwable e) {
                    ok = false;
                    output = "Failed to run as root: " + e
                            + "\n\nIs this device rooted, and has Magisk granted "
                            + "root to DeX Refresh Probe?";
                }
                final boolean fOk = ok;
                final String fOut = output;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(fOk, fOut);
                    }
                });
            }
        }, "dexrr-root");
        t.setDaemon(true);
        t.start();
    }

    private static final class Result {
        int exitCode;
        String output;
    }

    private static Result exec(String command, long timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                // A runaway logcat would otherwise fill memory.
                if (sb.length() > 512 * 1024) {
                    sb.append("... [truncated]\n");
                    break;
                }
            }
        } finally {
            try {
                reader.close();
            } catch (Throwable ignored) {
                // closing is best-effort
            }
        }
        Result r = new Result();
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy();
            r.exitCode = -1;
            sb.append("\n[timed out after ").append(timeoutMs / 1000).append("s]\n");
        } else {
            r.exitCode = process.exitValue();
        }
        r.output = sb.toString();
        return r;
    }
}
