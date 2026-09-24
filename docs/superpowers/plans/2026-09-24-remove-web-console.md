# Remove the web console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship jrsctl 2.0.0 as a command-line tool with no web console, without ever releasing a build that lacks the support bundle.

**Architecture:** Two pull requests in strict order. PR 1 (issue #150) lifts `SupportBundle` and `VendorLogs` out of `app.console`, cuts their dependence on the console's view objects, and exposes them as `jrsctl runs support-bundle <id>`; the console keeps delegating to the moved class until it is deleted. PR 2 (issue #151) deletes the console package, the static UI, the tests, the schemas and the Javalin dependency, tolerates a leftover `console:` block in `config.yaml` for one release, and rewrites the spec to Draft 1.2. Each task below is one commit, so the deletion PR is reviewed commit by commit rather than as one 12,000-line diff.

**Tech Stack:** Java 21, Maven (`scripts\mvn.cmd` / `scripts/mvn.sh`), picocli, Jackson, networknt JSON Schema, JUnit 5 + AssertJ, the `scripts\fast.cmd` tiers from `CLAUDE.md`.

**Spec:** `docs/decisions/0038-remove-the-web-console.md` (ADR-0038, PR #149). The spec it amends is `docs/spec.md` Draft 1.1; this plan produces Draft 1.2.

## Global Constraints

- Build only through `scripts\mvn.cmd` / `scripts/mvn.sh` (JDK 21; the machine default is 11). Iterate with `scripts\fast.cmd test <module> <TestClass>` and `scripts\fast.cmd guards`; run the full `verify` once per PR, then read CI (`gh pr checks <n>`), always including the ubuntu leg.
- Error Prone with `-Werror`: no unused imports, no dead fields, explicit casts, no nested class named `Record`.
- `google-java-format` via `scripts\fast.cmd fmt` before every commit; the Spotless check is the first CI step.
- Mutations only inside a `Step`; read-only commands are plain functions returning a report. `runs support-bundle` writes one zip and touches nothing else.
- No `java.io.File`; streaming only, no whole-file byte arrays; every output stream passes the `Redactor`.
- No new third-party dependency; this plan removes one (`io.javalin:javalin`) and the pins it dragged in (`kotlin-stdlib`).
- A new leaf command needs all three of: a `JsonSchemas` entry with a schema file, a `JsonOutputSchemaTest` scenario, and a `HelpExamples` entry (`HelpExamplesTest` fails otherwise).
- `docs/spec-changelog.md` and `docs/BUILD_STATUS.md` are `merge=union`; after any local rebase, diff and scan them for duplicated lines before pushing.
- Conventional Commits, one logical change per commit, branch per task, commit messages end with the attribution lines the session prescribes.
- Exit codes: 0 ok · 1 usage · 2 precheck (nothing mutated) · 9 lock held (`app/ExitCodes`).
- Release is a tag: CI sets the version from `v2.0.0`; no pom version edit in either PR (`main` is `1.9.0-SNAPSHOT`).

## Review Focus

1. A `config.yaml` written by 1.x that still carries a `console:` block: 2.0.0 must load it with exactly one warning, never a refusal (Task 6, `ConfigLoaderTest`).
2. `JRSCTL_CONSOLE_PORT` or another `JRSCTL_CONSOLE_*` variable left in a shell profile: ignored silently, since only schema keys are read from the environment (Task 6 test).
3. `runs support-bundle` when the server is down or unconfigured: the zip is still written and `server.json` says so, because a support bundle is most needed when the server is broken (Task 2 test runs against a home with no reachable server).
4. `runs support-bundle --out` naming a file that already exists: refuse with exit 2 and change nothing; a support engineer must never overwrite an earlier bundle by accident (Task 2 test).
5. A `console.token` file left behind by a 1.x crash: nothing in 2.0.0 reads it; the operator guide's home-layout table tells the operator it can be deleted (Task 7).

---

## PR 1 — `runs support-bundle` (issue #150)

Branch: `feat/150-support-bundle-command` off `main`.

```bash
git checkout main && git pull --ff-only
git checkout -b feat/150-support-bundle-command
```

### Task 1: Move `SupportBundle` and `VendorLogs` to `app` with no console dependency

**Files:**
- Move: `app/src/main/java/com/jaspersoft/jrsctl/app/console/SupportBundle.java` → `app/src/main/java/com/jaspersoft/jrsctl/app/SupportBundle.java`
- Move: `app/src/main/java/com/jaspersoft/jrsctl/app/console/VendorLogs.java` → `app/src/main/java/com/jaspersoft/jrsctl/app/VendorLogs.java`
- Move: `app/src/test/java/com/jaspersoft/jrsctl/app/console/VendorLogsTest.java` → `app/src/test/java/com/jaspersoft/jrsctl/app/VendorLogsTest.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/RunsCommand.java:180-210` (extract `showTree`, widen `parse`)
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleServer.java:115`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/console/ConsoleApi.java:61-82,557-566`
- Test: `app/src/test/java/com/jaspersoft/jrsctl/app/ConsoleServerTest.java:632` (unchanged, must stay green)

**Interfaces:**
- Consumes: `Services` (`config()`, `stateStore()`, `adapter()`, `home()`, `platform()`, `clock()`, `redactor()`), `RunRecord`, `StateStore.loadPlan/transitions/snapshots`, `DoctorOperation`, `ConfigShow.render`.
- Produces: `public final class com.jaspersoft.jrsctl.app.SupportBundle` with `public SupportBundle(Services services)`, `public Prepared prepare(RunRecord run)`, `public void write(Prepared prepared, OutputStream target) throws IOException`, and `public record Prepared(...)`. `static Map<String, Object> RunsCommand.showTree(RunRecord, Optional<JsonNode>, List<Transition>, List<SnapshotRecord>, Clock)`.

- [ ] **Step 1: Move the two classes and the test with `git mv`, fix the package lines**

```bash
git mv app/src/main/java/com/jaspersoft/jrsctl/app/console/SupportBundle.java app/src/main/java/com/jaspersoft/jrsctl/app/SupportBundle.java
git mv app/src/main/java/com/jaspersoft/jrsctl/app/console/VendorLogs.java app/src/main/java/com/jaspersoft/jrsctl/app/VendorLogs.java
git mv app/src/test/java/com/jaspersoft/jrsctl/app/console/VendorLogsTest.java app/src/test/java/com/jaspersoft/jrsctl/app/VendorLogsTest.java
```

In all three files change `package com.jaspersoft.jrsctl.app.console;` to `package com.jaspersoft.jrsctl.app;`. Make `SupportBundle` and `VendorLogs` `public final class`, `Prepared` a `public record`, and `prepare`, `write` (both overloads), `VendorLogs.Source`, `VendorLogs.locate`, `VendorLogs.tail`, `VendorLogs.blankPassword`, `VendorLogs.TAIL_BYTES` `public` (the console subpackage must still reach them).

- [ ] **Step 2: Extract the run document builder in `RunsCommand`**

In `RunsCommand.Show.call()` the JSON branch builds a `LinkedHashMap` with `run`, `plan`, `transitions`, `snapshots`. Replace those five lines with a call and add the static method next to `runTree`:

```java
/** The `runs show --json` document (runs-show.schema.json); the support bundle's run.json. */
static Map<String, Object> showTree(
    RunRecord run,
    Optional<JsonNode> plan,
    List<Transition> transitions,
    List<SnapshotRecord> snapshots,
    Clock clock) {
  Map<String, Object> root = new LinkedHashMap<>();
  root.put("run", runTree(run, clock));
  root.put("plan", plan);
  root.put("transitions", transitions);
  root.put("snapshots", snapshots);
  return root;
}
```

and in `Show.call()`:

```java
if (global.json()) {
  out.println(
      redactor.redact(
          JsonOut.write(showTree(run, planTree, transitions, snapshots, services.clock()))));
  out.flush();
  return ExitCodes.SUCCESS;
}
```

Change the private `parse(String)` helper in `RunsCommand` to package-private `static JsonNode parse(String json)` so `SupportBundle` can reuse it.

- [ ] **Step 3: Rewrite `SupportBundle`'s constructor and `prepare` without console types**

Replace the fields, constructor and `prepare` (lines 48-82 of the moved file) with:

```java
private final Services services;
private final Redactor redactor;

public SupportBundle(Services services) {
  this.services = Objects.requireNonNull(services, "services");
  this.redactor = services.redactor();
}

/**
 * Everything that has to be computed before the first byte is written: the live doctor report
 * and the server probe behind it, plus the documents that read the state store (review 4.8).
 */
public record Prepared(
    RunRecord run,
    String runJson,
    Optional<String> planJson,
    String serverJson,
    String doctorJson,
    String configYaml) {}

/** Runs everything that can fail. Throws before any byte of the archive is written. */
public Prepared prepare(RunRecord run) {
  StateStore store = services.stateStore().get();
  Optional<StoredPlan> plan = run.planId().flatMap(store::loadPlan);
  Map<String, Object> runDoc =
      RunsCommand.showTree(
          run,
          plan.map(p -> RunsCommand.parse(p.planJson())),
          store.transitions(run.runId()),
          store.snapshots(run.runId()),
          services.clock());
  return new Prepared(
      run,
      Json.writePretty(runDoc),
      plan.map(StoredPlan::planJson),
      Json.writePretty(serverDocument()),
      JsonOut.write(new DoctorOperation(services).run(DoctorOptions.DEFAULT)),
      ConfigShow.render(services.config()));
}

/**
 * The server as the adapter sees it, or {@code reachable:false} with the configured base URL when
 * it cannot be reached: a bundle is wanted most when the server is broken, so this never throws.
 */
private Map<String, Object> serverDocument() {
  Map<String, Object> m = new LinkedHashMap<>();
  m.put("baseUrl", services.config().baseUrl().map(Object::toString).orElse(""));
  try {
    ServerIdentity identity = services.adapter().get().identity();
    m.put("reachable", true);
    m.put("identity", identity);
  } catch (RuntimeException e) {
    m.put("reachable", false);
    m.put("error", redactor.redact(String.valueOf(e.getMessage())));
  }
  return m;
}
```

Add the imports: `com.jaspersoft.jrsctl.core.state.StoredPlan`, `com.jaspersoft.jrsctl.jrs.api.ServerIdentity`, `com.jaspersoft.jrsctl.ops.doctor.DoctorOperation`, `com.jaspersoft.jrsctl.ops.doctor.DoctorOptions`, `com.fasterxml.jackson.databind.JsonNode` if needed, `java.util.LinkedHashMap`, `java.util.Map`. Remove the `ConsoleViews`, `DoctorCache` and `LogFile` imports that no longer resolve (keep `LogFile`: `logFile()` still reads `LogFile.PROPERTY`). If `Config` has no `baseUrl()` accessor, use `services.config().server().baseUrl()`; `ConfigLoaderTest.should_return_base_url_when_server_is_configured` shows which one exists. Update the class Javadoc's first sentence to "The zip behind `jrsctl runs support-bundle` (and, until ADR-0038 lands, `GET /api/runs/{id}/support-bundle`)".

- [ ] **Step 4: Point the console at the moved class**

`ConsoleServer.java:115`: `SupportBundle bundle = new SupportBundle(services);` and import `com.jaspersoft.jrsctl.app.SupportBundle`. `ConsoleApi.java`: same import; the field, constructor parameter and the handler at line 560-565 compile unchanged. Delete the now-unused `views`/`doctor` arguments only if the compiler flags them (they are still used by other routes).

- [ ] **Step 5: Compile and run the two tests that pin the behaviour**

Run: `scripts\fast.cmd test app ConsoleServerTest,VendorLogsTest,RunsCommandTest`
Expected: PASS; `should_write_support_bundle_with_expected_entries_and_no_registered_secret` still finds `run.json`, `plan.json`, `transitions.jsonl`, `server.json`, `doctor.json`, `config-redacted.yaml`, `logs/jrsctl.log` and the `vendor/` entries and never the secret.

- [ ] **Step 6: Format and commit**

```bash
scripts\fast.cmd fmt
git add -A app
git commit -m "refactor(app): move SupportBundle and VendorLogs out of the console package (#150)"
```

### Task 2: `jrsctl runs support-bundle <id> [--out <zip>]`

**Files:**
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/RunsCommand.java:48-58` (subcommand list) and append the new nested class
- Create: `app/src/main/resources/schema/json/runs-support-bundle.schema.json`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/JsonSchemas.java:85-87`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/HelpExamples.java:329-334`
- Modify: `app/src/test/java/com/jaspersoft/jrsctl/app/JsonOutputSchemaTest.java:1178-1193`
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/RunsSupportBundleTest.java`

**Interfaces:**
- Consumes: `SupportBundle` from Task 1, `Bootstrap.open`, `ExitCodes.fail(out, err, json, code, message, Optional<String> hint)`, `JsonOut.write`.
- Produces: the leaf command path `"runs support-bundle"`, its JSON document `{runId, path, entries[], bytes}`.

- [ ] **Step 1: Write the failing unit test**

`RunsSupportBundleTest.java` (seed the home and run `r-1` exactly the way `RunsCommandTest` does; copy its `@BeforeEach` and its command-invocation helper rather than inventing a new fixture):

```java
package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsSupportBundleTest {

  private static final String SECRET = "hunter2-support";

  @TempDir Path tmp;

  @Test
  void should_write_a_redacted_zip_and_report_its_entries_when_the_run_exists() throws Exception {
    Path home = seededHome(tmp); // the RunsCommandTest fixture: config.yaml + state.db with run r-1
    Redactor.global().register(SECRET);
    Files.createDirectories(home.resolve("logs"));
    Files.writeString(
        home.resolve("logs").resolve("jrsctl.log"),
        "{\"message\":\"login password=" + SECRET + "\"}\n",
        StandardCharsets.UTF_8);
    Path out = tmp.resolve("r-1.zip");

    Cli.Result r = cli("runs", "support-bundle", "r-1", "--out", out.toString(), "--json", "--home", home.toString());

    assertThat(r.exit()).isEqualTo(0);
    JsonNode doc = Json.parse(r.stdout());
    assertThat(doc.get("runId").asText()).isEqualTo("r-1");
    assertThat(Path.of(doc.get("path").asText())).isEqualTo(out.toAbsolutePath());
    assertThat(doc.get("bytes").asLong()).isEqualTo(Files.size(out));
    List<String> entries = new ArrayList<>();
    StringBuilder everything = new StringBuilder();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(out)))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        entries.add(e.getName());
        everything.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    assertThat(entries)
        .contains("run.json", "transitions.jsonl", "server.json", "doctor.json", "config-redacted.yaml", "logs/jrsctl.log");
    assertThat(doc.get("entries")).extracting(JsonNode::asText).containsExactlyElementsOf(entries);
    assertThat(everything).doesNotContain(SECRET);
    assertThat(everything).contains("\"reachable\" : false"); // no server in the fixture
  }

  @Test
  void should_refuse_with_exit_2_and_write_nothing_when_the_run_is_unknown() throws Exception {
    Path home = emptyHome(tmp);
    Cli.Result r = cli("runs", "support-bundle", "r-nope", "--json", "--home", home.toString());
    assertThat(r.exit()).isEqualTo(2);
    assertThat(Files.list(tmp).filter(p -> p.toString().endsWith(".zip"))).isEmpty();
  }

  @Test
  void should_refuse_with_exit_2_when_the_output_file_already_exists() throws Exception {
    Path home = seededHome(tmp);
    Path out = Files.writeString(tmp.resolve("taken.zip"), "old");
    Cli.Result r = cli("runs", "support-bundle", "r-1", "--out", out.toString(), "--home", home.toString());
    assertThat(r.exit()).isEqualTo(2);
    assertThat(Files.readString(out)).isEqualTo("old");
    assertThat(r.stderr()).contains("already exists");
  }
}
```

(`Files.readAllBytes` on the test's own small zip is fine in a test; production code streams.)

- [ ] **Step 2: Run it to see it fail**

Run: `scripts\fast.cmd test app RunsSupportBundleTest`
Expected: FAIL, picocli reports `Unmatched argument at index 1: 'support-bundle'` (exit 1).

- [ ] **Step 3: Add the command**

Register it: in the `@Command(subcommands = {...})` of `RunsCommand` add `RunsCommand.SupportBundleCommand.class` after `Prune.class`. Append the nested class:

```java
/** {@code jrsctl runs support-bundle <id> [--out <zip>] [--json]}: the support bundle as a file. */
@Command(
    name = "support-bundle",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description =
        "Write one run's support bundle: run, plan, step transitions, doctor report, server"
            + " identity, redacted configuration, log tails and the vendor's own logs, all redacted.")
static final class SupportBundleCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Parameters(index = "0", paramLabel = "<id>", description = "Run id from `runs list`.")
  String runId;

  @Option(
      names = "--out",
      paramLabel = "<zip>",
      description = "File to write (default: <id>-support-bundle.zip in the current directory).")
  Path out;

  @Override
  public Integer call() throws IOException {
    PrintWriter o = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    Redactor redactor = Redactor.global();
    Path target = (out == null ? Path.of(runId + "-support-bundle.zip") : out).toAbsolutePath();
    if (Files.exists(target)) {
      return ExitCodes.fail(
          o, err, global.json(), ExitCodes.PRECHECK_FAILED,
          target + " already exists", Optional.of("choose another --out or move the old bundle"));
    }
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      Services services = boot.services();
      Optional<RunRecord> found = services.stateStore().get().run(runId);
      if (found.isEmpty()) {
        return ExitCodes.fail(
            o, err, global.json(), ExitCodes.PRECHECK_FAILED,
            "unknown run " + runId, Optional.of("see `jrsctl runs list`"));
      }
      SupportBundle bundle = new SupportBundle(services);
      SupportBundle.Prepared prepared = bundle.prepare(found.get()); // everything that can fail
      try (OutputStream zip = Files.newOutputStream(target)) {
        bundle.write(prepared, zip);
      }
      List<String> entries = new ArrayList<>();
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(target))) {
        for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
          entries.add(e.getName());
        }
      }
      if (global.json()) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("runId", runId);
        doc.put("path", target.toString());
        doc.put("entries", entries);
        doc.put("bytes", Files.size(target));
        o.println(redactor.redact(JsonOut.write(doc)));
      } else {
        o.println(
            redactor.redact("Support bundle written: " + target + " (" + entries.size() + " entries)"));
      }
      o.flush();
      return ExitCodes.SUCCESS;
    }
  }
}
```

Imports to add to `RunsCommand`: `java.io.OutputStream`, `java.nio.file.Files`, `java.nio.file.Path`, `java.util.zip.ZipEntry`, `java.util.zip.ZipInputStream`, `picocli.CommandLine.Option` (already present for `Prune`).

- [ ] **Step 4: Schema, registry, help example**

`runs-support-bundle.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://jaspersoft.com/jrsctl/runs-support-bundle.schema.json",
  "title": "jrsctl runs support-bundle <id> --json: where the bundle was written and what it holds",
  "type": "object",
  "additionalProperties": false,
  "required": ["runId", "path", "entries", "bytes"],
  "properties": {
    "runId": { "type": "string", "minLength": 1 },
    "path": { "type": "string", "minLength": 1 },
    "entries": { "type": "array", "minItems": 1, "items": { "type": "string" } },
    "bytes": { "type": "integer", "minimum": 1 }
  }
}
```

`JsonSchemas.java`, after the `"runs prune"` line: `m.put("runs support-bundle", new Document("runs-support-bundle.schema.json"));`

`HelpExamples.java`, after the `"runs show"` entry:

```java
m.put(
    "runs support-bundle",
    List.of(
        new Example("Write the bundle for a support ticket", "jrsctl runs support-bundle <run-id>"),
        new Example(
            "Into a named file, listing the entries as JSON",
            "jrsctl runs support-bundle <run-id> --out bundle.zip --json")));
```

`JsonOutputSchemaTest.java`: after the `"runs show unknown"` scenario add two scenarios that reuse the `runs show` fixture lines verbatim (the home with run `r-1` and `emptyHome(dir)`):

```java
scenario("runs support-bundle", "runs support-bundle", 0, dir -> {
  Path home = /* same seeded home as the "runs show" scenario */;
  return run("runs", "support-bundle", "r-1", "--out", dir.resolve("b.zip").toString(), "--json", "--home", home.toString());
}),
scenario("runs support-bundle unknown", "runs support-bundle", 2,
    dir -> run("runs", "support-bundle", "r-nope", "--json", "--home", emptyHome(dir).toString())),
```

- [ ] **Step 5: Run the new test, then the guards**

Run: `scripts\fast.cmd test app RunsSupportBundleTest,RunsCommandTest,HelpExamplesTest,JsonOutputSchemaTest`
Expected: PASS.
Run: `scripts\fast.cmd guards`
Expected: PASS (`IdempotencyCoverageTest` is unaffected: no new `Step`).

- [ ] **Step 6: Format and commit**

```bash
scripts\fast.cmd fmt
git add -A app
git commit -m "feat(app): runs support-bundle writes the support bundle from the CLI (#150)"
```

### Task 3: Documentation for PR 1, verify, PR, merge

**Files:**
- Modify: `docs/operator-guide.md:483-491` (add the section after `runs show`), `docs/operator-guide.md:701`
- Modify: `docs/spec.md:697` (the support-bundle endpoint row), `docs/spec.md` `runs` command table (§8 or §17, find `runs prune`)
- Modify: `docs/spec-changelog.md:1-3` (prepend), `docs/BUILD_STATUS.md` (one bullet after the #102 bullet)

- [ ] **Step 1: Operator guide**

After the `runs show` section insert:

```markdown
### `jrsctl runs support-bundle <id> [--out <zip>] [--json]`

Writes one run's support bundle as a zip: `run.json` (the `runs show --json` document), `plan.json`, `transitions.jsonl`, `events.jsonl` when the run kept one, `server.json` (identity, or `reachable: false` when the server cannot be probed), `doctor.json` (a fresh `doctor` run), `config-redacted.yaml`, the last 2,000 lines of `logs/jrsctl.log`, and under `vendor/` the newest buildomatic log, `jasperserver.log`, `catalina.out` and the installer log, each tail-capped at 5 MB with passwords blanked. Every byte passes the redactor.

- **Mutates:** only the zip it writes; refuses to overwrite an existing file.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when the id is unknown or the output file exists.
- **Flags:** `<id>` — from `runs list`; `--out <zip>` — default `<id>-support-bundle.zip` in the current directory; `--json` — `{runId, path, entries, bytes}`.
```

Line 701: replace "attach the support bundle from the console (`GET /api/runs/<id>/support-bundle`, or the button on the run page), which since issue #111 also carries" with "attach the bundle `jrsctl runs support-bundle <id>` writes, which since issue #111 also carries".

- [ ] **Step 2: Spec, changelog, status**

`docs/spec.md`: in the `runs` command table add the row `| runs support-bundle <id> [--out <zip>] | zip of plan, transitions (JSONL), logs, server info, doctor report, redacted config, vendor logs |`; change the §13.1 endpoint row's purpose to "as `runs support-bundle`". Prepend to `docs/spec-changelog.md`:

```markdown
## Draft 1.1 amendment — <date> (issue #150, support bundle on the CLI)

- §8, §13.1: `runs support-bundle <id> [--out <zip>]` writes the bundle the console served at `GET /api/runs/{id}/support-bundle`; same entries, same redaction, `server.json` degrades to `reachable:false` instead of failing. First step of ADR-0038. `SupportBundle` and `VendorLogs` move from `app.console` to `app`; the console delegates to them.
```

`docs/BUILD_STATUS.md`: one bullet in the same list as the #144/#102 bullets: "**#150 `runs support-bundle`.** The bundle has a CLI command (`RunsSupportBundleTest`, `runs-support-bundle.schema.json`); the console endpoint delegates. Step 1 of ADR-0038."

- [ ] **Step 3: Full verify, push, CI, merge**

```bash
scripts\fast.cmd fmt
git add docs
git commit -m "docs: runs support-bundle in the operator guide, spec and status (#150)"
scripts\mvn.cmd verify > verify-150.log 2>&1
grep -E "BUILD (SUCCESS|FAILURE)|Tests run:.*Failures: [1-9]" verify-150.log
git push -u origin feat/150-support-bundle-command
gh pr create --base main --title "feat(app): runs support-bundle, the support bundle on the CLI (#150)" --body "Closes #150. Step 1 of ADR-0038 (#149)."
gh pr checks <n> --watch
```

Expected: `BUILD SUCCESS` locally; every CI check green, the ubuntu leg included. Merge with the GitHub button (no `--delete-branch` until merged), then `git checkout main && git pull --ff-only`.

- [ ] **Step 4: Manual check against the real install**

With the jrsctl home you use against the local 10.0.0 install: `jrsctl runs list`, pick an id, `jrsctl runs support-bundle <id>`; open the zip and confirm `vendor/` has the installer log and `server.json` has `reachable: true`. Record the result in the PR before merging.

---

## PR 2 — remove the console (issue #151)

Branch: `feat/151-remove-console` off the `main` that contains PR 1. One commit per task; do not squash.

```bash
git checkout main && git pull --ff-only
git checkout -b feat/151-remove-console
```

### Task 4: Delete the console

**Files:**
- Delete: `app/src/main/java/com/jaspersoft/jrsctl/app/console/` (38 files), `app/src/main/resources/web/` (16 files), `app/src/main/java/com/jaspersoft/jrsctl/app/ConsoleCommand.java`, `app/src/main/java/com/jaspersoft/jrsctl/app/ConsoleShutdown.java`
- Delete tests: `app/src/test/java/com/jaspersoft/jrsctl/app/{ConsoleServerTest,ConsoleFixture,ConsoleHardeningTest,ConsoleHeadlessHintTest,ConsoleSchemaTest,ConsoleShutdownTest,ConsoleWireGoldenTest,ConsoleScrub}.java`, `app/src/test/java/com/jaspersoft/jrsctl/app/console/ConsoleAuthTest.java`, `app/src/test/java/com/jaspersoft/jrsctl/app/web/WebAssetsTest.java`, `acceptance/src/test/java/com/jaspersoft/jrsctl/acceptance/Phase6ConsoleTest.java`, `acceptance/phase6/`
- Delete schemas: `app/src/main/resources/schema/json/api-*.schema.json` (17) and `console.schema.json`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/JrsctlCommand.java:50`, `JsonSchemas.java:97,111-150`, `HelpExamples.java:385-390`, `GuidedMode.java:85`, `app/pom.xml:29-38`, `acceptance/src/test/java/com/jaspersoft/jrsctl/acceptance/Phase0SkeletonTest.java:43-52`, `scripts/fast.sh:23`, `scripts/fast.cmd` (the guards list), `CLAUDE.md:10`

- [ ] **Step 1: Delete**

```bash
git rm -r -q app/src/main/java/com/jaspersoft/jrsctl/app/console app/src/main/resources/web acceptance/phase6
git rm -q app/src/main/java/com/jaspersoft/jrsctl/app/ConsoleCommand.java app/src/main/java/com/jaspersoft/jrsctl/app/ConsoleShutdown.java
git rm -q app/src/test/java/com/jaspersoft/jrsctl/app/Console*.java app/src/test/java/com/jaspersoft/jrsctl/app/console/ConsoleAuthTest.java app/src/test/java/com/jaspersoft/jrsctl/app/web/WebAssetsTest.java
git rm -q acceptance/src/test/java/com/jaspersoft/jrsctl/acceptance/Phase6ConsoleTest.java
git rm -q app/src/main/resources/schema/json/api-*.schema.json app/src/main/resources/schema/json/console.schema.json
```

Check `ConsoleScrub.java` and `DelegatingPlatform.java` in the app tests: delete `ConsoleScrub` if nothing but the deleted tests used it (`grep -rl ConsoleScrub app/src/test`); keep `DelegatingPlatform` if another test uses it.

- [ ] **Step 2: Unhook**

- `JrsctlCommand.java:50`: remove `ConsoleCommand.class,` from the subcommand list.
- `JsonSchemas.java`: remove the `"console"` document line; remove `BY_ENDPOINT`, `endpointSchemas()`, `forEndpoint`, `endpoints()` and the `api-*` references; update the class Javadoc sentence that says the file "also holds the console API schemas".
- `HelpExamples.java`: remove the `"console"` entry.
- `GuidedMode.java:85`: the footer sentence becomes `"every command, and --json makes any command machine-readable."` (keep the sentence grammatical: read the two lines around it).
- `app/pom.xml`: delete the `<resources>` block with the `web/mock.js` and `web/README.md` excludes (lines 29-38) and the `Javalin console` words in the `<description>`.
- `Phase0SkeletonTest.java:43-52`: replace `packaged_jar_carries_no_mock_backend` with

```java
@Test
void packaged_jar_carries_no_web_console() throws Exception {
  Path jar = Path.of(System.getProperty("jrsctl.jar"));
  try (JarFile packaged = new JarFile(jar.toFile())) {
    assertThat(packaged.stream().map(JarEntry::getName))
        .as("ADR-0038: no static UI, no Javalin, no Jetty, no Kotlin in the jar")
        .noneMatch(n -> n.startsWith("web/") || n.startsWith("io/javalin/")
            || n.startsWith("org/eclipse/jetty/") || n.startsWith("kotlin/"));
  }
}
```

- `scripts/fast.sh:23`: `GUARDS='IdempotencyCoverageTest,HelpExamplesTest,JsonOutputSchemaTest,Phase0SkeletonTest'`; make the same edit in `scripts/fast.cmd`; `CLAUDE.md:10` drops `ConsoleSchemaTest` from the guards sentence.

- [ ] **Step 3: Compile, guards**

Run: `scripts\fast.cmd guards`
Expected: PASS. Fix every `cannot find symbol` the compiler reports; each one is a reference the grep in ADR-0038 missed, so note it in the commit body.

- [ ] **Step 4: Commit**

```bash
scripts\fast.cmd fmt
git add -A
git commit -m "refactor(app)!: remove the web console, its UI, tests and API schemas (ADR-0038, #151)"
```

### Task 5: Drop Javalin and the Kotlin pin

**Files:**
- Modify: `app/pom.xml:19`, `pom.xml:47,57,110,122-125`, `dist/src/image/LICENSE-THIRD-PARTY.txt:16-18`, `docs/spec.md:724` (§13.3 runtime list)

- [ ] **Step 1: Edit the poms and the licence file**

Remove `<dependency><groupId>io.javalin</groupId>...` from `app/pom.xml`; from the root pom remove the `javalin.version` and `kotlin-stdlib.version` properties, the Javalin managed dependency, the Kotlin comment block and managed dependency (the comment says "Transitive through Javalin"; with Javalin gone the pin has nothing to pin). Leave the WireMock/Jetty 11 comment at lines 145-146: it is about the acceptance module's test tooling, which stays. Delete the three Javalin rows from `LICENSE-THIRD-PARTY.txt`. In `docs/spec.md` §13.3 remove `javalin` from the runtime list.

- [ ] **Step 2: Prove the jar is clean**

```bash
scripts\mvn.cmd -pl app -am -DskipTests package > pkg.log 2>&1
jar tf app/target/jrsctl.jar | grep -E "^(io/javalin|org/eclipse/jetty|kotlin)/" | head
scripts\mvn.cmd -pl app dependency:tree | grep -i -E "javalin|jetty|kotlin"
jdeps --multi-release 21 --print-module-deps --ignore-missing-deps app/target/jrsctl.jar
```

Expected: the two greps print nothing; `jdeps` prints a list that is a subset of `dist.modules.fixed` in `dist/pom.xml:39`. Paste the `jdeps` line into the commit body.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "build: drop Javalin, Jetty and the Kotlin stdlib pin (ADR-0038, #151)"
```

### Task 6: Tolerate and then refuse the `console:` block

**Files:**
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigLoader.java:58-70,90-110,172-177,308-314,410-412,463-475`
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java:31,41,54,66,199-205,431-475`
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigWriter.java:155-164`
- Modify: `core/src/main/resources/schema/config.schema.json:86-109`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/ConfigKeys.java:69-77,103-104,146`, `app/src/main/java/com/jaspersoft/jrsctl/app/Bootstrap.java:87`
- Test: `core/src/test/java/com/jaspersoft/jrsctl/core/config/ConfigLoaderTest.java`, `ConfigWriterTest.java`, `app/src/test/java/com/jaspersoft/jrsctl/app/ConfigCommandTest.java`

**Interfaces:**
- Produces: `public ConfigLoader(Consumer<String> warnings)`; `public ConfigLoader()` keeps working with a no-op consumer. `Config` loses its `console` component; `Config.Console`, `Config.ConsoleAuth`, `Config.ConsoleAuthMode`, `Config.Tls` are deleted.

- [ ] **Step 1: Write the failing tests in `ConfigLoaderTest`**

```java
@Test
void should_drop_a_console_block_with_one_warning_when_a_1x_file_still_has_one() throws IOException {
  Path file = tmp.resolve("config.yaml");
  Files.writeString(file, """
      server:
        baseUrl: http://localhost:8080/jasperserver-pro
      console:
        port: 7421
        auth: { mode: local }
      """);
  List<String> warnings = new ArrayList<>();
  Config config = new ConfigLoader(warnings::add).load(file, Map.of(), Map.of());
  assertThat(config.server().baseUrl()).isPresent();
  assertThat(warnings).singleElement().asString()
      .contains("console:").contains(file.toString()).contains("ADR-0038");
}

@Test
void should_ignore_a_console_environment_variable_when_loading() throws IOException {
  Config config = new ConfigLoader().load(tmp.resolve("missing.yaml"), Map.of("JRSCTL_CONSOLE_PORT", "1"), Map.of());
  assertThat(config).isEqualTo(new ConfigLoader().load(tmp.resolve("missing.yaml"), Map.of(), Map.of()));
}

@Test
void should_refuse_a_console_key_naming_the_adr_when_setting() throws IOException {
  Path file = tmp.resolve("config.yaml");
  Files.writeString(file, "server:\n  baseUrl: http://localhost:8080/jasperserver-pro\n");
  assertThatThrownBy(() -> new ConfigLoader().set(file, "console.port", "7421"))
      .isInstanceOf(ConfigException.class)
      .hasMessageContaining("console.port is no longer used (ADR-0038)");
}

@Test
void should_skip_a_console_key_in_a_properties_file_with_one_warning() throws IOException {
  Path file = tmp.resolve("config.properties");
  Files.writeString(file, "server.baseUrl=http://localhost:8080/jasperserver-pro\nconsole.port=7421\n");
  List<String> warnings = new ArrayList<>();
  Config config = new ConfigLoader(warnings::add).load(file, Map.of(), Map.of());
  assertThat(config.server().baseUrl()).isPresent();
  assertThat(warnings).singleElement().asString().contains("console.port").contains("line 2");
}
```

(Use the method the existing `should_refuse_an_unknown_key_or_an_invalid_value_when_setting` test calls for "set", and its `tmp` field; the names above assume `set(Path, String, String)`.)

- [ ] **Step 2: Run them to see them fail**

Run: `scripts\fast.cmd test core ConfigLoaderTest`
Expected: the first three fail (no such constructor; validation refuses `console`), the fourth fails on "unknown configuration key console.port".

- [ ] **Step 3: Loader**

```java
private final Consumer<String> warnings;

public ConfigLoader() {
  this(w -> {});
}

/** {@code warnings} receives each tolerated-but-obsolete key (ADR-0038) once per load. */
public ConfigLoader(Consumer<String> warnings) {
  this.warnings = Objects.requireNonNull(warnings, "warnings");
  ... // the existing schema/leafKeys initialisation, unchanged
}

/** ADR-0038: a 1.x file may still carry console:; drop it with one warning, refuse it from 2.1. */
private void dropConsoleBlock(ObjectNode tree, Path file) {
  if (tree.remove("console") != null) {
    warnings.accept(
        "console: in " + file + " is no longer used (ADR-0038): remove the block; jrsctl 2.1 will refuse it");
  }
}
```

Call `dropConsoleBlock(tree, file)` in `load(Path, Map, Map)` immediately after the YAML tree is read and before environment and flag overrides are applied. In `requireKnown` add, before the `leafKeys` check:

```java
if (key.startsWith("console.")) {
  throw new ConfigException(
      key + " is no longer used (ADR-0038)", "remove console.* from the configuration: jrsctl has no web console");
}
```

In the properties parser (line 310) replace the unknown-key `throw` for `console.*` keys with a `continue` after `warnings.accept(key + " at " + where + " is no longer used (ADR-0038): remove the line")`. Delete lines 410-412 and the `new Config.Console(...)` argument (463-475) from `toConfig`.

- [ ] **Step 4: Records, writer, schema, keys, bootstrap**

`Config.java`: delete the `console` component and its `requireNonNull`, the `Console.defaults()` argument in `defaults()`, the `console().auth().passwordRef()` line in `secretRefs()`, the `ConsoleAuthMode` enum, and the `Console`, `Tls`, `ConsoleAuth` records. `ConfigWriter.java:155-164`: delete the block. `config.schema.json:86-109`: delete the `console` property. `ConfigKeys.java`: delete the seven `console.*` descriptions, the two `console.tls.*` path-key entries and the `JRS_CONSOLE_PASSWORD` case. `Bootstrap.java:87`: `new ConfigLoader(LOG::warn).load(home, env, options.set())`.

- [ ] **Step 5: Run the config tests across modules**

Run: `scripts\fast.cmd test core ConfigLoaderTest,ConfigWriterTest,PropertiesConfigTest`
Run: `scripts\fast.cmd test app ConfigCommandTest,InitCommandTest`
Expected: PASS. `ConfigCommandTest.should_describe_every_key_the_schema_knows` and `should_list_every_key_with_its_value_source_and_description` pass once `ConfigKeys` and the schema agree.

- [ ] **Step 6: Commit**

```bash
scripts\fast.cmd fmt
git add -A
git commit -m "feat(core)!: drop the console configuration block, tolerated with a warning for one release (ADR-0038, #151)"
```

### Task 7: Spec Draft 1.2 and documentation

**Files:**
- Modify: `docs/spec.md:6-8,48,95-115,122,139,150,188-192,277,333,347,626-632,636,681-722,752,790,833`
- Modify: `docs/spec-changelog.md:1`, `docs/BUILD_STATUS.md:13`, `docs/decisions/0007-headless-browser-test-in-ci.md:3-5`
- Modify: `README.md:20,205-227`, `docs/operator-guide.md:18,62,80,490,584-612`, `docs/security.md:4,14-23,26-45`, `docs/branding.md`, `docs/capabilities.md`, `docs/recovery-runbook.md`, `CONTRIBUTING.md`, `CLAUDE.md:26,56`

- [ ] **Step 1: Spec**

- Header: `**Status:** Draft 1.2 — build contract`, `**Date:** <today>`, `**Supersedes:** Draft 1.1 (2026-09-08)`.
- §1.2 line 48: `- Single self-contained application (\`jrsctl\`) with a CLI; guided mode (§17.1) is its interactive front end (ADR-0038 removed the web console).`
- §4 diagram: delete the `console (Javalin + SSE + UI)` box and its connector; the `cli (Picocli)` box feeds `ops` alone. Principle at line 122: `- **One engine, one front end.** The CLI (direct commands and guided mode) consumes the event stream live; \`runs show\` and \`runs support-bundle\` replay it from the journal.`
- §4 table line 139, `app` row: `Picocli commands, \`--json\` output, progress tree renderer, guided mode, support bundle, main entry, shaded JAR`.
- §5.1 line 150: remove `\`console.token\` (§11.2)`. Lines 188-192: delete the `console:` block from the sample and add the sentence `A \`console:\` block from a 1.x file is ignored with one warning in 2.0 and refused from 2.1 (ADR-0038).`
- §5.8 line 277: remove `console SSE,`. §6 line 333: `Plans serialize to JSON for \`--plan\` output; stored in the \`plans\` table with a 30-minute TTL for \`runs recover\`.` Line 347: `Single cancellation token shared by Ctrl-C and timeouts.`
- §11.2: title `### 11.2 Console security — removed`, body `Removed in Draft 1.2 by ADR-0038. jrsctl opens no listening socket.` §11.3 line 636: delete `console token issuance, launch-code exchange` from the audit row list.
- §13: title `## 13. Console — removed (ADR-0038)`; §13.1 body `The endpoints of Draft 1.1 are gone; \`runs support-bundle\` replaces \`GET /api/runs/{id}/support-bundle\`, every other endpoint had a CLI command already.` §13.2 body `Removed.` §13.3 keeps its number and content minus `javalin` (numbering stays because ADRs cite §13.3).
- §14 Phase 6: `**Phase 6 — Console** — removed in Draft 1.2 (ADR-0038); \`Phase6ConsoleTest\` deleted; the support bundle is covered by \`Phase8JsonSchemaTest\` through \`runs support-bundle\`.`
- §16 line 790: `docs/security.md — threat model, key management, hardening.` §19 Q3 line 833: append `Resolved by ADR-0038: no console.`

- [ ] **Step 2: Changelog, status, ADR-0007**

Prepend to `docs/spec-changelog.md`:

```markdown
## Draft 1.2 — <date> (ADR-0038, the web console is removed)

- §1.2, §4, §5.1, §5.8, §6, §11.2, §11.3, §13, §14, §16, §19: the Javalin console, its 25 endpoints, SSE stream and static UI are removed; jrsctl is a CLI with guided mode as its interactive front end. `runs support-bundle` (Draft 1.1 amendment for #150) replaces the one endpoint without a CLI counterpart. The `console:` configuration block is ignored with one warning in 2.0.0 and refused from 2.1. `javalin` leaves the §13.3 runtime list; Jetty and the Kotlin stdlib leave the jar with it. Released as 2.0.0. Field test evidence in issue #75; decision and options in ADR-0038.
```

`docs/BUILD_STATUS.md:13`: `| 6 | Console | removed | ADR-0038, 2.0.0: package, UI, \`Phase6ConsoleTest\` and the \`api-*\` schemas deleted; the support bundle lives on as \`runs support-bundle\` |`. ADR-0007 status line: `Superseded by ADR-0038 (<date>): there is no console to test in a browser.`

- [ ] **Step 3: README, operator guide, security notes, the rest**

- `README.md:20`: `| Get a support bundle for a ticket | \`jrsctl runs support-bundle <id>\` |`. Delete the "Use the web console" section (205-227).
- `docs/operator-guide.md`: delete the `console.token` row (18) and add `| \`console.token\` | left behind by jrsctl 1.x only; safe to delete |`; line 62 footer sentence drops `\`jrsctl console\` opens the web console,`; line 80 drops `the console's static files,`; line 490 `--json — the run document (the one the support bundle carries as run.json)`; delete the `jrsctl console` section (584-612).
- `docs/security.md`: line 4 drops `and §13 (console)`; delete the threat rows "Another local user starts a run", "DNS rebinding", "Cross-site request forgery", "Console exposed on the network", "Token on disk"; the "Secrets in output" row drops `SSE payloads,`; the "Replaying a stale plan" row becomes `| Replaying a stale plan | \`runs recover\` on a plan built against an earlier server state | Plans expire after 30 minutes, execute at most once, and are refused (exit 2) when their fingerprint no longer matches |`; delete the "Console token lifecycle" section and its closing paragraph.
- `CLAUDE.md:26`: the `app` row becomes `picocli commands, \`--json\`, progress renderer, guided mode, support bundle, \`Main\``; line 56: `Colour and type tokens live in \`docs/branding.md\`; values are provisional until the official brand guide is supplied.`
- Sweep the rest: `grep -n -i console docs/branding.md docs/capabilities.md docs/recovery-runbook.md CONTRIBUTING.md` and rewrite each hit that describes a current feature; leave historical entries in `spec-changelog.md`, `BUILD_STATUS.md`'s past bullets, `docs/reviews/` and older ADRs untouched (they record what was true then).

- [ ] **Step 4: Verify the documentation tests, commit**

Run: `scripts\fast.cmd test app DocsCommandTest,ExplainTest` (the embedded docs and `--explain` read the operator guide's sections at build time; a deleted section must not be referenced by a command that still exists).
Expected: PASS.

```bash
git add -A
git commit -m "docs: spec Draft 1.2, guides and threat model without the web console (ADR-0038, #151)"
```

### Task 8: Verify, review, merge, release

- [ ] **Step 1: Full local verify and the leftover grep**

```bash
scripts\fast.cmd guards
scripts\mvn.cmd verify > verify-151.log 2>&1
grep -E "BUILD (SUCCESS|FAILURE)|Tests run:.*Failures: [1-9]|Tests run:.*Errors: [1-9]" verify-151.log
grep -rn -i -E "javalin|jetty|kotlin|ConsoleServer|/api/|console\.token|JRS_CONSOLE|ConsoleSchemaTest" --include=*.java --include=*.xml --include=*.json --include=*.sh --include=*.cmd --include=*.yml . | grep -v -E "/target/|docs/reviews/|spec-changelog|BUILD_STATUS|decisions/00(07|23|37|38)"
```

Expected: `BUILD SUCCESS`; the grep prints only `java.io.Console` uses, the logback console appender in `LogFile`/`logback.xml`, and the WireMock Jetty comment in the poms. Anything else is a straggler: fix it in the task's commit (`git commit --fixup` then `git rebase -i --autosquash` is not available here; make a small follow-up commit named for the task).

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin feat/151-remove-console
gh pr create --base main --title "refactor!: remove the web console; jrsctl is a CLI (ADR-0038, #151)" --body-file - <<'EOF'
Closes #151. Step 2 of ADR-0038 (#149). Review commit by commit: each is one task of docs/superpowers/plans/2026-09-24-remove-web-console.md.

jdeps: <paste from Task 5>
Jar check: no io/javalin, org/eclipse/jetty, kotlin or web/ entries.
Manual checks: <fill in from Step 4>
EOF
gh pr checks <n> --watch
```

- [ ] **Step 3: Review the PR the way a deletion deserves**

1. `git diff --diff-filter=D --name-only main...feat/151-remove-console` and tick each name against the file lists in Task 4; a deleted file not in the lists is a question for the author.
2. `git diff --diff-filter=M -w main...feat/151-remove-console` is the diff to read line by line: the poms, `Config`, `ConfigLoader`, `ConfigWriter`, `ConfigKeys`, `Bootstrap`, `JsonSchemas`, `HelpExamples`, `GuidedMode`, `Phase0SkeletonTest`, the two `fast` scripts, the spec and the docs. Budget one to two hours here.
3. On GitHub, mark every deleted file "Viewed" first, then read the Files tab that remains.
4. Run `/code-review` on the PR for a second pass over the modified hunks.
5. Read `verify-151.log`'s coverage lines: the `core`, `jrs` and `ops` floors must not move, because nothing deleted lived there.

- [ ] **Step 4: Manual checks against the real install, then merge**

With the jrsctl home you use against the local 10.0.0 install, on the shaded jar from `app/target`:

1. `jrsctl --help`: no `console` line. `jrsctl config keys`: no `console.*` rows.
2. Add `console:\n  port: 7421\n` to that home's `config.yaml`, run `jrsctl doctor`: exactly one warning naming ADR-0038 on stderr, then the report. Remove the block again. If the warning does not appear, the logback console threshold hides `WARN`: route the loader's consumer to `err.println` in `Bootstrap` instead of `LOG::warn` and re-test.
3. `jrsctl config set console.port 1`: exit 2 with the ADR-0038 message, `config.yaml` unchanged.
4. `jrsctl runs support-bundle <id>` on a real run: zip written, `server.json` says `reachable: true`.
5. On the Linux laptop (`docs/BUILD_STATUS.md`, Linux laptop test gate): unpack the portable tar.gz from the PR's CI artifacts, run `jrsctl selfcheck` and step 2.

Record all five in the PR, then merge with the GitHub button. Rebase locally first if `main` moved (`git rebase main`, then diff `spec-changelog.md` and `BUILD_STATUS.md` for duplicated lines before force-pushing).

- [ ] **Step 5: Release 2.0.0**

```bash
git checkout main && git pull --ff-only
git tag -a v2.0.0 -m "jrsctl 2.0.0: the web console is removed (ADR-0038)"
git push origin v2.0.0
gh run watch
```

Release notes (paste into the GitHub release CI creates): what the console did; that `runs support-bundle <id>` replaces the download button, `runs show <id>` the run page, `runs recover` the resume and rollback buttons, `doctor`/`smoke`/`customizations`/`runs prune`/`config show`/`selfcheck`/`keys list` the remaining views; that `console:` in `config.yaml` is ignored with a warning until 2.1; that `console.token` can be deleted.

- [ ] **Step 6: File the follow-up and close the loop**

```bash
gh issue create --title "Refuse the console: configuration block (ADR-0038 step 3, after 2.0.0)" --body "Remove dropConsoleBlock and the console.* tolerance from ConfigLoader once 2.0.0 has shipped; a console: block or console.* key is then an unknown-key error like any other. Delete the ConfigLoaderTest cases that pin the tolerance and replace them with one that asserts the refusal."
```

Then update ADR-0038's action items (tick 1 and 2, add the issue number to 3), close #75's comment thread with a note that 2.0.0 shipped without a console so the terminal-UI question is now the only interactive-surface question, and update `docs/BUILD_STATUS.md`'s release line.

---

## Self-review

- **Spec coverage.** ADR-0038 decision items 1 to 6 map to Tasks 1-2 (support bundle first), 4 (delete), 5 (dependencies), 6 (configuration), 7 (docs and spec), 8 (release); action item 3 becomes the issue filed in Task 8 Step 6.
- **Placeholders.** Two deliberate pointers to existing fixtures (the `RunsCommandTest` home seeding and the `runs show` scenario lines in `JsonOutputSchemaTest`) are file-and-line references, not "similar to"; the executor copies them. `<date>` and `<n>` are filled at execution time.
- **Type consistency.** `SupportBundle(Services)`, `Prepared`, `prepare`, `write` are the same names in Tasks 1, 2 and the console delegation; `ConfigLoader(Consumer<String>)` is the same in Task 6 and `Bootstrap`.
- **Review focus.** Items 1-4 have tests in Tasks 6 and 2; item 5 is a documentation line in Task 7 and a manual check in Task 8.
