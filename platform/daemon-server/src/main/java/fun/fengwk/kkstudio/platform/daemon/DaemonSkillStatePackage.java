package fun.fengwk.kkstudio.platform.daemon;

import java.util.Objects;

/**
 * Daemon 回报的单个技能包安装状态：已安装归档摘要、本地安装根与去敏错误。
 *
 * <p>从未成功安装时摘要与路径都为 null；安装失败可以保留上一次成功安装的摘要与路径，同时携带错误。
 */
public record DaemonSkillStatePackage(
    String packageName, String installedSha256, String localPath, String error) {

  public DaemonSkillStatePackage {
    packageName = Objects.requireNonNull(packageName, "packageName");
  }
}
