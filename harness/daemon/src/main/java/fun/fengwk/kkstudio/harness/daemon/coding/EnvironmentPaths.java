package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * coding capability 的本地 workdir 与参数路径解析。
 *
 * <p>workdir 是本次调用 arguments 中的必填字段，指目标 Daemon 上的显式绝对目录。Daemon 用自身 {@code Path} 校验它必须绝对、现存且为目录， 不做
 * {@code ~}/环境变量展开、不自动 mkdir、不回退到 Environment Root 或任何会话默认值。每次调用独立解析，调用之间不继承目录。
 *
 * <p>workdir 不是文件系统沙箱：绝对路径与越出 workdir 的相对路径只要底层文件系统支持就照常解析，符号链接照常跟随。命令与文件系统的业务授权属于 Platform
 * permission。
 */
final class EnvironmentPaths {

  private EnvironmentPaths() {}

  /**
   * 解析本次调用的 workdir：复用目标 OS 的 {@link DaemonWorkdirSyntax} 词法校验（拒绝周边空白与未展开占位符）， 再要求自身 {@code Path}
   * 视角下绝对、现存、为目录且可读，最后返回其真实路径。
   *
   * <p>不做 home/环境变量展开、不自动 mkdir、不回退到 Environment Root 或任何会话默认值；每次调用独立解析。
   */
  static Path workdir(String rawWorkdir) {
    String validated =
        DaemonWorkdirSyntax.requireAbsolute(
            rawWorkdir, DaemonOperatingSystemDetector.detectCurrent());
    Path candidate = parse(validated, "workdir");
    if (!candidate.isAbsolute()) {
      throw new IllegalArgumentException("workdir must be an absolute path: " + rawWorkdir);
    }
    if (!Files.isDirectory(candidate)) {
      throw new IllegalArgumentException("workdir must be an existing directory: " + rawWorkdir);
    }
    if (!Files.isReadable(candidate)) {
      throw new IllegalArgumentException("workdir must be a readable directory: " + rawWorkdir);
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
    requirePath(raw, name);
    Path requested = parse(raw, name);
    return (requested.isAbsolute() ? requested : base.resolve(requested)).normalize();
  }

  private static Path parse(String raw, String name) {
    try {
      return Path.of(raw);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(name + " is not a valid path: " + raw, error);
    }
  }

  private static Path canonicalExisting(Path candidate, String name) {
    try {
      return candidate.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(name + " must exist: " + candidate, error);
    }
  }

  private static void requirePath(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static String display(String value) {
    return value == null ? "<missing>" : value;
  }

  /** 将目标绝对路径转换为相对 workdir 的展示路径，统一以 '/' 分隔。 */
  static String displayPath(Path target, Path workdir, String rawPath) {
    try {
      if (target != null && workdir != null && target.startsWith(workdir)) {
        Path relative = workdir.relativize(target);
        String relStr = relative.toString().replace('\\', '/');
        return relStr.isEmpty() ? "." : relStr;
      }
    } catch (IllegalArgumentException ignored) {
    }
    if (rawPath != null && !rawPath.isBlank()) {
      try {
        Path parsed = Path.of(rawPath);
        if (!parsed.isAbsolute()) {
          return parsed.normalize().toString().replace('\\', '/');
        }
      } catch (RuntimeException ignored) {
      }
    }
    return target != null ? target.toString().replace('\\', '/') : display(rawPath);
  }
}
