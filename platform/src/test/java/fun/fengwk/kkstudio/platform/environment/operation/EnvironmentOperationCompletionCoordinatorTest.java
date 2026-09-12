package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;

import java.util.List;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationCompletionCoordinatorImpl} 对结果的解析、安全过滤与终结编排。 */
class EnvironmentOperationCompletionCoordinatorTest {

  private EnvironmentOperationResultPublisher publisher;
  private EnvironmentOperationRepository repository;
  private ObjectMapper objectMapper;
  private EnvironmentOperationCompletionCoordinator coordinator;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    publisher = mock(EnvironmentOperationResultPublisher.class);
    repository = mock(EnvironmentOperationRepository.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    coordinator =
        new EnvironmentOperationCompletionCoordinatorImpl(publisher, repository, objectMapper);
  }

  /** 测试意图：验证当 daemon 报告 error=true 时，始终固化使用安全常量 OPERATION_FAILED，严禁透传 daemon 控制的 details.code。 */
  @Test
  void coordinateResultWithErrorPersistsConstantOperationFailed() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            opId.toString(), "DAEMON_CONTROLLED_CODE", "Sensitive daemon path /secret");

    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, errorResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishOperationFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            1L,
            sourceId,
            2L,
            EnvironmentOperationFailureCodes.OPERATION_FAILED,
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
  }

  /** 测试意图：验证返回结果的 callId 与 operation UUID 不匹配时，按 INVALID_RESULT 安全失败终结。 */
  @Test
  void coordinateResultWithCallIdMismatchCallsPublisherFailureWithInvalidResult() {
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json("wrong-call-id", "{}");

    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishOperationFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            1L,
            sourceId,
            2L,
            EnvironmentOperationFailureCodes.INVALID_RESULT,
            EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
  }

  /** 测试意图：验证成功结果是单个 JsonResultContent 且解码为 Snapshot 时，提取安全计数摘要后调用 publishOperationSuccess。 */
  @Test
  void coordinateResultWithValidSnapshotCallsPublisherSuccess() {
    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            sourceId, 2L, "my-skill", "desc", "/skills/my-skill", "0".repeat(64));
    DaemonSkillDiagnostic diag = new DaemonSkillDiagnostic("/skills", "ok");
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            sourceId,
            2L,
            "0123456789abcdef0123456789abcdef01234567",
            List.of(skill),
            List.of(diag));

    DaemonSkillSourceSnapshotCodec codec = new DaemonSkillSourceSnapshotCodec();
    String json = codec.encodeNode(snapshot).toString();

    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json(opId.toString(), json);

    when(publisher.publishOperationSuccess(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(snapshot), anyString()))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);

    ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
    verify(publisher)
        .publishOperationSuccess(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(snapshot),
            summaryCaptor.capture());

    String summary = summaryCaptor.getValue();
    assertTrue(summary.contains("\"skillCount\":1"));
    assertTrue(summary.contains("\"diagnosticCount\":1"));
    assertTrue(summary.contains("\"sourceRevision\":\"0123456789abcdef0123456789abcdef01234567\""));
  }

  /** 测试意图：验证非单个 JsonResultContent（如 Text 结果）按 INVALID_RESULT 终结为 FAILED。 */
  @Test
  void coordinateResultWithInvalidContentFormatCallsPublisherFailureWithInvalidResult() {
    EnvironmentCapabilityResult textResult =
        new EnvironmentCapabilityResult(
            opId.toString(), List.of(new TextResultContent("not-json")), false, "{}");

    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, textResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishOperationFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            1L,
            sourceId,
            2L,
            EnvironmentOperationFailureCodes.INVALID_RESULT,
            EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
  }

  /** 测试意图：验证 coordinateExecutionFailure 调用发布器终结为常量 OPERATION_FAILED。 */
  @Test
  void coordinateExecutionFailureCallsPublisherWithConstantFailed() {
    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateExecutionFailure(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishOperationFailure(
            envId,
            opId,
            nodeId,
            leaseToken,
            1L,
            sourceId,
            2L,
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
