package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.ops.Services;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RepoViews {

  private static final Logger LOG = LoggerFactory.getLogger(RepoViews.class);
  private final Services services;

  RepoViews(Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = Objects.requireNonNull(services, "services");
  }

  RepoDoc tree(String path) {
    String folder = (path == null || path.isBlank()) ? "/" : path.trim();
    if (!folder.startsWith("/")) {
      folder = "/" + folder;
    }
    List<RepoDoc.RepoNode> children = new ArrayList<>();
    try {
      JrsAdapter adapter = services.adapter().get();
      List<String> list = adapter.listFolder(folder);
      for (String childUri : list) {
        String label = childUri;
        int slash = childUri.lastIndexOf('/');
        if (slash >= 0 && slash < childUri.length() - 1) {
          label = childUri.substring(slash + 1);
        }
        children.add(new RepoDoc.RepoNode(childUri, label, "folder", true, true));
      }
    } catch (RuntimeException e) {
      LOG.debug("cannot list repository folder {}: {}", folder, e.getMessage());
    }
    return new RepoDoc(folder, children);
  }
}
