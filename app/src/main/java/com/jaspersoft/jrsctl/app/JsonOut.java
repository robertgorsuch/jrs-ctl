package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Single JSON writer for {@code --json} output so every command serialises the same way (spec §14
 * Phase 8 validates this output against schemas). Invariant: output is deterministic and never
 * contains unredacted secrets; redaction is applied by the caller's writer in later phases.
 */
final class JsonOut {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .registerModule(new Jdk8Module())
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private JsonOut() {}

  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName(), e);
    }
  }
}
