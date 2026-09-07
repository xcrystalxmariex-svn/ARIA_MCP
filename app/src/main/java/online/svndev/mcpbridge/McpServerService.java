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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

        // Reload custom tools / external MCP servers from prefs without a restart.
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            loadCustomTools();
            loadExternalServers();
            loadToolToggles();
            DebugLog.log(this, "Svc", "Reloaded custom tools (" + customTools.size()
                    + ") and external MCP servers (" + externalServers.size() + ")");
            return START_NOT_STICKY;
        }

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
     * Copies the bundled 'cloudflared' asset into the native library directory or 
     * code cache with a .so extension to bypass Android's noexec restrictions, 
     * makes it executable and validates it.
     */
    private String prepareCloudflaredBinary() {
        // Use nativeLibraryDir if writable, or fall back to code_cache with a .so extension
        // which modern Android runtimes permit for loading/executing binaries.
        File libDir = new File(getApplicationInfo().nativeLibraryDir);
        binaryFile = new File(libDir, "libcloudflared.so");
        
        if (!binaryFile.getParentFile().canWrite()) {
            binaryFile = new File(getCodeCacheDir(), "libcloudflared.so");
        }

        // 1) If a previous copy exists and looks valid, reuse it.
        if (binaryFile.exists() && binaryFile.length() > 0) {
            String status = validateBinaryFile(binaryFile);
            if (BINARY_OK.equals(status)) {
                return BINARY_OK;
            }
            // stale/broken copy -> remove and re-extract
            //noinspection ResultOfMethodCallIgnored
            binaryFile.delete();
        }

        // 2) Extract from assets.
        try (InputStream in = getAssets().open("cloudflared");
             FileOutputStream out = new FileOutputStream(binaryFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            Log.i(TAG, "Extracted cloudflared: " + total + " bytes");
            DebugLog.log(this, "Svc", "Extracted cloudflared: " + total + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract cloudflared asset", e);
            DebugLog.log(this, "Svc", "Extract FAILED: " + e.getMessage());
            notifyBinaryProblem(BINARY_EXTRACT_FAILED, e.getMessage());
            return BINARY_EXTRACT_FAILED;
        }

        // 3) Make executable.
        if (!binaryFile.setExecutable(true, false)) {
            Log.e(TAG, "setExecutable failed for " + binaryFile.getAbsolutePath());
            DebugLog.log(this, "Svc", "setExecutable FAILED");
            notifyBinaryProblem(BINARY_NOT_EXEC, null);
            return BINARY_NOT_EXEC;
        }

        // 4) Final validation.
        String status = validateBinaryFile(binaryFile);
        if (!BINARY_OK.equals(status)) {
            notifyBinaryProblem(status, null);
        }
        return status;
    }

    /**
     * Checks the file is present, roughly the expected size (~30-35MB) and
     * executable. Returns BINARY_OK or a failure constant.
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
        if (!file.canExecute()) {
            return BINARY_NOT_EXEC;
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
                while (true) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
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
                // (toolEnabled combines the category master switch with the per-tool toggle).
                tools.put(new JSONObject()
                        .put("name", "execute_oracle_js")
                        .put("description", "Executes embedded database JS routines pipeline."));
                tools.put(new JSONObject()
                        .put("name", "run_local_process")
                        .put("description", "Runs a local shell command on the device via Runtime.exec and returns its output."));
                tools.put(new JSONObject()
                        .put("name", "run_termux_command")
                        .put("description", "Forwards terminal runtime triggers to local system packages via Termux RUN_COMMAND."));

                // User-added custom tools (JS snippet or shell command).
                for (CustomTool t : customTools) {
                    tools.put(new JSONObject()
                            .put("name", t.name)
                            .put("description", t.description));
                }

                // Aggregated external MCP servers (this app acts as an MCP client).
                if (enableExternal) {
                    for (ExternalServer s : externalServers) {
                        if (!s.enabled) continue;
                        JSONArray remoteTools = fetchRemoteTools(s);
                        cacheExternalToolNames(s.name, remoteTools);
                        for (int i = 0; i < remoteTools.length(); i++) {
                            JSONObject rt = remoteTools.getJSONObject(i);
                            tools.put(new JSONObject()
                                .put("name", "ext_" + s.name + "_" + rt.optString("name"))
                                .put("description", rt.optString("description", "External MCP tool via " + s.url)));
                        }
                    }
                }

                // Management tools (always listed; each has its own toggle too).
                tools.put(new JSONObject()
                        .put("name", "custom_tool_add")
                        .put("description", "Registers a custom tool (JS snippet or shell command) on this multiplexer."));
                tools.put(new JSONObject()
                        .put("name", "external_mcp_add")
                        .put("description", "Adds a remote MCP server (URL + optional launch command, headers, auth) to aggregate."));
                tools.put(new JSONObject()
                        .put("name", "external_mcp_remove")
                        .put("description", "Removes a previously added remote MCP server."));

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
                    customTools.add(new CustomTool(cname, cdesc, ctype, ccode, true));
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
                        String out = executeCustomTool(ct);
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

    private String executeCustomTool(CustomTool t) {
        if ("shell".equals(t.type)) {
            return runLocalProcess(t.code);
        }
        return executeEmbeddedJs(t.code);
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
                        o.optBoolean("enabled", true)));
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
                        .put("enabled", t.enabled));
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
                JSONObject o = arr.getJSONObject(i);
                // Backward compatible: a plain string entry becomes a server with just a URL.
                if (o.has("url")) {
                    externalServers.add(new ExternalServer(
                            o.optString("name", o.optString("url")),
                            o.optString("url"),
                            o.optString("launch"),
                            o.optString("headers", "{}"),
                            o.optString("auth"),
                            o.optBoolean("enabled", true)));
                } else {
                    externalServers.add(new ExternalServer(
                            o.optString("url"), o.optString("url"),
                            "", "{}", "", true));
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

        CustomTool(String name, String description, String type, String code, boolean enabled) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.code = code;
            this.enabled = enabled;
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
                    "tunnel", "--url", "http://127.0.0.1:" + PORT, "--no-autoupdate");
            pb.redirectErrorStream(true);
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
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    Log.d(TAG, "[cloudflared] " + l);
                    if (MODE_QUICK.equals(mode)) {
                        String url = extractTryCloudflareUrl(l);
                        if (url != null) {
                            broadcastTunnelInfo(MODE_QUICK, url, true);
                            DebugLog.log(this, "Svc", "Quick tunnel URL: " + url);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "cloudflared output stream closed", e);
            }
        }).start();
    }

    /** Pulls the random https://*.trycloudflare.com URL out of a cloudflared log line. */
    private String extractTryCloudflareUrl(String line) {
        if (line == null || !line.contains("trycloudflare.com")) return null;
        int i = line.indexOf("https://");
        if (i < 0) return null;
        int j = i;
        while (j < line.length() && !Character.isWhitespace(line.charAt(j))) j++;
        String url = line.substring(i, j).trim();
        while (url.endsWith(",") || url.endsWith(".") || url.endsWith(")")) {
            url = url.substring(0, url.length() - 1).trim();
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
