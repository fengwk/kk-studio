package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.NOW;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.environmentToolBinding;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.generation;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.id;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.modelSnapshot;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.toolSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessActiveAssistantOutputDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAgentMessageContentDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class HarnessRuntimeResponseMapperSnapshotTest {

  @Test
  void projectsEntriesAndEveryCommandState() {
    // Entry parent 与 Command terminal marker 的 nullable 投影必须保持 domain 事实。
    HarnessSessionEntryDTO root =
        HarnessRuntimeResponseMapper.toEntryDto(HarnessRuntimeTestFixtures.rootEntry(), null);
    // 读取时费用是入口参数而不是 payload：没有投影时显式为 null，绝不会从 payloadJson 里被「解析」出来。
    HarnessUsageCostDTO usageCost = new HarnessUsageCostDTO();
    usageCost.setCurrency("USD");
    usageCost.setAmount("0.010000000000");
    HarnessSessionEntryDTO user =
        HarnessRuntimeResponseMapper.toEntryDto(
            HarnessRuntimeTestFixtures.userMessageEntry(), usageCost);
    assertNull(root.getParentEntryId());
    assertEquals(EntryType.ROOT, root.getEntryType());
    assertNull(root.getUsageCost());
    assertEquals(idText(2), user.getParentEntryId());
    assertEquals(EntryType.MESSAGE, user.getEntryType());
    assertEquals("USD", user.getUsageCost().getCurrency());
    assertEquals("0.010000000000", user.getUsageCost().getAmount());

    ThreadCommand queued = HarnessRuntimeTestFixtures.queuedUserMessageCommand();
    ThreadCommand applied = queued.markApplied(id(2));
    ThreadCommand cancelled = queued.cancel(id(90), NOW.plusSeconds(1));
    HarnessThreadCommandDTO queuedDto = acceptedCommand(queued);
    HarnessThreadCommandDTO appliedDto = acceptedCommand(applied);
    HarnessThreadCommandDTO cancelledDto = acceptedCommand(cancelled);

    assertEquals(ThreadCommandState.QUEUED, queuedDto.getState());
    assertNull(queuedDto.getCancelledAt());
    assertEquals(ThreadCommandState.APPLIED, appliedDto.getState());
    assertEquals(ThreadCommandState.CANCELLED, cancelledDto.getState());
    assertEquals(NOW.plusSeconds(1), cancelledDto.getCancelledAt());
    assertEquals(4L, queuedDto.getSequence());
    assertEquals(NOW, queuedDto.getCreatedAt());
  }

  /** 重放回执可以包含已应用或取消的命令，而快照的 queuedCommands 只能包含排队命令。 */
  private static HarnessThreadCommandDTO acceptedCommand(ThreadCommand command) {
    AcceptedCommands accepted =
        new AcceptedCommands(
            HarnessRuntimeTestFixtures.session(),
            HarnessRuntimeTestFixtures.rootEntry(),
            HarnessRuntimeTestFixtures.thread(id(1)),
            List.of(command),
            true,
            false);
    return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(
            accepted, HarnessRuntimeTestFixtures.idleSnapshot())
        .getAcceptedCommands()
        .getFirst();
  }

  @Test
  void projectsActiveAssistantOutputTypedContentErrorAndResultEntry() {
    // RUNNING/SUCCEEDED/FAILED 三种合法形状共同覆盖 contents/sequence/error/resultEntry 契约。
    GenerationProjection running =
        generation(
            GenerationStatus.RUNNING,
            1,
            7L,
            List.of(new TextMessageContent("partial"), new ThinkingMessageContent("thinking")),
            null,
            null,
            List.of());
    HarnessActiveAssistantOutputDTO runningDto =
        HarnessRuntimeResponseMapper.toSnapshotDto(modelSnapshot(running, List.of()), Map.of())
            .getActiveAssistantOutput();
    assertEquals(idText(10), runningDto.getGenerationId());
    assertEquals(idText(2), runningDto.getTurnStartEntryId());
    assertEquals(idText(3), runningDto.getRequestHeadEntryId());
    assertEquals(GenerationStatus.RUNNING, runningDto.getStatus());
    assertEquals(1, runningDto.getAttempt());
    assertEquals(7L, runningDto.getSequence());
    assertEquals(
        List.of(
            HarnessAgentMessageContentDTO.Type.TEXT, HarnessAgentMessageContentDTO.Type.THINKING),
        runningDto.getContents().stream().map(HarnessAgentMessageContentDTO::getType).toList());
    assertEquals("partial", runningDto.getContents().get(0).getText());
    assertEquals("thinking", runningDto.getContents().get(1).getText());
    assertNull(runningDto.getError());
    assertNull(runningDto.getResultEntryId());
    assertEquals(NOW, runningDto.getCreatedAt());
    assertEquals(NOW.plusSeconds(1), runningDto.getUpdatedAt());

    GenerationProjection succeeded =
        generation(
            GenerationStatus.SUCCEEDED,
            2,
            8L,
            List.of(new TextMessageContent("answer")),
            null,
            id(4),
            List.of());
    HarnessActiveAssistantOutputDTO succeededDto =
        HarnessRuntimeResponseMapper.toSnapshotDto(modelSnapshot(succeeded, List.of()), Map.of())
            .getActiveAssistantOutput();
    assertEquals(GenerationStatus.SUCCEEDED, succeededDto.getStatus());
    assertEquals(8L, succeededDto.getSequence());
    assertEquals("answer", succeededDto.getContents().getFirst().getText());
    assertEquals(idText(4), succeededDto.getResultEntryId());
    assertNull(succeededDto.getError());

    AssistantError error = new AssistantError("TRANSIENT", "provider unavailable");
    GenerationProjection failed =
        generation(GenerationStatus.FAILED, 3, 9L, List.of(), error, null, List.of());
    HarnessActiveAssistantOutputDTO failedDto =
        HarnessRuntimeResponseMapper.toSnapshotDto(modelSnapshot(failed, List.of()), Map.of())
            .getActiveAssistantOutput();
    assertEquals(GenerationStatus.FAILED, failedDto.getStatus());
    assertEquals("TRANSIENT", failedDto.getError().getCode());
    assertEquals("provider unavailable", failedDto.getError().getMessage());
    assertTrue(failedDto.getContents().isEmpty());
    assertNull(failedDto.getResultEntryId());
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
        new ToolResult("call-1", List.of(new TextResultContent("ok")), false, "{\"durationMs\":1}");
    ToolInvocation bound =
        toolInvocation(
            ToolInvocationStatus.SUCCEEDED, environmentToolBinding(), approval, result, null, 1);
    ToolInvocationDTO boundDto = HarnessRuntimeResponseMapper.toToolInvocationDto(bound);

    assertEquals("bash", boundDto.getToolName());
    assertEquals("bash", boundDto.getRendererKey());
    assertEquals("11111111-1111-1111-1111-111111111111", boundDto.getEnvironmentId());
    assertEquals(approval, new ToolApprovalJsonCodec().decodeNode(boundDto.getApproval()));
    assertEquals(result, ToolResultJsonCodec.decode(JsonValues.write(boundDto.getResult())));
    assertNull(boundDto.getError());

    ToolInvocationError error = new ToolInvocationError("FAILED", "unknown tool");
    ToolInvocation unbound =
        toolInvocation(ToolInvocationStatus.FAILED, null, null, null, error, 0);
    ToolInvocationDTO unboundDto = HarnessRuntimeResponseMapper.toToolInvocationDto(unbound);

    assertEquals("tool", unboundDto.getRendererKey());
    assertNull(unboundDto.getEnvironmentId());
    assertNull(unboundDto.getApproval());
    assertNull(unboundDto.getResult());
    assertEquals(
        error,
        StrictJson.standard().read(unboundDto.getError(), ToolInvocationError.class, "error"));
    assertEquals(idText(100), unboundDto.getId());
    assertEquals(idText(10), unboundDto.getThreadId());
    assertEquals(idText(11), unboundDto.getTurnStartEntryId());
    assertEquals(idText(4), unboundDto.getAssistantEntryId());
    assertEquals(0, unboundDto.getCallIndex());
    assertEquals("call-1", unboundDto.getToolCallId());
    assertEquals("{\"x\":1}", unboundDto.getArgumentsJson());
    assertEquals(NOW, unboundDto.getCreatedAt());
    assertEquals(NOW.plusSeconds(1), unboundDto.getUpdatedAt());
  }

  @Test
  void projectsModelSnapshotQueuesFailuresAndDisabledCompaction() {
    // Model phase snapshot 必须按稳定顺序返回 entries/queue/active output/failed attempts。
    FailureProjection failure =
        new FailureProjection(
            1,
            3,
            "first partial",
            "first thought",
            ProviderErrorKind.TRANSIENT.name(),
            "retry",
            NOW,
            NOW.plusSeconds(2));
    ThreadSnapshot snapshot =
        modelSnapshot(
            GenerationStatus.RUNNING,
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            List.of(failure));

    HarnessUsageCostDTO usageCost = new HarnessUsageCostDTO();
    usageCost.setCurrency("USD");
    usageCost.setAmount("0.010000000000");
    Map<UUID, HarnessUsageCostDTO> usageCosts = Map.of(id(3), usageCost);
    HarnessThreadSnapshotDTO dto = HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, usageCosts);

    assertEquals(3L, dto.getVersion());
    assertEquals(3, dto.getEntries().size());
    assertEquals(
        List.of(EntryType.ROOT, EntryType.TURN_START, EntryType.MESSAGE),
        dto.getEntries().stream().map(HarnessSessionEntryDTO::getEntryType).toList());
    // 读取时费用按 entry id 精确落位：只有被投影的 Entry 有值，其余显式缺席。
    assertEquals("0.010000000000", dto.getEntries().get(2).getUsageCost().getAmount());
    assertNull(dto.getEntries().get(0).getUsageCost());
    assertNull(dto.getEntries().get(1).getUsageCost());
    assertEquals(1, dto.getQueuedCommands().size());
    assertEquals(GenerationStatus.RUNNING, dto.getActiveAssistantOutput().getStatus());
    assertEquals("partial", dto.getActiveAssistantOutput().getContents().getFirst().getText());
    assertTrue(dto.getToolInvocations().isEmpty());
    assertEquals(1, dto.getGenerationFailures().size());
    assertEquals(idText(10), dto.getGenerationFailures().getFirst().getGenerationId());
    assertEquals(idText(2), dto.getGenerationFailures().getFirst().getTurnStartEntryId());
    assertEquals(idText(3), dto.getGenerationFailures().getFirst().getRequestHeadEntryId());
    assertEquals(1, dto.getGenerationFailures().getFirst().getAttempt());
    assertEquals(3L, dto.getGenerationFailures().getFirst().getSequence());
    assertEquals("first partial", dto.getGenerationFailures().getFirst().getText());
    assertEquals("first thought", dto.getGenerationFailures().getFirst().getThinking());
    assertEquals("TRANSIENT", dto.getGenerationFailures().getFirst().getErrorCode());
    assertEquals("retry", dto.getGenerationFailures().getFirst().getErrorMessage());
    assertEquals(NOW, dto.getGenerationFailures().getFirst().getFailedAt());
    assertEquals(NOW.plusSeconds(2), dto.getGenerationFailures().getFirst().getRetryAt());
    assertNull(dto.getBaseEntryId());
  }

  @Test
  void projectsToolSnapshotAndEnabledCompaction() {
    // Tool phase snapshot 必须同时暴露已成功 Model 与完整 sibling，并清空 disabledReason。
    ThreadSnapshot snapshot = toolSnapshot(ToolInvocationStatus.WAITING_APPROVAL);

    HarnessThreadSnapshotDTO dto = HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, Map.of());

    assertEquals(4, dto.getEntries().size());
    assertEquals(GenerationStatus.SUCCEEDED, dto.getActiveAssistantOutput().getStatus());
    assertEquals(1, dto.getToolInvocations().size());
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL, dto.getToolInvocations().getFirst().getStatus());
    assertTrue(dto.getGenerationFailures().isEmpty());
    assertNull(dto.getBaseEntryId());
    HarnessManualCompactionDTO availability =
        HarnessRuntimeResponseMapper.toManualCompactionDto(ManualCompactionAvailability.enabled());
    assertTrue(availability.getAvailable());
    assertNull(availability.getDisabledReason());
  }
}
