package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.ModelAttemptFailureProjection;
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetActiveToolsCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
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
 * canonical UUID string；revision 与 command sequence 仍是十进制字符串。domain JSON payload 只通过规范 runtime
 * codecs 编解码。Thread 状态与 processing 标志通过共享的 {@link ThreadContextClassifier} 从 {@link ThreadSnapshot}
 * 确定性推导：IDLE / CONTINUATION_DUE / MODEL_&lt;status&gt; / TOOL_&lt;status&gt; / APPLYING；只有 IDLE 时
 * processing 为 false。
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

  /** 解析严格非负十进制 revision 游标（{@code 0|[1-9][0-9]*}）。 */
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
    dto.setRevision(Long.toString(snapshot.thread().revision()));
    String status = deriveStatus(snapshot);
    dto.setStatus(status);
    dto.setProcessing(!"IDLE".equals(status));
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

  public static HarnessThreadSnapshotDTO toSnapshotDto(ThreadSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    HarnessThreadSnapshotDTO dto = new HarnessThreadSnapshotDTO();
    dto.setRevision(Long.toString(snapshot.thread().revision()));
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
    return dto;
  }

  /** 映射 create-thread 返回的初始仅 ROOT 快照。 */
  public static HarnessThreadDTO toCreatedThreadDto(CreatedThread created) {
    Objects.requireNonNull(created, "created");
    return toThreadDto(
        new ThreadSnapshot(
            created.thread(),
            new EntryPath(List.of(created.rootEntry())),
            List.of(),
            null,
            List.of(),
            List.of()));
  }

  /** 映射一次 Stop 结果及权威的 stop 后快照。 */
  public static HarnessThreadStopResultDTO toStopResultDto(
      StopResult result, ThreadSnapshot postStopSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(postStopSnapshot, "postStopSnapshot");
    if (!result.thread().id().equals(postStopSnapshot.thread().id())
        || result.thread().revision() != postStopSnapshot.thread().revision()) {
      throw new IllegalArgumentException("post-stop snapshot does not match Stop result");
    }
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStatus(result.status().name());
    dto.setThread(toThreadDto(postStopSnapshot));
    dto.setStoppedTurnEndEntryId(
        result.stoppedTurnEndEntryId() == null ? null : result.stoppedTurnEndEntryId().toString());
    dto.setCancelledCommandCount(result.cancelledCommandCount());
    return dto;
  }

  // ---------- DTO -> domain ----------

  public static CreateThreadCommand toCreateThreadCommand(HarnessThreadCreateDTO dto) {
    requireNonNull(dto, "createDTO");
    return new CreateThreadCommand(
        toBranchSettings(dto.getBranchSettings()),
        requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

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

  public static ThreadCommandBatch toCommandBatch(
      String threadId, HarnessThreadCommandBatchDTO dto) {
    requireNonNull(dto, "batchDTO");
    List<HarnessThreadCommandCreateDTO> commands = requireList(dto.getCommands(), "commands");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("command batch must contain at least one command");
    }
    List<NewThreadCommand> domain = new ArrayList<>(commands.size());
    for (HarnessThreadCommandCreateDTO command : commands) {
      domain.add(toNewThreadCommand(command));
    }
    return new ThreadCommandBatch(
        parseUuid(threadId, "threadId"),
        parseUuid(dto.getExpectedHeadEntryId(), "expectedHeadEntryId"),
        parsePositiveDecimal(dto.getExpectedNextCommandSequence(), "expectedNextCommandSequence"),
        domain);
  }

  /** 在严格字段规则下把一个 typed command DTO 映射为 domain payload，并按 raw 请求形态计算 requestHash。 */
  public static NewThreadCommand toNewThreadCommand(HarnessThreadCommandCreateDTO dto) {
    requireNonNull(dto, "commandDTO");
    ThreadCommandType type = requireType(dto.getType());
    ThreadCommandPayload payload = toPayload(type, dto);
    return new NewThreadCommand(
        payload,
        parseUuid(dto.getClientCommandId(), "clientCommandId"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  public static MoveHeadCommand toMoveHeadCommand(String threadId, HarnessThreadHeadUpdateDTO dto) {
    requireNonNull(dto, "headUpdateDTO");
    return new MoveHeadCommand(
        parseUuid(threadId, "threadId"),
        parseUuid(dto.getTargetEntryId(), "targetEntryId"),
        parseNonNegativeDecimal(dto.getExpectedRevision(), "expectedRevision"));
  }

  public static StopCommand toStopCommand(String threadId, HarnessThreadStopDTO dto) {
    requireNonNull(dto, "stopDTO");
    return new StopCommand(
        parseUuid(threadId, "threadId"),
        parseUuid(dto.getStopRequestId(), "stopRequestId"),
        parseNonNegativeDecimal(dto.getExpectedRevision(), "expectedRevision"));
  }

  public static SetThreadYoloCommand toSetThreadYoloCommand(
      String threadId, HarnessThreadYoloUpdateDTO dto) {
    requireNonNull(dto, "yoloUpdateDTO");
    return new SetThreadYoloCommand(
        parseUuid(threadId, "threadId"),
        parseNonNegativeDecimal(dto.getExpectedRevision(), "expectedRevision"),
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

  private static ThreadCommandPayload toPayload(
      ThreadCommandType type, HarnessThreadCommandCreateDTO dto) {
    return switch (type) {
      case USER_MESSAGE -> {
        requireForbidden(
            dto, "content", "role", "agentName", "model", "activeTools", "environment");
        yield new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, toUserMessageContents(dto)));
      }
      case CUSTOM_MESSAGE -> {
        requireForbidden(dto, "contents", "agentName", "model", "activeTools", "environment");
        AgentMessageRole role = requireRole(dto.getRole());
        yield new CustomMessageCommandPayload(
            new AgentMessage(
                role, List.of(new TextMessageContent(requireText(dto.getContent(), "content")))));
      }
      case SET_AGENT -> {
        requireForbidden(dto, "content", "contents", "role", "model", "activeTools", "environment");
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case SET_MODEL -> {
        requireForbidden(
            dto, "content", "contents", "role", "agentName", "activeTools", "environment");
        yield new SetModelCommandPayload(toModelSelection(requireNonNull(dto.getModel(), "model")));
      }
      case SET_ACTIVE_TOOLS -> {
        requireForbidden(dto, "content", "contents", "role", "agentName", "model", "environment");
        yield new SetActiveToolsCommandPayload(requireList(dto.getActiveTools(), "activeTools"));
      }
      case SET_ENVIRONMENT -> {
        requireForbidden(dto, "content", "contents", "role", "agentName", "model", "activeTools");
        if (!dto.hasEnvironmentField()) {
          throw new IllegalArgumentException(
              "SET_ENVIRONMENT must contain environment (a binding object selects, null unbinds)");
        }
        yield new SetEnvironmentCommandPayload(toEnvironmentBinding(dto.getEnvironment()));
      }
    };
  }

  private static ThreadCommandType requireType(String type) {
    if (type == null) {
      throw new IllegalArgumentException("type must not be null");
    }
    try {
      return ThreadCommandType.valueOf(type);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unknown command type: " + type, error);
    }
  }

  private static AgentMessageRole requireRole(String role) {
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    AgentMessageRole parsed;
    try {
      parsed = AgentMessageRole.valueOf(role);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unknown message role: " + role, error);
    }
    if (parsed != AgentMessageRole.SYSTEM && parsed != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be SYSTEM or USER: " + role);
    }
    return parsed;
  }

  /**
   * 映射有序 USER_MESSAGE contents 列表（Canvas 首次发送等非 thread-command 路径复用）。
   *
   * <p>与 {@link #toUserMessageContents(HarnessThreadCommandCreateDTO)} 的 contents 分支共享严格字段校验。
   */
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

  private static List<AgentMessageContent> toUserMessageContents(
      HarnessThreadCommandCreateDTO dto) {
    if (!dto.hasContentsField()) {
      throw new IllegalArgumentException(
          "USER_MESSAGE must contain exactly one non-empty contents list of TEXT/ATTACHMENT");
    }
    return toUserMessageContents(dto.getContents());
  }

  private static AgentMessageContent toUserMessageContent(
      HarnessUserMessageContentDTO dto, int index) {
    String context = "contents[" + index + "]";
    String type = requireText(dto.getType(), context + ".type");
    return switch (type) {
      case "TEXT" -> {
        requireContentForbidden(dto, context, "uploadId");
        yield new TextMessageContent(requireText(dto.getText(), context + ".text"));
      }
      case "ATTACHMENT" -> {
        requireContentForbidden(dto, context, "text");
        // 瞬时 upload 引用：canonical UUID string；READY upload 由应用 use-case 在入队事务内消费。
        yield new AttachmentMessageContent(parseUuid(dto.getUploadId(), context + ".uploadId"));
      }
      default -> throw new IllegalArgumentException(
          context + ".type must be one of TEXT, ATTACHMENT: " + type);
    };
  }

  private static void requireContentForbidden(
      HarnessUserMessageContentDTO dto, String context, String... fields) {
    for (String field : fields) {
      Object value =
          switch (field) {
            case "text" -> dto.hasTextField() ? Boolean.TRUE : null;
            case "uploadId" -> dto.hasUploadIdField() ? Boolean.TRUE : null;
            default -> throw new IllegalArgumentException("unknown content field: " + field);
          };
      if (value != null) {
        throw new IllegalArgumentException(
            "field " + context + "." + field + " is forbidden for content type " + dto.getType());
      }
    }
  }

  private static void requireForbidden(HarnessThreadCommandCreateDTO dto, String... fields) {
    for (String field : fields) {
      Object value =
          switch (field) {
            case "content" -> dto.hasContentField() ? Boolean.TRUE : null;
            case "contents" -> dto.hasContentsField() ? Boolean.TRUE : null;
            case "role" -> dto.getRole();
            case "agentName" -> dto.getAgentName();
            case "model" -> dto.getModel();
            case "activeTools" -> dto.getActiveTools();
            case "environment" -> dto.hasEnvironmentField() ? Boolean.TRUE : null;
            default -> throw new IllegalArgumentException("unknown field: " + field);
          };
      if (value != null) {
        throw new IllegalArgumentException(
            "field " + field + " is forbidden for command type " + dto.getType());
      }
    }
  }

  private static String deriveStatus(ThreadSnapshot snapshot) {
    ThreadContext context =
        CLASSIFIER.classify(
            snapshot.thread(), snapshot.entryPath(), snapshot.model(), snapshot.toolSiblings());
    return switch (context) {
      case ThreadContext.IdleOrHistorical ignored -> "IDLE";
      case ThreadContext.ContinuationDue ignored -> "CONTINUATION_DUE";
      case ThreadContext.ModelActive active -> "MODEL_" + active.model().status().name();
      case ThreadContext.ModelTerminalPending ignored -> "APPLYING";
      case ThreadContext.ToolActive active -> "TOOL_" + toolStatus(active.siblings());
      case ThreadContext.ToolTerminalPending ignored -> "APPLYING";
    };
  }

  /** TOOL_ACTIVE 上下文下 Tool siblings 的交互/在途阻塞状态优先级。 */
  private static String toolStatus(List<ToolInvocation> siblings) {
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.WAITING_APPROVAL) {
        return ToolInvocationStatus.WAITING_APPROVAL.name();
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.RUNNING) {
        return ToolInvocationStatus.RUNNING.name();
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.DISPATCHING) {
        return ToolInvocationStatus.DISPATCHING.name();
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.READY) {
        return ToolInvocationStatus.READY.name();
      }
    }
    throw new IllegalStateException(
        "TOOL_ACTIVE context must contain at least one non-terminal tool invocation");
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
