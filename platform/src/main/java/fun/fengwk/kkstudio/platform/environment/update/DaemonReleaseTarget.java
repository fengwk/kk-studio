package fun.fengwk.kkstudio.platform.environment.update;

import java.util.Objects;

/** 一个已验证可用的官方 Daemon 发布目标：目标版本、官方制品 URL 与 SHA256。 */
public record DaemonReleaseTarget(String targetVersion, String artifactUrl, String artifactSha256) {

  public DaemonReleaseTarget {
    targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
    artifactUrl = Objects.requireNonNull(artifactUrl, "artifactUrl");
    artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256");
  }
}
