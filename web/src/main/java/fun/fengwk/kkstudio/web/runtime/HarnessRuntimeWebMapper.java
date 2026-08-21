package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.core.studio.StudioOwner;
import fun.fengwk.kkstudio.core.studio.StudioOwnerType;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.ModelAttemptFailureProjection;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetActiveToolsCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCancelledUserMessageDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelAttemptFailureDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Harness Runtime HTTP 边界的严格 DTO&lt;-&gt;domain mapper。
 *
 * <p>实体 id（Thread / Session / Entry / Invocation / clientCommandId / stopRequestId / decisionId）均为
 * canonical UUID string；version 与 command sequence 仍是十进制字符串。domain JSON payload 只通过规范 runtime
 * codecs 编解码。Thread 状态与 processing 标志通过共享的 {@link ThreadContextClassifier} 与 {@link
 * ThreadRuntimeStatus} 从 {@link ThreadSnapshot} 确定性推导。
 *
 * <p>所有返回的 DTO 列表都是不可变副本。
 */
public final class HarnessRuntimeWebMapper {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");

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

  private HarnessRuntimeWebMapper() {}

  // ---------- 严格解析 ----------

  /** 解析 canonical UUID 实体 id（{@code UUID.fromString} 往返一致）。 */
  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  /** 解析严格正十进制 sequence 游标（{@code [1-9][0-9]*}）。 */
  public static long parsePositiveDecimal(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be an unsigned positive decimal: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds bigint range: " + value, error);
    }
  }

  /** 解析严格非负十进制 version 游标（{@code 0|[1-9][0-9]*}）。 */
  public static long parseNonNegativeDecimal(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " must be a non-negative decimal bigint: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds bigint range: " + value, error);
    }
  }

  // ---------- domain -> DTO ----------

  /** 映射一个一致的 Thread 快照；DTO 列表为不可变副本。 */
  public static HarnessThreadDTO toThreadDto(ThreadSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(snapshot.thread().id().toString());
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

  public static HarnessBranchSettingsDTO toBranchSettingsDto(BranchSettings settings) {
    Objects.requireNonNull(settings, "settings");
    HarnessBranchSettingsDTO dto = new HarnessBranchSettingsDTO();
    dto.setEnvironment(toEnvironmentBindingDto(settings.environment()));
    dto.setAgentName(settings.agentName());
    dto.setModel(toModelSelectionDto(settings.model()));
    dto.setActiveTools(List.copyOf(settings.activeTools()));
    return dto;
  }

  public static HarnessModelSelectionDTO toModelSelectionDto(ModelSelection selection) {
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

  public static HarnessThreadCommandDTO toCommandDto(ThreadCommand command) {
    Objects.requireNonNull(command, "command");
    HarnessThreadCommandDTO dto = new HarnessThreadCommandDTO();
    dto.setThreadId(command.threadId().toString());
    dto.setSequence(Long.toString(command.sequence()));
    dto.setType(command.type().name());
    dto.setState(command.state().name());
    dto.setClientCommandId(command.clientCommandId().toString());
    dto.setRequestHash(command.requestHash());
    dto.setPayloadJson(COMMAND_PAYLOADS.encode(command.payload()));
    dto.setConsumedTurnStartEntryId(
        command.consumedTurnStartEntryId() == null
            ? null
            : command.consumedTurnStartEntryId().toString());
    dto.setCancelledAt(command.cancelledAt());
    dto.setCreateTime(command.createdAt());
    return dto;
  }

  public static ModelInvocationDTO toModelInvocationDto(ModelInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ModelInvocationDTO dto = new ModelInvocationDTO();
    dto.setId(invocation.id().toString());
    dto.setThreadId(invocation.threadId().toString());
    dto.setTurnStartEntryId(invocation.turnStartEntryId().toString());
    dto.setBasisHeadEntryId(invocation.basisHeadEntryId().toString());
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
    dto.setOrdinal(invocation.ordinal());
    dto.setStatus(invocation.status().name());
    dto.setAttempt(invocation.attempt());
    dto.setToolCallId(invocation.call().id());
    dto.setToolName(invocation.call().toolName());
    dto.setArgumentsJson(invocation.call().argumentsJson());
    ToolBinding binding = invocation.binding();
    if (binding == null) {
      // unknown tool 槽位（immediate FAILED）：toolVersion/toolType/environment 显式 null，renderer 固定回退
      // tool。
      dto.setRendererKey(HistoryPayloadMapper.UNBOUND_RENDERER_KEY);
    } else {
      dto.setToolVersion(binding.descriptor().version());
      dto.setRendererKey(binding.descriptor().rendererKey());
      dto.setToolType(binding.type().name());
      dto.setEnvironment(toEnvironmentBindingDto(binding.environment()));
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
      failureDto.setBasisHeadEntryId(failure.basisHeadEntryId().toString());
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
    HarnessSessionDTO sessionDto = new HarnessSessionDTO();
    sessionDto.setSessionId(accepted.session().id().toString());
    sessionDto.setCreatedAt(accepted.session().createdAt());
    dto.setSession(sessionDto);
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
      messageDto.setClientCommandId(message.clientCommandId().toString());
      messageDto.setMessageJson(
          AGENT_MESSAGES.encode(new AgentMessage(AgentMessageRole.USER, message.contents())));
      cancelled.add(messageDto);
    }
    dto.setCancelledUserMessages(List.copyOf(cancelled));
    return dto;
  }

  // ---------- DTO -> domain ----------

  public static BranchSettings toBranchSettings(HarnessBranchSettingsDTO dto) {
    requireNonNull(dto, "branchSettings");
    return new BranchSettings(
        toEnvironmentBinding(dto.getEnvironment()),
        requireText(dto.getAgentName(), "branchSettings.agentName"),
        toModelSelection(dto.getModel()),
        requireList(dto.getActiveTools(), "branchSettings.activeTools"));
  }

  public static ModelSelection toModelSelection(HarnessModelSelectionDTO dto) {
    requireNonNull(dto, "model");
    return new ModelSelection(
        requireText(dto.getProviderName(), "model.providerName"),
        requireText(dto.getModelName(), "model.modelName"),
        requireText(dto.getVariant(), "model.variant"));
  }

  /** 映射 owner，并只允许产品公开的 CHAT/CANVAS discriminator。 */
  public static StudioOwner toOwner(HarnessCommandOwnerDTO dto) {
    requireNonNull(dto, "owner");
    String type = requireText(dto.getType(), "owner.type");
    StudioOwnerType ownerType;
    try {
      ownerType = StudioOwnerType.valueOf(type);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("owner.type must be CHAT or CANVAS: " + type, error);
    }
    return new StudioOwner(ownerType, parseUuid(dto.getId(), "owner.id"));
  }

  /** 将唯一产品 HTTP 写请求映射为 owner + sealed target + ordered commands。 */
  public static AcceptCommandsCommand toAcceptCommandsCommand(HarnessCommandBatchDTO dto) {
    requireNonNull(dto, "commandBatchDTO");
    List<HarnessCommandCreateDTO> requestCommands = requireList(dto.getCommands(), "commands");
    if (requestCommands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> commands = new ArrayList<>(requestCommands.size());
    for (HarnessCommandCreateDTO command : requestCommands) {
      commands.add(toNewHttpCommand(command));
    }
    validateHttpCommandShape(commands);
    return new AcceptCommandsCommand(toTarget(dto.getTarget()), commands);
  }

  public static CompactThreadCommand toCompactThreadCommand(
      String threadId, HarnessThreadCompactDTO dto) {
    requireNonNull(dto, "compactDTO");
    return new CompactThreadCommand(
        parseUuid(threadId, "threadId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"));
  }

  public static StopCommand toStopCommand(String threadId, HarnessThreadStopDTO dto) {
    requireNonNull(dto, "stopDTO");
    return new StopCommand(
        parseUuid(threadId, "threadId"),
        parseUuid(dto.getStopRequestId(), "stopRequestId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"));
  }

  public static SetThreadYoloCommand toSetThreadYoloCommand(
      String threadId, HarnessThreadYoloUpdateDTO dto) {
    requireNonNull(dto, "yoloUpdateDTO");
    return new SetThreadYoloCommand(
        parseUuid(threadId, "threadId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"),
        requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

  public static ToolApprovalCommand toToolApprovalCommand(
      String threadId, String toolInvocationId, HarnessToolApprovalDTO dto) {
    requireNonNull(dto, "approvalDTO");
    String decision = requireText(dto.getDecision(), "decision");
    ToolApprovalDecision parsed =
        switch (decision) {
          case "ALLOW" -> ToolApprovalDecision.ALLOWED;
          case "DENY" -> ToolApprovalDecision.DENIED;
          default -> throw new IllegalArgumentException(
              "decision must be ALLOW or DENY: " + decision);
        };
    return new ToolApprovalCommand(
        parseUuid(threadId, "threadId"),
        parseUuid(toolInvocationId, "toolInvocationId"),
        parsed,
        parseUuid(dto.getDecisionId(), "decisionId"),
        requireText(dto.getActor(), "actor"),
        dto.getReason());
  }

  // ---------- 内部辅助方法 ----------

  private static AcceptCommandsTarget toTarget(HarnessCommandTargetDTO dto) {
    requireNonNull(dto, "target");
    String type = requireText(dto.getType(), "target.type");
    return switch (type) {
      case "NEW_SESSION" -> {
        requireForbiddenTarget(
            dto, "startEntryId", "expectedHeadEntryId", "expectedNextCommandSequence");
        yield new AcceptCommandsTarget.NewSession(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            toBranchSettings(requireNonNull(dto.getRootSettings(), "target.rootSettings")),
            null,
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "ENTRY" -> {
        requireForbiddenTarget(
            dto, "rootSettings", "expectedHeadEntryId", "expectedNextCommandSequence");
        yield new AcceptCommandsTarget.Entry(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getStartEntryId(), "target.startEntryId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "THREAD" -> {
        requireForbiddenTarget(dto, "sessionId", "startEntryId", "rootSettings", "yoloEnabled");
        yield new AcceptCommandsTarget.Thread(
            parseUuid(dto.getThreadId(), "target.threadId"),
            parseUuid(dto.getExpectedHeadEntryId(), "target.expectedHeadEntryId"),
            parsePositiveDecimal(
                dto.getExpectedNextCommandSequence(), "target.expectedNextCommandSequence"));
      }
      default -> throw new IllegalArgumentException("unknown target type: " + type);
    };
  }

  private static NewThreadCommand toNewHttpCommand(HarnessCommandCreateDTO dto) {
    requireNonNull(dto, "command");
    ThreadCommandType type = requireHttpCommandType(dto.getType());
    ThreadCommandPayload payload = toPayload(type, dto);
    return new NewThreadCommand(
        payload,
        parseUuid(dto.getClientCommandId(), "command.clientCommandId"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static ThreadCommandPayload toPayload(
      ThreadCommandType type, HarnessCommandCreateDTO dto) {
    return switch (type) {
      case USER_MESSAGE -> {
        requireForbidden(dto, "agentName", "model", "activeTools", "environment");
        yield new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, toUserMessageContents(dto)));
      }
      case SET_AGENT -> {
        requireForbidden(dto, "contents", "model", "activeTools", "environment");
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case SET_MODEL -> {
        requireForbidden(dto, "contents", "agentName", "activeTools", "environment");
        yield new SetModelCommandPayload(toModelSelection(requireNonNull(dto.getModel(), "model")));
      }
      case SET_ACTIVE_TOOLS -> {
        requireForbidden(dto, "contents", "agentName", "model", "environment");
        yield new SetActiveToolsCommandPayload(requireList(dto.getActiveTools(), "activeTools"));
      }
      case SET_ENVIRONMENT -> {
        requireForbidden(dto, "contents", "agentName", "model", "activeTools");
        if (!dto.hasEnvironmentField()) {
          throw new IllegalArgumentException(
              "SET_ENVIRONMENT must contain environment (a binding object selects, null unbinds)");
        }
        yield new SetEnvironmentCommandPayload(toEnvironmentBinding(dto.getEnvironment()));
      }
      default -> throw new IllegalArgumentException(
          "command type is not allowed on the product HTTP surface: " + type);
    };
  }

  private static ThreadCommandType requireHttpCommandType(String type) {
    String required = requireText(type, "command.type");
    return switch (required) {
      case "USER_MESSAGE" -> ThreadCommandType.USER_MESSAGE;
      case "SET_ENVIRONMENT" -> ThreadCommandType.SET_ENVIRONMENT;
      case "SET_AGENT" -> ThreadCommandType.SET_AGENT;
      case "SET_MODEL" -> ThreadCommandType.SET_MODEL;
      case "SET_ACTIVE_TOOLS" -> ThreadCommandType.SET_ACTIVE_TOOLS;
      case "CUSTOM_MESSAGE" -> throw new IllegalArgumentException(
          "CUSTOM_MESSAGE is not allowed on the product HTTP surface");
      default -> throw new IllegalArgumentException("unknown command type: " + required);
    };
  }

  private static void validateHttpCommandShape(List<NewThreadCommand> commands) {
    List<ThreadCommandType> prefixOrder =
        List.of(
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_ACTIVE_TOOLS);
    int lastSetOrder = -1;
    int userMessageCount = 0;
    boolean sawUserMessage = false;
    for (int i = 0; i < commands.size(); i++) {
      ThreadCommandType type = commands.get(i).payload().type();
      if (type == ThreadCommandType.USER_MESSAGE) {
        userMessageCount++;
        sawUserMessage = true;
        if (i != commands.size() - 1) {
          throw new IllegalArgumentException("USER_MESSAGE must be the final HTTP command");
        }
        continue;
      }
      int order = prefixOrder.indexOf(type);
      if (order < 0) {
        throw new IllegalArgumentException(
            "command type is not allowed on the product HTTP surface: " + type);
      }
      if (sawUserMessage || order <= lastSetOrder) {
        throw new IllegalArgumentException(
            "HTTP commands must use SET_ENVIRONMENT, SET_AGENT, SET_MODEL, SET_ACTIVE_TOOLS order");
      }
      lastSetOrder = order;
    }
    if (userMessageCount != 1) {
      throw new IllegalArgumentException(
          "HTTP command batch must contain exactly one USER_MESSAGE");
    }
  }

  private static void requireForbiddenTarget(HarnessCommandTargetDTO dto, String... fields) {
    for (String field : fields) {
      boolean present =
          switch (field) {
            case "sessionId" -> dto.hasSessionIdField();
            case "startEntryId" -> dto.hasStartEntryIdField();
            case "threadId" -> dto.hasThreadIdField();
            case "rootSettings" -> dto.hasRootSettingsField();
            case "yoloEnabled" -> dto.hasYoloEnabledField();
            case "expectedHeadEntryId" -> dto.hasExpectedHeadEntryIdField();
            case "expectedNextCommandSequence" -> dto.hasExpectedNextCommandSequenceField();
            default -> throw new IllegalArgumentException("unknown target field: " + field);
          };
      if (present) {
        throw new IllegalArgumentException(
            "field " + field + " is forbidden for target type " + dto.getType());
      }
    }
  }

  /** 映射有序 USER_MESSAGE contents 列表（Canvas 首次发送等非 thread-command 路径复用）。 */
  public static List<AgentMessageContent> toUserMessageContents(
      List<HarnessUserMessageContentDTO> contents) {
    List<HarnessUserMessageContentDTO> required = requireList(contents, "contents");
    if (required.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    List<AgentMessageContent> mapped = new ArrayList<>(required.size());
    for (int i = 0; i < required.size(); i++) {
      mapped.add(toUserMessageContent(required.get(i), i));
    }
    return List.copyOf(mapped);
  }

  private static List<AgentMessageContent> toUserMessageContents(HarnessCommandCreateDTO dto) {
    if (!dto.hasContentsField()) {
      throw new IllegalArgumentException(
          "USER_MESSAGE must contain exactly one non-empty contents list of "
              + "TEXT/ATTACHMENT/RESOURCE");
    }
    return toUserMessageContents(dto.getContents());
  }

  private static AgentMessageContent toUserMessageContent(
      HarnessUserMessageContentDTO dto, int index) {
    String context = "contents[" + index + "]";
    String type = requireText(dto.getType(), context + ".type");
    return switch (type) {
      case "TEXT" -> {
        requireContentForbidden(dto, context, "uploadId", "blobId", "name", "preview");
        yield new TextMessageContent(requireText(dto.getText(), context + ".text"));
      }
      case "ATTACHMENT" -> {
        requireContentForbidden(dto, context, "text", "blobId", "name", "preview");
        // 瞬时 upload 引用：canonical UUID string；READY upload 由应用 use-case 在入队事务内消费。
        yield new AttachmentMessageContent(parseUuid(dto.getUploadId(), context + ".uploadId"));
      }
      case "RESOURCE" -> {
        requireContentForbidden(dto, context, "text", "uploadId");
        yield new ResourceMessageContent(
            parseUuid(dto.getBlobId(), context + ".blobId"),
            requireText(dto.getName(), context + ".name"),
            dto.getPreview());
      }
      default -> throw new IllegalArgumentException(
          context + ".type must be one of TEXT, ATTACHMENT, RESOURCE: " + type);
    };
  }

  private static void requireContentForbidden(
      HarnessUserMessageContentDTO dto, String context, String... fields) {
    for (String field : fields) {
      Object value =
          switch (field) {
            case "text" -> dto.hasTextField() ? Boolean.TRUE : null;
            case "uploadId" -> dto.hasUploadIdField() ? Boolean.TRUE : null;
            case "blobId" -> dto.hasBlobIdField() ? Boolean.TRUE : null;
            case "name" -> dto.hasNameField() ? Boolean.TRUE : null;
            case "preview" -> dto.hasPreviewField() ? Boolean.TRUE : null;
            default -> throw new IllegalArgumentException("unknown content field: " + field);
          };
      if (value != null) {
        throw new IllegalArgumentException(
            "field " + context + "." + field + " is forbidden for content type " + dto.getType());
      }
    }
  }

  private static void requireForbidden(HarnessCommandCreateDTO dto, String... fields) {
    for (String field : fields) {
      Object value =
          switch (field) {
            case "contents" -> dto.hasContentsField() ? Boolean.TRUE : null;
            case "agentName" -> dto.hasAgentNameField() ? Boolean.TRUE : null;
            case "model" -> dto.hasModelField() ? Boolean.TRUE : null;
            case "activeTools" -> dto.hasActiveToolsField() ? Boolean.TRUE : null;
            case "environment" -> dto.hasEnvironmentField() ? Boolean.TRUE : null;
            default -> throw new IllegalArgumentException("unknown field: " + field);
          };
      if (value != null) {
        throw new IllegalArgumentException(
            "field " + field + " is forbidden for command type " + dto.getType());
      }
    }
  }

  private static EnvironmentBinding toEnvironmentBinding(EnvironmentBindingDTO dto) {
    if (dto == null) {
      return null;
    }
    return new EnvironmentBinding(
        new EnvironmentName(requireText(dto.getName(), "environment.name")),
        requireText(dto.getWorkspacePath(), "environment.workspacePath"));
  }

  private static EnvironmentBindingDTO toEnvironmentBindingDto(EnvironmentBinding binding) {
    if (binding == null) {
      return null;
    }
    EnvironmentBindingDTO dto = new EnvironmentBindingDTO();
    dto.setName(binding.environmentName().value());
    dto.setWorkspacePath(binding.workspacePath());
    return dto;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static boolean requireBoolean(Boolean value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    return value;
  }

  private static <T> List<T> requireList(List<T> value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    for (T element : value) {
      if (element == null) {
        throw new IllegalArgumentException(field + " must not contain null elements");
      }
    }
    return List.copyOf(value);
  }
}
