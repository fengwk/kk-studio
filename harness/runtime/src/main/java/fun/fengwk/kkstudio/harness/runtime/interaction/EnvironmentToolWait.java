package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个 TOOL 调用的「环境等待」只读投影：该调用冻结的所需环境（server-side 工具为 null），以及此刻是否在等待该环境上线。
 *
 * <p>它与 {@link PendingEnvironmentWait} 读同一 Work 事实，只是不做聚合：工具行据此展示「等待环境 X 上线」而不需要从 {@code (真实执行根,
 * 环境)} 分组反推具体调用。它不是可操作 interaction，也不落地任何等待状态。
 *
 * <p>{@code environmentName} 是冻结环境身份的权威名称（可空），不取 Thread 当前设置；{@code freshnessAt} 是该 READY
 * 环境调用下一次可能因时间失效的边界（可空），不是全局交互列表截止点，也不推进 Thread version。
 */
public record EnvironmentToolWait(
    UUID invocationId,
    EnvironmentId environmentId,
    boolean waitingForEnvironment,
    String environmentName,
    Instant freshnessAt) {

  public EnvironmentToolWait {
    Objects.requireNonNull(invocationId, "invocationId");
    // environmentId 为 null 表示该调用没有冻结环境亲和性（server-side 工具）。
    if (waitingForEnvironment && environmentId == null) {
      throw new IllegalArgumentException(
          "waiting tool invocation must freeze a required environment");
    }
  }
}
