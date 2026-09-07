# MCP Universal Bridge - Build Notes & Corrections (v7)

This is the current build. It adds Python custom tools, fixes the Add Custom Tool
save bug, and makes Termux tool calls return their real output. Earlier fixes
(cloudflared tunnel, Termux result routing) are preserved below.

## v7 - New in this build

### 1. Add Custom Tool dialog save bug ("Name and code are required")

Root cause: the JSON-schema box added in v6 pushed the code box below the
dialog's fixed height. The dialog was not scrollable, so script text landed in a
clipped field the Save button never reads, making validation claim the fields
were empty even though text was present.

Fix in `MainActivity.showAddCustomToolDialog()`:
- The whole form is now wrapped in a `ScrollView` (fillViewport), so every field
  is reachable and scrollable on any screen size.
- Each field has a small bold label (Tool name / Description / JSON schema /
  Engine / Code / script).
- Validation is per-field: it reports exactly which field is missing
  ("Tool name is required" vs "Code / script is required").
- The code box is 6 lines tall and monospace for pasting scripts.

### 2. Python custom tools via Chaquopy (new engine)

The Add Custom Tool dialog now has a third engine: "Python script (Chaquopy
engine)". Scripts run on the embedded Chaquopy Python runtime, not Rhino.

- Saved with type `'python'`; the LLM can also create one via `custom_tool_add`
  (its schema now advertises `js | shell | python`).
- Script convention: the LLM's arguments arrive as a dict called `input`; assign
  your answer to `result`. Example:
      result = { "ok": True, "echo": input.get("data") }
  Scripts may also `print(...)`; stdout is captured and returned if `result` is
  not set. Errors return JSON with the traceback.
- Wiring: `com.chaquo.python` plugin 15.0.1 (supports AGP 7.0-8.5; this project
  uses AGP 7.4.2) plus a `python { version "3.8" }` block, and the new module
  `app/src/main/python/mcp_orchestrator.py`.
- The Java executor calls Chaquopy via reflection, so the app still compiles and
  runs if the plugin is not applied; Python tools then report a clear error.

BUILD CAVEAT: Chaquopy needs the Android NDK present in the build environment
(its native libraries are pre-compiled, so no NDK install is required, but AGP
wants the NDK declared). If A-IDE's toolchain rejects it, remove the
`id 'com.chaquo.python'` line from `app/build.gradle` to build without Python
support (the app still builds because of the reflection design).

### 3. Termux tool calls now return their real output

Previously `run_termux_command` returned an immediate ack ("Command dispatched...
result will arrive via notification") and only posted the output to a
notification - so the MCP tool call always looked broken even when the command
ran fine.

Fix in `McpServerService`:
- `runTermuxCommand` now waits for the actual result (CountDownLatch, 20s
timeout) and returns stdout/stderr/exit code to the LLM.
- `termuxResultReceiver` completes the pending call when the result arrives;
`termuxPending` (ConcurrentHashMap) tracks in-flight runs by request code.
- On timeout it returns a clear message telling you to enable "Allow external
apps" in Termux.

---

# MCP Universal Bridge - Build Notes & Corrections (v2)

This rebuild fixes the two issues reported after the last build:

## 1. cloudflared tunnel (BOTH modes) would not connect - PUBLIC URL never populated

Root cause: Android 10+ SELinux W^X policy forbids `execve()` for files extracted at runtime into the app's writable data directories (`files/`, `code_cache/`). The permission bit can be set successfully, but the kernel still returns `error=13, Permission denied`.

Fix in `McpServerService.prepareCloudflaredBinary()`:
- The 37 MB arm64 ELF is packaged as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`.
- `android:extractNativeLibs="true"` makes Android install it into `nativeLibraryDir`, the exec-permitted native-library location.
- The service executes that installed file directly and validates it with `cloudflared --version`; it does not copy the binary into app-data paths.
- Stale files from older builds are removed. Both tunnel modes set writable `HOME` and `TMPDIR` for cloudflared.

## 2. Termux test hung ("waiting for response") and never passed or failed

Three regressions were fixed:
- `TermuxBridge.buildResultPendingIntent()` now targets the manifest-declared
  `TermuxResultReceiver`; it no longer points at the `MainActivity` component.
- `TermuxResultReceiver` forwards the plugin result to the existing Activity/service
  listeners.
- The flags no longer OR `FLAG_IMMUTABLE` with `FLAG_MUTABLE`; only `FLAG_MUTABLE` is
  applied on API 31+ so Termux can attach the plugin-result bundle.
- `MainActivity.runTermuxTest()` no longer armed the 12-second watchdog. It is re-armed,
  so a missing response now always resolves to an explicit PASS with output, or FAIL
  telling you to enable "Allow external apps" in Termux.

IMPORTANT for Termux: enable "Allow external apps" in Termux once
(Termux > long-press terminal > More > Settings) or run:
    echo allow-external-apps=true >> ~/.termux/termux.properties
This is a Termux-side allow-list, not an Android runtime permission - the OS never
shows a dialog for it, so approving Android permissions does not change it.
