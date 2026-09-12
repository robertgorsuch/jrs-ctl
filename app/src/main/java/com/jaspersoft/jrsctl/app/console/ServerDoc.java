package com.jaspersoft.jrsctl.app.console;

/**
 * The {@code GET /api/server} document (spec §13.1, {@code api-server.schema.json}). Invariants:
 * component order is the key order on the wire; every component is always present, and an unknown
 * value is the empty string rather than {@code null}, so the document degrades to the
 * configuration-known fields with {@code reachable:false} instead of failing when the server cannot
 * be reached.
 */
record ServerDoc(
    String product,
    String version,
    String edition,
    String tenancy,
    Database database,
    String baseUrl,
    String installDir,
    Service service,
    Keystore keystore,
    String networkMode,
    boolean reachable) {

  /** The configured database's vendor and version; both empty when unknown. */
  record Database(String vendor, String version) {}

  /** The configured OS service's kind and name, and its observed state when inspectable. */
  record Service(String kind, String name, String state) {}

  /** Whether the keystore is present, and the OS user the service runs as. */
  record Keystore(boolean present, String user) {}
}
