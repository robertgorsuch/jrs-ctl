# ADR-0023: a full-screen terminal UI is not built for now

Status: proposed, 2026-09-16. Issue #75. Awaiting a decision from the maintainers; nothing is implemented.

## Context

The field tester used jrsctl only through the CLI on a Linux VM with no desktop and asked whether a console-based GUI could be considered alongside the browser console. Since then, two changes cover much of that need without a full-screen UI:

- **SSH tunnel (#64).** The web console works from any machine through `ssh -L 7420:127.0.0.1:7420`, and `jrsctl console` prints that command on a server without a display.
- **Guided mode (#71).** `jrsctl` with no command opens a menu that asks for each job's inputs and runs the ordinary command. It works over SSH, needs no library, and leaves scripts unaffected.

A full-screen terminal UI (a dashboard, live run progress, run history, recovery) would add what the menu does not: a live view of a run and of the server that updates without re-typing commands.

## Options evaluated

| Option | What it takes | Concerns |
|---|---|---|
| **A. No full-screen UI** (guided mode plus SSH tunnel to the web console) | Nothing further | No live terminal dashboard; progress is the CLI's line-by-line output |
| **B. JLine 3** (line editing, terminal control, key bindings) | A new dependency (ADR, spec §13.3); screens built on top | Needs a native console provider on Windows. JNA is not approved (spec §13.3), so a JNI or FFM provider would have to work inside the jlink image; licence to be confirmed as BSD-style; the screens themselves are still hand-written |
| **C. Lanterna 3** (full-screen text UI toolkit with widgets) | A new dependency (ADR, spec §13.3) | Licence to be confirmed (reported as LGPL-3.0; compatibility with GPL-3.0-only to be checked); Windows console support to be verified inside the jlink image, since its fallback terminal uses Swing (`java.desktop`), which the image may not include (ADR-0008); a larger API surface to keep reviewed |
| **D. Hand-written ANSI screens** | No dependency | Raw input handling (key reading without echo, resize) is not possible on Windows from pure Java without a native console API; a partial implementation would behave differently per platform |

Every option other than A must also:
- work in the jlink image on Windows (cmd.exe, PowerShell, Windows Terminal) and on Linux over SSH;
- degrade to `--ascii`;
- keep the redaction rules on every line drawn;
- drive runs through the same `RunService` as the CLI and the web console.

## Recommendation

Option A for 1.6.0. The reported need (a usable interface on a server without a desktop) is met by #64 and #71, at no dependency cost and with the same behaviour on both operating systems.

If testers of 1.6.0 still ask for a live terminal dashboard, build it with option B:
- first prove, in a spike, a JNI or FFM console provider that works in the jlink image on Windows without JNA;
- confirm the licence;
- then write the dependency ADR and scope a first version to a dashboard and live run progress, leaving configuration to guided mode.

## Consequences

- #75 stays open until the maintainers accept option A (close the issue) or ask for the option B spike.
- No dependency is added and the jlink image is unchanged.
