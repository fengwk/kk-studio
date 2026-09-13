package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.UUID;

/**
 * 验证 {@link FailClosedMcpDiscoveryResultPublisher} 的 fail-closed 语义：在 MCP 目录持久化尚未接线时， 成功结果一律收敛为
 * FAILED(INVALID_RESULT)，绝不允许操作进入 SUCCEEDED。
 */
class FailClosedMcpDiscoveryResultPublisherTest {

  private EnvironmentOperationRepository repository;
  private FailClosedMcpDiscoveryResultPublisher publisher;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID resourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repository = mock(EnvironmentOperationRepository.class);
    publisher = new FailClosedMcpDiscoveryResultPublisher(repository);
  }

  /** 测试意图：验证 discovery 成功结果在未接线目录持久化时 fail closed 为 FAILED(INVALID_RESULT)。 */
  @Test
  void publishDiscoverySuccess_failsClosedAsInvalidResult() {
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json(opId.toString(), "{}");
    when(repository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(true);

    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(envId, opId, nodeId, leaseToken, resourceId, 0L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(repository)
        .markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE));
    verifyNoMoreInteractions(repository);
  }

  /** 测试意图：验证 discovery 成功结果 fail closed 时租约失效返回 LEASE_LOST。 */
  @Test
  void publishDiscoverySuccess_leaseLost_returnsLeaseLost() {
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json(opId.toString(), "{}");
    when(repository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(false);

    OperationPublishOutcome outcome =
        publisher.publishDiscoverySuccess(envId, opId, nodeId, leaseToken, resourceId, 0L, result);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
  }

  /** 测试意图：验证 discovery 失败结果按给定失败码原子推进操作为 FAILED。 */
  @Test
  void publishDiscoveryFailure_marksFailedWithGivenCode() {
    when(repository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE)))
        .thenReturn(true);

    OperationPublishOutcome outcome =
        publisher.publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            resourceId,
            0L,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(repository)
        .markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE));
  }

  /** 测试意图：验证 discovery 失败结果在租约失效时返回 LEASE_LOST。 */
  @Test
  void publishDiscoveryFailure_leaseLost_returnsLeaseLost() {
    when(repository.markFailed(
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE)))
        .thenReturn(false);

    OperationPublishOutcome outcome =
        publisher.publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            resourceId,
            0L,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);

    assertEquals(OperationPublishOutcome.LEASE_LOST, outcome);
  }
}
