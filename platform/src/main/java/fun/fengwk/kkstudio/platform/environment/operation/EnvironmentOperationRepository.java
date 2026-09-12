package fun.fengwk.kkstudio.platform.environment.operation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * environment_operation PostgreSQL 仓库与原子状态机契约（包内私有）。
 *
 * <p>所有状态推进与截止时间比较均在 PostgreSQL 服务端以 {@code statement_timestamp()} 执行，避免应用节点与数据库时钟偏差。
 */
interface EnvironmentOperationRepository {

  /**
   * 插入一条完整的 PENDING 行。
   *
   * @param command 创建参数
   * @return 创建成功的操作行
   * @throws DuplicateActiveOperationException 同一 (environment, source) 已存在活动操作 (PENDING/RUNNING)
   * @throws fun.fengwk.kkstudio.platform.error.AiValidationException 参数形状或 JSON 非法、或截止时间早于数据库当前时间
   * @throws fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException 目标环境不存在
   */
  EnvironmentOperation createPending(CreatePendingOperationCommand command);

  /**
   * 根据 ID 查询操作详情（内部使用，包含私有 arguments）。
   *
   * @param id 操作 ID
   * @return 操作详情（若不存在返回 empty）
   */
  Optional<EnvironmentOperation> findById(UUID id);

  /**
   * 根据 ID 获取操作详情；若不存在抛出 404 异常。
   *
   * @param id 操作 ID
   * @return 操作详情
   */
  EnvironmentOperation getById(UUID id);

  /**
   * 按 Environment 列出操作历史（created_at 降序，id 降序），受 limit 约束（内部使用，包含 arguments）。
   *
   * @param environmentId 目标环境 ID
   * @param limit 最大返回行数
   * @return 操作历史列表
   */
  List<EnvironmentOperation> listByEnvironment(UUID environmentId, int limit);

  /**
   * 按 Environment 列出安全操作历史（created_at 降序，id 降序，物理不含 arguments/leaseToken/ownerNodeId）。
   *
   * @param environmentId 目标环境 ID
   * @param limit 最大返回行数
   * @return 安全投影历史列表
   */
  List<SafeEnvironmentOperation> listSafeByEnvironment(UUID environmentId, int limit);

  /**
   * 原子认领当前节点合格的 PENDING 操作至 RUNNING 状态。
   *
   * @param ownerNodeId 认领节点 ID
   * @param limit 最大认领条数
   * @return 本次成功认领并推进至 RUNNING 的操作列表
   */
  List<EnvironmentOperation> claimPending(UUID ownerNodeId, int limit);

  /**
   * 针对明确未发送的操作进行状态回滚或超时终结（由 operation id + RUNNING + ownerNodeId + leaseToken 围栏）。
   *
   * @param id 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 认领时的租约代币
   * @return 状态转换结果
   */
  RescheduleOutcome rescheduleUnsent(UUID id, UUID ownerNodeId, UUID leaseToken);

  /**
   * 权威 daemon 成功完成状态推进：由操作 RUNNING 认领元组与数据库中当前环境 READY 活跃连接共同围栏。 单终态。
   *
   * @param id 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 认领时的租约代币
   * @param resultSummaryJson 成功结果摘要 JSON 对象字符串（可空，空时存为 "{}"）
   * @return 是否成功推进至 SUCCEEDED
   */
  boolean markSucceeded(UUID id, UUID ownerNodeId, UUID leaseToken, String resultSummaryJson);

  /**
   * 权威 daemon 失败状态推进：由操作 RUNNING 认领元组与数据库中当前环境 READY 活跃连接共同围栏。 单终态。
   *
   * @param id 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 认领时的租约代币
   * @param failureCode 失败分类码
   * @param failureMessage 失败描述
   * @return 是否成功推进至 FAILED
   */
  boolean markFailed(
      UUID id, UUID ownerNodeId, UUID leaseToken, String failureCode, String failureMessage);

  /**
   * 路由断开或丢失后的状态推进为 UNKNOWN：仅需操作本身的 RUNNING 认领元组围栏。 单终态。
   *
   * @param id 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 认领时的租约代币
   * @param failureCode 失败分类码
   * @param failureMessage 失败描述
   * @return 是否成功推进至 UNKNOWN
   */
  boolean markUnknown(
      UUID id, UUID ownerNodeId, UUID leaseToken, String failureCode, String failureMessage);

  /**
   * 取消未发送的 PENDING 操作：仅允许 PENDING -> CANCELLED。 若操作已被认领为 RUNNING 或已终结，返回 false。
   *
   * @param id 操作 ID
   * @return 是否成功取消
   */
  boolean cancelPending(UUID id);

  /**
   * 清扫超期的 PENDING 操作 -> FAILED (ENVIRONMENT_UNAVAILABLE_TIMEOUT)。
   *
   * @return 清扫条数
   */
  int sweepExpiredPending();

  /**
   * 清扫超期的 RUNNING 操作 -> UNKNOWN (RESULT_TIMEOUT)，保留认领元组。
   *
   * @return 清扫条数
   */
  int sweepExpiredRunning();

  /**
   * 清扫所有超期的 PENDING 与 RUNNING 操作。
   *
   * @return 清扫结果汇总
   */
  DeadlineSweepResult sweepExpired();

  /**
   * 节点优雅停机时将该节点持有的所有 RUNNING 操作原子标记为 UNKNOWN（使用固定常量 DISPATCHER_SHUTDOWN）。
   *
   * @param ownerNodeId 停机节点 ID
   * @return 受影响条数
   */
  int markRunningUnknownOnShutdown(UUID ownerNodeId);
}
