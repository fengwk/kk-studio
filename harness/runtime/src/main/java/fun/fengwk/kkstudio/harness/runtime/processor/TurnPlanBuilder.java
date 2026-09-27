package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.GoalMessages;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
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
 * <p>CONTINUATION 消费普通配置命令（SET_AGENT / SET_MODEL / SET_ENVIRONMENT），保留全部
 * USER_MESSAGE、CUSTOM_MESSAGE 与 GOAL（含 max-turn 等内部 steering 提醒）；INPUT 消费到首条 user-like
 * 输入为止的命令前缀（typed GOAL 也是 user-like），并先做可选 history normalization（synthetic UNKNOWN/HISTORY_CUT
 * ToolResult + CANCELLED TURN_END），再追加 TURN_START(INPUT) 与按 sequence 顺序的 USER/CUSTOM Message；SET_*
 * 仅冻结在 TURN_START.settings，不生成模型可见消息。typed GOAL 在同一 TURN_START 快照内写入新 settings.goal， 并追加一条冻结 USER
 * 消息；COMPACTION 只追加 TURN_START(COMPACTION)（settings 快照为当前 branch），消费零 Command，切分事实由调用方传入的 {@link
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
    if (reason == TurnStartReason.STOP) {
      // STOP Turn 只由 StopControl 在自己的事务里完整写入；它绝不调度模型，因此必须 fail closed。
      throw new IllegalArgumentException("STOP turns are never planned by the processor");
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
    var settings =
        harvestReducer.reduce(threadId, sourcePath.baseSettings(), consumedCommands, idAllocator);

    UUID sessionId = sourcePath.root().sessionId();
    long cutoffSequence =
        plannedCommands.isEmpty() ? 0L : plannedCommands.get(plannedCommands.size() - 1).sequence();

    List<Entry> candidateEntries = new ArrayList<>();
    UUID parentId = sourcePath.head().id();
    if (reason == TurnStartReason.INPUT) {
      List<Entry> normalization = HistoryNormalization.suffix(sourcePath, idAllocator, now);
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
                settings,
                threadId,
                null,
                null,
                preparation == null ? null : preparation.frozenStart()),
            now));
    parentId = turnStartEntryId;
    if (reason == TurnStartReason.INPUT || reason == TurnStartReason.CONTINUATION) {
      for (ThreadCommand command : plannedCommands) {
        if (command.type() == ThreadCommandType.USER_MESSAGE
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
        } else if (command.type() == ThreadCommandType.GOAL && consumedCommands.contains(command)) {
          // 设置与清除走同一原子路径：settings 快照与这条冻结 USER 消息同属本 TURN_START。
          GoalCommandPayload goal = (GoalCommandPayload) command.payload();
          UUID entryId = idAllocator.get();
          candidateEntries.add(
              new Entry(
                  entryId,
                  sessionId,
                  parentId,
                  new MessagePayload(
                      goal.text() == null
                          ? GoalMessages.inputCleared()
                          : GoalMessages.inputSet(goal.text()),
                      null,
                      null),
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

  /** INPUT 消费完整 queued 快照；CONTINUATION 只消费 SET_* 配置命令；COMPACTION 消费零 Command。 */
  static boolean isConsumed(TurnStartReason reason, ThreadCommand command) {
    if (reason == TurnStartReason.INPUT) {
      return true;
    }
    if (reason == TurnStartReason.COMPACTION) {
      return false;
    }
    return command.type().isSetting();
  }

  /** user-like：最终用户输入，含 contributor / runtime 注入的 USER CUSTOM_MESSAGE 与 typed GOAL。 */
  private static boolean isUserLike(ThreadCommand command) {
    return command.type().isMessage();
  }
}
