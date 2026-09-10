package com.jaspersoft.jrsctl.jrs.rest;

import com.jaspersoft.jrsctl.core.compat.CompatMatrix;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.Handles;
import com.jaspersoft.jrsctl.jrs.api.HealthReport;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.api.Session;
import com.jaspersoft.jrsctl.jrs.keystore.KeystoreInspector;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link JrsAdapter} (spec §7.2, ADR-0004), driven by probed {@link Capability}s rather
 * than the version string. Invariants: {@link #identity()} and {@link #capabilities()} hit the
 * server at most once each and are cached, while {@link #refreshIdentity()} always re-reads and
 * replaces the cached identity; every probe is a non-mutating {@code GET}; the only
 * repository-mutating calls are {@link #startImport}, {@link #createFolder}, {@link
 * #uploadJrxmlReport} and {@link #deleteResource}; downloads and uploads stream through the client;
 * in {@code form} auth mode a session is established lazily before the first authenticated call
 * using the configured credentials; no exception message carries a credential.
 */
public final class RestJrsAdapter implements JrsAdapter {

  static final String SERVER_INFO = "/rest_v2/serverInfo";
  static final String EXPORT = "/rest_v2/export";
  static final String IMPORT = "/rest_v2/import";
  static final String RESOURCES = "/rest_v2/resources";
  static final String REPORTS = "/rest_v2/reports";
  static final String JOBS = "/rest_v2/jobs";
  static final String ORGANIZATIONS = "/rest_v2/organizations";
  static final String REST_LOGIN = "/rest_v2/login";
  static final String FORM_LOGIN = "/j_spring_security_check";
  static final String PROBE_ID = "jrsctl-probe";
  static final String DEFAULT_EXPORT_FILE = "export.zip";
  static final String FOLDER_TYPE = "application/repository.folder+json";
  static final String REPORT_UNIT_TYPE = "application/repository.reportUnit+json";

  private final RestClient client;
  private final Config config;
  private final CompatMatrix matrix;
  private final Optional<Credentials> configured;
  private final KeystoreInspector keystoreInspector;
  private final Object lock = new Object();
  private final Map<String, String> exportFileNames = new ConcurrentHashMap<>();
  private ServerIdentity identity;
  private Set<Capability> capabilities;
  private Map<Capability, String> probeResults;
  private volatile Optional<Boolean> restLoginExists = Optional.empty();
  private volatile int restLoginStatus;
  private volatile Optional<Session> session = Optional.empty();

  public RestJrsAdapter(
      RestClient client,
      Config config,
      Platform platform,
      CompatMatrix matrix,
      Optional<Credentials> configured) {
    this(client, config, matrix, configured, new KeystoreInspector(platform, config));
  }

  /** Constructor with an explicit keystore inspector (tests use a fake home layout). */
  public RestJrsAdapter(
      RestClient client,
      Config config,
      CompatMatrix matrix,
      Optional<Credentials> configured,
      KeystoreInspector keystoreInspector) {
    this.client = Objects.requireNonNull(client, "client");
    this.config = Objects.requireNonNull(config, "config");
    this.matrix = Objects.requireNonNull(matrix, "matrix");
    this.configured = Objects.requireNonNull(configured, "configured");
    this.keystoreInspector = Objects.requireNonNull(keystoreInspector, "keystoreInspector");
  }

  /** The underlying client, e.g. to set the correlation id for a run. */
  public RestClient client() {
    return client;
  }

  // ---------------------------------------------------------------- identity and capabilities

  @Override
  public ServerIdentity identity() {
    synchronized (lock) {
      if (identity == null) {
        identity = fetchIdentity();
      }
      return identity;
    }
  }

  @Override
  public ServerIdentity refreshIdentity() {
    ServerIdentity fresh = fetchIdentity();
    synchronized (lock) {
      identity = fresh;
    }
    return fresh;
  }

  private ServerIdentity fetchIdentity() {
    RestClient.Response r = client.get(SERVER_INFO);
    if (r.status() >= 500) {
      throw new JrsUnreachableException(
          client.baseUrl(),
          "server answered HTTP " + r.status() + " on GET " + SERVER_INFO,
          "the server may still be starting or its database may be down; check the Tomcat and"
              + " jasperserver logs",
          null);
    }
    client.require2xx(r, "GET", SERVER_INFO);
    return ServerIdentities.parse(client.baseUrl(), r.body());
  }

  @Override
  public Set<Capability> capabilities() {
    synchronized (lock) {
      if (capabilities == null) {
        probe();
      }
      return capabilities;
    }
  }

  /** One line per capability saying how it was decided; for {@code doctor}. */
  public Map<Capability, String> probeResults() {
    synchronized (lock) {
      if (probeResults == null) {
        probe();
      }
      return probeResults;
    }
  }

  /** Capabilities the compat matrix expects for the detected version and edition. */
  public Set<Capability> expectedCapabilities() {
    ServerIdentity id = identity();
    EnumSet<Capability> out = EnumSet.noneOf(Capability.class);
    for (String name : matrix.expectedCapabilities(id.version(), id.edition().name())) {
      try {
        out.add(Capability.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // a matrix entry naming a capability this build does not know is not an error here
      }
    }
    return Collections.unmodifiableSet(out);
  }

  private void probe() {
    ServerIdentity id = identity();
    ensureSession();
    Set<Capability> expected = expectedCapabilities();
    EnumMap<Capability, String> details = new EnumMap<>(Capability.class);
    EnumSet<Capability> found = EnumSet.noneOf(Capability.class);

    probeEndpoint(Capability.EXPORT_ASYNC, EXPORT + "/" + PROBE_ID + "/state", found, details);
    probeEndpoint(Capability.IMPORT_ASYNC, IMPORT + "/" + PROBE_ID + "/state", found, details);

    String orgs = ORGANIZATIONS + "?limit=1";
    int s = client.get(orgs).status();
    decide(Capability.ORGS, s == 200 || s == 204, "GET " + orgs, s, found, details);

    boolean restLogin = restLoginExists();
    decide(Capability.REST_LOGIN, restLogin, "POST " + REST_LOGIN, restLoginStatus, found, details);

    for (Capability c :
        List.of(Capability.KEYSTORE_ENCRYPTION, Capability.TOKEN_AUTH, Capability.PREAUTH)) {
      boolean present = expected.contains(c);
      if (present) {
        found.add(c);
      }
      details.put(
          c,
          (present ? "present" : "absent")
              + " per compat matrix for JRS "
              + id.version()
              + " "
              + id.edition()
              + " (not probeable without server configuration)");
    }
    capabilities = Collections.unmodifiableSet(found);
    probeResults = Collections.unmodifiableMap(details);
  }

  private void probeEndpoint(
      Capability c, String path, Set<Capability> found, Map<Capability, String> details) {
    int s = client.get(path).status();
    boolean present = s == 200 || s == 404;
    decide(c, present, "GET " + path, s, found, details);
  }

  private static void decide(
      Capability c,
      boolean present,
      String request,
      int status,
      Set<Capability> found,
      Map<Capability, String> details) {
    if (present) {
      found.add(c);
    }
    details.put(
        c, request + " answered HTTP " + status + " (" + (present ? "present" : "absent") + ")");
  }

  private boolean restLoginExists() {
    Optional<Boolean> known = restLoginExists;
    if (known.isPresent()) {
      return known.get();
    }
    // POST with no credentials: an existing endpoint answers 401/400/403 (or 200 on odd builds),
    // a server without it answers 404. A GET is unreliable because 10.x answers 404 to GET as well.
    int s = client.post(REST_LOGIN, "application/x-www-form-urlencoded", "").status();
    restLoginStatus = s;
    boolean exists = s != 404 && s < 500;
    restLoginExists = Optional.of(exists);
    return exists;
  }

  // ---------------------------------------------------------------- login

  @Override
  public Session login(Credentials credentials) {
    Objects.requireNonNull(credentials, "credentials");
    String principal =
        credentials
            .organization()
            .map(o -> credentials.username() + "|" + o)
            .orElse(credentials.username());
    Session s =
        switch (config.server().auth().mode()) {
          case BASIC -> {
            client.useBasic(principal, credentials.password());
            verifyAuthenticated();
            yield new Session(Session.AuthMode.BASIC, Optional.empty(), Instant.now());
          }
          case TOKEN -> {
            client.useToken(credentials.password());
            verifyAuthenticated();
            yield new Session(Session.AuthMode.TOKEN, Optional.empty(), Instant.now());
          }
          case FORM -> formLogin(principal, credentials);
        };
    session = Optional.of(s);
    return s;
  }

  private Session formLogin(String principal, Credentials credentials) {
    client.clearSession();
    boolean rest = restLoginExists();
    String path = rest ? REST_LOGIN : FORM_LOGIN;
    RestClient.Response r = client.formLogin(path, principal, credentials.password());
    Optional<String> cookie = client.sessionCookie();
    boolean accepted;
    if (rest) {
      accepted = r.ok();
    } else {
      // Spring Security answers 302 to the home page on success and to login.html?error on failure
      String location = r.header("Location").orElse("").toLowerCase(Locale.ROOT);
      accepted = (r.ok() || r.status() == 302) && !location.contains("error");
    }
    if (!accepted || cookie.isEmpty()) {
      throw new RestException(
          r.status(),
          "POST",
          path,
          "login rejected for user "
              + principal
              + " (HTTP "
              + r.status()
              + (cookie.isEmpty() ? ", no session cookie" : "")
              + ")");
    }
    return new Session(Session.AuthMode.FORM, cookie, Instant.now());
  }

  private void verifyAuthenticated() {
    String path = RESOURCES + "?folderUri=%2F&recursive=false&limit=1";
    RestClient.Response r = client.get(path);
    if (r.status() != 204) {
      client.require2xx(r, "GET", path);
    }
  }

  /** In form mode, logs in with the configured credentials before the first authenticated call. */
  private void ensureSession() {
    if (config.server().auth().mode() == Config.AuthMode.FORM
        && session.isEmpty()
        && configured.isPresent()) {
      login(configured.get());
    }
  }

  // ---------------------------------------------------------------- export

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    ensureSession();
    boolean everything = request.scope() == ExportRequest.Scope.EVERYTHING || request.fullServer();
    List<String> parameters = new ArrayList<>();
    if (everything) {
      parameters.add("everything");
    }
    parameters.add("repository-permissions");
    if (request.includeUsersRoles()) {
      parameters.add("role-users");
    }
    if (request.includeAccessEvents()) {
      parameters.add("include-access-events");
    }
    if (request.includeAuditEvents()) {
      parameters.add("include-audit-events");
    }
    if (request.includeMonitoring()) {
      parameters.add("include-monitoring-events");
    }
    if (request.includeSettings()) {
      parameters.add("include-server-settings");
    }
    List<String> uris = new ArrayList<>(new TreeSet<>(request.uris()));
    if (uris.isEmpty() && !everything) {
      uris.add("/");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("uris", uris);
    body.put("roles", List.of());
    body.put("users", List.of());
    body.put("parameters", parameters);
    RestClient.Response r = client.require2xx(client.postJson(EXPORT, body), "POST", EXPORT);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "POST", EXPORT);
    if (state.id() == null || state.id().isBlank()) {
      throw new RestException(r.status(), "POST", EXPORT, "export started but no task id returned");
    }
    if (state.fileName() != null) {
      exportFileNames.put(state.id(), state.fileName());
    }
    return new Handles.ExportHandle(state.id());
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    String path = EXPORT + "/" + RestClient.encodeQuery(handle.id()) + "/state";
    RestClient.Response r = client.require2xx(client.get(path), "GET", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "GET", path);
    if (state.fileName() != null) {
      exportFileNames.put(handle.id(), state.fileName());
    }
    return new Handles.ExportStatus(
        phase(state.phase()),
        Optional.ofNullable(state.message()),
        Optional.ofNullable(state.fileName()),
        Optional.ofNullable(state.errorCode()));
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    Objects.requireNonNull(target, "target");
    String fileName = exportFileNames.getOrDefault(handle.id(), DEFAULT_EXPORT_FILE);
    String path =
        EXPORT + "/" + RestClient.encodeQuery(handle.id()) + "/" + RestClient.encodeQuery(fileName);
    client.require2xx(client.getToFile(path, "application/zip", target), "GET", path);
    return target;
  }

  // ---------------------------------------------------------------- import

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(archive, "archive");
    ensureSession();
    String path =
        IMPORT
            + "?update="
            + request.update()
            + "&skipUserUpdate="
            + request.skipUserUpdate()
            + "&includeAccessEvents="
            + request.includeAccessEvents()
            + "&includeAuditEvents="
            + request.includeAuditEvents()
            + "&includeMonitoringEvents="
            + request.includeMonitoring()
            + "&includeServerSettings="
            + request.includeSettings()
            + "&skipThemes="
            + request.skipThemes();
    RestClient.Response r =
        client.require2xx(client.postBytesFromFile(path, archive, "application/zip"), "POST", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "POST", IMPORT);
    if (state.id() == null || state.id().isBlank()) {
      throw new RestException(r.status(), "POST", IMPORT, "import started but no task id returned");
    }
    return new Handles.ImportHandle(state.id());
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    String path = IMPORT + "/" + RestClient.encodeQuery(handle.id()) + "/state";
    RestClient.Response r = client.require2xx(client.get(path), "GET", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "GET", path);
    return new Handles.ImportStatus(
        phase(state.phase()),
        Optional.ofNullable(state.message()),
        Optional.ofNullable(state.errorCode()));
  }

  static Handles.Phase phase(String raw) {
    String p =
        raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return switch (p) {
      case "inprogress", "pending", "running", "queued" -> Handles.Phase.INPROGRESS;
      case "ready", "finished", "success", "succeeded", "completed" -> Handles.Phase.READY;
      default -> Handles.Phase.FAILED;
    };
  }

  // ---------------------------------------------------------------- keystore and health

  @Override
  public KeystoreInfo keystore() {
    boolean encrypted = true;
    Optional<String> version = Optional.empty();
    try {
      encrypted = capabilities().contains(Capability.KEYSTORE_ENCRYPTION);
      version = Optional.of(identity().version());
    } catch (JrsUnreachableException e) {
      // server down: still worth inspecting the files, doctor reports reachability separately
    }
    if (!encrypted) {
      return KeystoreInfo.absent(
          "JRS "
              + version.orElse("?")
              + " has no KEYSTORE_ENCRYPTION capability (pre 7.5); repository passwords are not"
              + " keystore-encrypted, nothing to carry across");
    }
    return keystoreInspector.inspect();
  }

  @Override
  public HealthReport health() {
    List<HealthReport.Item> items = new ArrayList<>();
    Instant start = Instant.now();
    ServerIdentity id;
    try {
      id = identity();
    } catch (JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "serverInfo", HealthReport.Status.FAIL, e.getMessage(), e.remediation()));
      return new HealthReport(false, Duration.between(start, Instant.now()), items);
    } catch (RestException e) {
      items.add(
          new HealthReport.Item(
              "serverInfo",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "check server.baseUrl points at the JasperReports Server web application"));
      return new HealthReport(false, Duration.between(start, Instant.now()), items);
    }
    Duration latency = Duration.between(start, Instant.now());
    items.add(
        new HealthReport.Item(
            "serverInfo",
            HealthReport.Status.PASS,
            "JRS "
                + id.version()
                + " "
                + id.edition()
                + " "
                + id.tenancy()
                + " answered in "
                + latency.toMillis()
                + " ms",
            ""));

    if (configured.isEmpty()) {
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.WARN,
              "no credentials configured; authenticated checks skipped",
              "set server.auth.username and server.auth.passwordRef"));
      return new HealthReport(true, latency, items);
    }
    try {
      Session s = login(configured.get());
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.PASS,
              "authenticated as " + configured.get().username() + " (" + s.mode() + ")",
              ""));
    } catch (RestException | JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "check server.auth.mode, server.auth.username and the secret behind"
                  + " server.auth.passwordRef"));
      return new HealthReport(true, latency, items);
    }
    try {
      List<String> children = listFolder("/");
      items.add(
          new HealthReport.Item(
              "repository",
              HealthReport.Status.PASS,
              "listed / (" + children.size() + " entries)",
              ""));
    } catch (RestException | JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "repository",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "the account must be able to read the repository root"));
    }
    boolean scheduler = schedulerReachable();
    items.add(
        new HealthReport.Item(
            "scheduler",
            scheduler ? HealthReport.Status.PASS : HealthReport.Status.FAIL,
            scheduler ? "GET " + JOBS + " answered" : "GET " + JOBS + " did not answer 200",
            scheduler ? "" : "check that the scheduler is enabled and the account may list jobs"));
    return new HealthReport(true, latency, items);
  }

  // ---------------------------------------------------------------- read-only helpers

  @Override
  public List<String> listFolder(String folderUri) {
    Objects.requireNonNull(folderUri, "folderUri");
    ensureSession();
    String path =
        RESOURCES
            + "?folderUri="
            + RestClient.encodeQuery(folderUri)
            + "&recursive=false&limit=100";
    RestClient.Response r = client.get(path);
    if (r.status() == 204) {
      return List.of();
    }
    client.require2xx(r, "GET", path);
    Wire.ResourceLookupList list =
        Wire.parse(r.body(), Wire.ResourceLookupList.class, "GET", RESOURCES);
    List<String> uris = new ArrayList<>();
    if (list.resourceLookup() != null) {
      for (Wire.ResourceLookup l : list.resourceLookup()) {
        if (l != null && l.uri() != null) {
          uris.add(l.uri());
        }
      }
    }
    return List.copyOf(uris);
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    Objects.requireNonNull(reportUri, "reportUri");
    Objects.requireNonNull(target, "target");
    ensureSession();
    String path = REPORTS + RestClient.encodePath(reportUri) + ".pdf";
    client.require2xx(client.getToFile(path, "application/pdf", target), "GET", path);
    return target;
  }

  @Override
  public boolean schedulerReachable() {
    try {
      ensureSession();
      int s = client.get(JOBS).status();
      return s == 200 || s == 204;
    } catch (JrsUnreachableException | RestException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- mutating helpers (Steps only)

  @Override
  public void createFolder(String folderUri, String label) {
    Objects.requireNonNull(folderUri, "folderUri");
    Objects.requireNonNull(label, "label");
    ensureSession();
    String parent = parentOf(folderUri);
    String path = RESOURCES + RestClient.encodePath(parent) + "?createFolders=true";
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("label", label);
    body.put("description", "created by jrsctl smoke");
    String json = Json.write(body);
    client.require2xx(client.post(path, FOLDER_TYPE, json), "POST", path);
  }

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
    Objects.requireNonNull(folderUri, "folderUri");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(jrxml, "jrxml");
    ensureSession();
    String id = slug(label);
    String path =
        RESOURCES
            + RestClient.encodePath(stripTrailingSlash(folderUri) + "/" + id)
            + "?createFolders=true&overwrite=true";
    Path tmp;
    try {
      tmp = Files.createTempFile("jrsctl-upload", ".json");
    } catch (IOException e) {
      throw new RestException(0, "PUT", path, "cannot create upload staging file", e);
    }
    try {
      writeReportUnit(tmp, label, jrxml);
      client.require2xx(client.putFromFile(path, REPORT_UNIT_TYPE, tmp), "PUT", path);
    } catch (IOException e) {
      throw new RestException(0, "PUT", path, "cannot stage " + jrxml + ": " + e.getMessage(), e);
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // a leftover staging file in the temp dir is harmless
      }
    }
  }

  @Override
  public void deleteResource(String uri) {
    Objects.requireNonNull(uri, "uri");
    ensureSession();
    String path = RESOURCES + RestClient.encodePath(uri);
    client.require2xx(client.delete(path), "DELETE", path);
  }

  /**
   * Streams the JRXML through a Base64 encoder into a reportUnit descriptor; nothing is buffered.
   */
  private static void writeReportUnit(Path target, String label, Path jrxml) throws IOException {
    Map<String, Object> head = new LinkedHashMap<>();
    head.put("label", label);
    head.put("description", "uploaded by jrsctl smoke --mutating");
    String prefix = Json.write(head);
    // drop the closing brace and open the nested jrxml object
    prefix =
        prefix.substring(0, prefix.length() - 1)
            + ",\"jrxml\":{\"jrxmlFile\":{\"type\":\"jrxml\",\"content\":\"";
    String suffix = "\"}}}";
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(prefix.getBytes(StandardCharsets.UTF_8));
      OutputStream b64 = Base64.getEncoder().wrap(new NonClosingOutputStream(out));
      Files.copy(jrxml, b64);
      b64.close();
      out.write(suffix.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final class NonClosingOutputStream extends java.io.FilterOutputStream {
    NonClosingOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      out.flush();
    }
  }

  static String parentOf(String uri) {
    String u = stripTrailingSlash(uri);
    int slash = u.lastIndexOf('/');
    return slash <= 0 ? "/" : u.substring(0, slash);
  }

  static String slug(String label) {
    String s = label.strip().replaceAll("[^A-Za-z0-9_.-]+", "_");
    return s.isEmpty() ? "report" : s;
  }

  private static String stripTrailingSlash(String s) {
    return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
