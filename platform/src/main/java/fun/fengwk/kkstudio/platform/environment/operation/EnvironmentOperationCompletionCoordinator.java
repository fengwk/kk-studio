package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.environment.skill.OperationPublishOutcome;

import java.util.UUID;

/** 协调 Daemon Capability 执行结果并原子终结操作记录与发布 Skill inventory。 */
public interface EnvironmentOperationCompletionCoordinator {

  /** 协调 Capability 执行返回结果（包括正常返回的成功与错误响应）。 */
  OperationPublishOutcome coordinateResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long sourceSetVersion,
      UUID sourceId,
      long sourceVersion,
      EnvironmentCapabilityResult result);

  /** 协调已知失败异常（如能力执行异常、取消或传输层失败）。 */
  OperationPublishOutcome coordinateFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long sourceSetVersion,
      UUID sourceId,
      long sourceVersion,
      String failureCode,
      String failureMessage);

  /** 协调不确定性断连或超时收敛至 UNKNOWN 状态。 */
  boolean coordinateUnknown(
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      String failureCode,
      String failureMessage);
}
