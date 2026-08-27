package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.ModelAttemptFailureProjection;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class HarnessRuntimeResponseMapperTest {

  private static final Instant NOW = HarnessRuntimeTestFixtures.NOW;
  private static final AgentMessageJsonCodec AGENT_MESSAGES = new AgentMessageJsonCodec();

  @Test
  void projectsEveryStableThreadRuntimeStatus() {
    // HTTP status 必须覆盖共享 ThreadRuntimeStatus 的完整 vocabulary，而不是自行重复推导。
    Map<ThreadRuntimeStatus, ThreadSnapshot> cases = new LinkedHashMap<>();
    cases.put(ThreadRuntimeStatus.IDLE, HarnessRuntimeTestFixtures.idleSnapshot());
    cases.put(ThreadRuntimeStatus.CONTINUATION_DUE, continuationDueSnapshot());
    cases.put(
        ThreadRuntimeStatus.MODEL_READY, modelSnapshot(ModelInvocationStatus.READY, List.of()));
    cases.put(
        ThreadRuntimeStatus.MODEL_DISPATCHING,
        modelSnapshot(ModelInvocationStatus.DISPATCHING, List.of()));
    cases.put(
        ThreadRuntimeStatus.MODEL_RUNNING, modelSnapshot(ModelInvocationStatus.RUNNING, List.of()));
    cases.put(ThreadRuntimeStatus.APPLYING, modelSnapshot(ModelInvocationStatus.FAILED, List.of()));
    cases.put(
        ThreadRuntimeStatus.TOOL_WAITING_APPROVAL,
        toolSnapshot(ToolInvocationStatus.WAITING_APPROVAL));
    cases.put(ThreadRuntimeStatus.TOOL_RUNNING, toolSnapshot(ToolInvocationStatus.RUNNING));
    cases.put(ThreadRuntimeStatus.TOOL_DISPATCHING, toolSnapshot(ToolInvocationStatus.DISPATCHING));
    cases.put(ThreadRuntimeStatus.TOOL_READY, toolSnapshot(ToolInvocationStatus.READY));

    for (Map.Entry<ThreadRuntimeStatus, ThreadSnapshot> entry : cases.entrySet()) {
      HarnessThreadDTO dto = HarnessRuntimeResponseMapper.toThreadDto(entry.getValue());
      assertEquals(entry.getKey().name(), dto.getStatus());
      assertEquals(entry.getKey().isProcessing(), dto.getProcessing());
    }

    HarnessThreadDTO terminalTool =
        HarnessRuntimeResponseMapper.toThreadDto(toolSnapshot(ToolInvocationStatus.FAILED));
    assertEquals(ThreadRuntimeStatus.APPLYING.name(), terminalTool.getStatus());
    assertTrue(terminalTool.getProcessing());
  }

  @Test
  void projectsThreadIdentitySettingsAndNullableEnvironment() {
    // Thread cursor、version、时间与 branch settings 必须来自同一 snapshot。
    HarnessThreadDTO dto =
        HarnessRuntimeResponseMapper.toThreadDto(HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals(idText(1), dto.getThreadId());
    assertEquals(idText(1), dto.getSessionId());
    assertEquals(idText(1), dto.getHeadEntryId());
    assertEquals("4", dto.getNextCommandSequence());
    assertEquals("3", dto.getVersion());
    assertTrue(dto.getYoloEnabled());
    assertEquals("env-1", dto.getBranchSettings().getEnvironment().getName());
    assertEquals(".", dto.getBranchSettings().getEnvironment().getWorkspacePath());
    assertEquals("default-assistant", dto.getBranchSettings().getAgentName());
    assertEquals("openai", dto.getBranchSettings().getModel().getProviderName());
    assertEquals("gpt-5", dto.getBranchSettings().getModel().getModelName());
    assertEquals("default", dto.getBranchSettings().getModel().getVariant());
    assertEquals(List.of("web_search"), dto.getBranchSettings().getActiveTools());
    assertEquals(NOW, dto.getCreateTime());
    assertEquals(NOW, dto.getUpdateTime());
    assertFalse(dto.getProcessing());

    ThreadSnapshot withoutEnvironment = idleSnapshot(settingsWithoutEnvironment());
    assertNull(
        HarnessRuntimeResponseMapper.toThreadDto(withoutEnvironment)
            .getBranchSettings()
            .getEnvironment());
  }

  @Test
  void projectsEntriesAndEveryCommandState() {
    // Entry parent 与 Command terminal marker 的 nullable 投影必须保持 domain 事实。
    HarnessSessionEntryDTO root =
        HarnessRuntimeResponseMapper.toEntryDto(HarnessRuntimeTestFixtures.rootEntry());
    HarnessSessionEntryDTO user =
        HarnessRuntimeResponseMapper.toEntryDto(HarnessRuntimeTestFixtures.userMessageEntry());
    assertNull(root.getParentEntryId());
    assertEquals("ROOT", root.getEntryType());
    assertEquals(idText(2), user.getParentEntryId());
    assertEquals("MESSAGE", user.getEntryType());

    ThreadCommand queued = HarnessRuntimeTestFixtures.queuedUserMessageCommand();
    ThreadCommand applied = queued.consume(id(2));
    ThreadCommand cancelled = queued.cancel(id(90), NOW.plusSeconds(1));
    HarnessThreadCommandDTO queuedDto = HarnessRuntimeResponseMapper.toCommandDto(queued);
    HarnessThreadCommandDTO appliedDto = HarnessRuntimeResponseMapper.toCommandDto(applied);
    HarnessThreadCommandDTO cancelledDto = HarnessRuntimeResponseMapper.toCommandDto(cancelled);

    assertEquals("QUEUED", queuedDto.getState());
    assertNull(queuedDto.getConsumedTurnStartEntryId());
    assertNull(queuedDto.getCancelledAt());
    assertEquals("APPLIED", appliedDto.getState());
    assertEquals(idText(2), appliedDto.getConsumedTurnStartEntryId());
    assertEquals("CANCELLED", cancelledDto.getState());
    assertEquals(NOW.plusSeconds(1), cancelledDto.getCancelledAt());
    assertEquals("4", queuedDto.getSequence());
    assertEquals(queued.requestHash(), queuedDto.getRequestHash());
    assertEquals(NOW, queuedDto.getCreateTime());
  }

  @Test
  void projectsModelInvocationCodecFieldsByLifecycleShape() {
    // RUNNING/SUCCEEDED/FAILED 三种合法形状共同覆盖 checkpoint/result/error/resultEntry nullable 契约。
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 2, "partial", "thinking");
    ModelInvocation running =
        modelInvocation(ModelInvocationStatus.RUNNING, checkpoint, null, null, null, 1, id(3));
    ModelInvocationDTO runningDto = HarnessRuntimeResponseMapper.toModelInvocationDto(running);
    assertEquals(
        checkpoint, new StreamCheckpointJsonCodec().decode(runningDto.getStreamCheckpointJson()));
    assertNull(runningDto.getResultJson());
    assertNull(runningDto.getErrorJson());
    assertNull(runningDto.getResultEntryId());

    ProviderResponse result = fullProviderResponse();
    ModelInvocation succeeded =
        modelInvocation(ModelInvocationStatus.SUCCEEDED, null, result, null, id(4), 1, id(3));
    ModelInvocationDTO succeededDto = HarnessRuntimeResponseMapper.toModelInvocationDto(succeeded);
    assertEquals(result, new ProviderResponseJsonCodec().decode(succeededDto.getResultJson()));
    assertEquals(idText(4), succeededDto.getResultEntryId());
    assertNull(succeededDto.getStreamCheckpointJson());
    assertNull(succeededDto.getErrorJson());

    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable");
    ModelInvocation failed =
        modelInvocation(ModelInvocationStatus.FAILED, null, null, error, null, 1, id(3));
    ModelInvocationDTO failedDto = HarnessRuntimeResponseMapper.toModelInvocationDto(failed);
    assertEquals(error, new ModelInvocationErrorJsonCodec().decode(failedDto.getErrorJson()));
    assertNull(failedDto.getStreamCheckpointJson());
    assertNull(failedDto.getResultJson());
    assertNull(failedDto.getResultEntryId());

    assertEquals(idText(10), failedDto.getId());
    assertEquals(idText(1), failedDto.getThreadId());
    assertEquals(idText(2), failedDto.getTurnStartEntryId());
    assertEquals(idText(3), failedDto.getBasisHeadEntryId());
    assertEquals("FAILED", failedDto.getStatus());
    assertEquals(1, failedDto.getAttempt());
    assertEquals(NOW, failedDto.getCreateTime());
    assertEquals(NOW.plusSeconds(1), failedDto.getUpdateTime());
  }

  @Test
  void projectsBoundAndUnboundToolInvocationShapes() {
    // 冻结 binding 的 environment/codec 字段与 unknown-tool fallback 必须分别完整投影。
    ToolApproval approval =
        ToolApproval.request(NOW, "requires approval")
            .decide(
                ToolApprovalDecision.ALLOWED,
                id(80),
                "user",
                "requires approval",
                NOW.plusSeconds(1));
    ToolResult result =
        new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{\"durationMs\":1}");
    ToolInvocation bound =
        toolInvocation(
            ToolInvocationStatus.SUCCEEDED, environmentToolBinding(), approval, result, null, 1);
    ToolInvocationDTO boundDto = HarnessRuntimeResponseMapper.toToolInvocationDto(bound);

    assertEquals("bash", boundDto.getToolName());
    assertEquals("1.0", boundDto.getToolVersion());
    assertEquals("bash", boundDto.getRendererKey());
    assertEquals("ENVIRONMENT", boundDto.getToolType());
    assertEquals("local", boundDto.getEnvironment().getName());
    assertEquals("workspace", boundDto.getEnvironment().getWorkspacePath());
    assertEquals(approval, new ToolApprovalJsonCodec().decode(boundDto.getApprovalJson()));
    assertEquals(result, ToolResultJsonCodec.decode(boundDto.getResultJson()));
    assertNull(boundDto.getErrorJson());

    ToolInvocationError error = new ToolInvocationError("FAILED", "unknown tool");
    ToolInvocation unbound =
        toolInvocation(ToolInvocationStatus.FAILED, null, null, null, error, 0);
    ToolInvocationDTO unboundDto = HarnessRuntimeResponseMapper.toToolInvocationDto(unbound);

    assertNull(unboundDto.getToolVersion());
    assertEquals("tool", unboundDto.getRendererKey());
    assertNull(unboundDto.getToolType());
    assertNull(unboundDto.getEnvironment());
    assertNull(unboundDto.getApprovalJson());
    assertNull(unboundDto.getResultJson());
    assertEquals(error, new ToolInvocationErrorJsonCodec().decode(unboundDto.getErrorJson()));
    assertEquals(idText(100), unboundDto.getId());
    assertEquals(idText(10), unboundDto.getModelInvocationId());
    assertEquals(idText(4), unboundDto.getAssistantEntryId());
    assertEquals(0, unboundDto.getOrdinal());
    assertEquals("call-1", unboundDto.getToolCallId());
    assertEquals("{\"x\":1}", unboundDto.getArgumentsJson());
    assertEquals(NOW, unboundDto.getCreateTime());
    assertEquals(NOW.plusSeconds(1), unboundDto.getUpdateTime());
  }

  @Test
  void projectsModelSnapshotQueuesFailuresAndDisabledCompaction() {
    // Model phase snapshot 必须按稳定顺序返回 entries/queue/checkpoint/failed attempts。
    ModelInvocation model =
        modelInvocation(
            ModelInvocationStatus.RUNNING,
            new StreamCheckpoint(1, 4, "partial", ""),
            null,
            null,
            null,
            1,
            id(3));
    ModelAttemptFailureProjection failure =
        new ModelAttemptFailureProjection(
            id(10),
            id(2),
            id(3),
            1,
            3,
            "first partial",
            "first thought",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "retry"),
            NOW,
            NOW.plusSeconds(2));
    ThreadSnapshot snapshot =
        modelSnapshot(
            model,
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            List.of(failure));

    HarnessThreadSnapshotDTO dto =
        HarnessRuntimeResponseMapper.toSnapshotDto(
            snapshot,
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.THREAD_BUSY));

    assertEquals("3", dto.getVersion());
    assertEquals(3, dto.getEntries().size());
    assertEquals(
        List.of("ROOT", "TURN_START", "MESSAGE"),
        dto.getEntries().stream().map(HarnessSessionEntryDTO::getEntryType).toList());
    assertEquals(1, dto.getQueuedCommands().size());
    assertEquals("RUNNING", dto.getModelInvocation().getStatus());
    assertTrue(dto.getToolInvocations().isEmpty());
    assertEquals(1, dto.getModelAttemptFailures().size());
    assertEquals("3", dto.getModelAttemptFailures().getFirst().getSequence());
    assertEquals("TRANSIENT", dto.getModelAttemptFailures().getFirst().getErrorCode());
    assertEquals("retry", dto.getModelAttemptFailures().getFirst().getErrorMessage());
    assertEquals(NOW, dto.getModelAttemptFailures().getFirst().getFailedAt());
    assertEquals(NOW.plusSeconds(2), dto.getModelAttemptFailures().getFirst().getRetryAt());
    assertFalse(dto.getManualCompaction().getAvailable());
    assertEquals("THREAD_BUSY", dto.getManualCompaction().getDisabledReason());
  }

  @Test
  void projectsToolSnapshotAndEnabledCompaction() {
    // Tool phase snapshot 必须同时暴露已成功 Model 与完整 sibling，并清空 disabledReason。
    ThreadSnapshot snapshot = toolSnapshot(ToolInvocationStatus.WAITING_APPROVAL);

    HarnessThreadSnapshotDTO dto =
        HarnessRuntimeResponseMapper.toSnapshotDto(
            snapshot, ManualCompactionAvailability.enabled());

    assertEquals(4, dto.getEntries().size());
    assertEquals("SUCCEEDED", dto.getModelInvocation().getStatus());
    assertEquals(1, dto.getToolInvocations().size());
    assertEquals("WAITING_APPROVAL", dto.getToolInvocations().getFirst().getStatus());
    assertTrue(dto.getModelAttemptFailures().isEmpty());
    assertTrue(dto.getManualCompaction().getAvailable());
    assertNull(dto.getManualCompaction().getDisabledReason());
  }

  @Test
  void projectsAcceptedCommandsAndRejectsThreadMismatch() {
    // Durable acceptance 结果与随后回读 snapshot 必须属于同一 Thread。
    AcceptedCommands accepted =
        new AcceptedCommands(
            HarnessRuntimeTestFixtures.session(),
            HarnessRuntimeTestFixtures.rootEntry(),
            HarnessRuntimeTestFixtures.thread(id(1)),
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            true);

    HarnessAcceptedCommandsDTO dto =
        HarnessRuntimeResponseMapper.toAcceptedCommandsDto(
            accepted, HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals(idText(1), dto.getSession().getSessionId());
    assertEquals(NOW, dto.getSession().getCreatedAt());
    assertEquals("ROOT", dto.getRootEntry().getEntryType());
    assertEquals(idText(1), dto.getThread().getThreadId());
    assertEquals(idText(50), dto.getAcceptedCommands().getFirst().getClientCommandId());
    assertTrue(dto.getReplayed());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toAcceptedCommandsDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
  }

  @Test
  void projectsCompactResultNullableInvocationAndRejectsThreadMismatch() {
    // Resolver 拒绝时 modelInvocationId 显式 null；接受时保持 invocation id。
    CompactThreadResult rejected =
        new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null);
    HarnessThreadCompactResultDTO rejectedDto =
        HarnessRuntimeResponseMapper.toCompactResultDto(
            rejected, HarnessRuntimeTestFixtures.idleSnapshot());
    assertEquals(idText(2), rejectedDto.getTurnStartEntryId());
    assertNull(rejectedDto.getModelInvocationId());

    CompactThreadResult accepted =
        new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), id(10));
    assertEquals(
        idText(10),
        HarnessRuntimeResponseMapper.toCompactResultDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot())
            .getModelInvocationId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toCompactResultDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
  }

  @Test
  void projectsStoppedIdleAndReplayedStopResults() {
    // STOPPED/IDLE/REPLAYED 三态与取消消息必须由 StopResult 唯一决定。
    StopResult stopped =
        new StopResult(
            false,
            HarnessRuntimeTestFixtures.thread(id(1)),
            id(5),
            2,
            List.of(
                new CancelledUserMessage(1, id(50), List.of(new TextMessageContent("first"))),
                new CancelledUserMessage(
                    2,
                    id(51),
                    List.of(new ResourceMessageContent(id(70), "report.txt", "preview")))));
    HarnessThreadStopResultDTO stoppedDto =
        HarnessRuntimeResponseMapper.toStopResultDto(
            stopped, HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals("STOPPED", stoppedDto.getStatus());
    assertEquals(idText(5), stoppedDto.getStoppedTurnEndEntryId());
    assertEquals(2, stoppedDto.getCancelledCommandCount());
    assertEquals(2, stoppedDto.getCancelledUserMessages().size());
    assertEquals(
        "first",
        ((TextMessageContent)
                AGENT_MESSAGES
                    .decode(stoppedDto.getCancelledUserMessages().getFirst().getMessageJson())
                    .contents()
                    .getFirst())
            .text());
    assertEquals(
        id(70),
        ((ResourceMessageContent)
                AGENT_MESSAGES
                    .decode(stoppedDto.getCancelledUserMessages().get(1).getMessageJson())
                    .contents()
                    .getFirst())
            .blobId());

    StopResult idle =
        new StopResult(false, HarnessRuntimeTestFixtures.thread(id(1)), null, 0, List.of());
    HarnessThreadStopResultDTO idleDto =
        HarnessRuntimeResponseMapper.toStopResultDto(
            idle, HarnessRuntimeTestFixtures.idleSnapshot());
    assertEquals("IDLE", idleDto.getStatus());
    assertNull(idleDto.getStoppedTurnEndEntryId());
    assertTrue(idleDto.getCancelledUserMessages().isEmpty());

    StopResult replay =
        new StopResult(true, stopped.thread(), stopped.stoppedTurnEndEntryId(), 0, List.of());
    assertEquals(
        "REPLAYED",
        HarnessRuntimeResponseMapper.toStopResultDto(
                replay, HarnessRuntimeTestFixtures.idleSnapshot())
            .getStatus());
  }

  @Test
  void rejectsStopSnapshotIdentityAndVersionMismatch() {
    // stop 后回读必须同时匹配 Thread id 与 version，不能返回另一时刻的快照。
    StopResult result =
        new StopResult(false, HarnessRuntimeTestFixtures.thread(id(1)), null, 0, List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toStopResultDto(
                result, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toStopResultDto(
                result, withVersion(HarnessRuntimeTestFixtures.idleSnapshot(), 4)));
  }

  private static ThreadSnapshot idleSnapshot(BranchSettings settings) {
    Entry root = new Entry(id(1), id(1), null, new RootPayload(settings), NOW);
    return new ThreadSnapshot(
        HarnessRuntimeTestFixtures.thread(id(1)),
        new EntryPath(List.of(root)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot continuationDueSnapshot() {
    Entry turnStart =
        new Entry(
            id(2),
            id(1),
            id(1),
            new TurnStartPayload(
                TurnStartReason.INPUT, HarnessRuntimeTestFixtures.settings(), id(1)),
            NOW);
    Entry turnEnd =
        new Entry(
            id(5),
            id(1),
            id(4),
            new TurnEndPayload(id(2), TurnEndOutcome.COMPLETED, true, null, null),
            NOW);
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                turnStart,
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.plainAssistantEntry(),
                turnEnd));
    return new ThreadSnapshot(
        HarnessRuntimeTestFixtures.thread(id(5)), path, List.of(), null, List.of(), List.of());
  }

  private static BranchSettings settingsWithoutEnvironment() {
    return new BranchSettings(
        null, "default-assistant", new ModelSelection("openai", "gpt-5", "default"), List.of());
  }

  private static ThreadSnapshot modelSnapshot(
      ModelInvocationStatus status, List<ModelAttemptFailureProjection> failures) {
    return modelSnapshot(
        modelInvocation(status, null, null, terminalError(status), null, attempt(status), id(3)),
        List.of(),
        failures);
  }

  private static ThreadSnapshot modelSnapshot(
      ModelInvocation model,
      List<ThreadCommand> queued,
      List<ModelAttemptFailureProjection> failures) {
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry(),
                HarnessRuntimeTestFixtures.userMessageEntry()));
    return new ThreadSnapshot(
        HarnessRuntimeTestFixtures.thread(id(3)), path, queued, model, List.of(), failures);
  }

  private static ThreadSnapshot toolSnapshot(ToolInvocationStatus status) {
    ModelInvocation model =
        modelInvocation(
            ModelInvocationStatus.SUCCEEDED,
            null,
            HarnessRuntimeTestFixtures.toolCallResponse(),
            null,
            id(4),
            1,
            id(3));
    ToolInvocation tool =
        toolInvocation(
            status,
            platformToolBinding(),
            approval(status),
            result(status),
            error(status),
            attempt(status));
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry(),
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.assistantEntry()));
    return new ThreadSnapshot(
        HarnessRuntimeTestFixtures.thread(id(4)), path, List.of(), model, List.of(tool), List.of());
  }

  private static ModelInvocation modelInvocation(
      ModelInvocationStatus status,
      StreamCheckpoint checkpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      int attempt,
      UUID basisHeadEntryId) {
    ModelInvocation invocation = mock(ModelInvocation.class);
    when(invocation.id()).thenReturn(id(10));
    when(invocation.threadId()).thenReturn(id(1));
    when(invocation.turnStartEntryId()).thenReturn(id(2));
    when(invocation.basisHeadEntryId()).thenReturn(basisHeadEntryId);
    when(invocation.status()).thenReturn(status);
    when(invocation.attempt()).thenReturn(attempt);
    when(invocation.streamCheckpoint()).thenReturn(checkpoint);
    when(invocation.result()).thenReturn(result);
    when(invocation.error()).thenReturn(error);
    when(invocation.resultEntryId()).thenReturn(resultEntryId);
    when(invocation.createdAt()).thenReturn(NOW);
    when(invocation.updatedAt()).thenReturn(NOW.plusSeconds(1));
    return invocation;
  }

  private static ToolInvocation toolInvocation(
      ToolInvocationStatus status,
      ToolBinding binding,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      int attempt) {
    ToolInvocation invocation = mock(ToolInvocation.class);
    when(invocation.id()).thenReturn(id(100));
    when(invocation.modelInvocationId()).thenReturn(id(10));
    when(invocation.assistantEntryId()).thenReturn(id(4));
    when(invocation.ordinal()).thenReturn(0);
    when(invocation.status()).thenReturn(status);
    when(invocation.attempt()).thenReturn(attempt);
    when(invocation.call()).thenReturn(new ToolCall("call-1", "bash", "{\"x\":1}"));
    when(invocation.binding()).thenReturn(binding);
    when(invocation.approval()).thenReturn(approval);
    when(invocation.result()).thenReturn(result);
    when(invocation.error()).thenReturn(error);
    when(invocation.createdAt()).thenReturn(NOW);
    when(invocation.updatedAt()).thenReturn(NOW.plusSeconds(1));
    return invocation;
  }

  private static ProviderResponse fullProviderResponse() {
    return new ProviderResponse(
        "answer",
        "reasoning",
        List.of(new ProviderToolCall("call-1", "bash", "{\"x\":1}")),
        GenerationStopReason.COMPLETE,
        HarnessRuntimeTestFixtures.usage(),
        HarnessRuntimeTestFixtures.cost(),
        "request-1",
        "priority",
        "{\"inputTokens\":11}");
  }

  private static ToolBinding environmentToolBinding() {
    return new ToolBinding(
        descriptor(),
        ToolType.ENVIRONMENT,
        new EnvironmentBinding(new EnvironmentName("local"), "workspace"));
  }

  private static ToolBinding platformToolBinding() {
    return new ToolBinding(descriptor(), ToolType.PLATFORM, null);
  }

  private static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "bash",
        "1.0",
        "execute a command",
        "bash",
        new ToolParamsSchema("command arguments", Map.of(), Set.of(), true),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ModelInvocationError terminalError(ModelInvocationStatus status) {
    return status == ModelInvocationStatus.FAILED
        ? new ModelInvocationError(ProviderErrorKind.TRANSIENT, "failed")
        : null;
  }

  private static ToolApproval approval(ToolInvocationStatus status) {
    return switch (status) {
      case WAITING_APPROVAL -> ToolApproval.request(NOW, null);
      case RUNNING, SUCCEEDED -> ToolApproval.notRequired();
      default -> null;
    };
  }

  private static ToolResult result(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.SUCCEEDED
        ? new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{}")
        : null;
  }

  private static ToolInvocationError error(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.FAILED
        ? new ToolInvocationError("FAILED", "failed")
        : null;
  }

  private static int attempt(ModelInvocationStatus status) {
    return status == ModelInvocationStatus.READY ? 0 : 1;
  }

  private static int attempt(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.READY
            || status == ToolInvocationStatus.WAITING_APPROVAL
            || status == ToolInvocationStatus.FAILED
        ? 0
        : 1;
  }

  private static ThreadSnapshot withVersion(ThreadSnapshot snapshot, long version) {
    ThreadState thread = snapshot.thread();
    ThreadState changed =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.headEntryId(),
            thread.materializationHash(),
            thread.yoloEnabled(),
            thread.nextCommandSequence(),
            version,
            thread.createdAt(),
            thread.updatedAt());
    return new ThreadSnapshot(
        changed,
        snapshot.entryPath(),
        snapshot.queuedCommands(),
        snapshot.model(),
        snapshot.toolSiblings(),
        snapshot.modelAttemptFailures());
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }
}
