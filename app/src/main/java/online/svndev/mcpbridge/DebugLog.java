package online.svndev.mcpbridge;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * DebugLog - a tiny live trace logger.
 *
 * Every log line is:
 *   - appended to an in-memory ring buffer (bounded)
 *   - appended to a persistent file in the app's files dir (mcp_debug.log)
 *   - broadcast as a DEBUG_LINE intent so the UI can show it live
 *
 * The MainActivity registers a receiver for DEBUG_LINE and renders the stream
 * in the on-screen "Debug / Live Trace" panel, so errors and calls are visible
 * in real time without adb.
 */
public final class DebugLog {

    private static final String TAG = "McpDebug";

    public static final String ACTION_DEBUG_LINE = "online.svndev.mcpbridge.DEBUG_LINE";
    public static final String EXTRA_LINE = "line";

    private static final int MAX_LINES = 600;
    private static final LinkedList<String> buffer = new LinkedList<>();
    private static volatile boolean fileLogging = true;

    private DebugLog() {
    }

    /** Log a line and fan it out to the ring buffer, file and live broadcast. */
    public static synchronized void log(Context ctx, String tag, String msg) {
        String line = "[" + timestamp() + "] [" + tag + "] " + msg;
        Log.d(TAG, line);
        buffer.add(line);
        while (buffer.size() > MAX_LINES) {
            buffer.removeFirst();
        }
        if (ctx != null) {
            if (fileLogging) {
                appendToFile(ctx, line);
            }
            try {
                Intent i = new Intent(ACTION_DEBUG_LINE);
                i.setPackage(ctx.getPackageName());
                i.putExtra(EXTRA_LINE, line);
                ctx.sendBroadcast(i);
            } catch (Exception ignored) {
            }
        }
    }

    /** Full in-memory trace as a single string (for copy/export). */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : buffer) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        buffer.clear();
    }

    public static synchronized void setFileLogging(boolean on) {
        fileLogging = on;
    }

    private static void appendToFile(Context ctx, String line) {
        try {
            File f = new File(ctx.getFilesDir(), "mcp_debug.log");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write('\n');
                w.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "log file write failed", e);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
