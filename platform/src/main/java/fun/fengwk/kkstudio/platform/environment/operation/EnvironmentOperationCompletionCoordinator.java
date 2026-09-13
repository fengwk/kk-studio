package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.UUID;

/** 协调 Daemon Capability 执行结果并原子终结操作记录与发布 Skill inventory（包内私有）。 */
interface EnvironmentOperationCompletionCoordinator {

  /** 协调 Capability 执行返回结果（包括正常返回的成功与错误响应）。 */
  OperationPublishOutcome coordinateResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments,
      EnvironmentCapabilityResult result);

  /** 协调确定性执行失败（如远程报告失败或取消）。 */
  OperationPublishOutcome coordinateExecutionFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments);

  /** 协调传输层不确定性错误或网络断开至 UNKNOWN 状态。 */
  boolean coordinateTransportUnknown(UUID operationId, UUID ownerNodeId, UUID leaseToken);
}
