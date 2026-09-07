package online.svndev.mcpbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Scriptable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * McpServerService - foreground service that:
 *  1. serves a small MCP-over-HTTP endpoint on the device (port 8000, /mcp)
 *  2. extracts the bundled cloudflared binary from assets and runs the tunnel
 *  3. validates the binary before starting (size ~30-35MB, executable bit set)
 *  4. exposes a Termux RUN_COMMAND bridge tool
 *
 * The embedded HTTP server is implemented with plain ServerSocket because
 * com.sun.net.httpserver (used in the original scaffold) is a desktop-JDK
 * class that does NOT exist on Android - it would fail to compile in AIDE.
 */
public class McpServerService extends Service {

    private static final String TAG = "McpServerService";

    public static final String ACTION_START = "online.svndev.mcpbridge.action.START";
    public static final String ACTION_STOP = "online.svndev.mcpbridge.action.STOP";
    public static final String ACTION_RELOAD = "online.svndev.mcpbridge.action.RELOAD";
    public static final String ACTION_BINARY_STATUS = "online.svndev.mcpbridge.action.BINARY_STATUS";
    public static final String EXTRA_BINARY_STATUS = "binary_status";
    public static final String ACTION_TUNNEL_INFO = "online.svndev.mcpbridge.action.TUNNEL_INFO";
    public static final String EXTRA_TUNNEL_MODE = "tunnel_mode";
    public static final String EXTRA_TUNNEL_URL = "tunnel_url";
    public static final String EXTRA_TUNNEL_ACTIVE = "tunnel_active";

    public static final String MODE_TOKEN = "token";
    public static final String MODE_QUICK = "quick";

    private static final String CHANNEL_ID = "mcp_channel";
    private static final int NOTIF_ID = 1;

    /** Expected size of the bundled cloudflared arm64 binary. */
    private static final long EXPECTED_MIN_BYTES = 25L * 1024 * 1024;   // ~25 MB lower bound
    private static final long EXPECTED_MAX_BYTES = 45L * 1024 * 1024;   // ~45 MB upper bound

    private static final int PORT = 8000;

    private ServerSocket serverSocket;
    private final ExecutorService httpPool = Executors.newFixedThreadPool(4);
    private Process cloudflaredProcess;
    private File binaryFile;
    private String tunnelMode = MODE_TOKEN;
    private String authCode = "";

    // Per-tool toggles (sent from the UI; gate which tools are advertised/run).
    private boolean enableOracle = true;
    private boolean enableShell = false;
    private boolean enableTermux = false;
    private boolean enableExternal = false;

    // External MCP servers this app aggregates (acts as an MCP client).
    // Each entry may carry custom headers and an auth token, and may be
    // started by a local launch command (self-host) before the app connects.
    private final List<ExternalServer> externalServers = new ArrayList<>();
    // Processes spawned for self-hosted external MCP servers (kept alive for the service lifetime).
    private final List<Process> launchedServers = new ArrayList<>();
    private String launchedServerCmd = "";
    // User-added custom tools (JS snippet or shell command) from the UI.
    private final List<CustomTool> customTools = new ArrayList<>();
    // Per-tool enable/disable map (tool name -> enabled). Every tool - built-in,
    // custom, or from an external server - has its own toggle here.
    private final java.util.Map<String, Boolean> toolToggles = new java.util.HashMap<>();

    private final BroadcastReceiver termuxResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TermuxBridge.ACTION_APP_RESULT.equals(intent.getAction())) {
                String text = TermuxBridge.extractResultText(intent);
                Log.i(TAG, "Termux result: " + text);
                DebugLog.log(context, "Svc", "Termux result: " + text);
                notifyTermuxResult(text);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerReceiver(termuxResultReceiver,
                new IntentFilter(TermuxBridge.ACTION_APP_RESULT));
    }
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
        stopSelf();
        return START_NOT_STICKY;
    }
    
    // Clean up before starting fresh
    stopExistingServer();
    stopExistingTunnel();
    // ... rest of method


        // Reload custom tools / external MCP servers from prefs without a restart.
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            loadCustomTools();
            loadExternalServers();
            loadToolToggles();
            DebugLog.log(this, "Svc", "Reloaded custom tools (" + customTools.size()
                    + ") and external MCP servers (" + externalServers.size() + ")");
            return START_NOT_STICKY;
        }

        // Clean up any existing server before starting fresh
        stopExistingServer();
        stopExistingTunnel();

        String token = null;
        String mode = MODE_TOKEN;
        String code = "";
        if (intent != null) {
            token = intent.getStringExtra("tunnel_token");
            String m = intent.getStringExtra("tunnel_mode");
            if (m != null) mode = m;
            String c = intent.getStringExtra("auth_code");
            if (c != null) code = c;
        }
        if ((token == null || token.trim().isEmpty()) && MODE_TOKEN.equals(mode)) {
            token = getSharedPreferences("mcp_prefs", MODE_PRIVATE)
                    .getString("tunnel_token", "");
        }
        this.tunnelMode = mode;
        this.authCode = code.trim();

        // Per-tool toggles from the UI checkboxes.
        if (intent != null) {
            enableOracle = intent.getBooleanExtra("chk_oracle", true);
            enableShell = intent.getBooleanExtra("chk_shell", false);
            enableTermux = intent.getBooleanExtra("chk_termux", false);
            enableExternal = intent.getBooleanExtra("chk_external", false);
        }
        loadCustomTools();
        loadExternalServers();
        loadToolToggles();

        startForeground(NOTIF_ID, buildNotification("Starting MCP server..."));

        // 1) Validate + extract the cloudflared binary BEFORE starting anything.
        String binaryStatus = prepareCloudflaredBinary();
        broadcastBinaryStatus(binaryStatus);
        DebugLog.log(this, "Svc", "Binary status: " + binaryStatus);

        // 2) Start the local MCP HTTP endpoint.
        startMcpHttpServer();

        // 3) Start the tunnel only if the binary is usable.
        if (!BINARY_OK.equals(binaryStatus)) {
            DebugLog.log(this, "Svc", "Tunnel not started - binary not OK");
        } else if (MODE_QUICK.equals(mode)) {
            startQuickTunnel();
        } else if (token != null && !token.isEmpty()) {
            startTokenTunnel(token);
        } else {
            DebugLog.log(this, "Svc", "Tunnel not started - token mode with no token provided");
            broadcastTunnelInfo(MODE_TOKEN, null, false);
        }

        return START_STICKY;
    }

    private void stopExistingServer() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    private void stopExistingTunnel() {
        if (cloudflaredProcess != null) {
            cloudflaredProcess.destroy();
            cloudflaredProcess = null;
        }
    }

    // ---------------------------------------------------------------------
    //  Binary extraction + validation
    // ---------------------------------------------------------------------

    private static final String BINARY_OK = "OK";
    private static final String BINARY_MISSING = "MISSING";
    private static final String BINARY_ZERO = "ZERO_BYTES";
    private static final String BINARY_TOO_SMALL = "TOO_SMALL";
    private static final String BINARY_NOT_EXEC = "NOT_EXECUTABLE";
    private static final String BINARY_EXTRACT_FAILED = "EXTRACT_FAILED";

    /**
     * Locates the bundled cloudflared binary in the app's native library
     * directory and PROVES it executes.
     *
     * WHY nativeLibraryDir and not an app-data path: on Android 10+ the
     * SELinux W^X policy forbids exec() of any file an app extracts into its
     * own data directory (files/, code_cache/) - they are labelled
     * app_data_file, which denies execve() regardless of the permission bits.
     * That is exactly the "error=13, Permission denied" seen in the logs even
     * after chmod 755. The one location an app may execute a bundled binary is
     * the native library directory: the PackageManager extracts jniLibs/*.so
     * there at install time with the correct exec SELinux label. The binary is
     * shipped as app/src/main/jniLibs/arm64-v8a/libcloudflared.so (a real ELF
     * executable that merely happens to end in .so).
     */
    private String prepareCloudflaredBinary() {
        // Remove any stale copy left in app-data paths by older builds.
        try {
            new File(getCodeCacheDir(), "libcloudflared.so").delete();
            new File(getFilesDir(), "cloudflared").delete();
            new File(getFilesDir(), "libcloudflared.so").delete();
        } catch (Exception ignored) {
        }

        binaryFile = new File(getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (!binaryFile.exists() || binaryFile.length() == 0) {
            DebugLog.log(this, "Svc", "Bundled native lib not found at " + binaryFile.getAbsolutePath());
            notifyBinaryProblem(BINARY_MISSING, binaryFile.getAbsolutePath());
            return BINARY_MISSING;
        }
        DebugLog.log(this, "Svc", "Using bundled cloudflared at: " + binaryFile.getAbsolutePath()
                + " (" + binaryFile.length() + " bytes)");

        // Prove it actually runs. nativeLibraryDir files are already executable
        // with the right SELinux label, so no chmod is needed - but we still
        // verify by executing '--version' rather than trusting existence.
        String status = verifyBinaryExecutes();
        if (!BINARY_OK.equals(status)) {
            notifyBinaryProblem(status, null);
        }
        return status;
    }

    /** Spawns 'cloudflared --version' and checks it exits 0. Catches SELinux/exec denials. */
    private String verifyBinaryExecutes() {
        try {
            Process p = new ProcessBuilder(binaryFile.getAbsolutePath(), "--version")
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            int code = p.waitFor();
            String out = sb.toString().trim();
            DebugLog.log(this, "Svc", "cloudflared --version exit=" + code + " : " + out);
            if (code != 0) {
                return BINARY_NOT_EXEC;
            }
            return BINARY_OK;
        } catch (Exception e) {
            DebugLog.log(this, "Svc", "cloudflared exec check FAILED: " + e.getMessage());
            return BINARY_NOT_EXEC;
        }
    }

    /**
     * Checks the file is present and roughly the expected size (~30-35MB).
     * NOTE: cannot rely on canExecute() here - the permission bit is set even
     * on noexec mounts; the real exec check happens in verifyBinaryExecutes().
     */
    private String validateBinaryFile(File file) {
        if (file == null || !file.exists()) {
            return BINARY_MISSING;
        }
        long size = file.length();
        if (size == 0) {
            return BINARY_ZERO;
        }
        if (size < EXPECTED_MIN_BYTES || size > EXPECTED_MAX_BYTES) {
            return BINARY_TOO_SMALL; // out of the expected band
        }
        return BINARY_OK;
    }

    /** Fires a system notification describing a binary problem. */
    private void notifyBinaryProblem(String status, String detail) {
        String title;
        String text;
        switch (status) {
            case BINARY_MISSING:
            case BINARY_ZERO:
                title = "Missing Tunnel Binary!";
                text = "Please add via Termux or curl.";
                break;
            case BINARY_TOO_SMALL:
                title = "Tunnel Binary Looks Wrong";
                text = "cloudflared asset is not the expected ~30-35MB size (got "
                        + (binaryFile != null ? binaryFile.length() : 0) + " bytes).";
                break;
            case BINARY_NOT_EXEC:
                title = "Tunnel Binary Not Executable";
                text = "The cloudflared asset could not be made executable.";
                break;
            default:
                title = "Tunnel Binary Error";
                text = detail != null ? detail : "Failed to extract the cloudflared asset.";
                break;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(2, buildNotification(title, text));
        }
    }

    private void broadcastBinaryStatus(String status) {
        Intent i = new Intent(ACTION_BINARY_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_BINARY_STATUS, status);
        sendBroadcast(i);
    }

    // ---------------------------------------------------------------------
    //  Embedded HTTP server (Android-native, no com.sun.net.httpserver)
    // ---------------------------------------------------------------------

    private void startMcpHttpServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            httpPool.execute(this::acceptLoop);
            Log.d(TAG, "MCP HTTP server listening on port " + PORT);
            DebugLog.log(this, "Svc", "HTTP server listening on port " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind MCP server on port " + PORT, e);
            DebugLog.log(this, "Svc", "HTTP bind FAILED on port " + PORT + ": " + e.getMessage());
        }
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                httpPool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    Log.e(TAG, "Accept failed", e);
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket s = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            // Read headers until blank line.
            String line;
            int contentLength = 0;
            String authHeader = null;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (lower.startsWith("authorization:")) {
                    authHeader = line.substring(14).trim();
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = reader.read(buf, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                body = new String(buf, 0, read);
            }

            if ("/mcp".equals(path) && "GET".equalsIgnoreCase(method)) {
                // SSE (Server-Sent Events) stream: keep the connection open and
                // push an endpoint event + heartbeats so streaming MCP clients
                // (and the spec's "SSE + JSON-RPC multiplexer") can subscribe.
                if (!authAllowed(authHeader)) {
                    out.write(("HTTP/1.1 401 Unauthorized\r\n"
                            + "WWW-Authenticate: Bearer\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String endpointEvent = "event: endpoint\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"endpoint\",\"params\":{\"path\":\"/mcp\",\"port\":" + PORT + "}}\n\n";
                out.write(endpointEvent.getBytes(StandardCharsets.UTF_8));
                out.flush();
                DebugLog.log(this, "Svc", "MCP SSE stream opened on GET /mcp");
                // SSE keepalive with socket health check
while (!s.isClosed() && !s.isInputShutdown() && !Thread.currentThread().isInterrupted()) {
    try {
        s.setSoTimeout(16000);
        out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        Thread.sleep(15000);
    } catch (SocketTimeoutException ste) {
        // Client still connected, continue
    } catch (IOException | InterruptedException e) {
        DebugLog.log(this, "Svc", "SSE client disconnected");
        break;
    }
}

            } else if ("/mcp".equals(path) && "POST".equalsIgnoreCase(method)) {
                // Optional access-code auth: when a code is configured, every
                // request must carry Authorization: Bearer <code>. Blank = open.
                if (!authAllowed(authHeader)) {
                    out.write(("HTTP/1.1 401 Unauthorized\r\n"
                            + "WWW-Authenticate: Bearer\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    DebugLog.log(this, "Svc", "MCP request rejected (401) - missing or wrong auth");
                    return;
                }
                String response = processMcpPayload(body);
                DebugLog.log(this, "Svc", "MCP POST /mcp handled");
                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + respBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(respBytes);
                out.flush();
            } else {
                out.write(("HTTP/1.1 405 Method Not Allowed\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error handling HTTP client", e);
        }
    }

    // ---------------------------------------------------------------------
    //  MCP payload handling
    // ---------------------------------------------------------------------

    private String processMcpPayload(String jsonRpcRequest) {
        try {
            JSONObject req = new JSONObject(jsonRpcRequest);
            String method = req.optString("method");
            JSONObject params = req.optJSONObject("params");
            Object id = req.opt("id");

            JSONObject result = new JSONObject();

            if ("tools/list".equals(method)) {
                JSONArray tools = new JSONArray();

                // Built-in tools. Every tool is listed and gated by its own toggle
                // (toolEnabled combines the category master switch with the per-tool toggle),
                // and each carries a JSON inputSchema so the LLM knows how to call it.
                if (enableOracle) {
    tools.put(new JSONObject()
            .put("name", "execute_oracle_js")
            .put("description", "Executes embedded database JS routines pipeline.")
            .put("inputSchema", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("code", new JSONObject()
                    .put("type", "string")
                    .put("description", "JavaScript source to evaluate with the embedded Rhino engine")))
                .put("required", new JSONArray().put("code"))));
}
if (enableShell) {
    tools.put(new JSONObject()
            .put("name", "run_local_process")
            .put("description", "Runs a local shell command on the device via Runtime.exec and returns its output.")
            .put("inputSchema", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("command", new JSONObject()
                    .put("type", "string")
                    .put("description", "Shell command line to execute on the device.")))
                .put("required", new JSONArray().put("command"))));
}
if (enableTermux) {
    tools.put(new JSONObject()
            .put("name", "run_termux_command")
            .put("description", "Forwards terminal runtime triggers to local system packages via Termux RUN_COMMAND.")
            .put("inputSchema", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("command", new JSONObject()
                    .put("type", "string")
                    .put("description", "Command to run inside Termux.")))
                .put("required", new JSONArray().put("command"))));
}
 

                // User-added custom tools (JS snippet or shell command).
                for (CustomTool t : customTools) {
                    tools.put(buildCustomToolEntry(t));
                }

                // Aggregated external MCP servers (this app acts as an MCP client).
                // Their own inputSchema is passed through so the LLM sees the real
                // parameter contract each remote tool expects.
                if (enableExternal) {
                    for (ExternalServer s : externalServers) {
                        if (!s.enabled) continue;
                        JSONArray remoteTools = fetchRemoteTools(s);
                        cacheExternalToolNames(s.name, remoteTools);
                        for (int i = 0; i < remoteTools.length(); i++) {
                            JSONObject rt = remoteTools.getJSONObject(i);
                            JSONObject entry = new JSONObject()
                                .put("name", "ext_" + s.name + "_" + rt.optString("name"))
                                .put("description", rt.optString("description", "External MCP tool via " + s.url));
                            Object schema = rt.opt("inputSchema");
                            if (schema instanceof JSONObject) {
                                entry.put("inputSchema", (JSONObject) schema);
                            } else {
                                entry.put("inputSchema", new JSONObject().put("type", "object"));
                            }
                            tools.put(entry);
                        }
                    }
                }

                // Management tools (always listed; each has its own toggle too).
                tools.put(new JSONObject()
                        .put("name", "custom_tool_add")
                        .put("description", "Registers a custom tool (JS snippet or shell command) on this multiplexer.")
                        .put("inputSchema", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                .put("name", new JSONObject().put("type", "string"))
                                .put("description", new JSONObject().put("type", "string"))
                                .put("type", new JSONObject().put("type", "string").put("enum", new JSONArray().put("js").put("shell")))
                                .put("code", new JSONObject().put("type", "string"))
                                .put("schema", new JSONObject().put("type", "string").put("description", "Optional JSON Schema string describing how to call this custom tool")))
                            .put("required", new JSONArray().put("name").put("code"))));
                tools.put(new JSONObject()
                        .put("name", "external_mcp_add")
                        .put("description", "Adds a remote MCP server (URL + optional launch command, headers, auth) to aggregate.")
                        .put("inputSchema", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                .put("name", new JSONObject().put("type", "string"))
                                .put("url", new JSONObject().put("type", "string"))
                                .put("launch", new JSONObject().put("type", "string"))
                                .put("headers", new JSONObject().put("type", "string"))
                                .put("auth", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("url"))));
                tools.put(new JSONObject()
                        .put("name", "external_mcp_remove")
                        .put("description", "Removes a previously added remote MCP server.")
                        .put("inputSchema", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject().put("url", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("url"))));

                // Advertise only the tools whose own toggle is enabled.
                JSONArray visible = new JSONArray();
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.getJSONObject(i);
                    if (toolEnabled(t.optString("name"))) {
                        visible.put(t);
                    }
                }
                result.put("tools", visible);
            } else if ("tools/call".equals(method)) {
                String name = params.optString("name");
                JSONObject toolArgs = params.optJSONObject("arguments");
                if (toolArgs == null) toolArgs = new JSONObject();

                if (!toolEnabled(name)) {
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "Tool is disabled: " + name)));
                } else if ("execute_oracle_js".equals(name)) {
                    String code = toolArgs.optString("code");
                    String jsResult = executeEmbeddedJs(code);
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", jsResult)));
                } else if ("run_local_process".equals(name)) {
                    String command = toolArgs.optString("command", "echo hello from local");
                    String out = runLocalProcess(command);
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", out)));
                } else if ("run_termux_command".equals(name)) {
                    String command = toolArgs.optString("command", "echo hello from termux");
                    DebugLog.log(this, "Svc", "MCP tool run_termux_command: " + command);
                    String termuxResult = runTermuxCommand(command);
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", termuxResult)));
                } else if ("custom_tool_add".equals(name)) {
                    String cname = toolArgs.optString("name");
                    String cdesc = toolArgs.optString("description", "Custom tool");
                    String ctype = toolArgs.optString("type", "js"); // js | shell
                    String ccode = toolArgs.optString("code");
                    String cschema = toolArgs.optString("schema", "");
                    customTools.add(new CustomTool(cname, cdesc, ctype, ccode, true, cschema));
                    saveCustomTools();
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "Custom tool '" + cname + "' registered.")));
                } else if ("external_mcp_add".equals(name)) {
                    String sname = toolArgs.optString("name");
                    String url = toolArgs.optString("url");
                    String launch = toolArgs.optString("launch");
                    String headers = toolArgs.optString("headers", "{}");
                    String auth = toolArgs.optString("auth");
                    boolean found = false;
                    for (ExternalServer s : externalServers) {
                        if (s.url.equals(url)) { found = true; break; }
                    }
                    if (!found) {
                        String nm = sname.isEmpty() ? url : sname;
                        externalServers.add(new ExternalServer(nm, url, launch, headers, auth, true));
                        saveExternalServers();
                    }
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "External MCP added: " + url)));
                } else if ("external_mcp_remove".equals(name)) {
                    String url = toolArgs.optString("url");
                    for (int i = externalServers.size() - 1; i >= 0; i--) {
                        if (externalServers.get(i).url.equals(url)) {
                            externalServers.remove(i);
                        }
                    }
                    saveExternalServers();
                    result.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "External MCP removed: " + url)));
                } else {
                    // Custom tool or external-aggregated tool.
                    CustomTool ct = findCustomTool(name);
                    if (ct != null) {
                        String out = executeCustomTool(ct, toolArgs);
                        result.put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", out)));
                    } else if (name.startsWith("ext_")) {
                        // ext_<serverName>_<toolName> or legacy ext_<toolName>
                        String rest = name.substring(4);
                        int sep = rest.indexOf('_');
                        if (sep > 0) {
                            String serverName = rest.substring(0, sep);
                            String realName = rest.substring(sep + 1);
                            String out = proxyExternalMcp(serverName, realName, toolArgs);
                            result.put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "text").put("text", out)));
                        } else {
                            String out = proxyExternalMcp("", rest, toolArgs);
                            result.put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "text").put("text", out)));
                        }
                    } else {
                        result.put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "Unknown or disabled tool: " + name)));
                    }
                }
            }

            return new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("result", result)
                    .put("id", id)
                    .toString();
        } catch (Exception e) {
            Log.e(TAG, "MCP payload error", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}";
        }
    }

    private String executeEmbeddedJs(String script) {
        org.mozilla.javascript.Context rhino = org.mozilla.javascript.Context.enter();
        rhino.setOptimizationLevel(-1);
        try {
            Scriptable scope = rhino.initStandardObjects();
            Object obj = rhino.evaluateString(scope, script, "McpScript", 1, null);
            return org.mozilla.javascript.Context.toString(obj);
        } catch (Exception e) {
            return "Script Error: " + e.getMessage();
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    // ---------------------------------------------------------------------
    //  Local device process tool (Runtime.exec)
    // ---------------------------------------------------------------------

    private String runLocalProcess(String command) {
        try {
            Process p = Runtime.getRuntime().exec(command);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append("[err] ").append(l).append('\n');
            }
            int code = p.waitFor();
            String text = sb.toString().trim();
            return text.isEmpty() ? "(exit=" + code + ", no output)" : text + "\n(exit=" + code + ")";
        } catch (Exception e) {
            return "Local process error: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------------
    //  Custom tools (JS snippet or shell command, added via the UI / MCP)
    // ---------------------------------------------------------------------

    private String executeCustomTool(CustomTool t, JSONObject args) {
        if (args == null) args = new JSONObject();
        if ("shell".equals(t.type)) {
            // Append any string args to the command so a schema-defined tool can
            // receive parameters. Supports both a plain "args" string and a
            // space-joined rendering of all non-empty argument values.
            return runLocalProcess(t.code + " " + renderShellArgs(args));
        }
        // JS: expose the full JSON arguments object as the global 'input' so the
        // snippet can read them, matching the advertised inputSchema.
        String jsonInput = args.toString();
        return executeEmbeddedJsWithInput(t.code, jsonInput);
    }

    /** Space-joins string argument values for a shell custom tool. */
    private String renderShellArgs(JSONObject args) {
        StringBuilder sb = new StringBuilder();
        if (args.has("args") && !args.isNull("args")) {
            return args.optString("args", "").trim();
        }
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = args.opt(k);
            if (v instanceof String && !v.toString().isEmpty()) {
                sb.append(v.toString()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /** Evaluates a JS snippet with the JSON arguments injected as a global 'input' object. */
    private String executeEmbeddedJsWithInput(String script, String jsonInput) {
        org.mozilla.javascript.Context rhino = org.mozilla.javascript.Context.enter();
        rhino.setOptimizationLevel(-1);
        try {
            Scriptable scope = rhino.initStandardObjects();
            try {
                scope.put("input", scope, org.mozilla.javascript.Context.javaToJS(
                        new JSONObject(jsonInput), scope));
            } catch (Exception ignored) {
                scope.put("input", scope, new org.mozilla.javascript.NativeObject());
            }
            Object obj = rhino.evaluateString(scope, script, "McpScript", 1, null);
            return org.mozilla.javascript.Context.toString(obj);
        } catch (Exception e) {
            return "Script Error: " + e.getMessage();
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    /** Builds the tools/list entry for a user custom tool, attaching its JSON
     *  inputSchema (user-provided, or a sensible type-derived fallback). */
    private JSONObject buildCustomToolEntry(CustomTool t) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", t.name);
            entry.put("description", t.description == null ? "" : t.description);
            JSONObject schema = parseToolSchema(t.schema, t.type);
            if (schema != null) {
                entry.put("inputSchema", schema);
            }
        } catch (Exception e) {
            DebugLog.log(this, "Svc", "buildCustomToolEntry failed: " + e.getMessage());
        }
        return entry;
    }

    /** Parses a user-supplied JSON Schema string for a custom tool. When absent
     *  (or invalid), returns a minimal object schema derived from the tool type
     *  so the LLM always has a callable contract for every custom tool. */
    private JSONObject parseToolSchema(String schemaStr, String type) {
        if (schemaStr != null && !schemaStr.trim().isEmpty()) {
            try {
                return new JSONObject(schemaStr);
            } catch (Exception ignored) {
                DebugLog.log(this, "Svc", "Invalid custom-tool schema JSON; using default: " + schemaStr);
            }
        }
        JSONObject props = new JSONObject();
        try {
            if ("shell".equals(type)) {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Arguments to append to the shell command (space-separated).");
                props.put("args", p);
            } else {
                JSONObject p = new JSONObject();
                p.put("type", "object");
                p.put("description", "Arbitrary JSON input passed to the JS snippet as the global 'input' variable.");
                props.put("input", p);
            }
            JSONObject out = new JSONObject();
            out.put("type", "object");
            out.put("properties", props);
            return out;
        } catch (Exception e) {
            try {
                return new JSONObject().put("type", "object");
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private CustomTool findCustomTool(String name) {
        for (CustomTool t : customTools) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    private void loadCustomTools() {
        customTools.clear();
        String raw = getSharedPreferences("mcp_prefs", MODE_PRIVATE).getString("custom_tools", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                customTools.add(new CustomTool(
                        o.optString("name"), o.optString("description"),
                        o.optString("type", "js"), o.optString("code"),
                        o.optBoolean("enabled", true), o.optString("schema", "")));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveCustomTools() {
        try {
            JSONArray arr = new JSONArray();
            for (CustomTool t : customTools) {
                arr.put(new JSONObject()
                        .put("name", t.name).put("description", t.description)
                        .put("type", t.type).put("code", t.code)
                        .put("enabled", t.enabled)
                        .put("schema", t.schema == null ? "" : t.schema));
            }
            getSharedPreferences("mcp_prefs", MODE_PRIVATE).edit()
                    .putString("custom_tools", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveCustomTools failed", e);
        }
    }

    // ---------------------------------------------------------------------
    //  Per-tool toggles (every tool - built-in, custom, external - has its
    //  own persisted on/off state used by both tools/list and tools/call)
    // ---------------------------------------------------------------------

    private void loadToolToggles() {
        toolToggles.clear();
        String raw = getSharedPreferences("mcp_prefs", MODE_PRIVATE).getString("tool_toggles", "{}");
        try {
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                toolToggles.put(k, o.optBoolean(k, true));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveToolToggles() {
        try {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, Boolean> e : toolToggles.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            getSharedPreferences("mcp_prefs", MODE_PRIVATE).edit()
                    .putString("tool_toggles", o.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveToolToggles failed", e);
        }
    }

    /** Whether a tool is enabled. Defaults to true when no explicit toggle exists. */
    private boolean toolEnabled(String name) {
        Boolean v = toolToggles.get(name);
        return v == null || v;
    }

    /** Records a per-tool toggle and persists it. */
    private void setToolEnabled(String name, boolean enabled) {
        toolToggles.put(name, enabled);
        saveToolToggles();
    }

    /** Caches the tool names fetched from an external server so the UI can
     *  list them with per-tool toggles even before a tools/list round-trip. */
    private void cacheExternalToolNames(String serverName, JSONArray tools) {
        try {
            SharedPreferences prefs = getSharedPreferences("mcp_prefs", MODE_PRIVATE);
            JSONObject cache;
            try {
                cache = new JSONObject(prefs.getString("external_tool_cache", "{}"));
            } catch (Exception e) {
                cache = new JSONObject();
            }
            JSONArray names = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                names.put(tools.getJSONObject(i).optString("name"));
            }
            cache.put(serverName, names);
            prefs.edit().putString("external_tool_cache", cache.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    //  External MCP aggregator (this app acts as an MCP client)
    // ---------------------------------------------------------------------

    private void loadExternalServers() {
        externalServers.clear();
        String raw = getSharedPreferences("mcp_prefs", MODE_PRIVATE).getString("external_mcp_urls", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    String url = o.optString("url", "").trim();
                    if (url.isEmpty()) continue;
                    externalServers.add(new ExternalServer(
                            o.optString("name", url), url,
                            o.optString("launch"),
                            o.optString("headers", "{}"),
                            o.optString("auth"),
                            o.optBoolean("enabled", true)));
                } else {
                    // Migrate legacy URL-only entries written by older builds.
                    String url = arr.optString(i, "").trim();
                    if (!url.isEmpty()) {
                        externalServers.add(new ExternalServer(url, url, "", "{}", "", true));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveExternalServers() {
        try {
            JSONArray arr = new JSONArray();
            for (ExternalServer s : externalServers) {
                arr.put(new JSONObject()
                        .put("name", s.name)
                        .put("url", s.url)
                        .put("launch", s.launch)
                        .put("headers", s.headers)
                        .put("auth", s.auth)
                        .put("enabled", s.enabled));
            }
            getSharedPreferences("mcp_prefs", MODE_PRIVATE).edit()
                    .putString("external_mcp_urls", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveExternalServers failed", e);
        }
    }

    /** Fetches the remote tool list from an external MCP server (JSON-RPC POST).
     *  If the server declares a launch command (self-host), it is started first. */
    private JSONArray fetchRemoteTools(ExternalServer s) {
        ensureServerRunning(s);
        JSONArray out = new JSONArray();
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
            String resp = httpPost(s, body);
            JSONObject o = new JSONObject(resp);
            JSONObject res = o.optJSONObject("result");
            JSONArray tools = res != null ? res.optJSONArray("tools") : null;
            if (tools != null) return tools;
        } catch (Exception e) {
            Log.e(TAG, "fetchRemoteTools failed for " + s.url, e);
        }
        return out;
    }

    /** Starts a self-hosted external MCP server via its launch command (once).
     *  The process is kept alive for the service lifetime; output is logged. */
    private void ensureServerRunning(ExternalServer s) {
        if (s.launch == null || s.launch.trim().isEmpty()) return;
        for (Process p : launchedServers) {
            if (p.isAlive() && launchedServerCmd.equals(s.launch)) return;
        }
        try {
            Process p = Runtime.getRuntime().exec(s.launch);
            launchedServers.add(p);
            launchedServerCmd = s.launch;
            DebugLog.log(this, "Svc", "Launched self-hosted MCP server: " + s.launch);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch external MCP server: " + s.launch, e);
        }
    }

    /** Forwards a tool call to the external MCP server that owns it and returns its text. */
    private String proxyExternalMcp(String serverName, String toolName, JSONObject args) {
        ExternalServer target = null;
        for (ExternalServer s : externalServers) {
            if (s.name.equals(serverName)) { target = s; break; }
        }
        if (target == null) {
            // Fall back to the first enabled server for backward compatibility.
            for (ExternalServer s : externalServers) {
                if (s.enabled) { target = s; break; }
            }
        }
        if (target == null) return "No external MCP servers configured.";
        try {
            JSONObject call = new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tools/call")
                    .put("id", System.currentTimeMillis())
                    .put("params", new JSONObject()
                            .put("name", toolName)
                            .put("arguments", args));
            String resp = httpPost(target, call.toString());
            JSONObject o = new JSONObject(resp);
            JSONObject res = o.optJSONObject("result");
            JSONArray content = res != null ? res.optJSONArray("content") : null;
            if (content != null && content.length() > 0) {
                return content.getJSONObject(0).optString("text", resp);
            }
            return resp;
        } catch (Exception e) {
            return "External MCP proxy error: " + e.getMessage();
        }
    }

    /** Minimal HTTP POST helper (JSON in, JSON out) with per-server headers + auth. */
    private String httpPost(ExternalServer s, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(s.url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        // Custom headers from the server config (JSON object of name->value).
        try {
            JSONObject h = new JSONObject(s.headers);
            java.util.Iterator<String> keys = h.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                conn.setRequestProperty(k, h.optString(k));
            }
        } catch (Exception ignored) {
        }
        // Auth token -> Authorization: Bearer <token> unless a header already set it.
        if (!s.auth.isEmpty() && conn.getRequestProperty("Authorization") == null) {
            conn.setRequestProperty("Authorization", "Bearer " + s.auth);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** A remote MCP server this app aggregates: URL + optional launch command,
     *  custom headers (JSON), and an auth token. */
    private static class ExternalServer {
        final String name;
        final String url;
        final String launch;   // optional local launch command (self-host), e.g. "npx -y @modelcontextprotocol/server-github"
        final String headers;  // JSON object of extra HTTP headers
        final String auth;     // bearer token
        final boolean enabled;

        ExternalServer(String name, String url, String launch, String headers, String auth, boolean enabled) {
            this.name = name;
            this.url = url;
            this.launch = launch;
            this.headers = headers;
            this.auth = auth;
            this.enabled = enabled;
        }
    }


    /** A user-defined tool: a JS snippet (Rhino) or a shell command (Runtime.exec). */
    private static class CustomTool {
        final String name;
        final String description;
        final String type; // "js" | "shell"
        final String code;
        final boolean enabled;
        final String schema; // JSON Schema (JSON string) telling the LLM how to call it; may be empty

        CustomTool(String name, String description, String type, String code, boolean enabled, String schema) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.code = code;
            this.enabled = enabled;
            this.schema = schema;
        }
    }

    // ---------------------------------------------------------------------
    //  Termux integration
    // ---------------------------------------------------------------------

    /**
     * Sends a background command to Termux and captures the result via the
     * plugin-result broadcast. Used by the run_termux_command MCP tool.
     */
    private String runTermuxCommand(String command) {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            return "Termux is not installed. Install Termux from F-Droid first.";
        }
        // The command string is split on whitespace: first token = executable
        // (absolute path inside Termux), rest = arguments.
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0) {
            return "Empty command.";
        }
        String executable = tokens[0];
        if (!executable.startsWith("/")) {
            executable = "/data/data/com.termux/files/usr/bin/" + executable;
        }
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, tokens.length - 1);

        PendingIntent pi = TermuxBridge.buildResultPendingIntent(this, (int) System.currentTimeMillis());
        String error = TermuxBridge.sendRunCommand(this, executable, args, null, pi);
        if (error != null) {
            return error;
        }
        // The actual output arrives asynchronously via termuxResultReceiver and
        // is also posted as a notification. Return an immediate ack here.
        return "Command dispatched to Termux (background). Result will arrive via notification.";
    }

    private void notifyTermuxResult(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(3, buildNotification("Termux Result", text));
        }
    }

    // ---------------------------------------------------------------------
    //  Cloudflare tunnel
    // ---------------------------------------------------------------------

    private void startTokenTunnel(String token) {
        if (binaryFile == null || !binaryFile.exists()) {
            Log.e(TAG, "cloudflared binary missing, cannot start tunnel");
            return;
        }
        tunnelMode = MODE_TOKEN;
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath(),
                    "tunnel", "run", "--token", token);
            pb.redirectErrorStream(true);
            // cloudflared needs a writable HOME (it caches its credentials/config)
            // and TMPDIR; the app process has neither set on Android.
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            cloudflaredProcess = pb.start();
            DebugLog.log(this, "Svc", "cloudflared token tunnel process started");
            broadcastTunnelInfo(MODE_TOKEN, null, true);
            drainCloudflaredOutput(cloudflaredProcess, MODE_TOKEN);
            Log.d(TAG, "Cloudflare outbound daemon executed.");
        } catch (IOException e) {
            Log.e(TAG, "Failed executing cloudflared binary daemon", e);
            DebugLog.log(this, "Svc", "cloudflared launch FAILED: " + e.getMessage());
            notifyBinaryProblem(BINARY_EXTRACT_FAILED, "Failed to launch cloudflared: " + e.getMessage());
        }
    }

    /**
     * Quick tunnel: no Cloudflare account or domain needed. Cloudflare returns a
     * random https://&lt;random&gt;.trycloudflare.com URL, which we capture from the
     * process output and broadcast so the UI can display it and persist it.
     */
    private void startQuickTunnel() {
        if (binaryFile == null || !binaryFile.exists()) {
            Log.e(TAG, "cloudflared binary missing, cannot start quick tunnel");
            return;
        }
        tunnelMode = MODE_QUICK;
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath(),
                    "tunnel", "--url", "http://127.0.0.1:" + PORT,
                    "--protocol", "http2", "--no-autoupdate");
            pb.redirectErrorStream(true);
            // cloudflared needs a writable HOME (it caches its credentials/config)
            // and TMPDIR; the app process has neither set on Android.
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            cloudflaredProcess = pb.start();
            DebugLog.log(this, "Svc", "cloudflared quick tunnel process started (awaiting URL)");
            broadcastTunnelInfo(MODE_QUICK, null, true);
            drainCloudflaredOutput(cloudflaredProcess, MODE_QUICK);
            Log.d(TAG, "Cloudflare quick tunnel executed.");
        } catch (IOException e) {
            Log.e(TAG, "Failed executing cloudflared quick tunnel", e);
            DebugLog.log(this, "Svc", "cloudflared quick tunnel launch FAILED: " + e.getMessage());
            notifyBinaryProblem(BINARY_EXTRACT_FAILED, "Failed to launch cloudflared: " + e.getMessage());
        }
    }

    /** Streams cloudflared's stdout; in quick mode watches for the assigned URL. */
    private void drainCloudflaredOutput(Process proc, final String mode) {
        new Thread(() -> {
            final boolean[] urlReceived = {false};
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    Log.d(TAG, "[cloudflared] " + l);
                    if (MODE_QUICK.equals(mode)) {
                        String url = extractTryCloudflareUrl(l);
                        if (url != null) {
                            urlReceived[0] = true;
                            broadcastTunnelInfo(MODE_QUICK, url, true);
                            DebugLog.log(this, "Svc", "Quick tunnel URL: " + url);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "cloudflared output stream closed", e);
            }
            if (MODE_QUICK.equals(mode) && (cloudflaredProcess == null
                    || !cloudflaredProcess.isAlive())) {
                if (!urlReceived[0]) {
                    DebugLog.log(this, "Svc", "cloudflared exited before a quick-tunnel URL was received");
                    broadcastTunnelInfo(MODE_QUICK, null, false);
                } else {
                    // Keep the assigned URL on screen - do not wipe it with a
                    // 'not connected' broadcast after the process stops.
                    DebugLog.log(this, "Svc", "cloudflared tunnel process ended (URL was assigned)");
                }
            }
        }).start();
    }

    /** Pulls the ASSIGNED random https://*.trycloudflare.com tunnel URL out of a
     *  cloudflared log line, ignoring the api.trycloudflare.com registration
     *  endpoint it is shouting next to on some network paths.
     *
     *  cloudflared prints the banner:
     *      |  https://<random>.trycloudflare.com  |
     *  before the registration request. That random host is the real public
     *  tunnel endpoint we advertise; any api.trycloudflare.com/... URL is the
     *  backend we talk to, never the tunnel we expose. */
    private String extractTryCloudflareUrl(String line) {
        if (line == null || !line.contains("trycloudflare.com")) return null;
        String lower = line.toLowerCase(Locale.US);
        // Skip the registration API host explicitly so an error line such as
        // "https://api.trycloudflare.com/tunnel ..." is never captured.
        if (lower.contains("api.trycloudflare.com/")) return null;
        int i = lower.indexOf("https://");
        if (i < 0) return null;
        int j = i;
        while (j < line.length() && !Character.isWhitespace(line.charAt(j))) j++;
        String url = line.substring(i, j).trim();
        // Strip trailing punctuation the banner format may attach: , . ) | ] "
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (last == ',' || last == '.' || last == ')' || last == '|'
                    || last == ']' || last == '"') {
                url = url.substring(0, url.length() - 1).trim();
            } else {
                break;
            }
        }
        // Only accept an actual assigned tunnel host: <something>.trycloudflare.com,
        // never a bare api./dash. subdomain.
        if (!url.toLowerCase(Locale.US).matches("https://[a-z0-9.-]+\\.trycloudflare\\.com/?.*")) {
            return null;
        }
        return url.isEmpty() ? null : url;
    }

    private void broadcastTunnelInfo(String mode, String url, boolean active) {
        Intent i = new Intent(ACTION_TUNNEL_INFO);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_TUNNEL_MODE, mode);
        i.putExtra(EXTRA_TUNNEL_URL, url);
        i.putExtra(EXTRA_TUNNEL_ACTIVE, active);
        sendBroadcast(i);
    }

    /**
     * Access-code gate for the public /mcp endpoint.
     * Blank configured code => open endpoint. Otherwise the request must carry
     * Authorization: Bearer &lt;code&gt; (a raw code is tolerated as well).
     */
    private boolean authAllowed(String authHeader) {
        String code = authCode == null ? "" : authCode.trim();
        if (code.isEmpty()) return true;
        if (authHeader == null) return false;
        String h = authHeader.trim();
        if (h.toLowerCase().startsWith("bearer ")) {
            return code.equals(h.substring(7).trim());
        }
        return code.equals(h);
    }

    // ---------------------------------------------------------------------
    //  Notifications / lifecycle
    // ---------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "MCP Server", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return buildNotification("MCP Multiplexer Server", text);
    }

    private Notification buildNotification(String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(termuxResultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        DebugLog.log(this, "Svc", "Service destroyed");
        broadcastTunnelInfo(tunnelMode, null, false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        httpPool.shutdownNow();
        if (cloudflaredProcess != null) {
            cloudflaredProcess.destroy();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
