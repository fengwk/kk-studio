package fun.fengwk.kkstudio.platform.daemon;

import java.util.Objects;

/**
 * 一条期望技能包事实：包名、归档摘要、归档字节数与预签名下载地址。
 *
 * <p>连接级控制消息只下发这些事实：不含 Git 地址、分支或访问令牌，Daemon 不需要也不应该知道技能来源。
 */
public record DaemonSkillPackage(
    String packageName, String sha256, long sizeBytes, String downloadUrl) {

  public DaemonSkillPackage {
    packageName = Objects.requireNonNull(packageName, "packageName");
    sha256 = Objects.requireNonNull(sha256, "sha256");
    downloadUrl = Objects.requireNonNull(downloadUrl, "downloadUrl");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
  }
}
