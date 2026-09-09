package com.jaspersoft.jrsctl.ops.init;

import com.jaspersoft.jrsctl.core.config.Config;
import java.util.List;
import java.util.Objects;

/**
 * What {@code init} detected (spec §12.0): the configuration it proposes and, for the operator's
 * confirmation, every value with where it came from. Invariants: {@code values} lists only keys
 * present in {@code config}; a source is human-readable ({@code "detected from conf/server.xml"},
 * {@code "from default_master.properties"}, {@code "default"}); password references appear as
 * placeholders, never as values.
 */
public record InitReport(Config config, List<Detected> values) {

  /** One detected configuration key with its provenance. */
  public record Detected(String key, String value, String source) {
    public Detected {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(source, "source");
    }
  }

  public InitReport {
    Objects.requireNonNull(config, "config");
    values = List.copyOf(values);
  }

  /** True when a Tomcat layout was found; otherwise the report holds defaults only. */
  public boolean detectedInstall() {
    return config.server().installDir().isPresent();
  }
}
