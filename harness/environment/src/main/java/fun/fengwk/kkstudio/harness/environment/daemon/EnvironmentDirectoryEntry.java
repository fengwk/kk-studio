package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;

import java.util.Objects;

/** 目录列表中的单个子目录条目。 */
public record EnvironmentDirectoryEntry(String name, String path) {

  public EnvironmentDirectoryEntry {
    name = requireNonBlank(name, "name");
    path = EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    if (!name.equals(lastSegment(path))) {
      throw new IllegalArgumentException("name must be the last segment of path: " + path);
    }
  }

  private static String lastSegment(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.strip();
  }
}
