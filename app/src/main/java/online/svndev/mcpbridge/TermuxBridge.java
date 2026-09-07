package online.svndev.mcpbridge;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * TermuxBridge - boilerplate for the Termux RUN_COMMAND integration.
 *
 * Sends background commands to Termux via the documented
 * com.termux.permission.RUN_COMMAND intent and captures the result that
 * Termux returns through the PendingIntent plugin-result contract:
 *
 *  - results come back inside the TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE
 *    bundle, delivered to the PendingIntent supplied in
 *    RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT (Termux >= 0.109)
 *  - the bundle carries _STDOUT, _STDERR, _EXIT_CODE and (on failure) _ERRMSG
 *
 * The constant strings below are Termux's own values, duplicated here so this
 * app compiles without any dependency on Termux source/jars.
 *
 * NOTE: "com.termux.permission.RUN_COMMAND" is NOT a normal runtime permission.
 * The OS never shows a dialog for it. Termux enforces its own allowlist, so the
 * user must enable it inside Termux first:
 *   Termux -> Settings (long-press the terminal screen) -> "Allow external apps"
 *   or add to ~/.termux/termux.properties :  allow-external-apps=true
 */
public final class TermuxBridge {

    private static final String TAG = "TermuxBridge";

    // --- Intent targets -----------------------------------------------------
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";

    /** The RUN_COMMAND intent action. */
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    // --- Extras we send with the RUN_COMMAND intent -------------------------
    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    public static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT";

    // --- Extras Termux sends back with the result ---------------------------
    public static final String EXTRA_PLUGIN_RESULT_BUNDLE = "com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE";
    public static final String EXTRA_RESULT_STDOUT = EXTRA_PLUGIN_RESULT_BUNDLE + "_STDOUT";
    public static final String EXTRA_RESULT_STDERR = EXTRA_PLUGIN_RESULT_BUNDLE + "_STDERR";
    public static final String EXTRA_RESULT_EXIT_CODE = EXTRA_PLUGIN_RESULT_BUNDLE + "_EXIT_CODE";
    public static final String EXTRA_RESULT_ERRMSG = EXTRA_PLUGIN_RESULT_BUNDLE + "_ERRMSG";

    // --- Broadcast action this app uses to receive Termux results -----------
    public static final String ACTION_APP_RESULT = "online.svndev.mcpbridge.TERMUX_RESULT";
    public static final String EXTRA_RUN_ID = "run_id";

    private TermuxBridge() {
    }

    /** True when the Termux app is installed on this device. */
    public static boolean isTermuxInstalled(Context context) {
        // Primary check: package lookup. Requires the <queries> declaration in the
        // manifest on Android 11+ (API 30+), otherwise NameNotFoundException is thrown
        // even when Termux is installed.
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // fall through to the service-resolution fallback
        }
        // Fallback: resolve the RUN_COMMAND service explicitly. This also works when
        // the package query is blocked but the service intent can still be resolved.
        Intent probe = new Intent(ACTION_RUN_COMMAND);
        probe.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        if (context.getPackageManager().resolveService(probe, 0) != null) {
            return true;
        }
        return false;
    }

    /**
     * Build the PendingIntent Termux should deliver the command result to.
     * requestCode must differ per in-flight command or the results collide.
     */
    public static PendingIntent buildResultPendingIntent(Context context, int requestCode) {
        Intent result = new Intent(ACTION_APP_RESULT);
        result.setPackage(context.getPackageName());
        // Mutable is required so Termux can attach the plugin-result bundle.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            flags |= 0x02000000; // PendingIntent.FLAG_MUTABLE (API 31+)
        }
        return PendingIntent.getBroadcast(context, requestCode, result, flags);
    }

    /**
     * Dispatch one background command to Termux.
     *
     * @param executable   absolute path inside Termux, e.g. "/data/data/com.termux/files/usr/bin/echo"
     * @param args         argument array (may be null)
     * @param workdir      Termux working directory (may be null)
     * @param resultIntent PendingIntent that receives the result (may be null)
     * @return null on success, or a user-readable error message on failure
     */
    public static String sendRunCommand(Context context, String executable, String[] args,
                                        String workdir, PendingIntent resultIntent) {
        if (!isTermuxInstalled(context)) {
            return "Termux is not installed. Install Termux from F-Droid first.";
        }
        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.putExtra(EXTRA_COMMAND_PATH, executable);
        if (args != null && args.length > 0) {
            intent.putExtra(EXTRA_ARGUMENTS, args);
        }
        if (workdir != null && !workdir.isEmpty()) {
            intent.putExtra(EXTRA_WORKDIR, workdir);
        }
        // Background execution: no terminal session is opened on screen.
        intent.putExtra(EXTRA_BACKGROUND, true);
        // Session action 0 = do not switch/create a visible session.
        intent.putExtra(EXTRA_SESSION_ACTION, 0);
        if (resultIntent != null) {
            intent.putExtra(EXTRA_PENDING_INTENT, resultIntent);
        }
        try {
            context.startService(intent);
            Log.i(TAG, "RUN_COMMAND dispatched: " + executable);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Termux RUN_COMMAND", e);
            return "Termux rejected the command: " + e.getMessage()
                    + ". Make sure this app is allowed in Termux (Settings -> Allow external apps "
                    + "or allow-external-apps=true in ~/.termux/termux.properties).";
        }
    }

    /**
     * Pull the command output out of the broadcast Intent Termux delivered.
     * Handles both the modern plugin-result bundle and legacy top-level extras.
     */
    public static String extractResultText(Intent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent == null) {
            return "(no result)";
        }
        Bundle bundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE);
        if (bundle != null) {
            String err = bundle.getString(EXTRA_RESULT_ERRMSG);
            if (err != null && !err.isEmpty() && !"null".equals(err)) {
                sb.append("[error] ").append(err);
            }
            String out = bundle.getString(EXTRA_RESULT_STDOUT);
            String errOut = bundle.getString(EXTRA_RESULT_STDERR);
            if (out != null && !out.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(out);
            }
            if (errOut != null && !errOut.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[stderr] ").append(errOut);
            }
            if (bundle.containsKey(EXTRA_RESULT_EXIT_CODE)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[exit] ").append(bundle.getInt(EXTRA_RESULT_EXIT_CODE));
            }
        } else {
            // Legacy / defensive fallback: extras directly on the intent.
            if (intent.hasExtra(EXTRA_RESULT_ERRMSG)) {
                sb.append("[error] ").append(intent.getStringExtra(EXTRA_RESULT_ERRMSG));
            }
            if (intent.hasExtra(EXTRA_RESULT_STDOUT)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(intent.getStringExtra(EXTRA_RESULT_STDOUT));
            }
            if (intent.hasExtra(EXTRA_RESULT_STDERR)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[stderr] ").append(intent.getStringExtra(EXTRA_RESULT_STDERR));
            }
            if (intent.hasExtra(EXTRA_RESULT_EXIT_CODE)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[exit] ").append(intent.getIntExtra(EXTRA_RESULT_EXIT_CODE, -1));
            }
        }
        if (sb.length() == 0) {
            return "(empty result - command finished with no output)";
        }
        return sb.toString().trim();
    }
}
