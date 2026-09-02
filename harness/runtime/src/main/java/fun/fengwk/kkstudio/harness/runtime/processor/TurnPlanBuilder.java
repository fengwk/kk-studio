package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestResult;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 纯 speculative turn planner：基于 plan 事务捕获的 source EntryPath 与 queued Command 快照构造完整合法 candidate
 * EntryPath，不接触 Store、不写任何 durable 状态。Thread YOLO 不进入 plan。
 *
 * <p>CONTINUATION 消费普通配置命令（SET_AGENT / SET_MODEL）与 SYSTEM CUSTOM_MESSAGE（用于 task soft steering），保留
 * USER_MESSAGE、USER CUSTOM_MESSAGE 与 SET_WORKSPACE_PATH；INPUT 只消费到首条 user-like message
 * 为止的命令前缀，并先做可选 history normalization（synthetic UNKNOWN/HISTORY_CUT ToolResult + CANCELLED
 * TURN_END），再追加 TURN_START(INPUT) 与按 sequence 顺序的 USER/CUSTOM Message；COMPACTION 只追加
 * TURN_START(COMPACTION)（settings 快照为当前 branch），消费零 Command，切分事实由调用方传入的 {@link
 * CompactionPreparation} 承载。candidate Entry 使用调用方提供的 ID 分配器，createdAt 使用调用方时钟。
 */
final class TurnPlanBuilder {

  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final CommandHarvestReducer harvestReducer = new CommandHarvestReducer();

  TurnPlan build(
      UUID threadId,
      EntryPath sourcePath,
      TurnStartReason reason,
      List<ThreadCommand> plannedCommands,
      Supplier<UUID> idAllocator,
      Instant now,
      CompactionPreparation preparation) {
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(plannedCommands, "plannedCommands");
    Objects.requireNonNull(idAllocator, "idAllocator");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(threadId, "threadId");
    if ((reason == TurnStartReason.COMPACTION) != (preparation != null)) {
      throw new IllegalArgumentException(
          "compaction preparation must be present iff reason is COMPACTION");
    }

    List<ThreadCommand> consumedCommands = new ArrayList<>();
    for (ThreadCommand command : plannedCommands) {
      if (reason == TurnStartReason.INPUT) {
        consumedCommands.add(command);
        if (isUserLike(command)) {
          break;
        }
        continue;
      }
      if (isConsumed(reason, command)) {
        consumedCommands.add(command);
      }
    }
    if (reason == TurnStartReason.INPUT
        && (consumedCommands.isEmpty() || !isUserLike(consumedCommands.getLast()))) {
      throw new IllegalArgumentException("INPUT plan requires one queued user-like message");
    }
    CommandHarvestResult harvest =
        harvestReducer.reduce(threadId, sourcePath.baseSettings(), consumedCommands);

    UUID sessionId = sourcePath.root().sessionId();
    long cutoffSequence =
        plannedCommands.isEmpty() ? 0L : plannedCommands.get(plannedCommands.size() - 1).sequence();

    List<Entry> candidateEntries = new ArrayList<>();
    UUID parentId = sourcePath.head().id();
    if (reason == TurnStartReason.INPUT) {
      List<Entry> normalization = normalizationSuffix(sourcePath, idAllocator, now);
      candidateEntries.addAll(normalization);
      if (!normalization.isEmpty()) {
        parentId = normalization.get(normalization.size() - 1).id();
      }
    }
    UUID turnStartEntryId = idAllocator.get();
    candidateEntries.add(
        new Entry(
            turnStartEntryId,
            sessionId,
            parentId,
            new TurnStartPayload(
                reason,
                harvest.branchSettings(),
                threadId,
                null,
                null,
                preparation == null ? null : preparation.frozenStart()),
            now));
    parentId = turnStartEntryId;
    if (reason == TurnStartReason.INPUT || reason == TurnStartReason.CONTINUATION) {
      for (ThreadCommand command : plannedCommands) {
        if (reason == TurnStartReason.INPUT
            && command.type() == ThreadCommandType.USER_MESSAGE
            && consumedCommands.contains(command)) {
          UUID entryId = idAllocator.get();
          candidateEntries.add(
              new Entry(
                  entryId,
                  sessionId,
                  parentId,
                  new MessagePayload(
                      ((UserMessageCommandPayload) command.payload()).message(), null, null),
                  now));
          parentId = entryId;
        } else if (command.type() == ThreadCommandType.CUSTOM_MESSAGE
            && consumedCommands.contains(command)) {
          UUID entryId = idAllocator.get();
          candidateEntries.add(
              new Entry(
                  entryId,
                  sessionId,
                  parentId,
                  new CustomMessagePayload(
                      CustomMessagePayload.CORE_CONTRIBUTOR_ID,
                      CustomMessagePayload.CORE_CUSTOM_TYPE,
                      CustomMessagePayload.CORE_RENDERER_KEY,
                      ((CustomMessageCommandPayload) command.payload()).message(),
                      CustomMessagePayload.CORE_DETAILS_JSON),
                  now));
          parentId = entryId;
        }
      }
    }

    List<Entry> fullPath = new ArrayList<>(sourcePath.entries());
    fullPath.addAll(candidateEntries);
    EntryPath candidatePath = new EntryPath(fullPath);

    return new TurnPlan(
        threadId,
        sessionId,
        sourcePath.head().id(),
        cutoffSequence,
        plannedCommands,
        consumedCommands,
        candidateEntries,
        candidatePath,
        turnStartEntryId,
        parentId,
        reason,
        preparation);
  }

  /** INPUT 消费完整 queued 快照；CONTINUATION 额外消费 SYSTEM steering message；COMPACTION 消费零 Command。 */
  static boolean isConsumed(TurnStartReason reason, ThreadCommand command) {
    if (reason == TurnStartReason.INPUT) {
      return true;
    }
    if (reason == TurnStartReason.COMPACTION) {
      return false;
    }
    return switch (command.type()) {
      case SET_AGENT, SET_MODEL -> true;
      case CUSTOM_MESSAGE -> ((CustomMessageCommandPayload) command.payload()).message().role()
          == AgentMessageRole.SYSTEM;
      case USER_MESSAGE, SET_WORKSPACE_PATH -> false;
    };
  }

  private static boolean isUserLike(ThreadCommand command) {
    if (command.type() == ThreadCommandType.USER_MESSAGE) {
      return true;
    }
    return command.payload() instanceof CustomMessageCommandPayload custom
        && custom.message().role() == AgentMessageRole.USER;
  }

  /**
   * history normalization suffix：source path 存在 open 历史 Turn 时，基于该 path 已有的严格 ToolResult 前缀补写缺失
   * callIndex 的 synthetic UNKNOWN/HISTORY_CUT ToolResult，再追加 CANCELLED TURN_END；不读取任何 descendant
   * Invocation 结果。ROOT / 已关闭 TURN_END 无 suffix。
   */
  private List<Entry> normalizationSuffix(
      EntryPath sourcePath, Supplier<UUID> idAllocator, Instant now) {
    var openTurn = sourcePath.openTurnStart();
    if (openTurn.isEmpty()) {
      return List.of();
    }
    Entry turn = openTurn.get();
    UUID sessionId = sourcePath.root().sessionId();
    List<Entry> suffix = new ArrayList<>();
    UUID parentId = sourcePath.head().id();
    Entry assistant = assistantResultInTurn(sourcePath, turn);
    if (assistant != null
        && assistant.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT) {
      List<ToolCallMessageContent> calls = new ArrayList<>();
      for (var content : message.message().contents()) {
        if (content instanceof ToolCallMessageContent call) {
          calls.add(call);
        }
      }
      int present = countToolResultsAfter(sourcePath, assistant.id());
      for (int callIndex = present; callIndex < calls.size(); callIndex++) {
        ToolCallMessageContent call = calls.get(callIndex);
        UUID entryId = idAllocator.get();
        suffix.add(
            new Entry(
                entryId,
                sessionId,
                parentId,
                payloadMapper.syntheticHistoryCutToolResult(assistant.id(), callIndex, call),
                now));
        parentId = entryId;
      }
    }
    UUID turnEndId = idAllocator.get();
    suffix.add(
        new Entry(
            turnEndId,
            sessionId,
            parentId,
            new TurnEndPayload(
                turn.id(), TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            now));
    return suffix;
  }

  /** 返回 open Turn 内唯一的 assistant result Entry（ASSISTANT MESSAGE / ASSISTANT_ERROR / ABORTED）。 */
  static Entry assistantResultInTurn(EntryPath path, Entry turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      EntryPayload payload = entry.payload();
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        return entry;
      }
      if (payload instanceof AssistantErrorPayload || payload instanceof AssistantAbortedPayload) {
        return entry;
      }
      if (payload instanceof TurnEndPayload) {
        return null;
      }
    }
    return null;
  }

  /** 统计 assistant Entry 之后 path 上已有的 TOOL Message 数量（TurnPathValidator 保证是 callIndex 严格前缀）。 */
  static int countToolResultsAfter(EntryPath path, UUID assistantEntryId) {
    int count = 0;
    boolean after = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(assistantEntryId)) {
        after = true;
        continue;
      }
      if (after
          && entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.TOOL) {
        count++;
      }
    }
    return count;
  }
}
