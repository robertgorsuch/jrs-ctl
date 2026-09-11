package com.jaspersoft.jrsctl.jrs.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jaspersoft.jrsctl.core.json.Json;
import java.util.List;

/**
 * JSON shapes of the REST v2 responses the adapter reads, bound with the shared mapper (unknown
 * properties ignored). Every field may be absent and is then {@code null}; callers normalise.
 */
final class Wire {

  private Wire() {}

  /** {@code GET /rest_v2/serverInfo}. */
  record ServerInfo(
      String version,
      String edition,
      String editionName,
      String features,
      String build,
      String dateFormatPattern,
      String datetimeFormatPattern,
      String licenseType,
      String expiration) {}

  /** The JSON error every JRS answers with for a missing task or resource. */
  record ErrorBody(String message, String errorCode) {}

  /** Body of {@code POST /rest_v2/export|import} and of the {@code /state} polls. */
  record AsyncState(String id, String phase, String message, String errorCode, String fileName) {}

  /** {@code GET /rest_v2/resources}. */
  record ResourceLookupList(List<ResourceLookup> resourceLookup) {}

  record ResourceLookup(String uri, String label, String resourceType) {}

  static <T> T parse(String body, Class<T> type, String method, String path) {
    try {
      return Json.mapper().readValue(body == null || body.isBlank() ? "{}" : body, type);
    } catch (JsonProcessingException e) {
      throw new RestException(
          0,
          method,
          path,
          "unparseable JSON from " + method + " " + path + " (" + type.getSimpleName() + ")",
          e);
    }
  }
}
