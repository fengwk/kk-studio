package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.GoalMessages;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 纯 speculative turn planner：基于 plan 事务捕获的 source EntryPath 与 queued Command 快照构造完整合法 candidate
 * EntryPath，不接触 Store、不写任何 durable 状态。Thread YOLO 不进入 plan。
 *
 * <p>INPUT 收获快照内<b>全部</b> queued Command（不再在首条 user-like 后截断），消费到冻结 cutoff 为止；SET_* 与
 * SET_CONTRIBUTOR_STATE 按 sequence 归约成该轮配置（SET_CONTRIBUTOR_STATE 同时追加 CUSTOM state Entry），
 * USER_MESSAGE / CUSTOM_MESSAGE / GOAL 追加对应消息，NOTIFICATION 追加为系统通知 Entry。cutoff 之后到达的输入留到下一轮。
 * 只要快照含至少一条消息或通知就允许规划；纯设置不单独触发。CONTINUATION 只消费设置，保留消息与通知留待下一轮 INPUT；COMPACTION 消费零
 * Command。candidate Entry 使用调用方提供的 ID 分配器，createdAt 使用调用方时钟。
 */
final class TurnPlanBuilder {

  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();
  private final CommandHarvestReducer harvestReducer = new CommandHarvestReducer();

  TurnPlan build(
      UUID threadId,
      EntryPath sourcePath,
      TurnStartReason reason,
      List<ThreadCommand> plannedCommands,
      long cutoffSequence,
      Supplier<UUID> idAllocator,
      Instant now,
      CompactionPreparation preparation,
      boolean pendingNotificationInput) {
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(plannedCommands, "plannedCommands");
    Objects.requireNonNull(idAllocator, "idAllocator");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(threadId, "threadId");
    if (cutoffSequence < 0) {
      throw new IllegalArgumentException("cutoffSequence must not be negative");
    }
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
      if (isConsumed(reason, command)) {
        consumedCommands.add(command);
      }
    }
    if (reason == TurnStartReason.INPUT
        && !hasInputDemand(consumedCommands)
        && !pendingNotificationInput) {
      throw new IllegalArgumentException(
          "INPUT plan requires at least one queued message or notification");
    }
    var settings =
        harvestReducer.reduce(threadId, sourcePath.baseSettings(), consumedCommands, idAllocator);

    UUID sessionId = sourcePath.root().sessionId();

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

    Map<Long, UUID> appliedEntryIds = new LinkedHashMap<>();
    if (reason == TurnStartReason.INPUT || reason == TurnStartReason.CONTINUATION) {
      for (ThreadCommand command : consumedCommands) {
        switch (command.payload()) {
          case UserMessageCommandPayload value -> {
            UUID entryId = idAllocator.get();
            candidateEntries.add(
                new Entry(
                    entryId,
                    sessionId,
                    parentId,
                    new MessagePayload(value.message(), null, null),
                    now));
            parentId = entryId;
          }
          case GoalCommandPayload value -> {
            UUID entryId = idAllocator.get();
            candidateEntries.add(
                new Entry(
                    entryId,
                    sessionId,
                    parentId,
                    new MessagePayload(
                        value.text() == null
                            ? GoalMessages.inputCleared()
                            : GoalMessages.inputSet(value.text()),
                        null,
                        null),
                    now));
            parentId = entryId;
          }
          case CustomMessageCommandPayload value -> {
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
                        value.message(),
                        CustomMessagePayload.CORE_DETAILS_JSON),
                    now));
            parentId = entryId;
          }
          case NotificationCommandPayload value -> {
            UUID entryId = idAllocator.get();
            candidateEntries.add(
                new Entry(
                    entryId,
                    sessionId,
                    parentId,
                    new NotificationPayload(
                        value.notificationId(),
                        value.kind(),
                        value.sourceThreadId(),
                        value.message()),
                    now));
            appliedEntryIds.put(command.sequence(), entryId);
            parentId = entryId;
          }
          case SetContributorStateCommandPayload value -> {
            UUID entryId = idAllocator.get();
            candidateEntries.add(new Entry(entryId, sessionId, parentId, value.state(), now));
            parentId = entryId;
          }
          case SetAgentCommandPayload ignored -> {}
          case SetModelCommandPayload ignored -> {}
          case SetEnvironmentCommandPayload ignored -> {}
        }
      }
    }
    for (ThreadCommand command : consumedCommands) {
      appliedEntryIds.putIfAbsent(command.sequence(), turnStartEntryId);
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
        appliedEntryIds,
        candidateEntries,
        candidatePath,
        turnStartEntryId,
        parentId,
        reason,
        preparation);
  }

  /** INPUT 消费全部 queued 快照；CONTINUATION 只消费设置（含 contributor state）；COMPACTION 消费零 Command。 */
  static boolean isConsumed(TurnStartReason reason, ThreadCommand command) {
    if (reason == TurnStartReason.INPUT) {
      return true;
    }
    if (reason == TurnStartReason.COMPACTION) {
      return false;
    }
    return command.type().isSetting();
  }

  /** INPUT 快照是否含至少一条消息或系统通知（纯设置不触发 turn；notification-only 可规划）。 */
  private static boolean hasInputDemand(List<ThreadCommand> consumedCommands) {
    for (ThreadCommand command : consumedCommands) {
      ThreadCommandType type = command.type();
      if (type.isMessage() || type.isNotification()) {
        return true;
      }
    }
    return false;
  }
}
