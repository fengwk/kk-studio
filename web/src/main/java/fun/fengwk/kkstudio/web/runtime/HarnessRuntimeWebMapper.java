package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetActiveToolsCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetThinkingLevelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetYoloCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
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
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict DTO&lt;-&gt;domain mapper of the Harness Runtime HTTP boundary.
 *
 * <p>Entity ids are strict positive decimal strings ({@code [1-9][0-9]*}) of the underlying bigint
 * values; revisions are strict non-negative decimal strings. Domain JSON payloads are
 * encoded/decoded exclusively through the canonical runtime codecs. The Thread status and
 * processing flag are derived deterministically from the {@link ThreadSnapshot} via the shared
 * {@link ThreadContextClassifier}: IDLE / CONTINUATION_DUE / MODEL_&lt;status&gt; /
 * TOOL_&lt;status&gt; / APPLYING; processing is false only for IDLE.
 *
 * <p>All returned DTO lists are immutable copies.
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

  // ---------- Strict decimal parsing ----------

  /** Parses a strict positive decimal id ({@code [1-9][0-9]*}) within long range. */
  public static long parsePositiveId(String value, String field) {
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

  /** Parses a strict non-negative decimal revision cursor ({@code 0|[1-9][0-9]*}). */
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

  // ---------- Domain -> DTO ----------

  /** Maps one consistent Thread snapshot; the DTO lists are immutable copies. */
  public static HarnessThreadDTO toThreadDto(ThreadSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(Long.toString(snapshot.thread().id()));
    dto.setSessionId(Long.toString(snapshot.entryPath().root().sessionId()));
    dto.setHeadEntryId(Long.toString(snapshot.thread().headEntryId()));
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
    dto.setEnvironmentId(
        settings.environmentId() == null ? null : settings.environmentId().value());
    dto.setAgentName(settings.agentName());
    dto.setModel(toModelSelectionDto(settings.model()));
    dto.setThinkingLevel(settings.thinkingLevel());
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
    dto.setEntryId(Long.toString(entry.id()));
    dto.setSessionId(Long.toString(entry.sessionId()));
    dto.setParentEntryId(
        entry.parentEntryId() == null ? null : Long.toString(entry.parentEntryId()));
    dto.setEntryType(entry.payload().type().name());
    dto.setPayloadJson(ENTRY_PAYLOADS.encode(entry.payload()));
    dto.setCreateTime(entry.createdAt());
    return dto;
  }

  public static HarnessThreadCommandDTO toCommandDto(ThreadCommand command) {
    Objects.requireNonNull(command, "command");
    HarnessThreadCommandDTO dto = new HarnessThreadCommandDTO();
    dto.setCommandId(Long.toString(command.id()));
    dto.setThreadId(Long.toString(command.threadId()));
    dto.setSequence(Long.toString(command.sequence()));
    dto.setType(command.type().name());
    dto.setState(command.state().name());
    dto.setClientCommandId(command.clientCommandId());
    dto.setPayloadJson(COMMAND_PAYLOADS.encode(command.payload()));
    dto.setConsumedTurnStartEntryId(
        command.consumedTurnStartEntryId() == null
            ? null
            : Long.toString(command.consumedTurnStartEntryId()));
    dto.setCancelledAt(command.cancelledAt());
    dto.setCreateTime(command.createdAt());
    return dto;
  }

  public static ModelInvocationDTO toModelInvocationDto(ModelInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ModelInvocationDTO dto = new ModelInvocationDTO();
    dto.setId(Long.toString(invocation.id()));
    dto.setThreadId(Long.toString(invocation.threadId()));
    dto.setTurnStartEntryId(Long.toString(invocation.turnStartEntryId()));
    dto.setBasisHeadEntryId(Long.toString(invocation.basisHeadEntryId()));
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
        invocation.resultEntryId() == null ? null : Long.toString(invocation.resultEntryId()));
    dto.setCreateTime(invocation.createdAt());
    dto.setUpdateTime(invocation.updatedAt());
    return dto;
  }

  public static ToolInvocationDTO toToolInvocationDto(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(Long.toString(invocation.id()));
    dto.setModelInvocationId(Long.toString(invocation.modelInvocationId()));
    dto.setAssistantEntryId(Long.toString(invocation.assistantEntryId()));
    dto.setOrdinal(invocation.ordinal());
    dto.setStatus(invocation.status().name());
    dto.setAttempt(invocation.attempt());
    dto.setToolCallId(invocation.request().call().id());
    ToolBinding binding = invocation.request().binding();
    dto.setToolName(binding.descriptor().name());
    dto.setToolVersion(binding.descriptor().version());
    dto.setToolType(binding.type().name());
    dto.setEnvironmentId(binding.environmentId() == null ? null : binding.environmentId().value());
    dto.setArgumentsJson(invocation.request().call().argumentsJson());
    dto.setApprovalJson(
        invocation.approval() == null ? null : TOOL_APPROVALS.encode(invocation.approval()));
    dto.setResultJson(
        invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result()));
    dto.setErrorJson(invocation.error() == null ? null : TOOL_ERRORS.encode(invocation.error()));
    dto.setResultEntryId(
        invocation.resultEntryId() == null ? null : Long.toString(invocation.resultEntryId()));
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
    return dto;
  }

  /** Maps the initial ROOT-only snapshot returned by create-thread. */
  public static HarnessThreadDTO toCreatedThreadDto(CreatedThread created) {
    Objects.requireNonNull(created, "created");
    return toThreadDto(
        new ThreadSnapshot(
            created.thread(),
            new EntryPath(List.of(created.rootEntry())),
            List.of(),
            null,
            List.of()));
  }

  /** Maps one Stop result together with the authoritative post-stop snapshot. */
  public static HarnessThreadStopResultDTO toStopResultDto(
      StopResult result, ThreadSnapshot postStopSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(postStopSnapshot, "postStopSnapshot");
    if (result.thread().id() != postStopSnapshot.thread().id()
        || result.thread().revision() != postStopSnapshot.thread().revision()) {
      throw new IllegalArgumentException("post-stop snapshot does not match Stop result");
    }
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStatus(result.status().name());
    dto.setThread(toThreadDto(postStopSnapshot));
    dto.setStoppedTurnEndEntryId(
        result.stoppedTurnEndEntryId() == null
            ? null
            : Long.toString(result.stoppedTurnEndEntryId()));
    dto.setCancelledCommandCount(result.cancelledCommandCount());
    return dto;
  }

  // ---------- DTO -> domain ----------

  public static CreateThreadCommand toCreateThreadCommand(HarnessThreadCreateDTO dto) {
    requireNonNull(dto, "createDTO");
    return new CreateThreadCommand(
        dto.getTitle(),
        toBranchSettings(dto.getBranchSettings()),
        requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

  public static BranchSettings toBranchSettings(HarnessBranchSettingsDTO dto) {
    requireNonNull(dto, "branchSettings");
    return new BranchSettings(
        toEnvironmentId(dto.getEnvironmentId()),
        requireText(dto.getAgentName(), "branchSettings.agentName"),
        toModelSelection(dto.getModel()),
        requireText(dto.getThinkingLevel(), "branchSettings.thinkingLevel"),
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
        parsePositiveId(threadId, "threadId"),
        parsePositiveId(dto.getExpectedHeadEntryId(), "expectedHeadEntryId"),
        parsePositiveId(dto.getExpectedNextCommandSequence(), "expectedNextCommandSequence"),
        domain);
  }

  /** Maps one typed command DTO into its domain payload under strict field rules. */
  public static NewThreadCommand toNewThreadCommand(HarnessThreadCommandCreateDTO dto) {
    requireNonNull(dto, "commandDTO");
    ThreadCommandType type = requireType(dto.getType());
    return new NewThreadCommand(
        toPayload(type, dto), requireText(dto.getClientCommandId(), "clientCommandId"));
  }

  public static MoveHeadCommand toMoveHeadCommand(String threadId, HarnessThreadHeadUpdateDTO dto) {
    requireNonNull(dto, "headUpdateDTO");
    return new MoveHeadCommand(
        parsePositiveId(threadId, "threadId"),
        parsePositiveId(dto.getTargetEntryId(), "targetEntryId"),
        parseNonNegativeDecimal(dto.getExpectedRevision(), "expectedRevision"));
  }

  public static StopCommand toStopCommand(String threadId, HarnessThreadStopDTO dto) {
    requireNonNull(dto, "stopDTO");
    return new StopCommand(
        parsePositiveId(threadId, "threadId"),
        requireText(dto.getStopRequestId(), "stopRequestId"),
        parseNonNegativeDecimal(dto.getExpectedRevision(), "expectedRevision"));
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
        parsePositiveId(threadId, "threadId"),
        parsePositiveId(toolInvocationId, "toolInvocationId"),
        parsed,
        requireText(dto.getDecisionId(), "decisionId"),
        requireText(dto.getActor(), "actor"),
        dto.getReason());
  }

  // ---------- Internal helpers ----------

  private static ThreadCommandPayload toPayload(
      ThreadCommandType type, HarnessThreadCommandCreateDTO dto) {
    return switch (type) {
      case USER_MESSAGE -> {
        requireForbidden(
            dto,
            "role",
            "agentName",
            "model",
            "thinkingLevel",
            "activeTools",
            "yoloEnabled",
            "environmentId");
        yield new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new TextMessageContent(requireText(dto.getContent(), "content")))));
      }
      case CUSTOM_MESSAGE -> {
        requireForbidden(
            dto,
            "agentName",
            "model",
            "thinkingLevel",
            "activeTools",
            "yoloEnabled",
            "environmentId");
        AgentMessageRole role = requireRole(dto.getRole());
        yield new CustomMessageCommandPayload(
            new AgentMessage(
                role, List.of(new TextMessageContent(requireText(dto.getContent(), "content")))));
      }
      case SET_AGENT -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "model",
            "thinkingLevel",
            "activeTools",
            "yoloEnabled",
            "environmentId");
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case SET_MODEL -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "agentName",
            "thinkingLevel",
            "activeTools",
            "yoloEnabled",
            "environmentId");
        yield new SetModelCommandPayload(toModelSelection(requireNonNull(dto.getModel(), "model")));
      }
      case SET_THINKING_LEVEL -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "agentName",
            "model",
            "activeTools",
            "yoloEnabled",
            "environmentId");
        yield new SetThinkingLevelCommandPayload(
            requireText(dto.getThinkingLevel(), "thinkingLevel"));
      }
      case SET_ACTIVE_TOOLS -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "agentName",
            "model",
            "thinkingLevel",
            "yoloEnabled",
            "environmentId");
        yield new SetActiveToolsCommandPayload(requireList(dto.getActiveTools(), "activeTools"));
      }
      case SET_YOLO -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "agentName",
            "model",
            "thinkingLevel",
            "activeTools",
            "environmentId");
        yield new SetYoloCommandPayload(requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
      }
      case SET_ENVIRONMENT -> {
        requireForbidden(
            dto,
            "content",
            "role",
            "agentName",
            "model",
            "thinkingLevel",
            "activeTools",
            "yoloEnabled");
        yield new SetEnvironmentCommandPayload(toEnvironmentId(dto.getEnvironmentId()));
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

  private static void requireForbidden(HarnessThreadCommandCreateDTO dto, String... fields) {
    for (String field : fields) {
      Object value =
          switch (field) {
            case "content" -> dto.getContent();
            case "role" -> dto.getRole();
            case "agentName" -> dto.getAgentName();
            case "model" -> dto.getModel();
            case "thinkingLevel" -> dto.getThinkingLevel();
            case "activeTools" -> dto.getActiveTools();
            case "yoloEnabled" -> dto.getYoloEnabled();
            case "environmentId" -> dto.getEnvironmentId();
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

  /** Interactive/in-flight blocker precedence of the Tool siblings of a TOOL_ACTIVE context. */
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

  private static EnvironmentId toEnvironmentId(String value) {
    return value == null ? null : new EnvironmentId(value);
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
