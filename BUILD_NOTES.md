# MCP Universal Bridge - Build Notes & Corrections (v2)

This rebuild fixes the two issues reported after the last build:

## 1. cloudflared tunnel (BOTH modes) would not connect - PUBLIC URL never populated

Root cause: the binary was extracted to `code_cache` (or `nativeLibraryDir`) because
the code picked the FIRST WRITABLE candidate directory. Modern Android mounts
`code_cache` and `nativeLibraryDir` with the **noexec** flag. `setExecutable(true)`
and `canExecute()` both report OK (they only touch/read the permission bit), but the
kernel refuses to `forkAndExec` - each tunnel start died with `error=13, Permission denied`.

Fix in `McpServerService.prepareCloudflaredBinary()`:
- Always extract to `getFilesDir()/cloudflared` (the app-private files dir, which
  Android mounts executable for the owning app).
- Delete stale copies left in `code_cache` and `nativeLibraryDir` so they are never reused.
- **Prove it runs** by executing `cloudflared --version` and checking exit 0, instead of
  trusting the permission bit. A noexec mount is now caught immediately at start.
- Both `startTokenTunnel` and `startQuickTunnel` now set `HOME` and `TMPDIR` env vars for
  the child process, which cloudflared needs to write its config/cache on Android.

## 2. Termux test hung ("waiting for response") and never passed or failed

Three regressions were fixed:
- `TermuxBridge.buildResultPendingIntent()` pointed the result broadcast at the
  `MainActivity` component (an Activity cannot receive a broadcast, so the result was
  silently dropped). Now the intent stays implicit (action + package, no component) so
  it reaches the dynamically-registered receiver.
- The flags OR'd `FLAG_IMMUTABLE` with `FLAG_MUTABLE`, which is rejected at creation on
  API 31+. Now only FLAG_MUTABLE is applied on API 31+ so Termux can attach the
  plugin-result bundle.
- `MainActivity.runTermuxTest()` no longer armed the 12-second watchdog. It is re-armed,
  so a missing response now always resolves to an explicit PASS with output, or FAIL
  telling you to enable "Allow external apps" in Termux.

IMPORTANT for Termux: enable "Allow external apps" in Termux once
(Termux > long-press terminal > More > Settings) or run:
    echo allow-external-apps=true >> ~/.termux/termux.properties
This is a Termux-side allow-list, not an Android runtime permission - the OS never
shows a dialog for it, so approving Android permissions does not change it.
