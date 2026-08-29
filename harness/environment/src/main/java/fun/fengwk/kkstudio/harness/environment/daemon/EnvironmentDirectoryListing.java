package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;

import java.util.List;
import java.util.Objects;

/** Environment Root 下单层目录浏览的结果模型。 */
public record EnvironmentDirectoryListing(
    String path,
    String displayPath,
    String parentPath,
    boolean truncated,
    String gitBranch,
    List<EnvironmentDirectoryEntry> entries) {

  public static final int MAX_ENTRIES = 1000;

  public EnvironmentDirectoryListing {
    path = EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    displayPath = requireNonBlank(displayPath, "displayPath");
    if (!displayPath.equals(lastSegment(path))) {
      throw new IllegalArgumentException("displayPath must be the last segment of path: " + path);
    }
    parentPath = EnvironmentWorkspacePath.requireCanonicalRelativePath(parentPath);
    if (!parentPath.equals(lexicalParentPath(path))) {
      throw new IllegalArgumentException("parentPath must be the lexical parent of path: " + path);
    }
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (entries.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException("entries must not exceed " + MAX_ENTRIES);
    }
    for (EnvironmentDirectoryEntry entry : entries) {
      requireDirectChild(path, entry);
    }
  }

  private static void requireDirectChild(String parentPath, EnvironmentDirectoryEntry entry) {
    String expectedPrefix = ".".equals(parentPath) ? "" : parentPath + "/";
    if (!entry.path().startsWith(expectedPrefix)) {
      throw new IllegalArgumentException("entry does not start with parent: " + entry.path());
    }
    String remainder = entry.path().substring(expectedPrefix.length());
    if (remainder.indexOf('/') >= 0) {
      throw new IllegalArgumentException("entry is not a direct child of parent: " + entry.path());
    }
  }

  private static String lastSegment(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String lexicalParentPath(String path) {
    if (".".equals(path)) {
      return ".";
    }
    int slash = path.lastIndexOf('/');
    if (slash < 0) {
      return ".";
    }
    return path.substring(0, slash);
  }

  private static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.strip();
  }
}
