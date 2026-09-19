package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.util.List;
import java.util.UUID;

/** Platform 侧异步 Environment 管理操作服务接口。 */
public interface EnvironmentOperationService {

  /**
   * 通用最小创建入口。
   *
   * @param environmentId 目标环境 ID
   * @param operationType 操作类型
   * @param resourceType 目标资源类型
   * @param resourceId 目标资源 ID
   * @param resourceVersion 目标资源行版本
   * @param arguments 严格私有 JSON 参数
   * @param parameterSummary 公开参数摘要 JSON（可为 null，默认 "{}"）
   * @param timeoutMillis 超时毫秒数
   * @return 创建成功的操作安全视图 DTO
   */
  EnvironmentOperationDTO createOperation(
      EnvironmentId environmentId,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments,
      String parameterSummary,
      long timeoutMillis);

  /**
   * 查询单个操作安全视图。
   *
   * @param environmentId 目标环境 ID
   * @param operationId 操作 ID
   * @return 操作安全视图 DTO
   */
  EnvironmentOperationDTO get(EnvironmentId environmentId, UUID operationId);

  /**
   * 按 Environment 倒序列出操作历史（安全视图，包含 limit 约束）。
   *
   * @param environmentId 目标环境 ID
   * @param limit 最大返回行数
   * @return 操作安全视图列表
   */
  List<EnvironmentOperationDTO> list(EnvironmentId environmentId, int limit);

  /**
   * 取消未认领的 PENDING 操作。
   *
   * @param environmentId 目标环境 ID
   * @param operationId 操作 ID
   * @return 取消后的操作安全视图 DTO
   */
  EnvironmentOperationDTO cancel(EnvironmentId environmentId, UUID operationId);
}
