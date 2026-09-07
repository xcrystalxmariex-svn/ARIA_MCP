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
