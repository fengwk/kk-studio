package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.ModelAttemptFailureProjection;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCancelledUserMessageDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelAttemptFailureDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Harness Runtime domain 事实到严格 HTTP DTO 的响应投影。 */
public final class HarnessRuntimeResponseMapper {

  private static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS =
      new HistoryEntryPayloadJsonCodec();
  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();
  private static final AgentMessageJsonCodec AGENT_MESSAGES = new AgentMessageJsonCodec();
  private static final StreamCheckpointJsonCodec STREAM_CHECKPOINTS =
      new StreamCheckpointJsonCodec();
  private static final ProviderResponseJsonCodec MODEL_RESULTS = new ProviderResponseJsonCodec();
  private static final ModelInvocationErrorJsonCodec MODEL_ERRORS =
      new ModelInvocationErrorJsonCodec();
  private static final ToolApprovalJsonCodec TOOL_APPROVALS = new ToolApprovalJsonCodec();
  private static final ToolInvocationErrorJsonCodec TOOL_ERRORS =
      new ToolInvocationErrorJsonCodec();
  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private HarnessRuntimeResponseMapper() {}

  /** 映射一个一致的 Thread 快照；DTO 列表为不可变副本。 */
  public static HarnessThreadDTO toThreadDto(ThreadSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(snapshot.thread().id().toString());
    dto.setName(snapshot.thread().name());
    dto.setSessionId(snapshot.entryPath().root().sessionId().toString());
    dto.setHeadEntryId(snapshot.thread().headEntryId().toString());
    dto.setYoloEnabled(snapshot.thread().yoloEnabled());
    dto.setNextCommandSequence(Long.toString(snapshot.thread().nextCommandSequence()));
    dto.setVersion(Long.toString(snapshot.thread().version()));
    ThreadRuntimeStatus status =
        ThreadRuntimeStatus.from(
            CLASSIFIER.classify(
                snapshot.thread(),
                snapshot.entryPath(),
                snapshot.model(),
                snapshot.toolSiblings()));
    dto.setStatus(status.name());
    dto.setProcessing(status.isProcessing());
    dto.setBranchSettings(toBranchSettingsDto(snapshot.entryPath().baseSettings()));
    dto.setCreateTime(snapshot.thread().createdAt());
    dto.setUpdateTime(snapshot.thread().updatedAt());
    return dto;
  }

  private static HarnessBranchSettingsDTO toBranchSettingsDto(BranchSettings settings) {
    Objects.requireNonNull(settings, "settings");
    HarnessBranchSettingsDTO dto = new HarnessBranchSettingsDTO();
    dto.setAgentName(settings.agentName());
    dto.setModel(toModelSelectionDto(settings.model()));
    return dto;
  }

  private static HarnessModelSelectionDTO toModelSelectionDto(ModelSelection selection) {
    Objects.requireNonNull(selection, "selection");
    HarnessModelSelectionDTO dto = new HarnessModelSelectionDTO();
    dto.setProviderName(selection.providerName());
    dto.setModelName(selection.modelName());
    dto.setVariant(selection.variant());
    return dto;
  }

  public static HarnessSessionEntryDTO toEntryDto(Entry entry) {
    Objects.requireNonNull(entry, "entry");
    HarnessSessionEntryDTO dto = new HarnessSessionEntryDTO();
    dto.setEntryId(entry.id().toString());
    dto.setSessionId(entry.sessionId().toString());
    dto.setParentEntryId(entry.parentEntryId() == null ? null : entry.parentEntryId().toString());
    dto.setEntryType(entry.payload().type().name());
    dto.setPayloadJson(ENTRY_PAYLOADS.encode(entry.payload()));
    dto.setCreateTime(entry.createdAt());
    return dto;
  }

  static HarnessThreadCommandDTO toCommandDto(ThreadCommand command) {
    Objects.requireNonNull(command, "command");
    HarnessThreadCommandDTO dto = new HarnessThreadCommandDTO();
    dto.setThreadId(command.threadId().toString());
    dto.setSequence(Long.toString(command.sequence()));
    dto.setType(command.type().name());
    dto.setState(command.state().name());
    dto.setIdempotencyKey(command.idempotencyKey().toString());
    dto.setPayloadJson(COMMAND_PAYLOADS.encode(command.payload()));
    dto.setCancelledAt(command.cancelledAt());
    dto.setCreateTime(command.createdAt());
    return dto;
  }

  static ModelInvocationDTO toModelInvocationDto(ModelInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ModelInvocationDTO dto = new ModelInvocationDTO();
    dto.setId(invocation.id().toString());
    dto.setThreadId(invocation.threadId().toString());
    dto.setTurnStartEntryId(invocation.turnStartEntryId().toString());
    dto.setRequestHeadEntryId(invocation.requestHeadEntryId().toString());
    dto.setStatus(invocation.status().name());
    dto.setAttempt(invocation.attempt());
    dto.setStreamCheckpointJson(
        invocation.streamCheckpoint() == null
            ? null
            : STREAM_CHECKPOINTS.encode(invocation.streamCheckpoint()));
    dto.setResultJson(
        invocation.result() == null ? null : MODEL_RESULTS.encode(invocation.result()));
    dto.setErrorJson(invocation.error() == null ? null : MODEL_ERRORS.encode(invocation.error()));
    dto.setResultEntryId(
        invocation.resultEntryId() == null ? null : invocation.resultEntryId().toString());
    dto.setCreateTime(invocation.createdAt());
    dto.setUpdateTime(invocation.updatedAt());
    return dto;
  }

  public static ToolInvocationDTO toToolInvocationDto(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(invocation.id().toString());
    dto.setModelInvocationId(invocation.modelInvocationId().toString());
    dto.setAssistantEntryId(invocation.assistantEntryId().toString());
    dto.setCallIndex(invocation.callIndex());
    dto.setStatus(invocation.status().name());
    dto.setAttempt(invocation.attempt());
    dto.setToolCallId(invocation.call().id());
    dto.setToolName(invocation.call().toolName());
    dto.setArgumentsJson(invocation.call().argumentsJson());
    ToolBinding binding = invocation.binding();
    if (binding == null) {
      dto.setRendererKey(HistoryPayloadMapper.UNBOUND_RENDERER_KEY);
    } else {
      dto.setRendererKey(binding.descriptor().rendererKey());
      // 环境身份平铺为 nullable canonical UUID 文本；不保留单字段 wrapper。
      dto.setEnvironmentId(
          binding.environmentId() == null ? null : binding.environmentId().toString());
    }
    dto.setApprovalJson(
        invocation.approval() == null ? null : TOOL_APPROVALS.encode(invocation.approval()));
    dto.setResultJson(
        invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result()));
    dto.setErrorJson(invocation.error() == null ? null : TOOL_ERRORS.encode(invocation.error()));
    dto.setCreateTime(invocation.createdAt());
    dto.setUpdateTime(invocation.updatedAt());
    return dto;
  }

  public static HarnessThreadSnapshotDTO toSnapshotDto(
      ThreadSnapshot snapshot, ManualCompactionAvailability manualCompaction) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(manualCompaction, "manualCompaction");
    HarnessThreadSnapshotDTO dto = new HarnessThreadSnapshotDTO();
    dto.setVersion(Long.toString(snapshot.thread().version()));
    dto.setThread(toThreadDto(snapshot));
    List<HarnessSessionEntryDTO> entries = new ArrayList<>(snapshot.entryPath().entries().size());
    for (Entry entry : snapshot.entryPath().entries()) {
      entries.add(toEntryDto(entry));
    }
    dto.setEntries(List.copyOf(entries));
    List<HarnessThreadCommandDTO> queued = new ArrayList<>(snapshot.queuedCommands().size());
    for (ThreadCommand command : snapshot.queuedCommands()) {
      queued.add(toCommandDto(command));
    }
    dto.setQueuedCommands(List.copyOf(queued));
    dto.setModelInvocation(
        snapshot.model() == null ? null : toModelInvocationDto(snapshot.model()));
    List<ToolInvocationDTO> tools = new ArrayList<>(snapshot.toolSiblings().size());
    for (ToolInvocation invocation : snapshot.toolSiblings()) {
      tools.add(toToolInvocationDto(invocation));
    }
    dto.setToolInvocations(List.copyOf(tools));
    List<ModelAttemptFailureDTO> failures = new ArrayList<>(snapshot.modelAttemptFailures().size());
    for (ModelAttemptFailureProjection failure : snapshot.modelAttemptFailures()) {
      ModelAttemptFailureDTO failureDto = new ModelAttemptFailureDTO();
      failureDto.setModelInvocationId(failure.modelInvocationId().toString());
      failureDto.setTurnStartEntryId(failure.turnStartEntryId().toString());
      failureDto.setRequestHeadEntryId(failure.requestHeadEntryId().toString());
      failureDto.setAttempt(failure.attempt());
      failureDto.setSequence(Long.toString(failure.sequence()));
      failureDto.setText(failure.text());
      failureDto.setThinking(failure.thinking());
      failureDto.setErrorCode(failure.error().kind().name());
      failureDto.setErrorMessage(failure.error().message());
      failureDto.setFailedAt(failure.failedAt());
      failureDto.setRetryAt(failure.retryAt());
      failures.add(failureDto);
    }
    dto.setModelAttemptFailures(List.copyOf(failures));
    HarnessManualCompactionDTO manualCompactionDto = new HarnessManualCompactionDTO();
    manualCompactionDto.setAvailable(manualCompaction.available());
    manualCompactionDto.setDisabledReason(
        manualCompaction.available() ? null : manualCompaction.disabledReason().name());
    dto.setManualCompaction(manualCompactionDto);
    return dto;
  }

  /** 可复用的 Session 身份/名称投影（rename 响应、accept 响应共用）。 */
  public static HarnessSessionDTO toSessionDto(Session session) {
    Objects.requireNonNull(session, "session");
    HarnessSessionDTO dto = new HarnessSessionDTO();
    dto.setSessionId(session.id().toString());
    dto.setName(session.name());
    dto.setCreatedAt(session.createdAt());
    return dto;
  }

  /** 映射命令接受结果，并使用接受后重新读取的当前 Thread snapshot 投影 Thread。 */
  public static HarnessAcceptedCommandsDTO toAcceptedCommandsDto(
      AcceptedCommands accepted, ThreadSnapshot currentSnapshot) {
    Objects.requireNonNull(accepted, "accepted");
    Objects.requireNonNull(currentSnapshot, "currentSnapshot");
    if (!accepted.thread().id().equals(currentSnapshot.thread().id())) {
      throw new IllegalArgumentException(
          "accepted result and current snapshot thread do not match");
    }
    HarnessAcceptedCommandsDTO dto = new HarnessAcceptedCommandsDTO();
    dto.setSession(toSessionDto(accepted.session()));
    dto.setRootEntry(toEntryDto(accepted.rootEntry()));
    dto.setThread(toThreadDto(currentSnapshot));
    List<HarnessThreadCommandDTO> commands = new ArrayList<>(accepted.acceptedCommands().size());
    for (ThreadCommand command : accepted.acceptedCommands()) {
      commands.add(toCommandDto(command));
    }
    dto.setAcceptedCommands(List.copyOf(commands));
    dto.setReplayed(accepted.replayed());
    return dto;
  }

  /** 映射手动压缩的提交结果，并使用提交后重新读取的当前 Thread snapshot 投影 Thread。 */
  public static HarnessThreadCompactResultDTO toCompactResultDto(
      CompactThreadResult result, ThreadSnapshot currentSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(currentSnapshot, "currentSnapshot");
    if (!result.thread().id().equals(currentSnapshot.thread().id())) {
      throw new IllegalArgumentException("compact result and current snapshot thread do not match");
    }
    HarnessThreadCompactResultDTO dto = new HarnessThreadCompactResultDTO();
    dto.setThread(toThreadDto(currentSnapshot));
    dto.setTurnStartEntryId(result.turnStartEntryId().toString());
    dto.setModelInvocationId(
        result.modelInvocationId() == null ? null : result.modelInvocationId().toString());
    return dto;
  }

  /** 映射一次 Stop 结果及权威的 stop 后快照。 */
  public static HarnessThreadStopResultDTO toStopResultDto(
      StopResult result, ThreadSnapshot postStopSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(postStopSnapshot, "postStopSnapshot");
    if (!result.thread().id().equals(postStopSnapshot.thread().id())
        || result.thread().version() != postStopSnapshot.thread().version()) {
      throw new IllegalArgumentException("post-stop snapshot does not match Stop result");
    }
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStatus(
        result.replayed()
            ? "REPLAYED"
            : result.stoppedTurnEndEntryId() == null ? "IDLE" : "STOPPED");
    dto.setThread(toThreadDto(postStopSnapshot));
    dto.setStoppedTurnEndEntryId(
        result.stoppedTurnEndEntryId() == null ? null : result.stoppedTurnEndEntryId().toString());
    dto.setCancelledCommandCount(result.cancelledCommandCount());
    List<HarnessCancelledUserMessageDTO> cancelled =
        new ArrayList<>(result.cancelledUserMessages().size());
    for (CancelledUserMessage message : result.cancelledUserMessages()) {
      HarnessCancelledUserMessageDTO messageDto = new HarnessCancelledUserMessageDTO();
      messageDto.setSequence(Long.toString(message.sequence()));
      messageDto.setIdempotencyKey(message.idempotencyKey().toString());
      messageDto.setMessageJson(
          AGENT_MESSAGES.encode(new AgentMessage(AgentMessageRole.USER, message.contents())));
      cancelled.add(messageDto);
    }
    dto.setCancelledUserMessages(List.copyOf(cancelled));
    return dto;
  }
}
