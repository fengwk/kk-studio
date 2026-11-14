package fun.fengwk.kkstudio.harness.api.tool;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lexical identity shared by mutation dispatch and read projections; never resolves filesystem
 * links.
 */
public final class ToolFileMutationOrder {

  private ToolFileMutationOrder() {}

  /**
   * Returns a key only for bound Environment edit/write calls with a normalizable absolute path.
   */
  public static Optional<Key> key(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    if (invocation.binding() == null || invocation.binding().environmentId() == null) {
      return Optional.empty();
    }
    String toolName = invocation.call().toolName();
    if (!"edit".equals(toolName) && !"write".equals(toolName)) {
      return Optional.empty();
    }
    try {
      var arguments = JsonValues.readTree(invocation.call().argumentsJson());
      if (!arguments.isObject() || !arguments.path("path").isTextual()) {
        return Optional.empty();
      }
      String path = arguments.path("path").textValue();
      String normalized = normalize(path);
      return normalized == null
          ? Optional.empty()
          : Optional.of(new Key(invocation.binding().environmentId(), normalized));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private static String normalize(String path) {
    if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
      return null;
    }
    String value = path.replace('\\', '/');
    boolean windows = isWindowsAbsolute(value);
    RootSplit split = splitRoot(value, windows);
    if (split == null) {
      return null;
    }
    String root = split.root();
    String rest = split.rest();
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : rest.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (!segments.isEmpty()) {
          segments.removeLast();
        }
      } else {
        segments.addLast(segment);
      }
    }
    String normalized = root + String.join("/", segments);
    return windows ? normalized.toLowerCase(Locale.ROOT) : normalized;
  }

  /** 把绝对路径拆成 canonical root 与剩余段；非绝对路径或 UNC 主机/共享缺失时返回 null。 */
  private static RootSplit splitRoot(String value, boolean windows) {
    if (windows && value.startsWith("//")) {
      String[] parts = value.substring(2).split("/", -1);
      if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        return null;
      }
      return new RootSplit(
          "//" + parts[0] + "/" + parts[1] + "/",
          String.join("/", Arrays.copyOfRange(parts, 2, parts.length)));
    }
    if (windows) {
      return new RootSplit(Character.toUpperCase(value.charAt(0)) + ":/", value.substring(3));
    }
    if (value.startsWith("/")) {
      return new RootSplit("/", value.substring(1));
    }
    return null;
  }

  private record RootSplit(String root, String rest) {}

  private static boolean isWindowsAbsolute(String value) {
    return (value.length() >= 3
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':'
            && value.charAt(2) == '/')
        || isWindowsUnc(value);
  }

  private static boolean isWindowsUnc(String value) {
    if (!value.startsWith("//")) {
      return false;
    }
    String[] parts = value.substring(2).split("/", -1);
    return parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank();
  }

  public record Key(EnvironmentId environmentId, String path) {
    public Key {
      Objects.requireNonNull(environmentId, "environmentId");
      Objects.requireNonNull(path, "path");
    }
  }
}
