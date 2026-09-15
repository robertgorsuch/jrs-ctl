# jrsctl Open Issues (1.4.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix or decide every open GitHub issue that this machine can close (#44 to #53), and hand the four issues that need people or infrastructure (#31, #33, #35, #36) to their owners with exact checklists, on a CI-gated branch that becomes 1.4.0.

**Architecture:** One branch `phase/issues-1.4.0`, one Conventional Commit per issue, fast-forwarded into `main` after both CI legs are green. Server-safety fixes first (#44 double import, #48 deployable staging tree, #49 plan-time write), then the security items (#47, #50, #51, #45, #46), then retention (#53), then documentation. Every fix was confirmed against `main` at `1e9edbf` on 2026-09-15 before this plan was written; the "Confirmed" line of each task says what was seen.

**Tech Stack:** Java 21, Maven through `scripts\mvn.cmd` (JDK 21 selector), JUnit 5 + AssertJ, WireMock 3.13.2 (test), google-java-format via Spotless, Error Prone with `-Werror`, JaCoCo floors, GitHub Actions (`ci.yml`), `gh` CLI.

**Spec:** `docs/spec.md` Draft 1.1 (§0 rules, §5.6 snapshots and retention, §6 engine, §7.3 REST client, §9.4 import rollback, §11 security, §13.3 approved dependencies, §15 PR checklist, §18 exit codes). Inputs: GitHub issues #31, #33, #35, #36, #44 to #53 (read 2026-09-15), `docs/reviews/2026-09-13-codebase-assessment.md` §3, `docs/BUILD_STATUS.md`.

## Global Constraints

- Build only through `scripts\mvn.cmd` (Windows) or `scripts/mvn.sh` (Linux); the default `java` on this machine is 11.
- `-pl <module>` always with `-am`; with `-Dtest=...` add `-Dsurefire.failIfNoSpecifiedTests=false`; never `install`.
- `-Werror` + Error Prone: explicit casts, no unused imports or variables, pattern-matching `switch` over sealed types with no `default`.
- `scripts\mvn.cmd spotless:apply` before every commit; `spotless:check` runs in `verify`.
- `core.engine` must not import `core.state`; `jrs` must not depend on `ops` (`Phase0SkeletonTest`).
- All I/O through `Platform`; no `java.io.File`; no `Runtime.exec(String)`; process arguments as lists.
- Secrets in `char[]`; every output stream passes the redaction filter.
- No new third-party dependency without an ADR (spec §13.3). This plan adds none.
- Mutations only inside a `Step`; every `Step.execute` idempotent with a compensation or `irreversible()` with a comment. `IdempotencyCoverageTest` must list every concrete `Step`.
- Tests named `should_<behaviour>_when_<condition>`; one-paragraph Javadoc stating invariants on every new class.
- Commits: Conventional Commits, one logical change each, `Refs #N` (never `Closes #N`), ending with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g`
- Write commit messages through a Bash heredoc (`git commit -F - <<'EOF'`), never PowerShell, which adds a BOM (memory `jrsctl-windows-host-traps`).
- After every push: check the `build+unit+acceptance (ubuntu-latest)` job for that commit, not only Windows (memory `jrsctl-check-ubuntu-ci-leg`).
- Coverage floors stay: `core` 0.80, `jrs` 0.78, `ops` 0.80 (`jacoco.line.minimum`).
- Exit codes: 0 ok · 1 usage · 2 precheck · 3 rolled back · 4 rollback incomplete · 5 cancelled · 6 unsupported · 7 signature · 8 recovery required · 9 lock held.

## Decisions taken by this plan (override before executing if you disagree)

| # | Decision | Why |
|---|---|---|
| D1 | Branch `phase/issues-1.4.0`, CI-gated, fast-forward into `main`; issues closed by hand after both CI legs pass | Matches the 1.3.0 practice and spec §15. |
| D2 | #44: `StartImport` writes an `import-started.txt` marker before the POST. 408, 429 and 503 (the request was not accepted) delete the marker and stay retryable. 502, 504, an unreachable server, or a marker without a handle on a later execute are **fatal**: no second upload, no automatic re-import. ADR-0017. | A gateway error or a dropped connection can arrive after JasperReports Server accepted the upload; REST v2 has no endpoint to list running imports, so jrsctl cannot tell. A fatal failure runs no compensation, so the pre-import snapshot is not re-imported over an import that may still be running (the same rule the poll timeout already follows). Losing the automatic retry for a refused connection is the price, recorded in the ADR. |
| D3 | #48: `PointB.restoreDir` stages in the grandparent when the target's parent is named `webapps`, as `.jrsctl-restore-<name>`, and deletes the pre-1.4.0 staging name `webapps/.<name>.jrsctl-restore` if a crash left one | Keeps the staging tree out of Tomcat's default `appBase` while staying on the same file store, so the final rename stays a rename. |
| D4 | #49: planning still re-packs into `runs/upgrade-reapply/<slug>.zip` to plan the apply (as `BundleWorkspace` already unpacks under `runs/` while planning) but **deletes it before returning**; a new step `RepackHotfixBundle`, embedded ahead of each re-applied hotfix's steps, writes it when the run reaches reconcile | A shown-but-never-run plan leaves nothing behind; the embedded `VerifySignature` precheck runs right before its own execute (`Runner.runStep`), so it re-verifies the ZIP the step just wrote. |
| D5 | #47: the vendor process loses every inherited `JRSCTL_*` variable and every variable a configured `env:` secret reference names; it is **not** an allowlist | An allowlist would break buildomatic on hosts that need `ANT_OPTS`, proxy variables or database client variables jrsctl cannot predict. The secrets jrsctl knows about are exactly the ones it can remove. ADR-0019. |
| D6 | #50: a home jrsctl **creates** on Windows gets a protected DACL with one owner entry inherited by files and directories; an existing home is left as the operator set it up (unchanged rule, review 4.1) and the operator guide gives the `icacls` commands | Fixes the gap without re-permissioning a directory someone else prepared. |
| D7 | #51 form login: the body is built as bytes straight from the `char[]`, with no `String` copy of the password. #51 `--storepass`: stays on the command line, documented as residual risk, ADR-0020 | The local 10.0.0 buildomatic (`conf_source/iePro/applicationContext-export-import.xml`) accepts the keystore password only as the `storepass` argument; there is no file or environment form to use instead. |
| D8 | #45: new key `server.auth.tokenLocation: query \| header`, default `query`; `header` sends the token as the `pp` request header | The vendor sample (`samples/externalAuth-sample-config/sample-applicationContext-externalAuth-preAuth-mt.xml`) reads `pp` from the header when `tokenInRequestParam` is `false` or unset. Default `query` keeps every existing token configuration working; the operator guide recommends `header`. ADR-0018. |
| D9 | #46: `REST_LOGIN` is taken from the compat matrix for every version it lists (all rows expect it); the credential-less probe runs only for a version the matrix does not list | No login attempt without credentials against any supported server. |
| D10 | #53: retention also removes `runs/<runId>/` of a run the state store knows, that has ended, started before the cut-off, and is not protected (nor a `<runId>-hf-<slug>` sub-run of a protected run); leftover `hotfix-verify-*` directories go by age; any other name under `runs/` is never touched | Same protection rules as snapshots (spec §5.6); `upgrade-reapply/` and unknown directories are not runs. |
| D11 | #52 is **blocked on the maintainer**: jrsctl cannot choose where conduct reports go | Task 10 applies the choice once it is made. |
| D12 | #31, #33, #35, #36 get checklists (Task 12), not code | Each needs a network share, a person at a console, a container registry or a support engineer. |
| D13 | Spec amendments land in `docs/spec.md` as Draft 1.2 with one `docs/spec-changelog.md` entry, and `CLAUDE.md` names Draft 1.2 | Spec rule 13; four sections change (§5.6, §7.3, §9.4, §11). |

---

## File map

| Path | Task | Responsibility |
|---|---|---|
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/StartImport.java` | 1 | Start marker; ambiguous starts are fatal. |
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/RunFiles.java` | 1 | New constant `IMPORT_STARTED`. |
| `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/strategy/RestStrategyImportTest.java` | 1 | Two new tests. |
| `docs/decisions/0017-ambiguous-import-start-is-fatal.md` | 1 | ADR for D2. |
| `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/PointB.java` | 2 | `stagingFor`, legacy cleanup. |
| `ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/ArchivesTest.java` | 2 | Two new tests. |
| `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/RepackHotfixBundle.java` | 3 | **New** step that writes the re-pack ZIP at run time. |
| `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/DefaultUpgradeOperations.java` | 3 | `embed` deletes the planning ZIP and embeds the new step. |
| `ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/UpgradeStepIdempotencyTest.java`, `ops/src/test/java/com/jaspersoft/jrsctl/ops/IdempotencyCoverageTest.java` | 3 | Step tests and coverage entry. |
| `core/src/main/java/com/jaspersoft/jrsctl/core/platform/ProcessRunner.java`, `DefaultProcessRunner.java` | 4 | `Request.unset`, `childEnvironment`. |
| `core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java` | 4, 7 | `secretRefs()`, `envSecretNames()`; `TokenLocation`. |
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/vendor/VendorTools.java`, `jrs/.../strategy/VendorAccess.java`, `ops/.../upgrade/DefaultUpgradeOperations.java`, `app/.../Bootstrap.java` | 4 | Withheld variables wired through. |
| `docs/decisions/0019-vendor-tools-environment.md` | 4 | ADR for D5. |
| `app/src/main/java/com/jaspersoft/jrsctl/app/OwnerOnlyFiles.java`, `Bootstrap.java` | 5 | `restrictDirectoryToOwner`; new Windows home. |
| `app/src/test/java/com/jaspersoft/jrsctl/app/BootstrapHomeTest.java` | 5 | **New**, Windows only. |
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/FormBodies.java` | 6 | **New** byte-level form body. |
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestClient.java` | 6, 7 | `formLogin` uses bytes; `useToken(Secret, TokenLocation)`. |
| `docs/decisions/0020-storepass-on-the-vendor-command-line.md` | 6 | ADR for D7. |
| `core/.../config/ConfigLoader.java`, `ConfigWriter.java`, `core/src/main/resources/schema/config.schema.json`, `jrs/.../rest/RestJrsAdapter.java` | 7 | `server.auth.tokenLocation`. |
| `docs/decisions/0018-pre-authentication-token-location.md` | 7 | ADR for D8. |
| `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapter.java`, `jrs/src/test/.../rest/RestJrsAdapterCapabilitiesTest.java` | 8 | Matrix-first `REST_LOGIN`. |
| `ops/src/main/java/com/jaspersoft/jrsctl/ops/retention/RetentionPruner.java`, `ops/src/test/.../retention/RetentionPrunerTest.java` | 9 | Run directory pruning. |
| `CODE_OF_CONDUCT.md` | 10 | Contact (blocked, D11). |
| `docs/spec.md`, `docs/spec-changelog.md`, `docs/operator-guide.md`, `docs/security.md`, `docs/BUILD_STATUS.md`, `CLAUDE.md` | 11 | Documentation of every change. |

---

### Task 0: Branch

- [ ] **Step 1: Create the branch from an up-to-date `main`**

```bash
cd /c/Users/rgorsuch/jrs-ctl
git fetch origin
git switch main && git pull --ff-only
git switch -c phase/issues-1.4.0
```

- [ ] **Step 2: Confirm the baseline is green**

Run: `scripts\mvn.cmd verify`
Expected: `BUILD SUCCESS`. If it is not, stop and report; nothing below is safe on a red baseline.

---

### Task 1: #44 — never upload an import twice

**Confirmed:** `StartImport.execute` writes `import-handle.txt` only after `startImport` returns, and a 502 (`RestException.transientFailure()`) returns `Failures.transientHttp`, which the runner retries with `RetryPolicy.HTTP_DEFAULT`. A proxy that answers 502 after the server accepted the upload therefore causes a second POST.

**Files:**
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/RunFiles.java` (constants block, line 19-20)
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/StartImport.java` (`execute`)
- Test: `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/strategy/RestStrategyImportTest.java`
- Create: `docs/decisions/0017-ambiguous-import-start-is-fatal.md`

**Interfaces:**
- Produces: `RunFiles.IMPORT_STARTED = "import-started.txt"`; `StartImport.AMBIGUOUS_START`, `StartImport.AMBIGUOUS_NEXT_ACTION` (package-private `static final String`).

- [ ] **Step 1: Write the failing tests** (add to `RestStrategyImportTest`, next to `should_import_through_runner_and_post_once_when_start_executes_twice`)

```java
  /** Issue #44: a 502 may come from a proxy after the server accepted the upload. */
  @Test
  void should_post_once_and_fail_fatally_when_start_import_gets_a_gateway_error()
      throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(aResponse().withStatus(502)));
    Step start = new StartImport(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult first = start.execute(ctx, EventSink.discard());
    StepResult again = start.execute(ctx, EventSink.discard());

    assertThat(first)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Fatal.class));
    assertThat(again)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Fatal.class));
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.in(ctx, RunFiles.IMPORT_STARTED)).exists();
  }

  /** Issue #44: a 503 is a refusal, so the request never started an import and may be retried. */
  @Test
  void should_retry_start_import_when_the_server_refused_it_with_503() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(aResponse().withStatus(503)));
    Step start = new StartImport(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult refused = start.execute(ctx, EventSink.discard());
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-2\",\"phase\":\"inprogress\"}")));
    StepResult accepted = start.execute(ctx, EventSink.discard());

    assertThat(refused)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Retryable.class));
    assertThat(accepted).isInstanceOf(StepResult.Ok.class);
    wm.verify(2, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE))).contains("imp-2");
  }
```

Add `import com.jaspersoft.jrsctl.core.engine.StepFailure;` if the file does not import it yet.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=RestStrategyImportTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol ... IMPORT_STARTED`.

- [ ] **Step 3: Add the marker constant** (`RunFiles.java`, after `IMPORT_HANDLE`)

```java
  static final String IMPORT_STARTED = "import-started.txt";
```

- [ ] **Step 4: Replace `StartImport.execute`**

Add to the class, below `ROLLBACK_NOTE`:

```java
  /** Statuses that mean the server did not accept the request, so no import can have started. */
  private static final Set<Integer> NOT_ACCEPTED = Set.of(408, 429, 503);

  static final String AMBIGUOUS_START =
      "the server may have accepted the import before its answer was lost; jrsctl does not"
          + " upload the archive a second time (issue #44)";
  static final String AMBIGUOUS_NEXT_ACTION =
      "check the server log and the repository for an import started at this time, let it"
          + " finish, verify the repository, and re-import the archive by hand only if it did"
          + " not run";
```

Replace the body of `execute` with:

```java
  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    Path startedFile = RunFiles.in(ctx, RunFiles.IMPORT_STARTED);
    try {
      Optional<String> existing = RunFiles.read(handleFile);
      if (existing.isPresent()) {
        Logs.info(out, ctx, this, "reusing import task " + existing.get());
        return StepResult.ok();
      }
      if (Files.exists(startedFile)) {
        return Failures.fatal(AMBIGUOUS_START, AMBIGUOUS_NEXT_ACTION);
      }
      RunFiles.write(startedFile, request.archive().toString());
      Handles.ImportHandle handle;
      try {
        handle =
            ctx.service(JrsAdapter.class)
                .startImport(request, request.archive(), ctx.cancel()::isCancelled);
      } catch (JrsUnreachableException e) {
        return Failures.fatal(
            "server unreachable while starting the import (" + e.getMessage() + "); "
                + AMBIGUOUS_START,
            AMBIGUOUS_NEXT_ACTION);
      } catch (RestException e) {
        if (NOT_ACCEPTED.contains(e.status())) {
          RunFiles.delete(startedFile);
          return Failures.transientHttp(
              e, "cannot start the import", Failures.TRANSIENT_REMEDIATION);
        }
        if (e.transientFailure()) {
          return Failures.fatal(
              "cannot start the import: HTTP " + e.status() + " (" + e.getMessage() + "); "
                  + AMBIGUOUS_START,
              AMBIGUOUS_NEXT_ACTION);
        }
        RunFiles.delete(startedFile);
        throw e;
      }
      RunFiles.write(handleFile, handle.id());
      Logs.info(out, ctx, this, "import task " + handle.id() + " started");
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot record the import task state: " + e.getMessage(),
          List.of(handleFile, startedFile),
          "check that " + handleFile.getParent() + " is writable");
    }
  }
```

Add `import java.util.Set;`. Update the class Javadoc's second sentence to: "Repository-mutating and idempotent through the run-scoped `import-handle.txt`: a recorded task id is reused instead of uploading again, and an `import-started.txt` without a handle means an earlier attempt may have started an import, which is fatal rather than retried (ADR-0017)."

- [ ] **Step 5: Run the jrs tests**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=RestStrategyImportTest,StepIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the existing `should_import_through_runner_and_post_once_when_start_executes_twice` and `StepIdempotencyTest`'s `startImport` double execute.

- [ ] **Step 6: Write ADR-0017** (`docs/decisions/0017-ambiguous-import-start-is-fatal.md`, same headings as `0016-opt-in-force-stop-for-script-kinds.md`)

Content: Context (issue #44; REST v2 has no list of running imports; 502/504 and a lost connection can follow an accepted upload; the retry uploaded a second archive). Decision (D2 verbatim, including the marker file and the three not-accepted statuses). Consequences (a refused connection while starting an import now ends the run with exit 4 and a next action instead of retrying; `runs recover --resume` on such a run is refused by the same marker; the operator checks the server before re-importing; spec §9.4 amended).

- [ ] **Step 7: Format, full jrs verify, commit**

```bash
scripts/mvn.sh spotless:apply   # or scripts\mvn.cmd spotless:apply on Windows
scripts\mvn.cmd -pl jrs -am verify
git add jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/RunFiles.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/StartImport.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/strategy/RestStrategyImportTest.java \
        docs/decisions/0017-ambiguous-import-start-is-fatal.md
git commit -F - <<'EOF'
fix(jrs): never upload an import twice after an ambiguous start

A 502 or 504 can arrive after the server accepted the upload, and the
retry then started a second server-side import. StartImport now writes
import-started.txt before the POST; 408, 429 and 503 remove it and stay
retryable, while a gateway error, an unreachable server or a marker
without a handle is fatal and names what to check (ADR-0017).

Refs #44

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 2: #48 — stage the restored webapp outside `webapps/`

**Confirmed:** `PointB.restoreDir` stages at `target.getParent().resolve("." + name + ".jrsctl-restore")`, which for the webapp is `<tomcat>/webapps/.jasperserver-pro.jrsctl-restore`. Both callers (`RestoreSteps` in `upgrade rollback`, `VendorSteps` compensation) run with the service stopped, but a crash leaves the tree in `appBase` for the next Tomcat start to deploy.

**Files:**
- Modify: `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/PointB.java:74-96`
- Test: `ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/ArchivesTest.java`

**Interfaces:**
- Produces: `static Path PointB.stagingFor(Path target)`.

- [ ] **Step 1: Write the failing tests** (add to `ArchivesTest`)

```java
  /** Issue #48: a staging tree under webapps/ is deployable by Tomcat. */
  @Test
  void should_stage_beside_webapps_when_the_target_is_a_webapp() {
    Path tomcat = tmp.resolve("tomcat").toAbsolutePath().normalize();
    Path install = tmp.resolve("install").toAbsolutePath().normalize();

    assertThat(PointB.stagingFor(tomcat.resolve("webapps").resolve("jasperserver-pro")))
        .isEqualTo(tomcat.resolve(".jrsctl-restore-jasperserver-pro"));
    assertThat(PointB.stagingFor(install.resolve("buildomatic")))
        .isEqualTo(install.resolve(".jrsctl-restore-buildomatic"));
  }

  @Test
  void should_leave_only_the_webapp_under_webapps_when_a_restore_finishes() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.WINDOWS;
    Path webapps = Files.createDirectories(tmp.resolve("tomcat").resolve("webapps"));
    Path webapp = Files.createDirectories(webapps.resolve("jasperserver-pro"));
    Files.writeString(webapp.resolve("v.txt"), "old", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("webapp.zip");
    Archives.create(os, webapp, archive, new CancellationToken());
    Files.writeString(webapp.resolve("v.txt"), "new", StandardCharsets.UTF_8);
    Path legacy = Files.createDirectories(webapps.resolve(".jasperserver-pro.jrsctl-restore"));
    Files.writeString(legacy.resolve("left.txt"), "crash", StandardCharsets.UTF_8);
    Path aside = tmp.resolve("run").resolve("aside").resolve("webapp");

    PointB.restoreDir(os, archive, webapp, aside, new CancellationToken());

    try (Stream<Path> listing = Files.list(webapps)) {
      assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("jasperserver-pro");
    }
    assertThat(webapp.resolve("v.txt")).hasContent("old");
    assertThat(tmp.resolve("tomcat").resolve(".jrsctl-restore-jasperserver-pro")).doesNotExist();
  }
```

Add `import java.util.stream.Stream;` if missing.

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl ops -am test -Dtest=ArchivesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol ... stagingFor`.

- [ ] **Step 3: Implement** (replace `restoreDir` and its Javadoc in `PointB.java`)

```java
  /** Pre-1.4.0 staging name, removed when a crash left one behind (issue #48). */
  private static final String LEGACY_STAGING_SUFFIX = ".jrsctl-restore";

  /**
   * Where {@link #restoreDir} extracts {@code target}'s archive: beside the target's parent when
   * that parent is Tomcat's {@code webapps} (a directory there is deployable), else beside the
   * target. Either way it is on the target's file store, so moving it into place stays a rename.
   */
  static Path stagingFor(Path target) {
    Path absolute = target.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    Path home =
        parent.getFileName() != null
                && parent.getFileName().toString().equalsIgnoreCase("webapps")
                && parent.getParent() != null
            ? parent.getParent()
            : parent;
    return home.resolve(".jrsctl-restore-" + absolute.getFileName());
  }

  /**
   * Replaces {@code target} with the contents of {@code archive}: extracts to {@link
   * #stagingFor}, moves the current target to {@code aside} (unless a previous attempt already
   * did) and renames the extracted tree into place.
   */
  static long restoreDir(
      Platform.OsFamily os, Path archive, Path target, Path aside, CancellationToken cancel)
      throws IOException {
    Path absolute = target.toAbsolutePath().normalize();
    Trees.deleteRecursively(
        absolute.getParent().resolve("." + absolute.getFileName() + LEGACY_STAGING_SUFFIX));
    Path staging = stagingFor(absolute);
    Trees.deleteRecursively(staging);
    long entries = Archives.extract(os, archive, staging, cancel);
    if (Files.exists(target)) {
      if (Files.exists(aside)) {
        Trees.deleteRecursively(target);
      } else {
        Files.createDirectories(aside.getParent());
        moveTree(target, aside);
      }
    }
    moveTree(staging, target);
    return entries;
  }
```

- [ ] **Step 4: Run the upgrade tests**

Run: `scripts\mvn.cmd -pl ops -am test -Dtest=ArchivesTest,UpgradeRunTest,UpgradeStepIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including `should_move_current_tree_aside_and_undo_when_restoring`.

- [ ] **Step 5: Format and commit**

```bash
scripts\mvn.cmd spotless:apply
git add ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/PointB.java \
        ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/ArchivesTest.java
git commit -F - <<'EOF'
fix(ops): stage a restored webapp outside Tomcat's webapps directory

PointB.restoreDir extracted into webapps/.<name>.jrsctl-restore, which a
Tomcat started after a crash would deploy. The staging tree now sits
beside webapps/ (same file store, still a rename), and a staging tree
left by an earlier version is removed first.

Refs #48

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 3: #49 — planning an upgrade leaves no re-pack ZIP behind

**Confirmed:** `DefaultUpgradeOperations.embed` calls `BundleZips.zip(bundleDir, runs/upgrade-reapply/<slug>.zip)` while planning, and the file stays whether or not the plan runs. The embedded `HotfixVerifySteps$VerifySignature.precheck` unpacks `in.bundle()` (that ZIP), and `Runner.runStep` runs each precheck immediately before its own execute, so a step placed before it can create the ZIP at run time.

**Files:**
- Create: `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/RepackHotfixBundle.java`
- Modify: `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/DefaultUpgradeOperations.java` (`embed`, lines 271-281)
- Test: `ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/UpgradeStepIdempotencyTest.java`
- Modify: `ops/src/test/java/com/jaspersoft/jrsctl/ops/IdempotencyCoverageTest.java` (`COVERAGE`)

**Interfaces:**
- Consumes: `BundleZips.zip(Path bundleDir, Path target)`, `EmbeddedStep(Step inner, String hotfixId)`, `Phases.RECONCILE`.
- Produces: `final class RepackHotfixBundle implements Step` with constructor `RepackHotfixBundle(Path bundleDir, Path zip)` and `static final String ID = "repack-bundle"`.

- [ ] **Step 1: Write the failing tests** (add to `UpgradeStepIdempotencyTest`)

```java
  /** Issue #49: the re-pack ZIP is written by a step, so planning leaves nothing behind. */
  @Test
  void should_converge_when_repack_hotfix_bundle_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path bundle = Files.createDirectories(tmp.resolve("bundle-copy"));
      Files.writeString(bundle.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
      Files.createDirectories(bundle.resolve("files"));
      Files.writeString(bundle.resolve("files").resolve("a.txt"), "a", StandardCharsets.UTF_8);
      Path zip = f.fake.home.runs().resolve("upgrade-reapply").resolve("hf-a.zip");
      Step step = new RepackHotfixBundle(bundle, zip);
      Context ctx = f.ctx("r-rp");

      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);

      List<String> names = new ArrayList<>();
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
        for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
          names.add(e.getName());
        }
      }
      assertThat(names).containsExactly("files/a.txt", "manifest.json");
    }
  }

  @Test
  void should_converge_when_repack_hotfix_bundle_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path bundle = Files.createDirectories(tmp.resolve("bundle-copy-c"));
      Files.writeString(bundle.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
      Path zip = f.fake.home.runs().resolve("upgrade-reapply").resolve("hf-c.zip");
      Step step = new RepackHotfixBundle(bundle, zip);
      Context ctx = f.ctx("r-rp-c");
      Idempotency.executeOk(step, ctx);

      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(zip).doesNotExist();
    }
  }

  @Test
  void should_fail_the_precheck_when_the_bundle_copy_is_gone() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Step step =
          new RepackHotfixBundle(tmp.resolve("missing"), f.fake.home.runs().resolve("x.zip"));

      assertThat(step.precheck(f.ctx("r-rp-m"))).isInstanceOf(CheckResult.Fail.class);
    }
  }
```

Imports: `java.util.ArrayList`, `java.util.List`, `java.util.zip.ZipEntry`, `java.util.zip.ZipInputStream`, `com.jaspersoft.jrsctl.core.engine.CheckResult` (skip any already present). `CheckResult.Fail(String message, String remediation)` is a record of the sealed `CheckResult`.

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl ops -am test -Dtest=UpgradeStepIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol ... RepackHotfixBundle`.

- [ ] **Step 3: Create the step** (`RepackHotfixBundle.java`, imports as in `EmbeddedStep.java` plus `java.io.IOException`, `java.nio.file.Files`, `java.nio.file.Path`, `com.jaspersoft.jrsctl.core.engine.StepFailure`)

```java
package com.jaspersoft.jrsctl.ops.upgrade;

/**
 * Writes the ZIP a re-applied hotfix's embedded apply steps read (issue #49). Planning builds it
 * only long enough to plan the apply and deletes it again, so the file exists once the run reaches
 * the reconcile phase and never for a plan that is shown and not run. Invariants: every execute
 * rebuilds the archive from the installing run's bundle copy, so a second execute converges on
 * the same entries; the embedded {@code VerifySignature} precheck that runs next re-verifies the
 * signature and every hash before anything is applied; compensation deletes the ZIP.
 */
final class RepackHotfixBundle implements Step {

  static final String ID = "repack-bundle";

  private final Path bundleDir;
  private final Path zip;

  RepackHotfixBundle(Path bundleDir, Path zip) {
    this.bundleDir = Objects.requireNonNull(bundleDir, "bundleDir");
    this.zip = Objects.requireNonNull(zip, "zip");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Re-pack the installed hotfix bundle";
  }

  @Override
  public String phase() {
    return Phases.RECONCILE;
  }

  @Override
  public String detail() {
    return bundleDir + " -> " + zip;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (!Files.isDirectory(bundleDir)) {
      return CheckResult.fail(
          "the installed bundle copy " + bundleDir + " is gone",
          "re-apply the hotfix by hand with `jrsctl hotfix apply <bundle>` after the upgrade");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    try {
      BundleZips.zip(bundleDir, zip);
      return StepResult.ok();
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.fatal(
              "cannot re-pack " + bundleDir + ": " + e.getMessage(),
              "check that " + zip.getParent() + " is writable"));
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    try {
      Files.deleteIfExists(zip);
    } catch (IOException e) {
      // a leftover re-pack ZIP is harmless: the next execute replaces it
    }
    return StepResult.ok();
  }
}
```

- [ ] **Step 4: Change `embed`** (`DefaultUpgradeOperations.java`, the `try` block that starts at `BundleZips.zip(c.bundleDir().orElseThrow(), zip);`)

```java
      try {
        Path bundleDir = c.bundleDir().orElseThrow();
        BundleZips.zip(bundleDir, zip);
        Plan apply;
        try {
          apply = rt.hotfixes().planApply(zip, new HotfixOperations.ApplyOptions(false));
        } finally {
          // Issue #49: planning is read-only; RepackHotfixBundle writes the ZIP when the run gets
          // there, and the embedded verify precheck re-checks it.
          Files.deleteIfExists(zip);
        }
        embedded.add(new EmbeddedStep(new RepackHotfixBundle(bundleDir, zip), c.hotfix().id()));
        for (Step s : apply.steps()) {
          embedded.add(new EmbeddedStep(s, c.hotfix().id()));
        }
```

The rest of the block (`embeddedIds.add`, warnings, the `catch`) is unchanged. Add `import java.nio.file.Files;` if missing.

- [ ] **Step 5: Register the step in `IdempotencyCoverageTest.COVERAGE`**

Find the prefix constant the upgrade entries use:

Run: `grep -n "UpgradeStepIdempotencyTest" ops/src/test/java/com/jaspersoft/jrsctl/ops/IdempotencyCoverageTest.java`

Add, beside the other `upgrade.` entries, using that constant in place of `U`:

```java
          Map.entry(
              OPS + "upgrade.RepackHotfixBundle",
              U
                  + "should_converge_when_repack_hotfix_bundle_executes_twice;"
                  + U
                  + "should_converge_when_repack_hotfix_bundle_compensates_twice"),
```

- [ ] **Step 6: Record the untested planning path**

No ops test plans an upgrade with a re-applicable hotfix today (checked 2026-09-15: the only `reapply-` id in the tests is `UpgradeStepIdempotencyTest`'s `EmbeddedStep` unit test at line 586, which builds the step directly). Building a signed bundle, an installed-hotfix row and a target package with the replaced files is its own fixture, so this task does not add one. Say so in the commit body (already in Step 7) and list it in `docs/BUILD_STATUS.md` in Task 11: "`embed` deleting the planning ZIP and embedding `RepackHotfixBundle` is covered by the step tests and review, not by a planning test with a re-applicable hotfix".

- [ ] **Step 7: Run ops tests, format, commit**

Run: `scripts\mvn.cmd -pl ops -am verify`
Expected: PASS, `IdempotencyCoverageTest` included.

```bash
scripts\mvn.cmd spotless:apply
git add ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/RepackHotfixBundle.java \
        ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/DefaultUpgradeOperations.java \
        ops/src/test/java/com/jaspersoft/jrsctl/ops/upgrade/UpgradeStepIdempotencyTest.java \
        ops/src/test/java/com/jaspersoft/jrsctl/ops/IdempotencyCoverageTest.java
git commit -F - <<'EOF'
fix(ops): write the hotfix re-pack ZIP in a step, not while planning

planUpgrade left runs/upgrade-reapply/<slug>.zip behind for every plan it
built, run or not. Planning now deletes the ZIP once the apply plan is
built, and RepackHotfixBundle, embedded before the re-applied hotfix's
steps, writes it when the run reaches reconcile; the embedded verify
precheck re-checks it.

Refs #49

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 4: #47 — vendor scripts do not inherit jrsctl's secrets

**Confirmed:** `VendorTools.run` passes `Map.of(JAVA_HOME, PATH, JAVA_OPTS)` and `DefaultProcessRunner.run` does `builder.environment().putAll(request.environment())`, so the child inherits everything, including `JRSCTL_PASSPHRASE` and the variables `env:` references name (`JRS_PASSWORD`, `JRS_DB_PASSWORD` by default).

**Files:**
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/platform/ProcessRunner.java` (`Request`)
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/platform/DefaultProcessRunner.java:93`
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/Bootstrap.java:159-177`
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/vendor/VendorTools.java` (constructor, `run`)
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/VendorAccess.java:40-47`
- Modify: `ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/DefaultUpgradeOperations.java:82`
- Test: `core/src/test/java/com/jaspersoft/jrsctl/core/platform/DefaultProcessRunnerTest.java`, `core/src/test/java/com/jaspersoft/jrsctl/core/config/ConfigLoaderTest.java`, `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/vendor/VendorToolsTest.java`
- Create: `docs/decisions/0019-vendor-tools-environment.md`

**Interfaces:**
- Produces: `ProcessRunner.Request(List<String> command, Optional<Path> workingDir, Map<String,String> environment, Duration timeout, Set<String> unset)` plus the existing 4-argument constructor; `static Map<String,String> DefaultProcessRunner.childEnvironment(Map<String,String> inherited, ProcessRunner.Request request)`; `List<SecretRef> Config.secretRefs()`; `Set<String> Config.envSecretNames()`; `VendorTools(ProcessRunner, FileOps, Redactor, Set<String> withheld)`; `static Set<String> VendorTools.withheldNames(Map<String,String> inherited, Set<String> configured)`.

- [ ] **Step 1: Write the failing tests**

`DefaultProcessRunnerTest`:

```java
  /** Issue #47: names in {@code unset} are not inherited; an explicit value still wins. */
  @Test
  void should_drop_unset_variables_and_keep_explicit_ones_when_building_the_child_environment() {
    ProcessRunner.Request request =
        new ProcessRunner.Request(
            List.of("x"),
            Optional.empty(),
            Map.of("JAVA_HOME", "/jdk"),
            Duration.ofSeconds(1),
            Set.of("jrs_password", "JAVA_HOME"));

    Map<String, String> child =
        DefaultProcessRunner.childEnvironment(
            Map.of("JRS_PASSWORD", "secret", "PATH", "/bin", "JAVA_HOME", "/old"), request);

    assertThat(child).containsOnly(Map.entry("PATH", "/bin"), Map.entry("JAVA_HOME", "/jdk"));
  }
```

`ConfigLoaderTest`:

```java
  /** Issue #47: every env: reference in the configuration, by variable name. */
  @Test
  void should_list_the_env_secret_names_when_references_use_env_file_and_enc() throws IOException {
    Files.writeString(
        tmp.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            passwordRef: env:JRS_PASSWORD
        database:
          passwordRef: enc:DB
        network:
          proxy:
            passwordRef: env:PROXY_PW
        """,
        StandardCharsets.UTF_8);

    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThat(c.secretRefs()).hasSize(3);
    assertThat(c.envSecretNames()).containsExactlyInAnyOrder("JRS_PASSWORD", "PROXY_PW");
  }
```

If the schema requires more keys under `network.proxy` for the file to load, add them as the loader's error message names them.

`VendorToolsTest`:

```java
  /** Issue #47: jrsctl's own variables and configured env: secrets never reach buildomatic. */
  @Test
  void should_withhold_jrsctl_and_configured_secret_variables_when_running_a_vendor_tool() {
    Map<String, String> inherited =
        Map.of(
            "JRSCTL_PASSPHRASE", "p",
            "jrsctl_home", "h",
            "JRS_PASSWORD", "s",
            "ANT_OPTS", "-Xmx1g",
            "PATH", "/bin");

    assertThat(VendorTools.withheldNames(inherited, Set.of("jrs_password")))
        .containsExactlyInAnyOrder("JRSCTL_PASSPHRASE", "jrsctl_home", "JRS_PASSWORD");
  }
```

And in the existing `should_pass_java_home_and_buildomatic_working_dir_when_running_export`, after the `JAVA_OPTS` assertion:

```java
    assertThat(req.unset()).noneMatch(n -> n.equalsIgnoreCase("PATH"));
```

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=DefaultProcessRunnerTest,ConfigLoaderTest,VendorToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL (`childEnvironment`, `secretRefs`, `withheldNames`, `unset` not found).

- [ ] **Step 3: `ProcessRunner.Request`**

```java
  /**
   * {@code unset} names inherited variables the child must not see (issue #47), matched without
   * regard to case; a name also present in {@code environment} is set to that value.
   */
  record Request(
      List<String> command,
      Optional<Path> workingDir,
      Map<String, String> environment,
      Duration timeout,
      Set<String> unset) {

    public Request {
      unset = Set.copyOf(unset);
    }

    /** Inherits this process's whole environment, with {@code environment} on top. */
    public Request(
        List<String> command,
        Optional<Path> workingDir,
        Map<String, String> environment,
        Duration timeout) {
      this(command, workingDir, environment, timeout, Set.of());
    }
  }
```

Add `import java.util.Set;`.

- [ ] **Step 4: `DefaultProcessRunner`**

Replace `builder.environment().putAll(request.environment());` with:

```java
    Map<String, String> env = builder.environment();
    Map<String, String> wanted = childEnvironment(Map.copyOf(env), request);
    env.clear();
    env.putAll(wanted);
```

Add:

```java
  /** The child's environment: {@code inherited} without {@code request.unset()}, then the request's own. */
  static Map<String, String> childEnvironment(
      Map<String, String> inherited, ProcessRunner.Request request) {
    Map<String, String> child = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : inherited.entrySet()) {
      boolean dropped = request.unset().stream().anyMatch(n -> n.equalsIgnoreCase(e.getKey()));
      if (!dropped) {
        child.put(e.getKey(), e.getValue());
      }
    }
    child.putAll(request.environment());
    return child;
  }
```

- [ ] **Step 5: `Config.secretRefs()` and `envSecretNames()`** (methods on `Config`)

```java
  /** Every configured secret reference: server, database, proxy, trust store, console. */
  public List<SecretRef> secretRefs() {
    List<SecretRef> refs = new ArrayList<>();
    server().auth().passwordRef().ifPresent(refs::add);
    database().passwordRef().ifPresent(refs::add);
    network().proxy().passwordRef().ifPresent(refs::add);
    network().trustStore().passwordRef().ifPresent(refs::add);
    console().auth().passwordRef().ifPresent(refs::add);
    return List.copyOf(refs);
  }

  /** Variable names of the {@code env:} references among {@link #secretRefs()}. */
  public Set<String> envSecretNames() {
    Set<String> names = new LinkedHashSet<>();
    for (SecretRef ref : secretRefs()) {
      switch (ref) {
        case SecretRef.Env e -> names.add(e.name());
        case SecretRef.File f -> {}
        case SecretRef.Enc c -> {}
      }
    }
    return Set.copyOf(names);
  }
```

In `Bootstrap.registerSecrets`, replace the six lines that build `refs` with `for (SecretRef ref : config.secretRefs()) {` and remove the now-unused `List`/`ArrayList` imports if nothing else uses them.

- [ ] **Step 6: `VendorTools`**

Keep the existing three-argument constructor and make it delegate:

```java
  public VendorTools(ProcessRunner runner, FileOps files, Redactor redactor) {
    this(runner, files, redactor, Set.of());
  }

  /** {@code withheld}: the variable names the configuration's {@code env:} secrets read. */
  public VendorTools(
      ProcessRunner runner, FileOps files, Redactor redactor, Set<String> withheld) {
    // existing assignments of runner, files and redactor move here unchanged
    this.withheld = Set.copyOf(withheld);
  }
```

Add the field `private final Set<String> withheld;` and:

```java
  /**
   * Inherited names the vendor scripts must not see (issue #47, ADR-0019): every {@code JRSCTL_*}
   * variable and every name in {@code configured}, compared without regard to case.
   */
  static Set<String> withheldNames(Map<String, String> inherited, Set<String> configured) {
    Set<String> names = new LinkedHashSet<>();
    String prefix = ConfigLoader.ENV_PREFIX;
    for (String name : inherited.keySet()) {
      boolean jrsctl = name.regionMatches(true, 0, prefix, 0, prefix.length());
      if (jrsctl || configured.stream().anyMatch(name::equalsIgnoreCase)) {
        names.add(name);
      }
    }
    return Set.copyOf(names);
  }
```

In `run`, change the request to:

```java
            new ProcessRunner.Request(
                command,
                Optional.of(invocation.buildomatic().dir()),
                env,
                timeout,
                withheldNames(inherited, withheld)),
```

Imports: `com.jaspersoft.jrsctl.core.config.ConfigLoader`, `java.util.LinkedHashSet`, `java.util.Set`.

- [ ] **Step 7: Wire the configured names**

`VendorAccess.fromContext()`:

```java
        ctx ->
            new VendorTools(
                ctx.platform().processes(),
                ctx.platform().files(),
                ctx.has(Redactor.class) ? ctx.service(Redactor.class) : Redactor.global(),
                ctx.has(Config.class) ? ctx.service(Config.class).envSecretNames() : Set.of()));
```

`DefaultUpgradeOperations` line 82:

```java
            s ->
                new VendorTools(
                    s.platform().processes(),
                    s.platform().files(),
                    s.redactor(),
                    s.config().envSecretNames()),
```

`RunService` registers `Config` in every run's context (`RunService.java:108`), so the `has` guard only matters for unit tests that build a bare context. `Strategies` (no configuration in scope) keeps the three-argument constructor; `JRSCTL_*` is still withheld there.

- [ ] **Step 8: Run, write ADR-0019, format, commit**

Run: `scripts\mvn.cmd verify -Dphase=4`
Expected: PASS (every module's unit tests, and phase 4 acceptance against the shaded jar, which drives the vendor fakes). `-pl app` would skip the `acceptance` module, so run the whole reactor.

ADR-0019: Context (issue #47; the child inherited `JRSCTL_PASSPHRASE` and every `env:` secret). Decision (D5). Consequences (a vendor tool that genuinely reads a `JRSCTL_*` variable no longer sees it; secrets referenced as `file:` or `enc:` were never in the environment; an allowlist was rejected because buildomatic reads host-specific variables).

```bash
scripts\mvn.cmd spotless:apply
git add core/src/main/java/com/jaspersoft/jrsctl/core/platform/ProcessRunner.java \
        core/src/main/java/com/jaspersoft/jrsctl/core/platform/DefaultProcessRunner.java \
        core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java \
        core/src/test/java/com/jaspersoft/jrsctl/core/platform/DefaultProcessRunnerTest.java \
        core/src/test/java/com/jaspersoft/jrsctl/core/config/ConfigLoaderTest.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/Bootstrap.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/vendor/VendorTools.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/strategy/VendorAccess.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/vendor/VendorToolsTest.java \
        ops/src/main/java/com/jaspersoft/jrsctl/ops/upgrade/DefaultUpgradeOperations.java \
        docs/decisions/0019-vendor-tools-environment.md
git commit -F - <<'EOF'
fix(jrs): keep jrsctl's secrets out of the vendor tools' environment

js-export, js-import and js-ant inherited the whole environment,
including JRSCTL_PASSPHRASE and the variables env: secret references
read. ProcessRunner.Request gains an unset set, VendorTools withholds
every JRSCTL_* variable and every configured env: name, and Config lists
its secret references once for Bootstrap and the vendor tools
(ADR-0019).

Refs #47

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 5: #50 — a new Windows home is owner-only

**Confirmed:** `Bootstrap.createHome` creates the Linux home `rwx------` but calls plain `Files.createDirectories(root)` on Windows, so `%ProgramData%\jrsctl` inherits `%ProgramData%`'s ACL (Users may read and create). Only the secret files are restricted later (`OwnerOnlyFiles.restrictToOwner`, whose ACL entry has no inheritance flags).

**Files:**
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/OwnerOnlyFiles.java`
- Modify: `app/src/main/java/com/jaspersoft/jrsctl/app/Bootstrap.java` (`createHome`)
- Create: `app/src/test/java/com/jaspersoft/jrsctl/app/BootstrapHomeTest.java`

**Interfaces:**
- Produces: `public static void OwnerOnlyFiles.restrictDirectoryToOwner(Platform platform, Path dir) throws IOException`.

- [ ] **Step 1: Write the failing test** (`BootstrapHomeTest.java`; imports as in `OwnerOnlyFilesTest` plus `org.junit.jupiter.api.condition.EnabledOnOs`, `org.junit.jupiter.api.condition.OS`, `com.jaspersoft.jrsctl.core.JrsctlHome`, `com.jaspersoft.jrsctl.core.platform.ProcessRunner`)

```java
package com.jaspersoft.jrsctl.app;

/** Issue #50: the Windows home jrsctl creates is private to its owner, and so is what it holds. */
@EnabledOnOs(OS.WINDOWS)
class BootstrapHomeTest {

  private final Platform platform = Platforms.detect(OperatorPrompt.nonInteractive());

  @Test
  void should_create_a_protected_owner_only_home_when_none_exists(@TempDir Path dir)
      throws IOException {
    JrsctlHome home = new JrsctlHome(dir.resolve("jrsctl"));

    Bootstrap.createHome(platform, home);
    Path file = Files.writeString(home.root().resolve("state.db"), "x", StandardCharsets.UTF_8);
    Path nested = Files.createDirectories(home.snapshots().resolve("r-1"));

    assertThat(icacls(home.root())).noneMatch(line -> line.contains("(I)"));
    assertThat(platform.files().isOwnerOnly(home.root())).isTrue();
    assertThat(platform.files().isOwnerOnly(file)).isTrue();
    assertThat(platform.files().isOwnerOnly(nested)).isTrue();
  }

  @Test
  void should_leave_an_existing_home_alone_when_it_already_exists(@TempDir Path dir)
      throws IOException {
    Path root = Files.createDirectories(dir.resolve("existing"));
    FileOps.Permissions before = platform.files().capturePermissions(root);

    Bootstrap.createHome(platform, new JrsctlHome(root));

    assertThat(platform.files().capturePermissions(root)).isEqualTo(before);
  }

  /** {@code icacls} marks inherited entries with {@code (I)}. */
  private List<String> icacls(Path path) {
    List<String> lines = new ArrayList<>();
    platform
        .processes()
        .run(
            new ProcessRunner.Request(
                List.of("icacls", path.toString()),
                Optional.empty(),
                Map.of(),
                Duration.ofSeconds(20)),
            l -> lines.add(l.text()));
    return lines;
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `scripts\mvn.cmd -pl app -am test -Dtest=BootstrapHomeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the `(I)` assertion (the temp directory's entries are inherited).

- [ ] **Step 3: `OwnerOnlyFiles.restrictDirectoryToOwner`**

```java
  /**
   * Restricts a directory jrsctl has just created (issue #50): on Windows one entry gives the
   * owner full control and is inherited by every file and directory created below it, and the
   * DACL is protected so nothing of the parent's access list flows in; on Linux the mode is {@code
   * rwx------}.
   */
  public static void restrictDirectoryToOwner(Platform platform, Path dir) throws IOException {
    FileOps files = platform.files();
    String owner = files.capturePermissions(dir).owner();
    List<String> entries =
        switch (platform.os()) {
          case WINDOWS ->
              owner.isEmpty()
                  ? List.of()
                  : List.of(
                      "ALLOW|"
                          + owner
                          + "|"
                          + WINDOWS_FULL_CONTROL
                          + "|FILE_INHERIT,DIRECTORY_INHERIT");
          case LINUX -> List.of("posix:rwx------");
        };
    if (entries.isEmpty()) {
      throw new IOException("cannot determine the owner of " + dir + " to restrict it");
    }
    files.applyPermissions(dir, new FileOps.Permissions(owner, entries));
    if (platform.os() == Platform.OsFamily.WINDOWS) {
      dropInheritedEntries(platform, dir);
    }
  }
```

- [ ] **Step 4: `Bootstrap.createHome`** (the `else` branch)

```java
      } else {
        java.nio.file.Files.createDirectories(root);
        if (platform.os() == Platform.OsFamily.WINDOWS) {
          try {
            OwnerOnlyFiles.restrictDirectoryToOwner(platform, root);
          } catch (java.io.IOException e) {
            LOG.warn(
                "created {} but could not make it private to its owner: {}; restrict it with"
                    + " icacls before storing secrets there",
                root,
                e.getMessage());
          }
        }
      }
```

Update the method's Javadoc: "Creates a missing home directory owner-only on both operating systems (review 4.1, issue #50); an existing one is left alone."

- [ ] **Step 5: Run the app tests, format, commit**

Run: `scripts\mvn.cmd -pl app -am test -Dtest=BootstrapHomeTest,OwnerOnlyFilesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on Windows; skipped on Linux (the ubuntu CI leg reports the class as skipped).

```bash
scripts\mvn.cmd spotless:apply
git add app/src/main/java/com/jaspersoft/jrsctl/app/OwnerOnlyFiles.java \
        app/src/main/java/com/jaspersoft/jrsctl/app/Bootstrap.java \
        app/src/test/java/com/jaspersoft/jrsctl/app/BootstrapHomeTest.java
git commit -F - <<'EOF'
fix(app): make a home created on Windows private to its owner

createHome restricted a new Linux home to rwx------ but let a Windows
home inherit %ProgramData%'s ACL, so local users could read state.db,
snapshots and logs. The new home gets one inheritable owner entry and a
protected DACL; an existing home is still left as the operator set it
up.

Refs #50

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 6: #51 — no `String` copy of the form-login password; `--storepass` ADR

**Confirmed:** `RestClient.formLogin` builds `"...&j_password=" + URLEncoder.encode(new String(pw), UTF_8)` and posts it with `BodyPublishers.ofString`. `VendorTools.keystoreArgs` adds `new String(chars)` after `--storepass`; the vendor's `applicationContext-export-import.xml` lists `storepass` only as a command argument.

**Files:**
- Create: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/FormBodies.java`
- Create: `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/FormBodiesTest.java`
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestClient.java:357-380`
- Modify: `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestClientTest.java` (`should_store_session_cookie_and_resend_it_when_form_login_succeeds`)
- Create: `docs/decisions/0020-storepass-on-the-vendor-command-line.md`

**Interfaces:**
- Produces: `static byte[] FormBodies.login(String username, char[] password)`.

- [ ] **Step 1: Write the failing test** (`FormBodiesTest.java`)

```java
package com.jaspersoft.jrsctl.jrs.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FormBodiesTest {

  @Test
  void should_encode_exactly_like_url_encoder_when_the_password_has_reserved_and_non_ascii_chars() {
    String password = "p&ss w\u00f6rd=1~%+*._-";

    byte[] body = FormBodies.login("jasperadmin|org_1", password.toCharArray());

    assertThat(new String(body, StandardCharsets.US_ASCII))
        .isEqualTo(
            "j_username="
                + URLEncoder.encode("jasperadmin|org_1", StandardCharsets.UTF_8)
                + "&j_password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8));
  }

  @Test
  void should_encode_an_empty_password_when_none_is_given() {
    assertThat(new String(FormBodies.login("u", new char[0]), StandardCharsets.US_ASCII))
        .isEqualTo("j_username=u&j_password=");
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=FormBodiesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol ... FormBodies`.

- [ ] **Step 3: Implement `FormBodies`**

```java
package com.jaspersoft.jrsctl.jrs.rest;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds the {@code j_username}/{@code j_password} login form body from a password held as {@code
 * char[]} (issue #51), without a {@link String} copy of the password. Invariants: the bytes are
 * exactly what {@link URLEncoder} produces with UTF-8 (letters, digits and {@code . - * _} as-is,
 * space as {@code +}, every other byte as upper-case {@code %XX}); the intermediate UTF-8 buffer
 * is zeroed before returning; the caller zeroes the returned array once it has been sent.
 */
final class FormBodies {

  private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

  private FormBodies() {}

  static byte[] login(String username, char[] password) {
    byte[] prefix =
        ("j_username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&j_password=")
            .getBytes(StandardCharsets.US_ASCII);
    ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
    try {
      int length = prefix.length;
      for (int i = utf8.position(); i < utf8.limit(); i++) {
        byte b = utf8.get(i);
        length += (b == ' ' || unreserved(b)) ? 1 : 3;
      }
      byte[] body = Arrays.copyOf(prefix, length);
      int at = prefix.length;
      for (int i = utf8.position(); i < utf8.limit(); i++) {
        byte b = utf8.get(i);
        if (b == ' ') {
          body[at++] = '+';
        } else if (unreserved(b)) {
          body[at++] = b;
        } else {
          body[at++] = '%';
          body[at++] = HEX[(b >> 4) & 0xF];
          body[at++] = HEX[b & 0xF];
        }
      }
      return body;
    } finally {
      if (utf8.hasArray()) {
        Arrays.fill(utf8.array(), (byte) 0);
      }
    }
  }

  private static boolean unreserved(byte b) {
    return (b >= 'a' && b <= 'z')
        || (b >= 'A' && b <= 'Z')
        || (b >= '0' && b <= '9')
        || b == '.'
        || b == '-'
        || b == '*'
        || b == '_';
  }
}
```

- [ ] **Step 4: Use it in `RestClient.formLogin`**

```java
  public Response formLogin(String path, String username, Secret password) {
    char[] pw = password.chars();
    byte[] body;
    try {
      body = FormBodies.login(username, pw);
    } finally {
      Arrays.fill(pw, '\0');
    }
    redactor.register(password);
    try {
      Response r =
          send(
              "POST",
              path,
              JSON,
              Optional.of("application/x-www-form-urlencoded"),
              HttpRequest.BodyPublishers.ofByteArray(body),
              requestTimeout,
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      sessionCookie().ifPresent(redactor::register);
      return r;
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }
```

Remove the `URLEncoder` import from `RestClient` only if nothing else uses it (`useToken` still does).

- [ ] **Step 5: Pin the wire body in `RestClientTest`**

In `should_store_session_cookie_and_resend_it_when_form_login_succeeds` (the secret is built from the class constant `PASSWORD`), replace the two `containing` body checks of the `/j_spring_security_check` verification:

```java
            .withRequestBody(containing("j_username=jasperadmin"))
            .withRequestBody(containing("j_password=")));
```

with one exact check:

```java
            .withRequestBody(
                equalTo(
                    "j_username=jasperadmin&j_password="
                        + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8))));
```

Add `import java.net.URLEncoder;` if the test does not import it.

- [ ] **Step 6: Run, write ADR-0020, format, commit**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=FormBodiesTest,RestClientTest,RestJrsAdapter* -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

ADR-0020: Context (issue #51; `js-import --keystore <ks> --storepass <pw>` is the only interface the 10.0.0 buildomatic offers, checked in `conf_source/iePro/applicationContext-export-import.xml`; the value is visible in the process list to other local accounts for the duration of the import). Decision (keep it; it is registered with the redactor before the command line is logged; only the vendor strategy with `--source-keystore` uses it; the operator guide's `import` section and `docs/security.md` say so; revisit when a vendor release accepts a file or an environment variable). Consequences (residual local exposure on shared hosts; operators on shared hosts copy the source keystore and import from a session no other user is logged into).

```bash
scripts\mvn.cmd spotless:apply
git add jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/FormBodies.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/FormBodiesTest.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestClient.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestClientTest.java \
        docs/decisions/0020-storepass-on-the-vendor-command-line.md
git commit -F - <<'EOF'
fix(jrs): build the form-login body without a String copy of the password

formLogin encoded the password through new String(char[]) and
URLEncoder. FormBodies now percent-encodes straight from the char[] into
bytes, zeroing the UTF-8 buffer and, after sending, the body. The vendor
--storepass argument stays on the command line because buildomatic
offers no other form (ADR-0020).

Refs #51

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 7: #45 — send the pre-authentication token as a header on request

**Confirmed:** `RestClient.resolve` appends `pp=<token>` to every URL in token mode. The vendor sample pre-auth configuration documents `tokenInRequestParam`: `false` reads `pp` from the header only, unset reads the header first.

**Files:**
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java` (`Auth`, new `TokenLocation`)
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigLoader.java:264-269`
- Modify: `core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigWriter.java:68-71`
- Modify: `core/src/main/resources/schema/config.schema.json:19-26`
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestClient.java` (`useToken`, `useBasic`, `clearAuth`, `request`, `resolve`)
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapter.java:371-373`
- Test: `core/src/test/java/com/jaspersoft/jrsctl/core/config/ConfigLoaderTest.java`, `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestClientTest.java`
- Create: `docs/decisions/0018-pre-authentication-token-location.md`

**Interfaces:**
- Produces: `enum Config.TokenLocation { QUERY, HEADER }` with `DEFAULT = QUERY`; `Config.Auth.tokenLocation()`; `RestClient.useToken(Secret token, Config.TokenLocation location)`.

- [ ] **Step 1: Write the failing tests**

`ConfigLoaderTest`:

```java
  /** Issue #45: {@code server.auth.tokenLocation} defaults to query and accepts header. */
  @Test
  void should_read_token_location_when_set_and_default_to_query_when_absent() throws IOException {
    assertThat(loader.load(new JrsctlHome(tmp), Map.of(), Map.of()).server().auth().tokenLocation())
        .isEqualTo(Config.TokenLocation.QUERY);
    Files.writeString(
        tmp.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            mode: token
            tokenLocation: header
            passwordRef: env:JRS_TOKEN
        """,
        StandardCharsets.UTF_8);

    Config c = loader.load(new JrsctlHome(tmp), Map.of(), Map.of());

    assertThat(c.server().auth().tokenLocation()).isEqualTo(Config.TokenLocation.HEADER);
  }
```

`RestClientTest` (next to `should_append_pp_parameter_when_token_auth_configured`):

```java
  /** Issue #45: a server with tokenInRequestParam false or unset reads the pp header. */
  @Test
  void should_send_pp_header_and_no_query_parameter_when_token_location_is_header() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret token = Secret.fromString("tok&en=1")) {
      client.useToken(token, Config.TokenLocation.HEADER);
    }

    client.get("/rest_v2/jobs?limit=5");

    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withQueryParam("limit", equalTo("5"))
            .withQueryParam("pp", absent())
            .withHeader("pp", equalTo("tok&en=1"))
            .withHeader("Authorization", absent()));
    assertThat(redactor.redact("saw tok&en=1 here")).doesNotContain("tok&en=1");
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=ConfigLoaderTest,RestClientTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol ... TokenLocation`.

- [ ] **Step 3: `Config`**

After `AuthMode`:

```java
  /** {@code server.auth.tokenLocation}: where token mode sends the pre-authentication token. */
  public enum TokenLocation implements YamlValued {
    QUERY,
    HEADER;

    public static final TokenLocation DEFAULT = QUERY;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
```

Replace the `Auth` record header and compact constructor:

```java
  /** The {@code server.auth:} block. */
  public record Auth(
      AuthMode mode,
      Optional<String> username,
      Optional<SecretRef> passwordRef,
      TokenLocation tokenLocation) {

    public Auth {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
      Objects.requireNonNull(tokenLocation, "tokenLocation");
    }

    public Auth(AuthMode mode, Optional<String> username, Optional<SecretRef> passwordRef) {
      this(mode, username, passwordRef, TokenLocation.DEFAULT);
    }
```

`defaults()` keeps compiling through the three-argument constructor.

- [ ] **Step 4: Loader, writer, schema**

`ConfigLoader`, the `new Config.Auth(` call, add a fourth argument:

```java
                text(auth, "passwordRef").map(v -> secretRef("server.auth.passwordRef", v)),
                text(auth, "tokenLocation")
                    .map(v -> yamlEnum("server.auth.tokenLocation", Config.TokenLocation.class, v))
                    .orElse(Config.TokenLocation.DEFAULT))),
```

`ConfigWriter`, after `ref(c.server().auth().passwordRef(), auth);`:

```java
    if (c.server().auth().tokenLocation() != Config.TokenLocation.DEFAULT) {
      auth.put("tokenLocation", c.server().auth().tokenLocation().yamlValue());
    }
```

Schema, inside `server.auth.properties`:

```json
            "tokenLocation": { "type": "string", "enum": ["query", "header"], "default": "query" },
```

- [ ] **Step 5: `RestClient`**

Add the field after `tokenParam` (line 137), declared the same way:

```java
  private volatile Optional<String> tokenHeader = Optional.empty();
```

Replace `useToken`:

```java
  /** Token / pre-authentication in the query string, as before issue #45. */
  public void useToken(Secret token) {
    useToken(token, Config.TokenLocation.QUERY);
  }

  /**
   * Sends {@code pp=<token>} on every subsequent request, as a query parameter or as the {@code
   * pp} header (issue #45); the token and its encoded form are registered with the redactor.
   */
  public void useToken(Secret token, Config.TokenLocation location) {
    char[] chars = token.chars();
    String raw;
    String encoded;
    try {
      raw = new String(chars);
      encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(chars, '\0');
    }
    redactor.register(token);
    redactor.register(encoded);
    this.basicHeader = Optional.empty();
    switch (location) {
      case QUERY -> {
        this.tokenParam = Optional.of(encoded);
        this.tokenHeader = Optional.empty();
      }
      case HEADER -> {
        this.tokenParam = Optional.empty();
        this.tokenHeader = Optional.of(raw);
      }
    }
  }
```

In `useBasic` (where `this.tokenParam = Optional.empty();` is set) and in `clearAuth`, also set `this.tokenHeader = Optional.empty();`. In `request(...)`, after `basicHeader.ifPresent(...)`:

```java
    tokenHeader.ifPresent(t -> rb.header(PREAUTH_HEADER, t));
```

with `private static final String PREAUTH_HEADER = "pp";` among the constants. Add `import com.jaspersoft.jrsctl.core.config.Config;` if missing.

- [ ] **Step 6: `RestJrsAdapter.login`**, `case TOKEN`:

```java
          case TOKEN -> {
            client.useToken(credentials.password(), config.server().auth().tokenLocation());
            verifyAuthenticated();
            yield new Session(Session.AuthMode.TOKEN, Optional.empty(), Instant.now());
          }
```

- [ ] **Step 7: Run, ADR-0018, format, commit**

Run: `scripts\mvn.cmd -pl jrs -am verify`
Expected: PASS, including the golden and schema tests in core (a failing `ConfigWriter` golden means the default is being written; it must not be).

ADR-0018: Context (issue #45; URLs reach proxy and Tomcat access logs; vendor `tokenInRequestParam` semantics quoted from the sample). Decision (D8). Consequences (header mode fails login cleanly with exit 2 on a server configured `tokenInRequestParam=true`; `doctor`'s authentication item names the key; the operator guide recommends `header`).

```bash
scripts\mvn.cmd spotless:apply
git add core/src/main/java/com/jaspersoft/jrsctl/core/config/Config.java \
        core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigLoader.java \
        core/src/main/java/com/jaspersoft/jrsctl/core/config/ConfigWriter.java \
        core/src/main/resources/schema/config.schema.json \
        core/src/test/java/com/jaspersoft/jrsctl/core/config/ConfigLoaderTest.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestClient.java \
        jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapter.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestClientTest.java \
        docs/decisions/0018-pre-authentication-token-location.md
git commit -F - <<'EOF'
feat(jrs): send the pre-authentication token as a header on request

Token mode appended pp=<token> to every URL, where proxies and access
logs keep it. server.auth.tokenLocation: header sends it as the pp
request header, which JasperReports Server reads when
tokenInRequestParam is false or unset; the default stays query so
existing configurations keep working (ADR-0018).

Refs #45

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 8: #46 — no credential-less login against a listed server

**Confirmed:** `RestJrsAdapter.restLoginExists()` POSTs an empty form to `/rest_v2/login` on first use (capability probe and form login). Every row of `compat/matrix.yaml` expects `REST_LOGIN`.

**Files:**
- Modify: `jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapter.java` (`restLoginExists`, the `REST_LOGIN` `decide` call at line 213-214)
- Test: `jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapterCapabilitiesTest.java`

- [ ] **Step 1: Write the failing tests**

```java
  /** Issue #46: a version the matrix lists needs no credential-less login attempt. */
  @Test
  void should_not_post_to_rest_login_when_the_matrix_lists_the_version() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();

    assertThat(f.adapter.capabilities()).contains(Capability.REST_LOGIN);
    assertThat(f.adapter.probeResults())
        .hasEntrySatisfying(
            Capability.REST_LOGIN, d -> assertThat(d).contains("compat matrix").contains("8.2.0"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login"))));
  }

  @Test
  void should_probe_rest_login_when_the_matrix_does_not_list_the_version() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("6.4.0-CE");
    f.allProbesPresent();

    f.adapter.capabilities();

    wm.verify(1, postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login"))));
  }
```

In `should_report_absent_capabilities_when_ce_7_1_server_lacks_them`, 7.1 is listed, so `REST_LOGIN` now comes from the matrix: replace `assertThat(f.adapter.capabilities()).isEmpty();` with `assertThat(f.adapter.capabilities()).containsExactly(Capability.REST_LOGIN);` and delete the now-unused `f.probe("/rest_v2/login", 404);`. Add `postRequestedFor` to the WireMock static imports if missing.

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl jrs -am test -Dtest=RestJrsAdapterCapabilitiesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `Expected ... 0 requests ... received 1` for the first test.

- [ ] **Step 3: Implement**

Add the field beside `restLoginStatus`:

```java
  private volatile Optional<String> restLoginFromMatrix = Optional.empty();
```

Replace `restLoginExists`:

```java
  private boolean restLoginExists() {
    Optional<Boolean> known = restLoginExists;
    if (known.isPresent()) {
      return known.get();
    }
    ServerIdentity id = identity();
    boolean exists;
    if (matrix.find(id.version()).isPresent()) {
      // Issue #46: a listed version is decided by the matrix; no login without credentials.
      exists = expectedCapabilities().contains(Capability.REST_LOGIN);
      restLoginFromMatrix =
          Optional.of("per compat matrix for JRS " + id.version() + " " + id.edition());
    } else {
      // POST with no credentials: an existing endpoint answers 401/400/403 (or 200 on odd
      // builds), a server without it answers 404. A GET is unreliable because 10.x answers 404.
      int s = client.post(REST_LOGIN, "application/x-www-form-urlencoded", "").status();
      restLoginStatus = s;
      exists = s != 404 && s < 500;
    }
    restLoginExists = Optional.of(exists);
    return exists;
  }
```

Replace the `REST_LOGIN` lines in `probe()` (213-214):

```java
    boolean restLogin = restLoginExists();
    Optional<String> fromMatrix = restLoginFromMatrix;
    if (fromMatrix.isPresent()) {
      if (restLogin) {
        found.add(Capability.REST_LOGIN);
      }
      details.put(Capability.REST_LOGIN, (restLogin ? "present, " : "absent, ") + fromMatrix.get());
    } else {
      decide(
          Capability.REST_LOGIN, restLogin, "POST " + REST_LOGIN, restLoginStatus, found, details);
    }
```

`identity()` is safe to call from the form-login path: it caches the result of an unauthenticated `GET /rest_v2/serverInfo` (`fetchIdentity`) and never calls `ensureSession()`.

- [ ] **Step 4: Run the jrs suite, format, commit**

Run: `scripts\mvn.cmd -pl jrs -am verify`
Expected: PASS, including `RestJrsAdapterContractTest` (the recorded contract suite replays every matrix row; a recording that expects the empty login POST now has an unmatched stub, which WireMock tolerates, and no unrecorded request).

```bash
scripts\mvn.cmd spotless:apply
git add jrs/src/main/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapter.java \
        jrs/src/test/java/com/jaspersoft/jrsctl/jrs/rest/RestJrsAdapterCapabilitiesTest.java
git commit -F - <<'EOF'
fix(jrs): take REST_LOGIN from the compat matrix for listed versions

restLoginExists() probed /rest_v2/login with an empty form, a login
attempt without credentials that servers may log or count. Every matrix
row expects REST_LOGIN, so a listed version is decided by the matrix and
only an unlisted version is still probed.

Refs #46

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 9: #53 — prune run directories under the snapshot protection rules

**Confirmed:** `RetentionPruner.looseArtefacts` walks only `home.snapshots()`; nothing removes `home.runs()/<runId>/`. `RetentionProtection.compute` protects the installing run of each installed hotfix (`installedRunId`, which for a re-applied hotfix is the `<runId>-hf-<slug>` sub-run), customization runs, the latest successful upgrade and pending runs. `hotfix rollback` refuses when `runs/<installRunId>/bundle/` is gone (H1).

**Files:**
- Modify: `ops/src/main/java/com/jaspersoft/jrsctl/ops/retention/RetentionPruner.java`
- Test: `ops/src/test/java/com/jaspersoft/jrsctl/ops/retention/RetentionPrunerTest.java`

**Interfaces:**
- Produces: `Removed` ids of the form `runs/<name>`; private `Loose runDirectories(StateStore, Set<String>, Duration, boolean)`.

- [ ] **Step 1: Write the failing tests** (add to `RetentionPrunerTest`)

```java
  private Path runDir(String runId, Duration age, boolean ended) throws IOException {
    Instant started = now.minus(age);
    fake.stateStore().recordRunStart(runId, "hotfix-apply", Optional.empty(), started);
    if (ended) {
      fake.stateStore()
          .recordRunEnd(runId, started.plusSeconds(60), TerminalState.SUCCEEDED, 0);
    }
    Path bundle = Files.createDirectories(fake.home.runDir(runId).resolve("bundle"));
    Files.writeString(bundle.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
    return fake.home.runDir(runId);
  }

  /** Issue #53: run directories follow retention like the run's snapshots. */
  @Test
  void should_remove_an_ended_unprotected_run_directory_when_it_is_older_than_retention()
      throws Exception {
    services(30, 20);
    Path old = runDir("r-old", Duration.ofDays(40), true);
    Path fresh = runDir("r-new", Duration.ofDays(1), true);

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).contains("runs/r-old").doesNotContain("runs/r-new");
    assertThat(old).doesNotExist();
    assertThat(fresh).exists();
  }

  @Test
  void should_keep_the_bundle_copy_of_an_installed_hotfix_when_its_run_is_expired()
      throws Exception {
    services(30, 20);
    Path dir = runDir("r-hf", Duration.ofDays(40), true);
    fake.stateStore()
        .recordHotfixInstalled(
            new HotfixInstalled(
                "HF-1",
                "1",
                "t",
                "r-hf",
                Optional.empty(),
                HotfixState.INSTALLED,
                now.minus(Duration.ofDays(40))),
            List.of());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).doesNotContain("runs/r-hf");
    assertThat(dir.resolve("bundle").resolve("manifest.json")).exists();
    assertThat(result.protectedCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void should_keep_unended_runs_and_directories_that_are_not_runs_when_pruning() throws Exception {
    services(30, 20);
    Path pending = runDir("r-pending", Duration.ofDays(40), false);
    Path reapply = Files.createDirectories(fake.home.runs().resolve("upgrade-reapply"));
    Files.setLastModifiedTime(reapply, FileTime.from(now.minus(Duration.ofDays(40))));
    Path subRunOfPending = runDir("r-pending-hf-hf-1", Duration.ofDays(40), true);

    pruner().prune(false);

    assertThat(pending).exists();
    assertThat(reapply).exists();
    assertThat(subRunOfPending).exists();
  }

  @Test
  void should_report_but_keep_run_directories_when_dry_run() throws Exception {
    services(30, 20);
    Path old = runDir("r-old", Duration.ofDays(40), true);

    RetentionPruner.Result result = pruner().prune(true);

    assertThat(ids(result)).contains("runs/r-old");
    assertThat(old).exists();
  }
```

Imports: `com.jaspersoft.jrsctl.core.state.HotfixInstalled`, `com.jaspersoft.jrsctl.core.state.HotfixState` (skip any already present; `TerminalState` and `FileTime` are already used by the file).

- [ ] **Step 2: Run to verify they fail**

Run: `scripts\mvn.cmd -pl ops -am test -Dtest=RetentionPrunerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `Expecting ... to contain "runs/r-old"`.

- [ ] **Step 3: Implement** (in `RetentionPruner`)

Constants:

```java
  /** Working directories {@code BundleWorkspace} leaves when its cleanup fails. */
  static final String VERIFY_PREFIX = "hotfix-verify-";

  /** {@code EmbeddedStep.RUN_SUFFIX}: a re-applied hotfix runs as {@code <runId>-hf-<slug>}. */
  static final String SUB_RUN_SUFFIX = "-hf-";
```

Method:

```java
  /**
   * Issue #53: run directories ({@code runs/<runId>/}: bundle copies, staging, stop markers) follow
   * the protection that keeps their snapshots. A directory goes only when its name is a run the
   * state store knows, that run has ended and started before the retention cut-off, and neither it
   * nor the run it is a {@code -hf-} sub-run of is protected. A leftover {@code hotfix-verify-*}
   * directory goes by age. Any other name under {@code runs/} is never touched.
   */
  private Loose runDirectories(
      StateStore store, Set<String> protectedRuns, Duration retention, boolean dryRun)
      throws IOException {
    Path root = services.home().runs();
    if (retention.isZero() || retention.isNegative() || !Files.isDirectory(root)) {
      return new Loose(List.of(), 0, 0);
    }
    Instant cutoff = services.clock().instant().minus(retention);
    List<Removed> removed = new ArrayList<>();
    int kept = 0;
    int protectedKept = 0;
    List<Path> entries;
    try (Stream<Path> listing = Files.list(root)) {
      entries = listing.filter(Files::isDirectory).sorted().toList();
    }
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      boolean expired;
      if (name.startsWith(VERIFY_PREFIX)) {
        expired = Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff);
      } else {
        Optional<RunRecord> run = store.run(name);
        if (run.isEmpty()) {
          continue;
        }
        if (isProtected(name, protectedRuns)) {
          kept++;
          protectedKept++;
          continue;
        }
        expired =
            run.get().terminalState().isPresent() && run.get().startedAt().isBefore(cutoff);
      }
      if (!expired) {
        kept++;
        continue;
      }
      removed.add(new Removed("runs/" + name, name, "*", entry));
      if (!dryRun) {
        LOG.info("pruning run directory {}", entry);
        Trees.deleteRecursively(entry);
      }
    }
    return new Loose(removed, kept, protectedKept);
  }

  private static boolean isProtected(String runId, Set<String> protectedRuns) {
    for (String p : protectedRuns) {
      if (runId.equals(p) || runId.startsWith(p + SUB_RUN_SUFFIX)) {
        return true;
      }
    }
    return false;
  }
```

In `prune(boolean dryRun, Set<String> alsoProtected, boolean auditAlways)`, directly after

```java
    removed.addAll(loose.removed());
    kept += loose.kept();
    protectedKept += loose.protectedKept();
```

and before `if (!dryRun) { sweepStaleRows(store, protectedRuns); }`, add:

```java
    Loose runDirs = runDirectories(store, protectedRuns, retention, dryRun);
    removed.addAll(runDirs.removed());
    kept += runDirs.kept();
    protectedKept += runDirs.protectedKept();
```

(`store`, `protectedRuns`, `retention`, `removed`, `kept` and `protectedKept` are the method's existing locals.) Update the class Javadoc to name run directories.

- [ ] **Step 4: Run ops tests, format, commit**

Run: `scripts\mvn.cmd -pl ops -am verify`
Expected: PASS (floor 0.80 still met).

```bash
scripts\mvn.cmd spotless:apply
git add ops/src/main/java/com/jaspersoft/jrsctl/ops/retention/RetentionPruner.java \
        ops/src/test/java/com/jaspersoft/jrsctl/ops/retention/RetentionPrunerTest.java
git commit -F - <<'EOF'
feat(ops): prune run directories under the snapshot protection rules

Retention removed snapshots but never runs/<runId>/. A run directory now
goes when its run has ended, started before the cut-off and is not
protected, nor a -hf- sub-run of a protected run, so an installed
hotfix keeps the bundle copy its rollback needs. Leftover
hotfix-verify-* directories go by age; nothing else under runs/ is
touched.

Refs #53

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
```

---

### Task 10: #52 — conduct report contact (blocked, D11)

**Confirmed:** `CODE_OF_CONDUCT.md:62` reads "reported to the community leaders responsible for enforcement at" with no address.

- [ ] **Step 1: Get the maintainer's choice** of address or channel. Do not invent one and do not use a personal address found elsewhere in the repository or session.
- [ ] **Step 2: Replace the end of line 62** with the chosen contact in the Contributor Covenant form, for example `at [conduct@example.com](mailto:conduct@example.com).` with the real value.
- [ ] **Step 3: Confirm `Phase0SkeletonTest` still passes** (it does not read this file today, but the licence check reads neighbours): `scripts\mvn.cmd verify -Dphase=0`.
- [ ] **Step 4: Commit** `docs: name the conduct report contact` with `Refs #52` and the trailers.

---

### Task 11: Documentation, merge and issue closure

- [ ] **Step 1: `docs/spec.md` → Draft 1.2**
  - Header `**Status:** Draft 1.2 — build contract`, `**Date:** 2026-09-15`, `**Supersedes:** Draft 1.1`.
  - §5.6 third bullet becomes: "Retention pruning respects `backups.*` and never prunes a snapshot, or the run directory `runs/<runId>/`, of a run referenced by an installed hotfix, by a registered customization, by the most recent successful upgrade, or pending recovery; a `<runId>-hf-<slug>` sub-run follows its run (issue #53)."
  - §7.3: add "In token mode the pre-authentication token is sent as the `pp` query parameter (`server.auth.tokenLocation: query`, default) or header (`header`) (ADR-0018)."
  - §9.4: add "A start the server may have accepted (HTTP 502 or 504, an unreachable server, or a start marker without a task id) is fatal: no second upload and no automatic re-import (ADR-0017)."
  - §11: add the vendor environment rule (ADR-0019), the Windows home rule (issue #50) and the `--storepass` residual risk (ADR-0020).
- [ ] **Step 2: `docs/spec-changelog.md`**: one "Draft 1.2 (2026-09-15)" entry listing the four sections and ADRs 0017 to 0020.
- [ ] **Step 3: `CLAUDE.md`** first paragraph: `(Draft 1.2)`.
- [ ] **Step 4: `docs/operator-guide.md`**
  - `import`: the ambiguous-start rule and what to check before re-importing; `--source-keystore` notes that the password is visible in the process list while `js-import` runs.
  - `upgrade rollback` / field layouts: the staging directory is now `<tomcat>/.jrsctl-restore-<webapp>`.
  - `runs prune` and step 8 of "How a mutating command runs": run directories are pruned with their runs.
  - Global/home section: a home jrsctl creates is owner-only on Windows too; for an existing home, `icacls "%ProgramData%\jrsctl" /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F`.
  - Configuration: `server.auth.tokenLocation`, recommending `header` when the server's `tokenInRequestParam` is `false` or unset.
  - Environment variables paragraph: vendor tools do not receive `JRSCTL_*` or configured `env:` variables.
- [ ] **Step 5: `docs/security.md`**: the same four security changes, one short paragraph each.
- [ ] **Step 6: `docs/BUILD_STATUS.md`**: a "1.4.0 open issues (2026-09-15, `docs/superpowers/plans/2026-09-15-open-issues-1.4.0.md`)" section in the style of the 1.3.0 one: per issue, what was confirmed, the fix, the tests, and anything not verified live (#44 against a real proxy, #45 against a pre-auth-configured server, #50 on a real `%ProgramData%` home, #53 on a long-lived home).
- [ ] **Step 7: Verify everything**

Run: `scripts\mvn.cmd verify`
Expected: `BUILD SUCCESS` (every module, every acceptance phase).

- [ ] **Step 8: Commit, push, check both CI legs**

```bash
git add docs/spec.md docs/spec-changelog.md CLAUDE.md docs/operator-guide.md docs/security.md docs/BUILD_STATUS.md
git commit -F - <<'EOF'
docs: record the 1.4.0 issue fixes in the spec, guides and build status

Refs #44 #45 #46 #47 #48 #49 #50 #51 #53

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01613Dz41mUTrYifM7SAWP4g
EOF
git push -u origin phase/issues-1.4.0
gh run list --branch phase/issues-1.4.0 --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch <that id> --exit-status
gh run view <that id> --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
```

Expected: `build+unit+acceptance (ubuntu-latest): success` **and** `build+unit+acceptance (windows-latest): success`. If ubuntu fails, fix on the branch before merging; do not merge on Windows alone.

- [ ] **Step 9: Fast-forward `main` and close issues by hand**

```bash
git switch main && git merge --ff-only phase/issues-1.4.0 && git push origin main
for n in 44 45 46 47 48 49 50 51 53; do
  gh issue close $n --comment "Fixed on main in the 1.4.0 issue series (docs/superpowers/plans/2026-09-15-open-issues-1.4.0.md); CI green on both operating systems."
done
```

Close #52 only after Task 10 lands. Cutting and publishing v1.4.0 is a separate decision through the tag-driven release path (memory `jrsctl-release-path`).

---

### Task 12: Hand-offs for the issues this machine cannot close

These produce comments on the issues, not code. Post each checklist as an issue comment with `gh issue comment <n> --body-file <file>` once Task 11 is merged, so the owner starts from the current `main`.

- [ ] **#31 — buildomatic on a share (partly doable here, needs elevation):**
  1. Windows loopback UNC, elevated PowerShell: `New-SmbShare -Name jrsbuild -Path C:\Jaspersoft\jasperreports-server-10.0.0\buildomatic -ReadAccess $env:USERNAME`, then `jrsctl --set server.buildomaticDir=\\localhost\jrsbuild doctor`: `vendor` names the rule and warns about UNC; `database` finds the JDBC driver; `keystore` reads `keystore.init.properties`.
  2. Same share as a mapped drive (`net use J: \\localhost\jrsbuild`) and as `mklink /D C:\jrsbuild-link \\localhost\jrsbuild`: `vendor` passes without the UNC warning.
  3. `Remove-SmbShare -Name jrsbuild -Force`: `vendor` FAILs naming the path and does not fall back.
  4. Linux laptop (memory `jrsctl-linux-laptop`): a CIFS mount of the same share, then `doctor`.
  5. Vendor `export` / `import` / `upgrade` against a share stay with the issue owner on a disposable installation.
- [ ] **#33 — Ctrl-C through `jrsctl.cmd`:** a person runs `bin\jrsctl.cmd export --full-server --out C:\tmp\x.zip --yes` in a real console window, presses Ctrl-C during `js-export`, answers `N` then `Y` in two runs, and records `echo %ERRORLEVEL%` each time. Attach the output to the issue; the launcher change is decided from it.
- [ ] **#35 — JRS container image:** the maintainer decides the image source and registry (spec §19 Q6); then wire `LiveJrsContainerTest` into the Linux `integration` job behind a registry secret, skipped when absent.
- [ ] **#36 — support engineering review:** the maintainer names a reviewer; send `jrsctl docs operator-guide` output from the 1.4.0 build with the scope list in the issue; record who, when and which commit in `docs/BUILD_STATUS.md`.
