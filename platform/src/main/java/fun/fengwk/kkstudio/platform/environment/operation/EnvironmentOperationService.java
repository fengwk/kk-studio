package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.util.UUID;

/** Platform 侧异步 Environment Skill 来源管理操作服务接口。 */
public interface EnvironmentOperationService {

  /**
   * 创建异步 Skill 来源管理操作。
   *
   * @param environmentId 目标环境 ID
   * @param sourceId 目标来源 ID
   * @param operationType 操作类型
   * @param request 创建请求（包含 timeoutMillis）
   * @return 创建成功的操作安全视图 DTO
   */
  EnvironmentOperationDTO create(
      EnvironmentId environmentId,
      UUID sourceId,
      EnvironmentOperationType operationType,
      EnvironmentOperationCreateDTO request);

  /**
   * 查询单个操作安全视图。
   *
   * @param environmentId 目标环境 ID
   * @param operationId 操作 ID
   * @return 操作安全视图 DTO
   */
  EnvironmentOperationDTO get(EnvironmentId environmentId, UUID operationId);

  /**
   * 取消未认领的 PENDING 操作。
   *
   * @param environmentId 目标环境 ID
   * @param operationId 操作 ID
   * @return 取消后的操作安全视图 DTO
   */
  EnvironmentOperationDTO cancel(EnvironmentId environmentId, UUID operationId);
}
