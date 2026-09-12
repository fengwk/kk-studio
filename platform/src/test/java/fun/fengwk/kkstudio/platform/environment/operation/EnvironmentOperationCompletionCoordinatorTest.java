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
import fun.fengwk.kkstudio.platform.environment.skill.OperationPublishOutcome;
import fun.fengwk.kkstudio.platform.environment.skill.SkillInventoryPublisher;

import java.util.List;
import java.util.UUID;

/** 验证 {@link EnvironmentOperationCompletionCoordinatorImpl} 对结果的解析、安全过滤与终结编排。 */
class EnvironmentOperationCompletionCoordinatorTest {

  private SkillInventoryPublisher publisher;
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
    publisher = mock(SkillInventoryPublisher.class);
    repository = mock(EnvironmentOperationRepository.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    coordinator =
        new EnvironmentOperationCompletionCoordinatorImpl(publisher, repository, objectMapper);
  }

  /** 测试意图：验证当 capability 返回业务错误时，协调器提取错误码，固定使用安全常量消息，终结为 FAILED。 */
  @Test
  void coordinateResultWithErrorCallsPublisherFailureWithConstantMessage() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            "call-1", "INVALID_INPUT", "Sensitive daemon path /secret");

    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq("INVALID_INPUT"),
            eq(EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE),
            eq("{}")))
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
            "INVALID_INPUT",
            EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE,
            "{}");
  }

  /** 测试意图：验证成功结果必须是且仅是单个 JsonResultContent 且解码为 Snapshot，提取安全计数摘要后调用 publishOperationSuccess。 */
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

    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json("call-1", json);

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
            "call-1", List.of(new TextResultContent("not-json")), false, "{}");

    when(publisher.publishOperationFailure(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(1L),
            eq(sourceId),
            eq(2L),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT),
            eq(EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE),
            eq("{}")))
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
            EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE,
            "{}");
  }

  /** 测试意图：验证 coordinateUnknown 正确收敛至 UNKNOWN 状态。 */
  @Test
  void coordinateUnknownCallsRepositoryMarkUnknown() {
    when(repository.markUnknown(
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE))
        .thenReturn(true);

    boolean ok =
        coordinator.coordinateUnknown(
            opId,
            nodeId,
            leaseToken,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
            EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE);

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
