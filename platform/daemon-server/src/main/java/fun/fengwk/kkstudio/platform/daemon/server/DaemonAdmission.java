package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Duration;
import java.util.Objects;

/** Daemon 准入裁决结果。 */
public sealed interface DaemonAdmission {

  /**
   * 准入成功。
   *
   * @param environmentId 绑定的环境标识
   * @param daemonInstanceId Daemon 实例唯一标识
   */
  record Admitted(EnvironmentId environmentId, String daemonInstanceId) implements DaemonAdmission {

    public Admitted {
      Objects.requireNonNull(environmentId, "environmentId must not be null");
      Objects.requireNonNull(daemonInstanceId, "daemonInstanceId must not be null");
    }
  }

  /** 准入被明确拒绝。 */
  record Rejected() implements DaemonAdmission {}

  /**
   * 暂不可准入，建议稍后重试。
   *
   * @param retryAfter 建议重试等待时长
   */
  record RetryLater(Duration retryAfter) implements DaemonAdmission {

    public RetryLater {
      Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }
  }
}
