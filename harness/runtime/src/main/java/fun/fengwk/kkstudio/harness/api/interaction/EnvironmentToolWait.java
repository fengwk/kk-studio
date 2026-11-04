package fun.fengwk.kkstudio.harness.api.interaction;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 单调用冻结环境与 Work 事实；环境名称和连接状态由上层环境服务判断。 */
public record EnvironmentToolWait(
    UUID invocationId,
    EnvironmentId environmentId,
    boolean invocationReady,
    boolean workAvailable,
    Instant workFreshnessAt) {

  public EnvironmentToolWait {
    Objects.requireNonNull(invocationId, "invocationId");
    // environmentId 为 null 表示该调用没有冻结环境亲和性（server-side 工具）。
    if (workAvailable && (!invocationReady || environmentId == null)) {
      throw new IllegalArgumentException(
          "available environment work requires a ready invocation and a frozen environment");
    }
  }
}
