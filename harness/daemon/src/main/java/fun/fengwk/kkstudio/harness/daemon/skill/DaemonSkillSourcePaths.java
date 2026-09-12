package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;

import java.nio.file.Path;
import java.util.Objects;

/**
 * PATH 来源配置的本地路径解析。
 *
 * <p>只接受目标 OS 上的显式绝对路径，或前导 {@code ~/} 形式；只有前导 {@code ~/} 会按 Daemon 用户 HOME 展开，其余 {@code ~}
 * 用法一律拒绝。解析不做环境变量展开、不自动 mkdir，也不影响任何工具 workdir —— 这里的目录只用于 Skill 发现。
 */
final class DaemonSkillSourcePaths {

  private static final String HOME_PREFIX = "~/";

  private DaemonSkillSourcePaths() {}

  /** 解析来源路径文本；形状非法时抛 {@link DaemonSkillException}。 */
  static Path resolve(String rawPath, Path userHome) {
    Objects.requireNonNull(rawPath, "rawPath");
    Objects.requireNonNull(userHome, "userHome");
    String expanded;
    if (rawPath.startsWith(HOME_PREFIX)) {
      String remainder = rawPath.substring(HOME_PREFIX.length());
      expanded = userHome.resolve(remainder).toString();
    } else if ("~".equals(rawPath) || rawPath.startsWith("~")) {
      throw new DaemonSkillException("skill source path must be absolute or start with '~/'");
    } else {
      expanded = rawPath;
    }
    String validated;
    try {
      validated =
          DaemonWorkdirSyntax.requireAbsolute(
              expanded, DaemonOperatingSystemDetector.detectCurrent());
    } catch (IllegalArgumentException error) {
      throw new DaemonSkillException("skill source path must be an absolute path on this host");
    }
    Path path = Path.of(validated);
    if (!path.isAbsolute()) {
      throw new DaemonSkillException("skill source path must be an absolute path on this host");
    }
    return path.normalize();
  }
}
