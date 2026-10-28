# Extra-key editor model (in progress)

`ExtraKeyLayout` is an in-memory model for the existing `extra-keys` array. It accepts representative upstream JSON-like single-quoted and unquoted syntax, checks one to three rows with up to 24 keys per row, and preserves complete key objects—including unknown fields, macros, display names and nested popup objects—when moving them. It rejects duplicate object fields, malformed keys and oversized or overly complex input. It exports valid JSON accepted in principle by the existing extra-key format; exact Android parser compatibility still requires device or Android framework tests. Layouts outside these limits are rejected for editing and must not be silently replaced.

The model provides Classic, Coding, Vim, Tmux and Compact starting layouts, plus row/key add, remove and move operations. A user-edited configuration can serve as a custom layout. It does not currently read or write `termux.properties`, back it up, preview or hot-reload the keybar, offer a visual editor, manage named profiles, or import/export files. Those are required before this workstream is complete.

`./gradlew :app:testDebugUnitTest --tests com.termux.app.extrakeys.ExtraKeyLayoutTest` tests representative parsing, presets, field preservation, defensive copying and bounds. It does not exercise the Android view or actual properties files.
