package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个「等待环境」只读条目：按 {@code (真实执行根, 所需环境)} 聚合的一组待领取 TOOL 调用的只读投影。
 *
 * <p>它是与 {@link PendingInteraction} 并列的只读事实，不复制待办台账、不落地持久等待状态。条目身份是 {@code rootThreadId +
 * environmentId}；{@code representativeCreatedAt}/{@code representativeInvocationId}
 * 只是组内最早的排序/游标代表，调用方不得把它当作可操作的 interaction 主键或审批目标。
 */
public record PendingEnvironmentWait(
    UUID rootThreadId,
    EnvironmentId environmentId,
    Instant representativeCreatedAt,
    UUID representativeInvocationId,
    int waitingCount) {

  public PendingEnvironmentWait {
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(representativeCreatedAt, "representativeCreatedAt");
    Objects.requireNonNull(representativeInvocationId, "representativeInvocationId");
    if (waitingCount <= 0) {
      throw new IllegalArgumentException("waitingCount must be positive");
    }
  }
}
