package fun.fengwk.kkstudio.platform.environment.update;

import java.util.Objects;

/** 一个已验证可用的官方 Daemon 发布目标：目标版本取自运行中 Platform 的打包版本。 */
public record DaemonReleaseTarget(String targetVersion) {

  public DaemonReleaseTarget {
    targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
  }
}
