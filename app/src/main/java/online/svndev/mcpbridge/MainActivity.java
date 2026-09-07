package online.svndev.mcpbridge;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CODE_NOTIFICATIONS = 1001;
    private static final int REQ_CODE_FOREGROUND_SERVICE = 1002;

    private static final String PREFS_NAME = "mcp_prefs";
    private static final String KEY_SETUP_SHOWN = "guided_setup_shown";
    private static final String KEY_TUNNEL_TOKEN = "tunnel_token";
    private static final String KEY_TUNNEL_MODE = "tunnel_mode";
    private static final String KEY_AUTH_CODE = "auth_code";
    private static final String KEY_LAST_ENDPOINT = "last_endpoint_url";

    private static final String MODE_TOKEN = "token";
    private static final String MODE_QUICK = "quick";

    private static final int DEBUG_MAX_CHARS = 12000;

    private SwitchMaterial switchServer;
    private EditText editToken;
    private EditText editAuthCode;
    private RadioGroup rgTunnelMode;
    private TextView txtEndpoint;
    private TextView txtDiagnostic;
    private TextView txtDebugLog;
    private ScrollView scrollDebug;
    private CheckBox chkOracleDb;
    private CheckBox chkLocalShell;
    private CheckBox chkTermux;
    private CheckBox chkExternalMcp;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean termuxTestPending;
    private int termuxRequestSeq = 0;
    private int termuxActiveRunId = -1;
    private final Runnable termuxTestTimeout = new Runnable() {
        @Override
        public void run() {
            if (!termuxTestPending) return;
            termuxTestPending = false;
            appendDiagnostic("Termux test FAILED: no response after 30 seconds. Enable Allow external apps in Termux, then retry.");
            DebugLog.log(MainActivity.this, "UI", "Termux test timed out; check allow-external-apps");
        }
    };

    // ------------------------------------------------------------------
    // Receivers
    // ------------------------------------------------------------------

    private final BroadcastReceiver binaryStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (McpServerService.ACTION_BINARY_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(McpServerService.EXTRA_BINARY_STATUS);
                updateDiagnostic(status);
            }
        }
    };

    private final BroadcastReceiver termuxResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TermuxBridge.ACTION_APP_RESULT.equals(intent.getAction())) {
                String text = TermuxBridge.extractResultText(intent);
                // A result can still legitimately arrive after the watchdog fired
                // (Termux cold start, slow device). Report it either way - never
                // silently drop a late response so we always learn the truth.
                boolean late = !termuxTestPending;
                termuxTestPending = false;
                mainHandler.removeCallbacks(termuxTestTimeout);
                appendDiagnostic((late ? "Termux test result (after timeout): " : "Termux test PASS/RESULT: ") + text);
                DebugLog.log(MainActivity.this, "Termux", "Result received (late=" + late + "): " + text);
            }
        }
    };

    private final BroadcastReceiver debugLineReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DebugLog.ACTION_DEBUG_LINE.equals(intent.getAction())) {
                String line = intent.getStringExtra(DebugLog.EXTRA_LINE);
                if (line != null) {
                    appendDebugLine(line);
                }
            }
        }
    };

    private final BroadcastReceiver tunnelInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (McpServerService.ACTION_TUNNEL_INFO.equals(intent.getAction())) {
                String mode = intent.getStringExtra(McpServerService.EXTRA_TUNNEL_MODE);
                String url = intent.getStringExtra(McpServerService.EXTRA_TUNNEL_URL);
                boolean active = intent.getBooleanExtra(McpServerService.EXTRA_TUNNEL_ACTIVE, false);
                updateEndpoint(mode, active, url);
            }
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        loadSettingsIntoViews();
        refreshEndpointFromPrefs();

        // First launch: request the runtime permissions Android actually shows
        // dialogs for, then walk the user through the one-time setup steps.
        if (isFirstRun()) {
            requestLaunchPermissions();
            switchServer.postDelayed(this::showGuidedSetupDialog, 700);
        }

        refreshBinaryDiagnostic();
        wireControls();
        registerReceivers();

        DebugLog.log(this, "UI", "MainActivity started");
    }

    private void bindViews() {
        switchServer = findViewById(R.id.switchServer);
        editToken = findViewById(R.id.editToken);
        editAuthCode = findViewById(R.id.editAuthCode);
        rgTunnelMode = findViewById(R.id.rgTunnelMode);
        txtEndpoint = findViewById(R.id.txtEndpoint);
        txtDiagnostic = findViewById(R.id.txtDiagnostic);
        txtDebugLog = findViewById(R.id.txtDebugLog);
        scrollDebug = findViewById(R.id.scrollDebug);
        chkOracleDb = findViewById(R.id.chkOracleDb);
        chkLocalShell = findViewById(R.id.chkLocalShell);
        chkTermux = findViewById(R.id.chkTermux);
        chkExternalMcp = findViewById(R.id.chkExternalMcp);
    }

    private void registerReceivers() {
    registerReceiver(binaryStatusReceiver,
            new IntentFilter(McpServerService.ACTION_BINARY_STATUS));
    registerReceiver(termuxResultReceiver,
            new IntentFilter(TermuxBridge.ACTION_APP_RESULT));
    registerReceiver(debugLineReceiver,
            new IntentFilter(DebugLog.ACTION_DEBUG_LINE));
    registerReceiver(tunnelInfoReceiver,
            new IntentFilter(McpServerService.ACTION_TUNNEL_INFO));
}


    private void wireControls() {
        switchServer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    switchServer.setText("Server Status: Starting...");
                    saveSettings();
                    String mode = rgTunnelMode.getCheckedRadioButtonId() == R.id.rbModeQuick
                            ? MODE_QUICK : MODE_TOKEN;
                    Intent intent = new Intent(MainActivity.this, McpServerService.class);
                    intent.setAction(McpServerService.ACTION_START);
                    intent.putExtra("tunnel_token", editToken.getText().toString().trim());
                    intent.putExtra("tunnel_mode", mode);
                    intent.putExtra("auth_code", editAuthCode.getText().toString().trim());
                    intent.putExtra("chk_oracle", chkOracleDb.isChecked());
                    intent.putExtra("chk_shell", chkLocalShell.isChecked());
                    intent.putExtra("chk_termux", chkTermux.isChecked());
                    intent.putExtra("chk_external", chkExternalMcp.isChecked());
                    startService(intent);
                    DebugLog.log(MainActivity.this, "UI", "Start requested by switch (mode=" + mode + ")");
                } else {
                    switchServer.setText("Server Status: Stopped");
                    Intent intent = new Intent(MainActivity.this, McpServerService.class);
                    intent.setAction(McpServerService.ACTION_STOP);
                    startService(intent);
                    DebugLog.log(MainActivity.this, "UI", "Stop requested by switch");
                }
            }
        });

        // Persist settings as the user types / toggles mode.
        TextWatcher settingsWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { saveSettings(); }
        };
        editToken.addTextChangedListener(settingsWatcher);
        editAuthCode.addTextChangedListener(settingsWatcher);

        rgTunnelMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean tokenMode = checkedId == R.id.rbModeToken;
                editToken.setEnabled(tokenMode);
                saveSettings();
                DebugLog.log(MainActivity.this, "UI",
                        "Tunnel mode set: " + (tokenMode ? MODE_TOKEN : MODE_QUICK));
            }
        });

        findViewById(R.id.btnTermuxTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxTest();
            }
        });

        // Help buttons - one per control / tool.
        findViewById(R.id.btnHelpServer).setOnClickListener(v -> showHelpDialog(
                "Server Switch",
                "Turns the local MCP server and the tunnel on or off.\n\n" +
                "ON: uses the installed cloudflared native binary, validates it, starts the " +
                "embedded HTTP server on port 8000 (/mcp), then starts the tunnel in the " +
                "mode chosen under Connection Setup - your Cloudflare token/custom domain " +
                "or a random trycloudflare URL. The Endpoint line shows the public URL; " +
                "Diagnostic + Debug panels show live status.\n\n" +
                "OFF: stops the server and the tunnel.",
                null));
        findViewById(R.id.btnHelpToken).setOnClickListener(v -> showHelpDialog(
                "Cloudflare Tunnel Token",
                "Only used in token mode. Paste the token from your Cloudflare dashboard:\n" +
                "Cloudflare Zero Trust -> Networks -> Tunnels -> your tunnel -> Configure -> " +
                "\"token\" field (starts with eyJ...).\n\n" +
                "The public hostname (e.g. https://mcp.yourdomain.com/mcp) is configured in " +
                "the dashboard for that token - the app does NOT hard-code any domain, so each " +
                "user brings their own. Quick-tunnel mode ignores this field.\n\n" +
                "Without a valid token the tunnel cannot connect, but the local MCP server " +
                "still starts.",
                null));
        findViewById(R.id.btnHelpAuth).setOnClickListener(v -> showHelpDialog(
                "Optional Access Code (Bearer auth)",
                "Protects the public /mcp endpoint. When set, every MCP request must send " +
                "the header: Authorization: Bearer <your code>. Leave blank to keep the " +
                "endpoint open (no auth). Set the same code in your MCP client so it can " +
                "connect. Example client call:",
                "curl -s -X POST https://YOUR-ENDPOINT/mcp -H \"Content-Type: application/json\" -H \"Authorization: Bearer YOUR_CODE\" -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}'"));
        findViewById(R.id.btnCopyEndpoint).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyEndpoint();
            }
        });
        findViewById(R.id.btnHelpTermux).setOnClickListener(v -> showHelpDialog(
                "Test Termux RUN_COMMAND",
                "Sends a background echo command to Termux and prints the captured output in " +
                "the diagnostic box. Requires the one-time Termux setup below.",
                "echo allow-external-apps=true >> ~/.termux/termux.properties"));
        findViewById(R.id.btnHelpOracle).setOnClickListener(v -> showHelpDialog(
                "Oracle DB JS Engine",
                "Enables the execute_oracle_js MCP tool, which evaluates JavaScript snippets " +
                "locally (Rhino) as a stand-in pipeline for database-side JS routines.",
                null));
        findViewById(R.id.btnHelpShell).setOnClickListener(v -> showHelpDialog(
                "Device Native Process Shell",
                "Enables running local processes through the device shell. Kept off by default " +
                "because it can execute anything on the phone.",
                null));
        findViewById(R.id.btnHelpTermuxTool).setOnClickListener(v -> showHelpDialog(
                "Termux Native App Bridge",
                "Exposes the run_termux_command MCP tool, which forwards commands to Termux via " +
                "the RUN_COMMAND intent. Requires Termux (F-Droid) plus the allow-external-apps " +
                "setting - see the Test button help for the setup command.",
                "echo allow-external-apps=true >> ~/.termux/termux.properties"));
        findViewById(R.id.btnHelpExternal).setOnClickListener(v -> showHelpDialog(
                "External Secondary MCP",
                "When enabled, this app acts as an MCP client: it connects to other " +
                "public or remote SSE/JSON-RPC MCP servers, reads their tool schemas, and " +
                "re-exposes them under the single /mcp endpoint (names prefixed ext_). " +
                "Add server URLs below and toggle this on.",
                null));

        // Add Custom Tool: JS snippet (Rhino) or shell command.
        findViewById(R.id.btnAddCustomTool).setOnClickListener(v -> showAddCustomToolDialog());

        // Manage all tools: every tool (built-in, custom, external) with its own toggle.
        findViewById(R.id.btnManageAllTools).setOnClickListener(v -> showManageAllToolsDialog());

        // Add an external MCP server (URL + launch / headers / auth).
        findViewById(R.id.btnAddExternalMcp).setOnClickListener(v -> showAddExternalMcpDialog());

        // Manage connected external MCP servers (view / toggle / remove).
        findViewById(R.id.btnManageExternalMcp).setOnClickListener(v -> showManageExternalMcpDialog());

        // Connection formats reference for 3rd-party LLM wiring.
        findViewById(R.id.btnFormatsRef).setOnClickListener(v -> showFormatsReference());

        // Debug panel controls.
        findViewById(R.id.btnDebugClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                txtDebugLog.setText("(debug log cleared)");
                DebugLog.clear();
            }
        });
        findViewById(R.id.btnDebugCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String dump = DebugLog.dump();
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("MCP debug log", dump));
                Toast.makeText(MainActivity.this, "Debug log copied to clipboard",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------
    // First-run permission prompts
    // ------------------------------------------------------------------

    private boolean isFirstRun() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean shown = prefs.getBoolean(KEY_SETUP_SHOWN, false);
        if (!shown) {
            prefs.edit().putBoolean(KEY_SETUP_SHOWN, true).apply();
        }
        return !shown;
    }

    private void requestLaunchPermissions() {
        // Android 13+: POST_NOTIFICATIONS is a real runtime dialog. String literal
        // keeps this compiling against compileSdk 30 in AIDE.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_CODE_NOTIFICATIONS);
            }
        }
        // Android 14+: foreground services need FOREGROUND_SERVICE at runtime.
        if (Build.VERSION.SDK_INT >= 34) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.FOREGROUND_SERVICE},
                        REQ_CODE_FOREGROUND_SERVICE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CODE_NOTIFICATIONS) {
            DebugLog.log(this, "UI", "Notifications permission granted=" + granted);
            Toast.makeText(this,
                    granted ? "Notifications permission granted."
                            : "Notifications denied - alerts will be silent.",
                    Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_CODE_FOREGROUND_SERVICE) {
            DebugLog.log(this, "UI", "Foreground service permission granted=" + granted);
            Toast.makeText(this,
                    granted ? "Foreground service permission granted."
                            : "Foreground service denied - server cannot run in background.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    //  Connection settings (tunnel mode / access code / endpoint)
    // ------------------------------------------------------------------

    private void loadSettingsIntoViews() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String token = prefs.getString(KEY_TUNNEL_TOKEN, "");
        editToken.setText(token);
        String mode = prefs.getString(KEY_TUNNEL_MODE, MODE_TOKEN);
        boolean tokenMode = MODE_TOKEN.equals(mode);
        rgTunnelMode.check(tokenMode ? R.id.rbModeToken : R.id.rbModeQuick);
        editToken.setEnabled(tokenMode);
        editAuthCode.setText(prefs.getString(KEY_AUTH_CODE, ""));
    }

    private void saveSettings() {
        if (editToken == null || editAuthCode == null || rgTunnelMode == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_TUNNEL_TOKEN, editToken.getText().toString().trim())
                .putString(KEY_TUNNEL_MODE,
                        rgTunnelMode.getCheckedRadioButtonId() == R.id.rbModeQuick
                                ? MODE_QUICK : MODE_TOKEN)
                .putString(KEY_AUTH_CODE, editAuthCode.getText().toString().trim())
                .apply();
    }

    private void refreshEndpointFromPrefs() {
        if (txtEndpoint == null) return;
        String url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_ENDPOINT, "");
        txtEndpoint.setText(url.isEmpty()
                ? "ENDPOINT: not connected"
                : "ENDPOINT: " + url + " (last known)");
    }

    private void updateEndpoint(String mode, boolean active, String url) {
        if (txtEndpoint == null) return;
        if (!active) {
            txtEndpoint.setText("ENDPOINT: not connected");
            return;
        }
        if (MODE_QUICK.equals(mode)) {
            if (url != null && !url.isEmpty()) {
                txtEndpoint.setText("ENDPOINT: " + url);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_ENDPOINT, url).apply();
                DebugLog.log(this, "UI", "Endpoint URL live: " + url);
            } else {
                txtEndpoint.setText("ENDPOINT: connecting (waiting for trycloudflare URL)...");
            }
        } else {
            // Token mode: the public hostname lives in the user's Cloudflare dashboard.
            txtEndpoint.setText("ENDPOINT: custom-domain tunnel active (hostname set in Cloudflare)");
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(KEY_LAST_ENDPOINT).apply();
        }
    }

    private void copyEndpoint() {
        if (txtEndpoint == null) return;
        String text = txtEndpoint.getText().toString();
        String url = text.startsWith("ENDPOINT: ")
                ? text.substring("ENDPOINT: ".length()) : text;
        int idx = url.indexOf(" (");
        if (idx > 0) url = url.substring(0, idx);
        if (url.startsWith("http")) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("MCP endpoint URL", url));
            Toast.makeText(this, "Endpoint URL copied", Toast.LENGTH_SHORT).show();
            DebugLog.log(this, "UI", "Endpoint URL copied to clipboard");
        } else {
            Toast.makeText(this, "No public URL to copy yet", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    //  Guided first-run setup dialog (step-by-step walkthrough)
    // ------------------------------------------------------------------

    private void showGuidedSetupDialog() {
        String message =
                "Welcome! Complete these steps once:\n\n" +
                "1. CONNECT: pick a mode under Connection Setup - paste your own " +
                "Cloudflare tunnel token (custom domain) or choose Random trycloudflare " +
                "URL for a free public address with no domain needed.\n\n" +
                "2. SECURE (optional but recommended): set an access code so only " +
                "clients that send it can call your server. Leave blank to bypass.\n\n" +
                "3. INSTALL Termux from F-Droid if you want the Termux tools:\n" +
                "   https://f-droid.org/en/packages/com.termux/\n\n" +
                "4. ALLOW external apps in Termux by running this inside Termux once:\n" +
                "   (tap Copy below, paste into Termux, press Enter)\n\n" +
                "5. Flip the Server switch. The Endpoint line shows your public URL " +
                "(Copy URL to grab it). Watch Diagnostic and Debug / Live Trace for status.\n\n" +
                "Tap the ? buttons next to any control for help at any time.";
        showCopyableDialog("First-run Setup", message,
                "echo allow-external-apps=true >> ~/.termux/termux.properties");
    }

    // ------------------------------------------------------------------
    // Help dialogs with optional copyable command
    // ------------------------------------------------------------------

    private void showHelpDialog(String title, String message, String command) {
        if (command != null) {
            showCopyableDialog(title, message, command);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    /** Dialog with a message plus a monospace, copyable command block. */
    private void showCopyableDialog(String title, String message, String command) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView body = new TextView(this);
        body.setText(message);
        layout.addView(body);

        final TextView cmd;
        if (command != null && !command.isEmpty()) {
            cmd = new TextView(this);
            cmd.setText(command);
            cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
            cmd.setTextIsSelectable(true);
            cmd.setPadding(0, pad, 0, pad / 2);
            layout.addView(cmd);

            Button copy = new Button(this);
            copy.setText("Copy Command");
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Termux command",
                            cmd.getText().toString()));
                    Toast.makeText(MainActivity.this,
                            "Command copied - paste it into Termux",
                            Toast.LENGTH_LONG).show();
                }
            });
            layout.addView(copy);
        } else {
            cmd = null;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Done", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Add Custom Tool (JS snippet or shell command)
    // ------------------------------------------------------------------

    private void showAddCustomToolDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        layout.addView(makeDialogLabel("Tool name"));
        final EditText nameInput = new EditText(this);
        nameInput.setHint("e.g. web_search");
        layout.addView(nameInput);

        layout.addView(makeDialogLabel("Description (shown to the LLM)"));
        final EditText descInput = new EditText(this);
        descInput.setHint("e.g. Searches the web and returns result titles + URLs");
        layout.addView(descInput);

        layout.addView(makeDialogLabel("JSON schema (optional) - tells the LLM how to call this tool"));
        final EditText schemaInput = new EditText(this);
        schemaInput.setHint("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
        schemaInput.setMinLines(3);
        schemaInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        schemaInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        layout.addView(schemaInput);

        layout.addView(makeDialogLabel("Engine"));
        final RadioGroup typeGroup = new RadioGroup(this);
        final android.widget.RadioButton jsRadio = new android.widget.RadioButton(this);
        jsRadio.setText("JavaScript snippet (Rhino engine)");
        jsRadio.setId(android.view.View.generateViewId());
        typeGroup.addView(jsRadio);
        final android.widget.RadioButton shellRadio = new android.widget.RadioButton(this);
        shellRadio.setText("Shell command (Runtime.exec)");
        shellRadio.setId(android.view.View.generateViewId());
        typeGroup.addView(shellRadio);
        final android.widget.RadioButton pyRadio = new android.widget.RadioButton(this);
        pyRadio.setText("Python script (Chaquopy engine)");
        pyRadio.setId(android.view.View.generateViewId());
        typeGroup.addView(pyRadio);
        jsRadio.setChecked(true);
        layout.addView(typeGroup);

        layout.addView(makeDialogLabel("Code / script"));
        final EditText codeInput = new EditText(this);
        codeInput.setHint("JS: (function(){ return { answer: 42 }; })()  |  Shell: echo hello  |  Python: print('hello')");
        codeInput.setMinLines(6);
        codeInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        codeInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        layout.addView(codeInput);

        typeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == shellRadio.getId()) {
                    codeInput.setHint("Shell command, e.g. echo hello");
                } else if (checkedId == pyRadio.getId()) {
                    codeInput.setHint("Python script, e.g. print('hello')");
                } else {
                    codeInput.setHint("JS snippet, e.g. (function(){ return { answer: 42 }; })()");
                }
            }
        });

        // Wrap in a ScrollView so no field is ever clipped off-screen on small
        // screens (the schema box added in a prior update pushed the code box
        // below the dialog's fixed height, which made saves fail with
        // "Name and code are required" even when text was present).
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle("Add Custom Tool")
                .setView(scroll)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = nameInput.getText().toString().trim();
                        String desc = descInput.getText().toString().trim();
                        String code = codeInput.getText().toString().trim();
                        String schema = schemaInput.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Tool name is required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Code / script is required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!schema.isEmpty()) {
                            try {
                                new org.json.JSONObject(schema);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this,
                                        "Schema must be valid JSON (check brackets/quotes)", Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                        String type = shellRadio.isChecked() ? "shell"
                                : (pyRadio.isChecked() ? "python" : "js");
                        saveCustomToolToPrefs(name, desc, type, code, schema);
                        Toast.makeText(MainActivity.this,
                                "Custom tool '" + name + "' saved", Toast.LENGTH_SHORT).show();
                        notifyServiceReload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private android.widget.TextView makeDialogLabel(String text) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        int p = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, p, 0, 0);
        return tv;
    }

    private void saveCustomToolToPrefs(String name, String desc, String type, String code, String schema) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existing = prefs.getString("custom_tools", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(existing);
            arr.put(new org.json.JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put("type", type)
                    .put("code", code)
                    .put("enabled", true)
                    .put("schema", schema == null ? "" : schema));
            prefs.edit().putString("custom_tools", arr.toString()).apply();
        } catch (Exception e) {
            DebugLog.log(this, "UI", "saveCustomTool failed: " + e.getMessage());
        }
    }

    /** Loads custom tools from prefs as a JSON array (each entry: name/description/type/code/enabled). */
    private org.json.JSONArray loadCustomToolsFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            return new org.json.JSONArray(prefs.getString("custom_tools", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private void saveCustomToolsToPrefs(org.json.JSONArray arr) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("custom_tools", arr.toString()).apply();
    }

    /** Dialog listing custom tools with per-tool enable/disable toggle and delete. */
    private void showManageCustomToolsDialog() {
        final org.json.JSONArray arr = loadCustomToolsFromPrefs();
        if (arr.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Custom Tools")
                    .setMessage("No custom tools yet. Tap 'Add Custom Tool' to create one.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        final java.util.List<org.json.JSONObject> entries = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                entries.add(arr.getJSONObject(i));
            } catch (Exception ignored) {
            }
        }

        for (final org.json.JSONObject o : entries) {
            final String nm = o.optString("name");
            final String ty = o.optString("type", "js");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final CheckBox chk = new CheckBox(this);
            chk.setText(nm + "  (" + ty + ")");
            chk.setChecked(o.optBoolean("enabled", true));
            chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        o.put("enabled", isChecked);
                    } catch (Exception ignored) {
                    }
                }
            });
            row.addView(chk, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button del = new Button(this);
            del.setText("Delete");
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    entries.remove(o);
                    layout.removeView(row);
                }
            });
            row.addView(del);
            layout.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle("Manage Custom Tools")
                .setMessage("Toggle each tool on/off (only enabled tools are advertised to the LLM) or delete it.")
                .setView(layout)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        org.json.JSONArray out = new org.json.JSONArray();
                        for (org.json.JSONObject o : entries) out.put(o);
                        saveCustomToolsToPrefs(out);
                        notifyServiceReload();
                        Toast.makeText(MainActivity.this, "Custom tools updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // External MCP servers (add full connection: URL + launch/headers/auth)
    // ------------------------------------------------------------------

    private void showAddExternalMcpDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Name (e.g. my-github-server)");
        layout.addView(nameInput);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("URL (http(s)://.../mcp or SSE endpoint)");
        urlInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(urlInput);

        final EditText launchInput = new EditText(this);
        launchInput.setHint("Launch command (optional, for self-hosted), e.g. npx -y @modelcontextprotocol/server-github");
        launchInput.setSingleLine(true);
        layout.addView(launchInput);

        final EditText headersInput = new EditText(this);
        headersInput.setHint("Custom headers as JSON, e.g. {\"X-API-Key\":\"abc\",\"Authorization\":\"Bearer tok\"}");
        headersInput.setSingleLine(true);
        layout.addView(headersInput);

        final EditText authInput = new EditText(this);
        authInput.setHint("Auth token (sent as Authorization: Bearer <token>)");
        authInput.setSingleLine(true);
        layout.addView(authInput);

        new AlertDialog.Builder(this)
                .setTitle("Add External MCP Server")
                .setMessage("URL is required. For self-hosted servers that expose their MCP over stdio, provide a launch command " +
                        "(e.g. npx -y @modelcontextprotocol/server-...); for network servers give the URL. " +
                        "Headers/auth are optional and are sent with every request to this server.")
                .setView(layout)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String nm = nameInput.getText().toString().trim();
                        String url = urlInput.getText().toString().trim();
                        String launch = launchInput.getText().toString().trim();
                        String headers = headersInput.getText().toString().trim();
                        String auth = authInput.getText().toString().trim();
                        if (nm.isEmpty()) nm = url;
                        if (url.isEmpty()) {
                            Toast.makeText(MainActivity.this, "URL is required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "https://" + url;
                        }
                        if (headers.isEmpty()) headers = "{}";
                        saveExternalServerToPrefs(nm, url, launch, headers, auth);
                        Toast.makeText(MainActivity.this, "External MCP added: " + nm, Toast.LENGTH_SHORT).show();
                        DebugLog.log(MainActivity.this, "UI", "external MCP added: " + nm + " @ " + url);
                        notifyServiceReload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveExternalServerToPrefs(String name, String url, String launch, String headers, String auth) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("external_mcp_urls", "[]"));
            boolean dup = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && url.equals(o.optString("url"))) dup = true;
            }
            if (!dup) {
                arr.put(new org.json.JSONObject()
                        .put("name", name)
                        .put("url", url)
                        .put("launch", launch)
                        .put("headers", headers)
                        .put("auth", auth)
                        .put("enabled", true));
                prefs.edit().putString("external_mcp_urls", arr.toString()).apply();
            } else {
                Toast.makeText(this, "That URL is already added", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            DebugLog.log(this, "UI", "saveExternalServer failed: " + e.getMessage());
        }
    }

    private org.json.JSONArray loadExternalServersFromPrefs() {
        try {
            return new org.json.JSONArray(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("external_mcp_urls", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private void saveExternalServersToPrefs(org.json.JSONArray arr) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("external_mcp_urls", arr.toString()).apply();
    }

    /** Dialog listing external MCP servers with per-server enable/disable toggle and delete. */
    private void showManageExternalMcpDialog() {
        final org.json.JSONArray arr = loadExternalServersFromPrefs();
        if (arr.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("External MCP Servers")
                    .setMessage("No external MCP servers configured. Tap 'Add External MCP Server' to connect one.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        final java.util.List<org.json.JSONObject> entries = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                // legacy plain-string entry
                String legacyUrl = arr.optString(i);
                o = new org.json.JSONObject();
                try {
                    o.put("name", legacyUrl).put("url", legacyUrl)
                            .put("launch", "").put("headers", "{}")
                            .put("auth", "").put("enabled", true);
                } catch (Exception ignored) {
                }
            }
            entries.add(o);
        }

        for (final org.json.JSONObject o : entries) {
            final String nm = o.optString("name", o.optString("url"));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final CheckBox chk = new CheckBox(this);
            chk.setText(nm);
            chk.setChecked(o.optBoolean("enabled", true));
            chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        o.put("enabled", isChecked);
                    } catch (Exception ignored) {
                    }
                }
            });
            row.addView(chk, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button del = new Button(this);
            del.setText("Delete");
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    entries.remove(o);
                    layout.removeView(row);
                }
            });
            row.addView(del);
            layout.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle("Manage External MCP Servers")
                .setMessage("Toggle each server on/off (only enabled servers' tools are advertised) or delete it.")
                .setView(layout)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        org.json.JSONArray out = new org.json.JSONArray();
                        for (org.json.JSONObject o : entries) out.put(o);
                        saveExternalServersToPrefs(out);
                        notifyServiceReload();
                        Toast.makeText(MainActivity.this, "External MCP servers updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Manage all tools (every tool - built-in, custom, external - with its
    // own toggle; custom tools can also be deleted here)
    // ------------------------------------------------------------------

    /** Reads the persisted per-tool toggle map (tool name -> enabled). */
    private org.json.JSONObject loadToolTogglesFromPrefs() {
        try {
            return new org.json.JSONObject(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("tool_toggles", "{}"));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    private void saveToolTogglesToPrefs(org.json.JSONObject toggles) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("tool_toggles", toggles.toString()).apply();
    }

    /** Builds the list of every known tool: built-ins, custom tools, and
     *  external-server tools (from the cached remote tool names). */
    private java.util.List<String> allKnownToolNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("execute_oracle_js");
        names.add("run_local_process");
        names.add("run_termux_command");
        names.add("custom_tool_add");
        names.add("external_mcp_add");
        names.add("external_mcp_remove");
        org.json.JSONArray ct = loadCustomToolsFromPrefs();
        for (int i = 0; i < ct.length(); i++) {
            org.json.JSONObject o = ct.optJSONObject(i);
            if (o != null) names.add(o.optString("name"));
        }
        try {
            org.json.JSONObject cache = new org.json.JSONObject(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("external_tool_cache", "{}"));
            java.util.Iterator<String> servers = cache.keys();
            while (servers.hasNext()) {
                String serverName = servers.next();
                org.json.JSONArray toolNames = cache.optJSONArray(serverName);
                if (toolNames != null) {
                    for (int i = 0; i < toolNames.length(); i++) {
                        names.add("ext_" + serverName + "_" + toolNames.optString(i));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    /** Unified dialog: every tool listed with its own enable/disable toggle.
     *  Custom tools also get a Delete button (removed from the custom list). */
    private void showManageAllToolsDialog() {
        final org.json.JSONObject toggles = loadToolTogglesFromPrefs();
        final java.util.List<org.json.JSONObject> customEntries = new java.util.ArrayList<>();
        org.json.JSONArray customArr = loadCustomToolsFromPrefs();
        for (int i = 0; i < customArr.length(); i++) {
            org.json.JSONObject o = customArr.optJSONObject(i);
            if (o != null) customEntries.add(o);
        }
        final java.util.Set<String> customNames = new java.util.HashSet<>();
        for (org.json.JSONObject o : customEntries) customNames.add(o.optString("name"));

        final java.util.List<String> names = allKnownToolNames();
        if (names.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Manage All Tools")
                    .setMessage("No tools registered yet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        for (final String nm : names) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final CheckBox chk = new CheckBox(this);
            chk.setText(nm);
            chk.setChecked(toggles.optBoolean(nm, true));
            chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        toggles.put(nm, isChecked);
                    } catch (Exception ignored) {
                    }
                }
            });
            row.addView(chk, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (customNames.contains(nm)) {
                Button del = new Button(this);
                del.setText("Delete");
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Remove the matching custom entry by name.
                        for (int i = customEntries.size() - 1; i >= 0; i--) {
                            if (nm.equals(customEntries.get(i).optString("name"))) {
                                customEntries.remove(i);
                            }
                        }
                        layout.removeView(row);
                        names.remove(nm);
                    }
                });
                row.addView(del);
            }
            layout.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle("Manage All Tools")
                .setMessage("Every tool is listed here - built-ins, custom tools, and tools from external MCP servers. "
                        + "Uncheck a tool to hide it from the LLM and block calls to it. "
                        + "Custom tools also have a Delete button.")
                .setView(layout)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        saveToolTogglesToPrefs(toggles);
                        org.json.JSONArray out = new org.json.JSONArray();
                        for (org.json.JSONObject o : customEntries) out.put(o);
                        saveCustomToolsToPrefs(out);
                        notifyServiceReload();
                        Toast.makeText(MainActivity.this, "Tool toggles updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Connection formats reference
    // ------------------------------------------------------------------

    private void showFormatsReference() {
    String endpoint = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_ENDPOINT, "");
    if (endpoint.isEmpty()) {
        endpoint = "https://<your-domain>/mcp";
    }
    String message =
            "Point any LLM system at your unified /mcp endpoint (" +
            endpoint + "). Formats accepted:\n\n" +
            "1) JSON-RPC POST (tools/list, tools/call)\n" +
            "   POST " + endpoint + "\n" +
            "   Content-Type: application/json\n" +
            "   Body: {\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}\n\n" +
            "2) SSE stream (Server-Sent Events)\n" +
            "   GET " + endpoint + "\n" +
            "   Accept: text/event-stream\n" +
            "   First event carries the endpoint JSON-RPC path.\n\n" +
            "3) Authorization (optional)\n" +
            "   When an access code is set, send:\n" +
            "   Authorization: Bearer <your access code>\n\n" +
            "Example curl:\n" +
            "  curl -X POST " + endpoint + " \\\\n" +
            "    -H 'Content-Type: application/json' \\\\n" +
            "    -H 'Authorization: Bearer CODE' \\\\n" +
            "    -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}'";
    showCopyableDialog("Connection Formats", message, null);
}


    private void notifyServiceReload() {
        Intent i = new Intent(this, McpServerService.class);
        i.setAction(McpServerService.ACTION_RELOAD);
        startService(i);
    }

    // ------------------------------------------------------------------
    // Diagnostic box (binary status)
    // ------------------------------------------------------------------

    private void refreshBinaryDiagnostic() {
        // cloudflared is packaged as a native library so Android installs it
        // into nativeLibraryDir with the SELinux label required for exec().
        File binaryFile = new File(getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (binaryFile.exists() && binaryFile.length() > 0) {
            appendDiagnostic("Bundled native binary: " + formatSize(binaryFile.length())
                    + (binaryFile.canExecute() ? " [executable]" : " [NOT executable]"));
            DebugLog.log(this, "UI", "Bundled native binary: " + binaryFile.getAbsolutePath()
                    + " size=" + binaryFile.length());
        } else {
            appendDiagnostic("Bundled native binary: MISSING from nativeLibraryDir");
            DebugLog.log(this, "UI", "Bundled native binary MISSING: " + binaryFile.getAbsolutePath());
        }
        appendDiagnostic("Runtime copy: none - Android executes the installed native binary directly");
    }

    private void updateDiagnostic(String status) {
        switch (status == null ? "UNKNOWN" : status) {
            case "OK":
                appendDiagnostic("Binary status: OK - cloudflared ready.");
                break;
            case "MISSING":
            case "ZERO_BYTES":
                appendDiagnostic("Binary status: Missing Tunnel Binary! Please add via Termux or curl.");
                break;
            case "TOO_SMALL":
                appendDiagnostic("Binary status: Size check failed (expected ~30-35MB).");
                break;
            case "NOT_EXECUTABLE":
                appendDiagnostic("Binary status: File is not executable.");
                break;
            default:
                appendDiagnostic("Binary status: " + status);
                break;
        }
    }

    private void appendDiagnostic(String line) {
        if (txtDiagnostic == null) return;
        String current = txtDiagnostic.getText().toString();
        if ("Checking binary...".equals(current)) current = "";
        String updated = current + (current.isEmpty() ? "" : "\n") + line;
        txtDiagnostic.setText(updated);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        return bytes + " B";
    }

    // ------------------------------------------------------------------
    // Debug / live trace panel
    // ------------------------------------------------------------------

    private void appendDebugLine(String line) {
        if (txtDebugLog == null) return;
        String current = txtDebugLog.getText().toString();
        if ("(debug log will appear here)".equals(current)
                || "(debug log cleared)".equals(current)) {
            current = "";
        }
        String updated = current + (current.isEmpty() ? "" : "\n") + line;
        if (updated.length() > DEBUG_MAX_CHARS) {
            updated = updated.substring(updated.length() - DEBUG_MAX_CHARS);
        }
        txtDebugLog.setText(updated);
        scrollDebug.post(new Runnable() {
            @Override
            public void run() {
                scrollDebug.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ------------------------------------------------------------------
    // Termux test
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Termux setup dialog (shown when Termux is not installed)
    // ------------------------------------------------------------------

    private void showTermuxSetupDialog() {
        String message =
                "Termux is not installed on this device, so the Termux tools " +
                "(run_termux_command and the test button) cannot run.\n\n" +
                "1. Install Termux from F-Droid (the Play Store version is outdated):\n" +
                "   https://f-droid.org/en/packages/com.termux/\n\n" +
                "2. Open Termux once so it can set up its home directory.\n\n" +
                "3. Allow external apps by running this command inside Termux " +
                "(tap Copy below, paste into Termux, press Enter):\n\n" +
                "   echo allow-external-apps=true >> ~/.termux/termux.properties\n\n" +
                "4. Tap the Termux test button again. If it still reports missing, " +
                "fully close and reopen the app.";
        showCopyableDialog("Enable Termux Tools", message,
                "echo allow-external-apps=true >> ~/.termux/termux.properties");
    }

    private void runTermuxTest() {
        DebugLog.log(this, "UI", "Termux test button pressed");
        if (!TermuxBridge.isTermuxInstalled(this)) {
            appendDiagnostic("Termux test: Termux is NOT installed (get it from F-Droid).");
            showTermuxSetupDialog();
            return;
        }
        appendDiagnostic("Termux test: dispatching test command...");
        // Use /bin/sh -c to avoid missing echo binary issues.
        String executable = "/data/data/com.termux/files/usr/bin/sh";
        String[] args = new String[]{"-c", "echo 'hello from mcp bridge'"};
        termuxActiveRunId = ++termuxRequestSeq;
        android.app.PendingIntent pi = TermuxBridge.buildResultPendingIntent(this, termuxActiveRunId);
        String error = TermuxBridge.sendRunCommand(this, executable, args, null, pi);
        if (error != null) {
            appendDiagnostic("Termux test FAILED: " + error);
            DebugLog.log(this, "UI", "Termux test failed: " + error);
        } else {
            termuxTestPending = true;
            mainHandler.removeCallbacks(termuxTestTimeout);
            mainHandler.postDelayed(termuxTestTimeout, 30000L);
            appendDiagnostic("Termux test: waiting up to 30 seconds for a response...");
            DebugLog.log(this, "UI", "Termux test dispatched, awaiting result");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(binaryStatusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(termuxResultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(debugLineReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(tunnelInfoReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
