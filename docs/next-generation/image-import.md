# Image import (draft)

The Paste action accepts image-only clipboard clips; the terminal menu also offers **Import image** through Open Document. A separate Sharesheet target asks for confirmation before pasting paths. Android 7.1 and later may send supported image content from an IME. Normal text clipboard content takes precedence. PNG, JPEG and WebP content URIs are streamed to the app cache and checked against their provider MIME and file signature. The resulting quoted paths are pasted into the originating session without an Enter key; the destination program must accept file paths.

Import is limited to eight images; the default quotas are 20 MiB per image, 40 MiB per batch and 100 MiB for the image cache. Partial files older than 24 hours are removed; completed files are retained until the user or Android removes them. Move important files out of cache. A batch failure removes its completed files. MIME and signature checks are not full image decoding or validation. An import that completes after switching sessions leaves its files in cache instead of inserting paths into the new session.

This work is a draft. Sharesheet and IME URI grants, picker and activity lifecycle, process-death recovery, shell quoting across shells, provider failures, rotation, session switching and repeated imports require Android testing. There is no image preview or cache management UI. This does not add inline graphics or a binary clipboard protocol. Large text or image path writes may be rejected by the bounded input queue.

`./gradlew :app:testDebugUnitTest --tests com.termux.app.terminal.clipboard.ImageStreamImporterTest` checks streaming signatures and size limits. `scripts/test-share-uri-host.sh` tests share selection using Android stubs, not the Android framework or URI permission lifecycle.

The Paste action uses direct clipboard text first, then image MIME, then explicitly text-typed content URIs; unsupported non-text items are rejected without coercing their URI or Intent into terminal input. Text-typed HTTP(S) URI items are pasted as URLs. Provider-backed text is read as UTF-8 on a worker with a 1 MiB limit and pasted only if the originating session remains visible; unreadable, oversized or interrupted provider text must be copied again. This intentionally declines other URI and Intent coercions that older Android clipboard handling could paste. A slow provider may keep the one text-import worker occupied. Android clipboard, provider, keyboard and lifecycle behavior still needs device validation.

Image quota properties in `~/.termux/termux.properties` (or Termux's configured secondary properties file) are whole MiB:

```properties
clipboard-image-max-file-mib = 20
clipboard-image-max-batch-mib = 40
clipboard-image-max-cache-mib = 100
```

Valid ranges are 1–64, 1–128 and 1–256 MiB respectively. Invalid values use that property's default. Each import captures its current settings; the smallest applicable file, batch or remaining cache quota wins. Lowering the cache limit never deletes completed files: imports fail until enough space is freed. Reload Termux settings after editing. These limits control encoded bytes, not image dimensions or full image validation. Actual provider and Android properties-reload behavior still require device testing.

A pending Open Document result is held until the activity is visible and its service and session are attached. The result is limited to eight content URIs, each at most 4096 characters, and the selected session handle and pending URI list are included in Android saved state. If the original session exited or another session is selected, the result is discarded and the user must select again. URI grants are temporary; a provider can revoke access or a process restart can lose permission, in which case import reports failure. Saved-state delivery, URI grants and rotation still require Android device validation.
