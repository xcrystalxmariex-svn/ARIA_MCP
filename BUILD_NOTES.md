# MCP Universal Bridge - Build Notes & Corrections

## What was fixed in this rebuild (read before building)

1. **HTTP server rewritten for Android.** The base project imported
   `com.sun.net.httpserver` (JDK-only) which does NOT exist on Android and would
   fail to compile in AIDE. It is now a plain `ServerSocket` HTTP server on
   port 8000, path `/mcp`. No extra dependencies.
2. **cloudflared binary is now actually bundled.** The base zip only contained
   the placeholder `put_cloudflared_arm64_binary_here.txt`. This zip ships the
   real official arm64 binary at `app/src/main/assets/cloudflared`
   (~35.6 MB, verified ELF arm64). The earlier instruction
   `curl -L "https://github.com"` would only have saved the GitHub homepage
   HTML - the correct source is:
   https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64
3. **Launcher icons added.** The manifest referenced `@mipmap/ic_launcher` but
   no mipmap resources existed -> build would fail. Icons now ship for all
   densities.
4. **Layout fixed.** Root used invalid `layout_width="match_size"`; corrected
   to `match_parent`, plus added the diagnostic text box and Termux test button.
5. **Java 8 compile options pinned** in `app/build.gradle` so the lambda-based
   code compiles with AGP 4.1.0 in AIDE.

## The four requested features - where they live

1. Setup UI alerts -> `MainActivity.java` (`requestLaunchPermissions()` +
   `onRequestPermissionsResult()`). On first launch the app requests the
   runtime permissions that Android actually exposes dialogs for:
   - POST_NOTIFICATIONS on Android 13+ (needed for the service notifications)
   - FOREGROUND_SERVICE on Android 14+
   There is NO "Local Network" runtime dialog on this target SDK (30) - a local
   network prompt only exists for the nearby-devices permission on Android 13+,
   which this app does not use. Nothing is lost by its absence.
2. Visual diagnostic box -> the monospace `txtDiagnostic` TextView on the main
   screen. Shows bundled-asset size + extracted-copy state on launch and live
   "Binary status:" lines pushed from the service via broadcast every time the
   switch is flipped.
3. Binary loading robustness -> `McpServerService.prepareCloudflaredBinary()`
   + `validateBinaryFile()`. On start the service extracts the asset, applies
   the exec bit, and checks the size band. Failure posts a system notification:
   - 0 bytes or missing -> "Missing Tunnel Binary! Please add via Termux or
     curl."
   - wrong size / not executable / extract failure -> its own clear alert.
4. Termux boilerplate -> new `TermuxBridge.java` + wiring in the service and
   activity. Implements the documented RUN_COMMAND intent contract
   (extras + plugin-result bundle back through a PendingIntent broadcast).
   A "Test Termux RUN_COMMAND" button on the main screen runs
   `echo hello from mcp bridge` and prints the captured output to the
   diagnostic box. The MCP `run_termux_command` tool now dispatches real
   commands instead of returning a placeholder.

## Guided first-run setup, help dialogs and live debug log (this update)

- **Guided first-run setup dialog.** On first launch, after the permission
  prompts, the app shows a step-by-step setup dialog: paste the tunnel token,
  install Termux from F-Droid, run the allow-external-apps command (with a
  **Copy Command** button), then flip the server switch. It only shows once
  (stored in SharedPreferences).
- **Help dialogs on every control.** Each `?` button explains its control/tool
  in plain language. The Termux-related ones include a monospace command block
  with a **Copy Command** button so the user can paste it straight into Termux.
- **Debug / Live Trace panel.** A new on-screen panel (`txtDebugLog`) shows a
  live, timestamped trace of everything the app does - binary extraction,
  HTTP server bind, MCP calls, Termux results, tunnel start/stop, permission
  results. Every line is also appended to `mcp_debug.log` in the app's files
  dir and broadcast live. **Clear Log** empties the panel, **Copy Log** puts
  the whole trace on the clipboard so you can paste it into a bug report.
  No adb needed to see what the app is doing.

## One-time Termux setup on the phone

`com.termux.permission.RUN_COMMAND` is NOT a normal Android runtime permission -
Termux itself enforces an allowlist and the OS shows no dialog for it. Before
the test button / tool will work:

1. Install Termux from F-Droid (the Play Store build is deprecated).
2. Open Termux once and either:
   - long-press the screen -> More -> "Allow external apps", or
   - run:  `echo allow-external-apps=true >> ~/.termux/termux.properties`
3. The app checks Termux is installed and surfaces a readable error if the
   allowlist is missing.

## Building in AIDE

1. Import the project folder (the zip root contains `build.gradle` +
   `settings.gradle` + `app/`).
2. Build as usual - the 35.6 MB asset is inside the APK; first build/install
   will be slower because of it.
3. On Android 14+ the foreground service type is `dataSync` (set in the
   manifest) so no extra `FOREGROUND_SERVICE_DATA_SYNC` permission is needed.

## Note on port 8000

The embedded server binds 127.0.0.1:8000 inside the app. The cloudflared tunnel
then exposes it at the configured endpoint. If anything else on the device is
already using 8000 the server will fail to bind and log an error - stop the
other listener first.

## Per-user tunnel mode + optional access code (this update)

- **Tunnel mode is per-user and set in the app (NOT hard-coded).** Under
  Connection Setup on the main screen each user picks:
  - **My Cloudflare token / custom domain** (default): the app runs
    `cloudflared tunnel run --token <token>`. The public hostname is configured
    in the USER'S OWN Cloudflare dashboard for that token - the app never sees
    or hard-codes a domain, so nobody is routed through svn-dev.online.
  - **Random trycloudflare URL**: the app runs `cloudflared tunnel --url
    http://127.0.0.1:8000 --no-autoupdate`, captures the assigned
    `https://<random>.trycloudflare.com` from cloudflared's output, shows it on
    the Endpoint line and stores it as the last-known URL. No Cloudflare
    account or domain needed. (Caveat: the URL is random and changes each
    tunnel restart.)
- **Endpoint line + Copy URL**: the main screen shows the live/known public
  URL and a Copy URL button. Token-mode shows a note that the hostname is set
  in Cloudflare.
- **Optional access code (Bearer auth).** A user-assigned code typed into the
  Optional access code field. When set, every request to /mcp must carry
  `Authorization: Bearer <code>`; missing/wrong code gets HTTP 401. Leave
  blank to keep the endpoint open. This protects the device tools (including
  shell/termux) once the endpoint is public.
- Everything (token, mode, code, last URL) is persisted in the app's
  SharedPreferences (`mcp_prefs`) and sent to the service when the switch is
  flipped.
