package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个 TOOL 调用的「环境等待」只读快照行（工具行投影）。
 *
 * <p>它是 {@link PendingEnvironmentWaitRow} 的单调用视图：同一 Work 冻结事实下，给出该调用冻结的 {@code
 * requiredEnvironmentId}（server-side 工具没有冻结环境，为 null），以及它此刻是否在等待该环境上线。因此工具行不再需要按 {@code (真实执行根,
 * 环境)} 聚合反推某个具体调用，也不需要新增任何持久等待状态。
 *
 * <p>{@code environmentName} 沿冻结身份读取环境目录，可空；{@code freshnessAt} 仅对 READY 环境调用取未来有效 READY 连接租约、Work
 * availableAt 和 leaseUntil 的最小值，非 READY 或无未来边界为 null。两者都是读取时事实。
 */
public record EnvironmentToolWaitRow(
    UUID invocationId,
    EnvironmentId environmentId,
    boolean waitingForEnvironment,
    String environmentName,
    Instant freshnessAt) {

  public EnvironmentToolWaitRow {
    Objects.requireNonNull(invocationId, "invocationId");
    // environmentId 为 null 表示该调用没有冻结环境亲和性（server-side 工具），不是未知状态。
    if (waitingForEnvironment && environmentId == null) {
      throw new IllegalArgumentException(
          "waiting tool invocation must freeze a required environment");
    }
  }
}
