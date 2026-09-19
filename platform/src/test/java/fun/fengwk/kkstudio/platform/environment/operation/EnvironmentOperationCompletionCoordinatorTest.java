package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.UUID;

/** 验证 {@link EnvironmentOperationCompletionCoordinatorImpl} 对结果的解析、安全过滤与终结编排。 */
class EnvironmentOperationCompletionCoordinatorTest {

  private McpDiscoveryResultPublisher mcpDiscoveryResultPublisher;
  private EnvironmentOperationRepository repository;
  private EnvironmentOperationCompletionCoordinator coordinator;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID resourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mcpDiscoveryResultPublisher = mock(McpDiscoveryResultPublisher.class);
    repository = mock(EnvironmentOperationRepository.class);
    coordinator =
        new EnvironmentOperationCompletionCoordinatorImpl(mcpDiscoveryResultPublisher, repository);
  }

  /** 测试意图：验证当传入 null 结果时，直接收敛为 INVALID_RESULT，绝不抛出异常等待超时。 */
  @Test
  void coordinateResultWithNullResultPersistsInvalidResult() {
    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            null);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE));
  }

  /** 测试意图：验证返回结果 callId 为 null 时，直接收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithNullCallIdCallsPublisherInvalidResult() {
    EnvironmentCapabilityResult result = mock(EnvironmentCapabilityResult.class);
    when(result.callId()).thenReturn(null);

    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE));
  }

  /** 测试意图：验证返回结果 callId 与 operation UUID 不匹配时，按 INVALID_RESULT 安全失败终结。 */
  @Test
  void coordinateResultWithCallIdMismatchCallsPublisherInvalidResult() {
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json("wrong-call-id", "{\"tools\":[]}");

    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE));
  }

  /** 测试意图：验证在检查 error 之前先验证 callId，若 callId 不匹配即使 error=true 也收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithErrorAndCallIdMismatchPrefersInvalidResult() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            "wrong-call-id", "DAEMON_ERROR", "Sensitive daemon message");

    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            errorResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE));
  }

  /** 测试意图：验证当 daemon 报告 error=true 时，固化调用 publishDiscoveryFailure 为 OPERATION_FAILED。 */
  @Test
  void coordinateResultWithErrorPersistsOperationFailed() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            opId.toString(), "DAEMON_ERROR", "Sensitive daemon path /secret");

    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            errorResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(resourceId),
            eq(0L),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE));
  }

  /** 测试意图：验证 MCP_SERVER_DISCOVER 成功委托至 McpDiscoveryResultPublisher。 */
  @Test
  void coordinateMcpDiscoverResultSuccessDelegatesToMcpPublisher() {
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), "{\"tools\":[]}");
    when(mcpDiscoveryResultPublisher.publishDiscoverySuccess(
            envId, opId, nodeId, leaseToken, resourceId, 0L, result))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}",
            result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoverySuccess(envId, opId, nodeId, leaseToken, resourceId, 0L, result);
  }

  /** 测试意图：验证 coordinateExecutionFailure 协调收敛至 McpDiscoveryResultPublisher。 */
  @Test
  void coordinateExecutionFailureDelegatesToMcpPublisher() {
    when(mcpDiscoveryResultPublisher.publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            resourceId,
            0L,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateExecutionFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationType.MCP_SERVER_DISCOVER,
            EnvironmentOperationResourceType.MCP_SERVER,
            resourceId,
            0L,
            "{}");

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(mcpDiscoveryResultPublisher)
        .publishDiscoveryFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            resourceId,
            0L,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
  }

  /** 测试意图：验证 coordinateTransportUnknown 将未决操作收敛至 UNKNOWN (TRANSPORT_ERROR)。 */
  @Test
  void coordinateTransportUnknownCallsRepositoryMarkUnknown() {
    when(repository.markUnknown(
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE))
        .thenReturn(true);

    boolean ok = coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);

    assertTrue(ok);
    verify(repository)
        .markUnknown(
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE);
  }
}
