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
import java.util.function.LongSupplier;

/**
 * 纯 speculative turn planner：基于 plan 事务捕获的 source EntryPath、queued Command 快照与 YOLO 开关构造完整合法
 * candidate EntryPath，不接触 Store、不写任何 durable 状态。
 *
 * <p>CONTINUATION 消费普通配置命令（SET_AGENT / SET_MODEL / SET_ACTIVE_TOOLS / SET_YOLO）与 SYSTEM
 * CUSTOM_MESSAGE（用于 task soft steering），保留 USER_MESSAGE、USER CUSTOM_MESSAGE 与 SET_ENVIRONMENT；INPUT
 * 消费 cutoff 内完整 queued 快照并先做可选 history normalization（synthetic UNKNOWN/HISTORY_CUT ToolResult +
 * CANCELLED TURN_END），再追加 TURN_START(INPUT) 与按 sequence 顺序的 USER/CUSTOM Message；COMPACTION 只追加
 * TURN_START(COMPACTION)（settings 快照为当前 branch），消费零 Command，切分事实由调用方传入的 {@link
 * CompactionPreparation} 承载。candidate Entry 使用调用方提供的 ID 分配器，createdAt 使用调用方时钟。
 */
final class TurnPlanBuilder {

  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final CommandHarvestReducer harvestReducer = new CommandHarvestReducer();

  TurnPlan build(
      long threadId,
      EntryPath sourcePath,
      boolean sourceYoloEnabled,
      TurnStartReason reason,
      List<ThreadCommand> plannedCommands,
      LongSupplier idAllocator,
      Instant now,
      CompactionPreparation preparation) {
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(plannedCommands, "plannedCommands");
    Objects.requireNonNull(idAllocator, "idAllocator");
    Objects.requireNonNull(now, "now");
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if ((reason == TurnStartReason.COMPACTION) != (preparation != null)) {
      throw new IllegalArgumentException(
          "compaction preparation must be present iff reason is COMPACTION");
    }

    List<ThreadCommand> consumedCommands = new ArrayList<>();
    for (ThreadCommand command : plannedCommands) {
      if (isConsumed(reason, command)) {
        consumedCommands.add(command);
      }
    }
    CommandHarvestResult harvest =
        harvestReducer.reduce(
            threadId, sourcePath.baseSettings(), sourceYoloEnabled, consumedCommands);

    long sessionId = sourcePath.root().sessionId();
    long cutoffSequence =
        plannedCommands.isEmpty() ? 0L : plannedCommands.get(plannedCommands.size() - 1).sequence();

    List<Entry> candidateEntries = new ArrayList<>();
    long parentId = sourcePath.head().id();
    if (reason == TurnStartReason.INPUT) {
      List<Entry> normalization = normalizationSuffix(sourcePath, idAllocator, now);
      candidateEntries.addAll(normalization);
      if (!normalization.isEmpty()) {
        parentId = normalization.get(normalization.size() - 1).id();
      }
    }
    long turnStartEntryId = idAllocator.getAsLong();
    candidateEntries.add(
        new Entry(
            turnStartEntryId,
            sessionId,
            parentId,
            new TurnStartPayload(reason, harvest.branchSettings()),
            now));
    parentId = turnStartEntryId;
    if (reason == TurnStartReason.INPUT || reason == TurnStartReason.CONTINUATION) {
      for (ThreadCommand command : plannedCommands) {
        if (reason == TurnStartReason.INPUT && command.type() == ThreadCommandType.USER_MESSAGE) {
          long entryId = idAllocator.getAsLong();
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
            && isConsumed(reason, command)) {
          long entryId = idAllocator.getAsLong();
          candidateEntries.add(
              new Entry(
                  entryId,
                  sessionId,
                  parentId,
                  new CustomMessagePayload(
                      CustomMessagePayload.CORE_PLUGIN_ID,
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
        sourceYoloEnabled,
        cutoffSequence,
        plannedCommands,
        consumedCommands,
        candidateEntries,
        candidatePath,
        turnStartEntryId,
        parentId,
        harvest.yoloEnabled(),
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
      case SET_AGENT, SET_MODEL, SET_ACTIVE_TOOLS, SET_YOLO -> true;
      case CUSTOM_MESSAGE -> ((CustomMessageCommandPayload) command.payload()).message().role()
          == AgentMessageRole.SYSTEM;
      case USER_MESSAGE, SET_ENVIRONMENT -> false;
    };
  }

  /**
   * history normalization suffix：source path 存在 open 历史 Turn 时，基于该 path 已有的严格 ToolResult 前缀补写缺失
   * ordinal 的 synthetic UNKNOWN/HISTORY_CUT ToolResult，再追加 CANCELLED TURN_END；不读取任何 descendant
   * Invocation 结果。ROOT / 已关闭 TURN_END 无 suffix。
   */
  private List<Entry> normalizationSuffix(
      EntryPath sourcePath, LongSupplier idAllocator, Instant now) {
    var openTurn = sourcePath.openTurnStart();
    if (openTurn.isEmpty()) {
      return List.of();
    }
    Entry turn = openTurn.get();
    long sessionId = sourcePath.root().sessionId();
    List<Entry> suffix = new ArrayList<>();
    long parentId = sourcePath.head().id();
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
      for (int ordinal = present; ordinal < calls.size(); ordinal++) {
        ToolCallMessageContent call = calls.get(ordinal);
        long entryId = idAllocator.getAsLong();
        suffix.add(
            new Entry(
                entryId,
                sessionId,
                parentId,
                payloadMapper.syntheticHistoryCutToolResult(assistant.id(), ordinal, call),
                now));
        parentId = entryId;
      }
    }
    long turnEndId = idAllocator.getAsLong();
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
      if (entry.id() == turn.id()) {
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

  /** 统计 assistant Entry 之后 path 上已有的 TOOL Message 数量（TurnPathValidator 保证是 ordinal 严格前缀）。 */
  static int countToolResultsAfter(EntryPath path, long assistantEntryId) {
    int count = 0;
    boolean after = false;
    for (Entry entry : path.entries()) {
      if (entry.id() == assistantEntryId) {
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
