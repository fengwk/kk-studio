package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  /** 测试意图：验证当传入 null 结果时，直接收敛为 INVALID_RESULT，绝不抛出异常等待超时。 */
  @Test
  void coordinateResultWithNullResultPersistsInvalidResult() {
    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, null);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证当 daemon 报告 error=true 时，固化调用 publishExecutionFailure。 */
  @Test
  void coordinateResultWithErrorPersistsConstantOperationFailed() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            opId.toString(), "DAEMON_CONTROLLED_CODE", "Sensitive daemon path /secret");

    when(publisher.publishExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, errorResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证在检查 error 之前先验证 callId，若 callId 不匹配即使 error=true 也收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithErrorAndCallIdMismatchPrefersInvalidResult() {
    EnvironmentCapabilityResult errorResult =
        EnvironmentCapabilityResult.codedError(
            "wrong-call-id", "DAEMON_CONTROLLED_CODE", "Sensitive daemon path /secret");

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(
            envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, errorResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证返回结果的 callId 与 operation UUID 不匹配时，按 INVALID_RESULT 安全失败终结。 */
  @Test
  void coordinateResultWithCallIdMismatchCallsPublisherFailureWithInvalidResult() {
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json("wrong-call-id", "{}");

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证成功结果是单个 JsonResultContent 且解码为 Snapshot 时调用 publishOperationSuccess。 */
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
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(snapshot)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishOperationSuccess(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(snapshot));
  }

  /** 测试意图：验证非单个 JsonResultContent（如 Text 结果）按 INVALID_RESULT 终结为 FAILED。 */
  @Test
  void coordinateResultWithInvalidContentFormatCallsPublisherFailureWithInvalidResult() {
    EnvironmentCapabilityResult textResult =
        new EnvironmentCapabilityResult(
            opId.toString(), List.of(new TextResultContent("not-json")), false, "{}");

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, textResult);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证 coordinateExecutionFailure 调用发布器终结为常量 OPERATION_FAILED。 */
  @Test
  void coordinateExecutionFailureCallsPublisherWithConstantFailed() {
    when(publisher.publishExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateExecutionFailure(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
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

  /** 测试意图：验证返回结果 callId 为 null 时（如第三方实现或 mock），直接收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithNullCallIdCallsPublisherInvalidResult() {
    EnvironmentCapabilityResult result = mock(EnvironmentCapabilityResult.class);
    when(result.callId()).thenReturn(null);

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证返回的 JSON 无法解析（抛出 JsonProcessingException）时，捕获异常并收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithMalformedJsonCallsPublisherInvalidResult() throws Exception {
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.readTree(any(String.class)))
        .thenThrow(new JsonParseException(null, "invalid json"));
    EnvironmentOperationCompletionCoordinator coord =
        new EnvironmentOperationCompletionCoordinatorImpl(publisher, repository, failingMapper);

    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), "{\"raw\":\"json\"}");

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coord.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证返回的 JSON 结构不符合 Snapshot 契约（缺少必填字段）时，捕获异常并收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithNonSnapshotJsonCallsPublisherInvalidResult() {
    EnvironmentCapabilityResult result =
        EnvironmentCapabilityResult.json(opId.toString(), "{\"unrelated\":\"content\"}");

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证返回的快照中 sourceId 与操作所属来源标识不符时，识别身份不一致并收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithSourceIdMismatchCallsPublisherInvalidResult() {
    UUID differentSourceId = UUID.randomUUID();
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            differentSourceId,
            2L,
            "0123456789abcdef0123456789abcdef01234567",
            List.of(),
            List.of());
    String json = new DaemonSkillSourceSnapshotCodec().encodeNode(snapshot).toString();
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json(opId.toString(), json);

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }

  /** 测试意图：验证返回的快照中 sourceVersion 与操作版本不匹配时，识别版本不一致并收敛为 INVALID_RESULT。 */
  @Test
  void coordinateResultWithSourceVersionMismatchCallsPublisherInvalidResult() {
    long differentSourceVersion = 999L;
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            sourceId,
            differentSourceVersion,
            "0123456789abcdef0123456789abcdef01234567",
            List.of(),
            List.of());
    String json = new DaemonSkillSourceSnapshotCodec().encodeNode(snapshot).toString();
    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.json(opId.toString(), json);

    when(publisher.publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L)))
        .thenReturn(OperationPublishOutcome.APPLIED);

    OperationPublishOutcome outcome =
        coordinator.coordinateResult(envId, opId, nodeId, leaseToken, 1L, sourceId, 2L, result);

    assertEquals(OperationPublishOutcome.APPLIED, outcome);
    verify(publisher)
        .publishInvalidResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(1L), eq(sourceId), eq(2L));
  }
}
