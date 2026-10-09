package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * coding capability 的本地 workdir 与参数路径解析。
 *
 * <p>文件工具的 {@code path} 必须是目标 Daemon 本机文件系统上的绝对路径；相对路径直接拒绝，没有任何默认目录、cwd、HOME 或会话默认值可回退。Daemon 用自身
 * {@code Path} 校验绝对性（因此 Windows 形态由 Windows 宿主接受、被 Linux 宿主拒绝），不做 {@code ~}/环境变量展开，也不自动 mkdir。
 *
 * <p>只有 {@code process.exec} 仍接受
 * workdir：它必须由本次调用显式给出一个绝对、现存、可读的目录。目录不是文件系统沙箱：绝对路径只要底层文件系统支持就照常解析，符号链接照常跟随。命令与文件系统的业务授权属于 Platform
 * permission。
 */
final class EnvironmentPaths {

  private EnvironmentPaths() {}

  /**
   * 解析 {@code process.exec} 本次调用的 workdir：复用目标 OS 的 {@link DaemonWorkdirSyntax}
   * 词法校验（拒绝周边空白与未展开占位符）， 再要求自身 {@code Path} 视角下绝对、现存、为目录且可读，最后返回其真实路径。
   *
   * <p>不做 home/环境变量展开、不自动 mkdir、不回退到 Environment Root 或任何会话默认值；每次调用独立解析。
   */
  static Path workdir(String rawWorkdir) {
    String validated;
    try {
      validated =
          DaemonWorkdirSyntax.requireAbsolute(
              rawWorkdir, DaemonOperatingSystemDetector.detectCurrent());
    } catch (IllegalArgumentException error) {
      throw new ToolInputRejectedException(rejectWorkdir(rawWorkdir));
    }
    Path candidate = parse(validated, "workdir");
    if (!candidate.isAbsolute()) {
      throw new ToolInputRejectedException(
          "workdir must be an absolute path (a relative or non-absolute value was given)");
    }
    if (!Files.isDirectory(candidate)) {
      throw new ToolInputRejectedException("workdir must be an existing directory: " + rawWorkdir);
    }
    if (!Files.isReadable(candidate)) {
      throw new ToolInputRejectedException("workdir must be a readable directory: " + rawWorkdir);
    }
    return canonicalExisting(candidate, "workdir");
  }

  /** workdir 词法校验的拒绝文案：空值说明字段要求；其余只指出绝对路径要求，不回显调用方给出的原始值（词法校验自身会内联该值）。 */
  private static String rejectWorkdir(String rawWorkdir) {
    if (rawWorkdir == null || rawWorkdir.isBlank()) {
      return "workdir must be a non-blank absolute directory path";
    }
    return "workdir must be an absolute path (a relative or non-absolute value was given)";
  }

  /** 解析已存在的文件或目录；{@code rawPath} 必须是绝对路径，返回其真实路径。 */
  static Path existing(String rawPath) {
    Path candidate = requireAbsolute(rawPath, "path");
    if (!Files.exists(candidate)) {
      throw new ToolInputRejectedException("path does not exist: " + display(rawPath));
    }
    return canonicalExisting(candidate, "path");
  }

  /**
   * 解析写目标：允许尚不存在的末段，把现存祖先解析为真实路径后再拼接剩余段。
   *
   * <p>{@code rawPath} 必须是绝对路径；相对路径在任何文件系统访问之前被拒绝，不回退到任何默认目录。
   */
  static Path writable(String rawPath) {
    Path candidate = requireAbsolute(rawPath, "path");
    if (Files.exists(candidate)) {
      return canonicalExisting(candidate, "path");
    }
    Path ancestor = candidate.getParent();
    while (ancestor != null && !Files.exists(ancestor)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      throw new ToolInputRejectedException("path has no existing ancestor: " + display(rawPath));
    }
    return canonicalExisting(ancestor, "path ancestor")
        .resolve(ancestor.relativize(candidate))
        .normalize();
  }

  private static Path requireAbsolute(String raw, String name) {
    requirePath(raw, name);
    Path requested = parse(raw, name);
    if (!requested.isAbsolute()) {
      throw new ToolInputRejectedException(
          name + " must be an absolute path (a relative or non-absolute value was given)");
    }
    return requested.normalize();
  }

  private static Path parse(String raw, String name) {
    try {
      return Path.of(raw);
    } catch (RuntimeException error) {
      throw new ToolInputRejectedException(name + " is not a valid path", error);
    }
  }

  private static Path canonicalExisting(Path candidate, String name) {
    try {
      return candidate.toRealPath();
    } catch (IOException error) {
      throw new ToolInputRejectedException(name + " must exist: " + candidate, error);
    }
  }

  private static void requirePath(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      throw new ToolInputRejectedException(name + " is required");
    }
  }

  private static String display(String value) {
    return value == null ? "<missing>" : value;
  }

  /** 绝对路径的统一展示形态：回显调用方给出的绝对路径（仅做词法归一与 {@code '/'} 分隔），绝不回退到任何默认目录，也不因 symlink 规范化而替换调用方传入的坐标。 */
  static String displayPath(Path target, String rawPath) {
    if (rawPath != null && !rawPath.isBlank()) {
      try {
        Path parsed = Path.of(rawPath);
        if (parsed.isAbsolute()) {
          return parsed.normalize().toString().replace('\\', '/');
        }
      } catch (RuntimeException ignored) {
        // 非法 rawPath 无法词法归一，退回已解析目标。
      }
    }
    return target != null ? target.toString().replace('\\', '/') : display(rawPath);
  }
}
