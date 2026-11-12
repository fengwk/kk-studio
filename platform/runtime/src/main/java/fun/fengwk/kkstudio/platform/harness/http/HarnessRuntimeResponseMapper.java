package fun.fengwk.kkstudio.platform.harness.http;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.ThreadTreeNode;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolFileMutationOrder;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessActiveAssistantOutputDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAgentMessageContentDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAssistantErrorDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCancelledInputDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessExecutionExitDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessGenerationFailureDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessStoppedThreadReceiptDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Harness Runtime domain 事实到严格 HTTP DTO 的响应投影。 */
public final class HarnessRuntimeResponseMapper {

  private static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS =
      new HistoryEntryPayloadJsonCodec();
  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();
  private static final ToolApprovalJsonCodec TOOL_APPROVALS = new ToolApprovalJsonCodec();
  private static final StrictJson JSON = StrictJson.standard();

  private HarnessRuntimeResponseMapper() {}

  /**
   * 映射一次事务内读出的执行树。节点顺序保持 Runtime 的 {@code createdAt} 再 UUID 序，不在此重排。
   *
   * <p>只取名称、当前模型、运行状态和当前路径计数；不序列化 Entry、Command 或 Tool 载荷。
   */
  public static List<HarnessThreadTreeNodeDTO> toThreadTreeDtos(List<ThreadTreeNode> nodes) {
    Objects.requireNonNull(nodes, "nodes");
    return mapAll(nodes, HarnessRuntimeResponseMapper::toThreadTreeNodeDto);
  }

  private static HarnessThreadTreeNodeDTO toThreadTreeNodeDto(ThreadTreeNode node) {
    Objects.requireNonNull(node, "node");
    HarnessThreadTreeNodeDTO dto = new HarnessThreadTreeNodeDTO();
    dto.setThreadId(node.thread().id().toString());
    dto.setKind(node.thread().kind());
    dto.setParentThreadId(
        node.thread().parentThreadId() == null ? null : node.thread().parentThreadId().toString());
    dto.setName(node.thread().name());
    dto.setAgentName(node.branchSettings().agentName());
    dto.setExecution(toExecutionSelectionDto(node.branchSettings().execution()));
    dto.setStatus(node.status());
    dto.setExecutionControl(node.thread().executionControl());
    dto.setTurnCount(node.metrics().turnCount());
    dto.setToolCallCount(node.metrics().toolCallCount());
    dto.setOutcome(node.outcome());
    dto.setUpdatedAt(node.thread().updatedAt());
    return dto;
  }

  /**
   * 映射一个一致的 Thread 快照；DTO 列表为不可变副本。
   *
   * <p>对外状态完全来自 {@link ThreadSnapshot#runtimeStatus()}：不注入委派/子树状态，Thread API 的结果只取决于该 Thread 自己的
   * durable 事实。
   */
  public static HarnessThreadDTO toThreadDto(ThreadSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(snapshot.thread().id().toString());
    dto.setParentThreadId(
        snapshot.thread().parentThreadId() == null
            ? null
            : snapshot.thread().parentThreadId().toString());
    dto.setName(snapshot.thread().name());
    dto.setSessionId(snapshot.thread().sessionId().toString());
    dto.setHeadEntryId(snapshot.thread().headEntryId().toString());
    dto.setRootThreadId(snapshot.thread().rootThreadId().toString());
    dto.setYoloEnabled(snapshot.thread().yoloEnabled());
    dto.setNextCommandSequence(snapshot.thread().nextCommandSequence());
    dto.setVersion(snapshot.thread().version());
    ThreadRuntimeStatus status = snapshot.runtimeStatus();
    dto.setStatus(status);
    dto.setExecutionControl(snapshot.thread().executionControl());
    dto.setBranchSettings(toBranchSettingsDto(snapshot.entryState().baseSettings()));
    dto.setCreatedAt(snapshot.thread().createdAt());
    dto.setUpdatedAt(snapshot.thread().updatedAt());
    return dto;
  }

  private static HarnessBranchSettingsDTO toBranchSettingsDto(BranchSettings settings) {
    Objects.requireNonNull(settings, "settings");
    HarnessBranchSettingsDTO dto = new HarnessBranchSettingsDTO();
    dto.setAgentName(settings.agentName());
    dto.setExecution(toExecutionSelectionDto(settings.execution()));
    // Environment 引用平铺为 nullable canonical name；null 显式保留（@JsonInclude(ALWAYS)）。
    dto.setEnvironmentName(settings.environmentName());
    return dto;
  }

  private static HarnessExecutionSelectionDTO toExecutionSelectionDto(
      ExecutionSelection selection) {
    Objects.requireNonNull(selection, "selection");
    HarnessExecutionSelectionDTO dto = new HarnessExecutionSelectionDTO();
    dto.setDriver(selection.driver());
    dto.setSettings(JSON.read(selection.settingsJson(), JsonNode.class, "execution.settings"));
    return dto;
  }

  /** Entry 投影；{@code usageCost} 是读取时按当前目录价现算的费用（不可计价时为 null），绝不进入 {@code payload}。 */
  public static HarnessSessionEntryDTO toEntryDto(Entry entry, HarnessUsageCostDTO usageCost) {
    Objects.requireNonNull(entry, "entry");
    HarnessSessionEntryDTO dto = new HarnessSessionEntryDTO();
    dto.setEntryId(entry.id().toString());
    dto.setSessionId(entry.sessionId().toString());
    dto.setParentEntryId(entry.parentEntryId() == null ? null : entry.parentEntryId().toString());
    dto.setEntryType(entry.payload().type());
    dto.setPayload(ENTRY_PAYLOADS.encodeNode(entry.payload()));
    dto.setCreatedAt(entry.createdAt());
    dto.setUsageCost(usageCost);
    return dto;
  }

  static HarnessThreadCommandDTO toCommandDto(ThreadCommand command) {
    Objects.requireNonNull(command, "command");
    HarnessThreadCommandDTO dto = new HarnessThreadCommandDTO();
    dto.setThreadId(command.threadId().toString());
    dto.setSequence(command.sequence());
    dto.setType(command.type());
    dto.setState(command.state());
    dto.setIdempotencyKey(command.idempotencyKey().toString());
    dto.setPayload(COMMAND_PAYLOADS.encodeNode(command.payload()));
    dto.setCancelledAt(command.cancelledAt());
    dto.setCreatedAt(command.createdAt());
    return dto;
  }

  static HarnessActiveAssistantOutputDTO toActiveAssistantOutputDto(
      GenerationProjection generation) {
    Objects.requireNonNull(generation, "generation");
    HarnessActiveAssistantOutputDTO dto = new HarnessActiveAssistantOutputDTO();
    dto.setGenerationId(generation.generationId().toString());
    dto.setTurnStartEntryId(generation.turnStartEntryId().toString());
    dto.setRequestHeadEntryId(generation.requestHeadEntryId().toString());
    dto.setStatus(generation.status());
    dto.setAttempt(generation.attempt());
    dto.setSequence(generation.sequence());
    dto.setContents(
        mapAll(generation.outputContents(), HarnessRuntimeResponseMapper::toContentDto));
    if (generation.error() != null) {
      HarnessAssistantErrorDTO error = new HarnessAssistantErrorDTO();
      error.setCode(generation.error().code());
      error.setMessage(generation.error().message());
      dto.setError(error);
    }
    dto.setResultEntryId(
        generation.resultEntryId() == null ? null : generation.resultEntryId().toString());
    dto.setCreatedAt(generation.createdAt());
    dto.setUpdatedAt(generation.updatedAt());
    return dto;
  }

  private record ToolFileMutationGroup(UUID assistantEntryId, ToolFileMutationOrder.Key fileKey) {}

  private static List<ToolInvocationDTO> toToolInvocationDtos(List<ToolInvocation> invocations) {
    Map<ToolFileMutationGroup, TreeMap<Integer, ToolInvocation>> grouped = new HashMap<>();
    List<ToolFileMutationOrder.Key> fileKeys = new ArrayList<>(invocations.size());
    for (ToolInvocation invocation : invocations) {
      ToolFileMutationOrder.Key key = ToolFileMutationOrder.key(invocation).orElse(null);
      fileKeys.add(key);
      if (key != null) {
        grouped
            .computeIfAbsent(
                new ToolFileMutationGroup(invocation.assistantEntryId(), key),
                unused -> new TreeMap<>())
            .put(invocation.callIndex(), invocation);
      }
    }

    List<ToolInvocationDTO> dtos = new ArrayList<>(invocations.size());
    for (int index = 0; index < invocations.size(); index++) {
      ToolInvocation invocation = invocations.get(index);
      ToolInvocationDTO dto = toToolInvocationDto(invocation);
      ToolFileMutationOrder.Key key = fileKeys.get(index);
      if (key != null) {
        Map.Entry<Integer, ToolInvocation> previous =
            grouped
                .get(new ToolFileMutationGroup(invocation.assistantEntryId(), key))
                .lowerEntry(invocation.callIndex());
        if (previous != null) {
          dto.setQueuedBehindToolCallId(previous.getValue().call().id());
        }
      }
      dtos.add(dto);
    }
    return List.copyOf(dtos);
  }

  public static ToolInvocationDTO toToolInvocationDto(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(invocation.id().toString());
    dto.setThreadId(invocation.threadId().toString());
    dto.setTurnStartEntryId(invocation.turnStartEntryId().toString());
    dto.setAssistantEntryId(invocation.assistantEntryId().toString());
    dto.setCallIndex(invocation.callIndex());
    dto.setStatus(invocation.status());
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
    dto.setApproval(
        invocation.approval() == null ? null : TOOL_APPROVALS.encodeNode(invocation.approval()));
    dto.setResult(
        invocation.result() == null ? null : ToolResultJsonCodec.encodeNode(invocation.result()));
    dto.setError(invocation.error() == null ? null : JSON.tree(invocation.error()));
    dto.setCreatedAt(invocation.createdAt());
    dto.setUpdatedAt(invocation.updatedAt());
    return dto;
  }

  /**
   * 映射一个一致的 Thread 快照；DTO 列表为不可变副本。
   *
   * <p>只投影本次返回的 Entry slice，并为这些 Entry 带上读取时费用。完整读取的 {@code baseEntryId} 为 null。
   */
  public static HarnessThreadSnapshotDTO toSnapshotDto(
      ThreadSnapshot snapshot, Map<UUID, HarnessUsageCostDTO> usageCosts) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(usageCosts, "usageCosts");
    HarnessThreadSnapshotDTO dto = new HarnessThreadSnapshotDTO();
    dto.setVersion(snapshot.thread().version());
    dto.setThread(toThreadDto(snapshot));
    dto.setBaseEntryId(
        snapshot.entrySlice().baseEntryId() == null
            ? null
            : snapshot.entrySlice().baseEntryId().toString());
    dto.setEntries(
        mapAll(
            snapshot.entrySlice().entries(),
            entry -> toEntryDto(entry, usageCosts.get(entry.id()))));
    dto.setQueuedCommands(
        mapAll(snapshot.queuedCommands(), HarnessRuntimeResponseMapper::toCommandDto));
    GenerationProjection generation = snapshot.generation();
    dto.setActiveAssistantOutput(
        generation == null ? null : toActiveAssistantOutputDto(generation));
    dto.setToolInvocations(toToolInvocationDtos(snapshot.toolSiblings()));
    dto.setGenerationFailures(
        mapAll(
            snapshot.generationFailures(), failure -> toGenerationFailureDto(failure, generation)));
    dto.setStopReceipts(toStopReceiptDtos(snapshot.stopReceipts()));
    dto.setUnconfirmedExecutions(
        mapAll(snapshot.unconfirmedExecutions(), HarnessRuntimeResponseMapper::toExecutionExitDto));
    return dto;
  }

  /** 映射独立压缩 advisory；可用时 disabledReason 显式为 null。 */
  public static HarnessManualCompactionDTO toManualCompactionDto(
      ManualCompactionAvailability availability) {
    Objects.requireNonNull(availability, "availability");
    HarnessManualCompactionDTO dto = new HarnessManualCompactionDTO();
    dto.setAvailable(availability.available());
    dto.setDisabledReason(availability.available() ? null : availability.disabledReason().name());
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
    requireSameThread(
        accepted.thread().id(),
        currentSnapshot,
        "accepted result and current snapshot thread do not match");
    HarnessAcceptedCommandsDTO dto = new HarnessAcceptedCommandsDTO();
    dto.setSession(toSessionDto(accepted.session()));
    // 创建回执只回显 root entry 本身，它不是读取投影，因此显式不带费用。
    dto.setRootEntry(toEntryDto(accepted.rootEntry(), null));
    dto.setThread(toThreadDto(currentSnapshot));
    dto.setAcceptedCommands(
        mapAll(accepted.acceptedCommands(), HarnessRuntimeResponseMapper::toCommandDto));
    dto.setReplayed(accepted.replayed());
    return dto;
  }

  /** 映射手动压缩的提交结果，并使用提交后重新读取的当前 Thread snapshot 投影 Thread。 */
  public static HarnessThreadCompactResultDTO toCompactResultDto(
      CompactThreadResult result, ThreadSnapshot currentSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(currentSnapshot, "currentSnapshot");
    requireSameThread(
        result.thread().id(),
        currentSnapshot,
        "compact result and current snapshot thread do not match");
    HarnessThreadCompactResultDTO dto = new HarnessThreadCompactResultDTO();
    dto.setThread(toThreadDto(currentSnapshot));
    dto.setTurnStartEntryId(result.turnStartEntryId().toString());
    dto.setChildThreadId(result.childThreadId() == null ? null : result.childThreadId().toString());
    return dto;
  }

  /** 映射一次 Stop 结果及权威的 stop 后快照。 */
  public static HarnessThreadStopResultDTO toStopResultDto(
      StopResult result, ThreadSnapshot postStopSnapshot) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(postStopSnapshot, "postStopSnapshot");
    requireSameThread(
        result.thread().id(), postStopSnapshot, "post-stop snapshot does not match Stop result");
    if (result.thread().version() != postStopSnapshot.thread().version()) {
      throw new IllegalArgumentException("post-stop snapshot does not match Stop result");
    }
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStatus(
        result.replayed()
            ? HarnessThreadStopResultDTO.Status.REPLAYED
            : HarnessThreadStopResultDTO.Status.STOPPED);
    dto.setThread(toThreadDto(postStopSnapshot));
    dto.setStoppedThreads(toStopReceiptDtos(result.stoppedThreads()));
    return dto;
  }

  private static HarnessAgentMessageContentDTO toContentDto(AgentMessageContent content) {
    HarnessAgentMessageContentDTO contentDto = new HarnessAgentMessageContentDTO();
    switch (content) {
      case TextMessageContent text -> {
        contentDto.setType(HarnessAgentMessageContentDTO.Type.TEXT);
        contentDto.setText(text.text());
      }
      case ThinkingMessageContent thinking -> {
        contentDto.setType(HarnessAgentMessageContentDTO.Type.THINKING);
        contentDto.setText(thinking.text());
      }
      default -> throw new IllegalStateException(
          "unsupported public assistant output content: " + content.getClass().getName());
    }
    return contentDto;
  }

  private static HarnessGenerationFailureDTO toGenerationFailureDto(
      FailureProjection failure, GenerationProjection generation) {
    if (generation == null) {
      throw new IllegalStateException("failure projection requires a generation projection");
    }
    HarnessGenerationFailureDTO failureDto = new HarnessGenerationFailureDTO();
    failureDto.setGenerationId(generation.generationId().toString());
    failureDto.setTurnStartEntryId(generation.turnStartEntryId().toString());
    failureDto.setRequestHeadEntryId(generation.requestHeadEntryId().toString());
    failureDto.setAttempt(failure.attempt());
    failureDto.setSequence(failure.sequence());
    failureDto.setText(failure.text());
    failureDto.setThinking(failure.thinking());
    failureDto.setErrorCode(failure.errorCode());
    failureDto.setErrorMessage(failure.errorMessage());
    failureDto.setFailedAt(failure.failedAt());
    failureDto.setRetryAt(failure.retryAt());
    return failureDto;
  }

  private static HarnessExecutionExitDTO toExecutionExitDto(ExecutionExitFact fact) {
    HarnessExecutionExitDTO execution = new HarnessExecutionExitDTO();
    execution.setExecutionId(fact.executionId().toString());
    execution.setInvocationId(fact.invocationId().toString());
    execution.setAttempt(fact.attempt());
    execution.setKind(fact.kind());
    execution.setRegisteredAt(fact.registeredAt());
    return execution;
  }

  private static HarnessCancelledInputDTO toCancelledInputDto(CancelledThreadInput input) {
    HarnessCancelledInputDTO inputDto = new HarnessCancelledInputDTO();
    inputDto.setSequence(input.sequence());
    inputDto.setIdempotencyKey(input.idempotencyKey().toString());
    inputDto.setType(input.payload().type());
    inputDto.setPayload(COMMAND_PAYLOADS.encodeNode(input.payload()));
    return inputDto;
  }

  /** 列表映射的唯一入口：保留源顺序、不可变副本与逐项 null 拒绝语义。 */
  private static <T, R> List<R> mapAll(List<T> source, Function<T, R> mapper) {
    List<R> mapped = new ArrayList<>(source.size());
    for (T element : source) {
      mapped.add(mapper.apply(element));
    }
    return List.copyOf(mapped);
  }

  /** 写后回读的唯一一致性判定：结果与当前快照必须指向同一 Thread。 */
  private static void requireSameThread(
      UUID expectedThreadId, ThreadSnapshot snapshot, String message) {
    if (!expectedThreadId.equals(snapshot.thread().id())) {
      throw new IllegalArgumentException(message);
    }
  }

  /** 映射 Stop 的聚合回执集合；只投影可恢复人工输入，不把 CUSTOM_MESSAGE / NOTIFICATION 当作草稿。 */
  private static List<HarnessStoppedThreadReceiptDTO> toStopReceiptDtos(
      List<StoppedThreadReceipt> receipts) {
    Objects.requireNonNull(receipts, "receipts");
    return mapAll(receipts, HarnessRuntimeResponseMapper::toStopReceiptDto);
  }

  private static HarnessStoppedThreadReceiptDTO toStopReceiptDto(StoppedThreadReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    HarnessStoppedThreadReceiptDTO dto = new HarnessStoppedThreadReceiptDTO();
    dto.setThreadId(receipt.threadId().toString());
    dto.setStopRequestId(receipt.stopRequestId().toString());
    dto.setStoppedTurnEndEntryId(
        receipt.stoppedTurnEndEntryId() == null
            ? null
            : receipt.stoppedTurnEndEntryId().toString());
    dto.setCancelledCommandCount(receipt.cancelledCommandCount());
    dto.setCancelledInputs(
        mapAll(receipt.cancelledInputs(), HarnessRuntimeResponseMapper::toCancelledInputDto));
    return dto;
  }
}
