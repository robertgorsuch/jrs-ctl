# jrsctl branding

jrsctl ships under the **Actian Jaspersoft** name. Branding is applied in three places: the CLI banner and help text, the web console, and the operator documentation.

## Naming

- Product: `jrsctl` (lowercase, monospace where the medium allows).
- Vendor line: "Actian Jaspersoft". Used in `--version`, the console footer, and document footers.
- Server product name in copy: "JasperReports Server" on first mention, "JRS" afterwards.
- Never "TIBCO Jaspersoft" in new copy.

## Tokens

The console reads every colour and type value from `app/src/main/resources/web/brand.css`. Replacing that one file rebrands the console.

> **Provisional values.** The official Actian brand guide was not reachable from the build machine. The values below were chosen to be plausible for Actian's navy-and-blue identity and are flagged in the CSS with `/* provisional */`. Replace them with the guide's values before release; nothing else in the code needs to change.

| Token | Provisional value | Role |
|---|---|---|
| `--brand-navy` | `#0B1F3A` | Nav rail, headings, terminal title bar |
| `--brand-blue` | `#1F5EFF` | Primary action, active nav item, links |
| `--brand-blue-ink` | `#143FB0` | Link text on light ground |
| `--brand-sky` | `#DCE6FF` | Tint behind active state and info chips |
| `--paper` | `#F4F6F9` | Page ground |
| `--surface` | `#FFFFFF` | Panels and tables |
| `--ink` | `#16202C` | Body text |
| `--ink-2` | `#4B5A6B` | Secondary text |
| `--ink-3` | `#7D8A99` | Labels, muted text |
| `--rule` | `#D6DDE6` | Borders |
| `--ok` | `#177A5B` | Pass / succeeded |
| `--warn` | `#A05E0A` | Warn / attention |
| `--fail` | `#B23A2E` | Fail / irreversible |

Type: "IBM Plex Sans" for UI text and "IBM Plex Mono" for commands, ids and logs, both with system fallbacks. The console must not load fonts from a CDN (isolated mode), so the font files are bundled in `web/fonts/` or the fallback stack is used; Phase 6 decides which and records it in `BUILD_STATUS.md`.

## Rules

- Status is always icon + word; colour is never the only signal (spec §13.2).
- The Actian logo is not embedded until an approved SVG is supplied; the console shows the wordmark as text until then.
- Terminal output uses colour only when stdout is a TTY and `NO_COLOR` is unset.
