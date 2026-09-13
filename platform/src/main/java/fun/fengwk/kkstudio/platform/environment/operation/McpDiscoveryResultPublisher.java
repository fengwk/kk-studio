package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.UUID;

/**
 * MCP 资源操作结果发布器：在 MCP 操作终态推进时原子消费发现等结果。
 *
 * <p>实现必须在推进操作终态的同一围栏/事务内消费 discovery 结果，避免“先 SUCCEEDED、后无围栏写入目录”的竞态。 未提供实现时 Platform 一律 fail
 * closed（见 {@link FailClosedMcpDiscoveryResultPublisher}）。
 */
public interface McpDiscoveryResultPublisher {

  /**
   * 当 MCP 发现成功返回结果时调用。
   *
   * @param environmentId 环境 ID
   * @param operationId 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 租约 Token
   * @param resourceId MCP Server 资源 ID
   * @param resourceVersion MCP Server 期望版本
   * @param result Daemon 返回的能力结果（包含 discovered tools/prompts/resources JSON）
   * @return 发布结果（APPLIED 或 LEASE_LOST / RESOURCE_CHANGED）
   */
  OperationPublishOutcome publishDiscoverySuccess(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      EnvironmentCapabilityResult result);

  /**
   * 当 MCP 发现执行失败（或返回 error / invalid result）时调用。
   *
   * @param environmentId 环境 ID
   * @param operationId 操作 ID
   * @param ownerNodeId 认领节点 ID
   * @param leaseToken 租约 Token
   * @param resourceId MCP Server 资源 ID
   * @param resourceVersion MCP Server 期望版本
   * @param failureCode 失败码
   * @param failureMessage 失败摘要
   * @return 发布结果
   */
  OperationPublishOutcome publishDiscoveryFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      String failureCode,
      String failureMessage);
}
