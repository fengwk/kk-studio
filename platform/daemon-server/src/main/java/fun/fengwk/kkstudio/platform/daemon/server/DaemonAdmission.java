package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Duration;
import java.util.Objects;

/** Daemon 准入裁决结果。 */
public sealed interface DaemonAdmission {

  /** 一次握手的准入凭据，以对象身份区分同一 Daemon 实例的重叠握手。 */
  final class Admitted implements DaemonAdmission {
    private final EnvironmentId environmentId;
    private final String daemonInstanceId;

    public Admitted(EnvironmentId environmentId, String daemonInstanceId) {
      this.environmentId = Objects.requireNonNull(environmentId, "environmentId must not be null");
      this.daemonInstanceId =
          Objects.requireNonNull(daemonInstanceId, "daemonInstanceId must not be null");
    }

    public EnvironmentId environmentId() {
      return environmentId;
    }

    public String daemonInstanceId() {
      return daemonInstanceId;
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
