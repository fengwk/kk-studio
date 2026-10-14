package fun.fengwk.kkstudio.platform.environment.update;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 受管更新操作的持久仓库窄端口。
 *
 * <p>「一个 Environment 同一时刻至多一次活动更新」由数据库部分唯一索引强制：{@link #insertPending} 依赖该约束表达原子准入，
 * 因此并发请求中至多一个成功。阶段推进是条件更新（只允许指定来源阶段），避免并发/重放把阶段回退。
 */
public interface EnvironmentUpdateRepository {

  Optional<EnvironmentUpdateOperation> find(String operationId);

  /** 该 Environment 当前的活动操作（PENDING/RUNNING/PREPARED），没有则为空。 */
  Optional<EnvironmentUpdateOperation> findActive(EnvironmentId environmentId);

  /** 该 Environment 最近一次操作（含终态），没有则为空。 */
  Optional<EnvironmentUpdateOperation> findLatest(EnvironmentId environmentId);

  /** 该 Environment 的操作历史，按创建时间倒序，至多 {@code limit} 条。 */
  List<EnvironmentUpdateOperation> list(EnvironmentId environmentId, int limit);

  /** 原子持久化一次 PENDING 准入；已有活动操作或 operationId 重复时返回 false。 */
  boolean insertPending(EnvironmentUpdateOperation operation);

  /**
   * 条件推进阶段：只有当前阶段在 {@code allowedFrom} 中时才更新，返回是否命中。
   *
   * @param error 终态失败说明；成功推进时为 null
   */
  boolean advance(
      String operationId,
      EnvironmentUpdatePhase next,
      String error,
      Set<EnvironmentUpdatePhase> allowedFrom);
}
