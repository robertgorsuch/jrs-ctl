package com.jaspersoft.jrsctl.app.console;

import java.util.List;

record RepoDoc(String path, List<RepoNode> children) {

  record RepoNode(
      String uri, String label, String resourceType, boolean isFolder, boolean hasChildren) {}
}
