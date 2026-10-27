# Control core: current implementation

The in-process core is created by `TermuxService`. It supports protocol version 1, capability enumeration, `session.list`, `session.create`, `session.rename`, `session.resize`, `session.close`, and bounded binary `session.write`. It advertises close only while the local opt-in is enabled. Snapshots contain session handle, custom name, state and shell PID; they do not include command arguments, environment, terminal output or files. A handle identifies a live service session, not a process that can be resurrected.

The backend must run on the main thread. It takes a short service lock to inspect session membership and releases it before enqueueing input. Writes use the specified session handle and the existing terminal input queue; a rejected frame returns a backpressure error. Resize is limited to initialized running sessions and returns a conflict when the local activity visibly displays the target; local UI geometry takes precedence and is restored when the activity returns. Multi-client resize arbitration is not implemented. There is no event stream, attachment, command, file, process, Binder, or network transport in this implementation.

Scopes passed to this in-process API are assertions by a trusted caller, not authentication. A future transport must authenticate peers and derive scopes from its own authorization state; it must never accept a client-supplied set of scopes as proof. No Android component exposes this core. Legacy RUN_COMMAND permissions and behavior are unchanged. Device tests for service destruction, session exit, concurrent callers and queue saturation remain required before exposing it externally.

Focused test command on the Termux development host:

```sh
. ~/android-sdk/env.sh
./gradlew :app:testDebugUnitTest --tests com.termux.app.control.ControlCoreTest -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

The focused test command was run on the Termux host. It uses a fake backend for authorization and frame tests and does not verify Android service or PTY lifecycle behavior.

`session.close` sends SIGKILL to the session shell through a Linux pidfd and returns when the signal is accepted, not when the process is reaped. Process handles are disabled by default and must be enabled by a trusted local caller before a new session starts; there is no settings UI yet. Existing sessions, unsupported kernels and denied syscalls return an unavailable error. Disabling the setting revokes control close and closes existing process handles. The transport must never enable this setting on a client's behalf. Other existing UI shutdown paths still use numeric PIDs and retain the exit/reuse race. JNI and multi-ABI packaging, permission behavior, process exit races and Android devices need testing before external exposure.

A local syscall smoke test can be compiled with `clang -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror terminal-emulator/src/test/native/pidfd-smoke.c -o build/pidfd-smoke` and run as `build/pidfd-smoke`. It forks a disposable child, signals it through a pidfd, and checks that a signal after exit fails. This exercises kernel syscalls only, not the JNI bridge, service lifecycle or APK packaging. The test passed on the current Termux host; the feature remains disabled by default.

`session.create` starts the existing default shell with the configured default working directory and bounded geometry. It enforces the same eight-session limit as the activity. An initialization failure before a PID exists removes the new non-plugin entry; a failure after startup leaves the session registered for its existing lifecycle. No command, environment, or arbitrary working directory is accepted. Live service, native failure rollback, activity attachment and multi-client tests remain required.

