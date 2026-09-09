# jrsctl branding

jrsctl ships under the **Actian Jaspersoft** name. Branding is applied in three places: the CLI banner and help text, the web console, and the operator documentation.

## Naming

- Product: `jrsctl` (lowercase, monospace where the medium allows).
- Vendor line: "Actian Jaspersoft". Used in `--version`, the console footer, and document footers.
- Server product name in copy: "JasperReports Server" on first mention, "JRS" afterwards.
- Never "TIBCO Jaspersoft" in new copy.

## Tokens

The console reads every colour and type value from `app/src/main/resources/web/brand.css`. Replacing that one file rebrands the console.

> **Source.** No official, downloadable Actian brand guide (PDF or design-tokens file) was found on this machine. The values below are ported from `webapp/jasper-wizard/src/main/webapp/css/style.css` in the sibling `tx-geocoder` project, an internal Actian Jaspersoft tool whose stylesheet is explicitly headed *"Actian corporate identity — palette + type sampled from actian.com"*. That repository also holds a couple of secondary, mutually inconsistent navy/blue palettes (a docx export theme, a static UI mockup) that were **not** used here because they don't match the actian.com-sampled tokens and aren't labelled as authoritative. Tokens not given directly by that source (`--ink-3`, wash colours, dark-mode variants, `--brand-sky`) are derived from it by blending toward white/black so the ramp stays internally consistent; `--warn` reuses the amber accent Actian's own JasperReports chart customizer (`GradientTrendCustomizer.java` in `tx-geocoder`) already uses on Actian-branded charts, since actian.com's own palette has no warning colour. Replace any of this with the official guide's values if one is later supplied; nothing else in the code needs to change.

| Token | Value | Role |
|---|---|---|
| `--brand-navy` | `#12161E` | Nav rail, headings, terminal title bar (Actian's near-black "ink", their own token for headings/dark surfaces) |
| `--brand-blue` | `#0F5FDC` | Primary action, active nav item, links (Actian's primary action blue) |
| `--brand-blue-ink` | `#19368E` | Link text on light ground (Actian's deep-blue hover shade) |
| `--brand-sky` | `#DBE7FA` | Tint behind active state and info chips (derived: 15% brand-blue over white) |
| `--paper` | `#EFF3F7` | Page ground (Actian's app background) |
| `--surface` | `#FFFFFF` | Panels and tables (Actian's card colour) |
| `--ink` | `#344054` | Body text (Actian's body-text token) |
| `--ink-2` | `#556579` | Secondary text (Actian's muted/secondary-text token) |
| `--ink-3` | `#929DAB` | Labels, muted text (derived: midpoint of `--ink-2` and `--rule`) |
| `--rule` | `#D0D5DD` | Borders (Actian's line colour) |
| `--ok` | `#198754` | Pass / succeeded (Actian's own success colour) |
| `--warn` | `#E87722` | Warn / attention (Actian's chart-accent amber, reused — actian.com's own palette has no warn colour) |
| `--fail` | `#DC3545` | Fail / irreversible (Actian's own error colour) |

Actian's identity also carries a teal (`#35BBD8` / `#36D6D9`) and a magenta (`#990073`, used sparingly) that jrsctl doesn't currently need as UI tokens; `--log-accent` below reuses the brighter teal for legibility on the dark log pane, which is the one place the console already wanted a second accent colour.

Type: **"Open Sans"** for UI text (Actian's own body font on actian.com; system fallbacks `"Segoe UI", Roboto, Helvetica, Arial, sans-serif`) and a native monospace stack (`"Consolas", "Cascadia Mono", ui-monospace, monospace`) for commands, ids and logs — Actian's site doesn't specify a code/monospace face, so this stays a neutral OS-provided choice rather than an invented brand claim. Actian's real heading face is the proprietary "Roobert PRO" (with an Inter fallback in the source stylesheet); since it isn't licensed for redistribution, jrsctl heads with Inter's open fallback where the source did, but the console has no distinct heading face today (`--font-ui` covers both), so it isn't wired in — a future distinct heading style should use `"Inter", "Open Sans", "Segoe UI", system-ui, sans-serif`. The console must not load fonts from a CDN (isolated mode); no font files are bundled in `web/fonts/`, so every face above is left to resolve through the OS's own font substitution — this was already the de facto behaviour (no `web/fonts/` directory exists and `WebAssetsTest` forbids any `http(s)://` reference), it just wasn't stated plainly before.

## Rules

- Status is always icon + word; colour is never the only signal (spec §13.2).
- The Actian logo is not embedded until an approved SVG is supplied; the console shows the wordmark as text until then. (`tx-geocoder` has a raster `actian-logo.jpg` with a baked-in white background plate — not a vetted, redistributable master asset, so it was not adopted here.)
- Terminal output uses colour only when stdout is a TTY and `NO_COLOR` is unset.
