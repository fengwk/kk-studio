package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * coding capability 的缺省 cwd 与参数路径解析。
 *
 * <p>workdir 只是缺省 cwd：Daemon 把 invocation workspace 的真实路径写入 {@code
 * EnvironmentCapabilityExecutionRequest.workdir}，参数省略路径或 workdir 时以它为基准；绝对值直接使用，相对值以它为基准。 workdir
 * 不是文件系统沙箱：绝对路径与越出 workdir 的相对路径只要底层文件系统支持就照常解析，符号链接照常跟随。
 *
 * <p>本类只保留取得可用缺省 cwd 与目标自身所需的校验：workdir 必须是现存目录，读取目标必须存在，写目标必须能回溯到现存祖先。命令与文件系统的业务授权属于 Platform
 * permission。
 */
final class EnvironmentPaths {

  private EnvironmentPaths() {}

  /** 解析缺省 workdir：未提供时使用 invocation workspace，相对值以其为基准，绝对值直接使用；结果必须是现存目录。 */
  static Path workdir(String rawWorkdir, Path invocationWorkdir) {
    Path base = requireWorkdir(invocationWorkdir);
    if (rawWorkdir == null || rawWorkdir.isBlank()) {
      return base;
    }
    Path candidate = resolve(rawWorkdir, base, "workdir");
    if (!Files.isDirectory(candidate)) {
      throw new IllegalArgumentException(
          "workdir must be an existing directory: " + display(rawWorkdir));
    }
    return canonicalExisting(candidate, "workdir");
  }

  /** 解析已存在的文件或目录，返回其真实路径。 */
  static Path existing(String rawPath, Path workdir) {
    Path candidate = resolve(rawPath, requireWorkdir(workdir), "path");
    if (!Files.exists(candidate)) {
      throw new IllegalArgumentException("path does not exist: " + display(rawPath));
    }
    return canonicalExisting(candidate, "path");
  }

  /** 解析写目标：允许尚不存在的末段，把现存祖先解析为真实路径后再拼接剩余段。 */
  static Path writable(String rawPath, Path workdir) {
    Path candidate = resolve(rawPath, requireWorkdir(workdir), "path");
    if (Files.exists(candidate)) {
      return canonicalExisting(candidate, "path");
    }
    Path ancestor = candidate.getParent();
    while (ancestor != null && !Files.exists(ancestor)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      throw new IllegalArgumentException("path has no existing ancestor: " + display(rawPath));
    }
    return canonicalExisting(ancestor, "path ancestor")
        .resolve(ancestor.relativize(candidate))
        .normalize();
  }

  private static Path requireWorkdir(Path workdir) {
    Objects.requireNonNull(workdir, "workdir");
    Path canonical = canonicalExisting(workdir, "workdir");
    if (!Files.isDirectory(canonical)) {
      throw new IllegalArgumentException("workdir must be an existing directory: " + workdir);
    }
    return canonical;
  }

  private static Path resolve(String raw, Path base, String name) {
    String value = stripPrefix(raw, name);
    Path requested;
    try {
      requested = Path.of(value);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, error);
    }
    return (requested.isAbsolute() ? requested : base.resolve(requested)).normalize();
  }

  private static Path canonicalExisting(Path candidate, String name) {
    try {
      return candidate.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(name + " must exist: " + candidate, error);
    }
  }

  private static String stripPrefix(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    String value = raw.startsWith("@") ? raw.substring(1) : raw;
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static String display(String value) {
    return value == null ? "<missing>" : value;
  }
}
