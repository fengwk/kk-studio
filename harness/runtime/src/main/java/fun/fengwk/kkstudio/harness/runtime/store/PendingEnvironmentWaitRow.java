package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 环境等待分页的 storage 投影：按 {@code (真实执行根, 冻结的所需环境)} 聚合的一组待领取 TOOL 调用。
 *
 * <p>分组代表取组内最早的 {@code (createdAt, invocationId)}，只用于稳定 keyset 排序与游标推进，不冒充任何可操作的 interaction
 * 主键；真实等待身份由 {@code rootThreadId + environmentId} 表达。投影只描述「调用已 READY、其 TOOL Work 已到期且无有效执行
 * lease、所需环境没有有效 READY 连接租约」这一读取事实，不新增任何持久等待状态。
 */
public record PendingEnvironmentWaitRow(
    UUID rootThreadId,
    EnvironmentId environmentId,
    Instant representativeCreatedAt,
    UUID representativeInvocationId,
    int waitingCount) {

  public PendingEnvironmentWaitRow {
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(representativeCreatedAt, "representativeCreatedAt");
    Objects.requireNonNull(representativeInvocationId, "representativeInvocationId");
    if (waitingCount <= 0) {
      throw new IllegalArgumentException("waitingCount must be positive");
    }
  }
}
