package fun.fengwk.kkstudio.harness.spi.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 单调用冻结环境、调用状态及数据库时间下的 Work 可领取性和下一时间边界。 */
public record EnvironmentToolWaitRow(
    UUID invocationId,
    EnvironmentId environmentId,
    boolean invocationReady,
    boolean workAvailable,
    Instant workFreshnessAt) {

  public EnvironmentToolWaitRow {
    Objects.requireNonNull(invocationId, "invocationId");
    // environmentId 为 null 表示该调用没有冻结环境亲和性（server-side 工具），不是未知状态。
    if (workAvailable && (!invocationReady || environmentId == null)) {
      throw new IllegalArgumentException(
          "available environment work requires a ready invocation and a frozen environment");
    }
  }
}
