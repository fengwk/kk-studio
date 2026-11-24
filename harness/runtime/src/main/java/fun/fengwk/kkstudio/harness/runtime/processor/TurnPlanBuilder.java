package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestReducer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 纯 turn planner：基于同一事务内捕获的 source EntryPath 与 queued Command 快照构造完整合法 candidate EntryPath，不接触
 * Store、不写任何 durable 状态。Thread YOLO 不进入 plan。
 *
 * <p>INPUT 收获快照内<b>全部</b> queued Command，消费到冻结 cutoff 为止；SET_* 按 sequence 归约成该轮配置， USER_MESSAGE /
 * CUSTOM_MESSAGE 追加对应消息，NOTIFICATION 追加为系统通知 Entry。cutoff 之后到达的输入留到下一轮。
 * 只要快照含至少一条消息或通知就允许规划；纯设置不单独触发。CONTINUATION 只消费设置，保留消息与通知留待下一轮 INPUT；COMPACTION 消费零
 * Command。candidate Entry 使用调用方提供的 ID 分配器，createdAt 使用调用方时钟。
 */
final class TurnPlanBuilder {

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
      boolean pendingNotificationInput,
      boolean childThread,
      String rootEnvironmentName) {
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

    List<ThreadCommand> consumedCommands =
        consumedCommands(reason, plannedCommands, pendingNotificationInput);
    BranchSettings settings =
        plannedSettings(
            threadId,
            sourcePath.baseSettings(),
            consumedCommands,
            childThread,
            rootEnvironmentName);
    UUID sessionId = sourcePath.root().sessionId();
    CandidateEntries candidates =
        new CandidateEntries(sessionId, sourcePath.head().id(), idAllocator, now);
    UUID turnStartEntryId =
        appendTurnStart(candidates, sourcePath, reason, settings, threadId, preparation);
    Map<Long, UUID> appliedEntryIds =
        appendConsumedCommands(candidates, reason, consumedCommands, turnStartEntryId);
    return new TurnPlan(
        threadId,
        sessionId,
        cutoffSequence,
        plannedCommands,
        consumedCommands,
        appliedEntryIds,
        candidates.entries(),
        candidates.extend(sourcePath),
        turnStartEntryId,
        candidates.parentId(),
        reason,
        preparation);
  }

  /** 按 reason 过滤出本轮消费的 Command，并拒绝没有任何消息/通知的 INPUT 规划。 */
  private static List<ThreadCommand> consumedCommands(
      TurnStartReason reason,
      List<ThreadCommand> plannedCommands,
      boolean pendingNotificationInput) {
    List<ThreadCommand> consumedCommands = new ArrayList<>();
    for (ThreadCommand command : plannedCommands) {
      if (isConsumed(reason, command)) {
        consumedCommands.add(command);
      }
    }
    if ((reason == TurnStartReason.INPUT || reason == TurnStartReason.COMPACTION_BRANCH_INPUT)
        && !hasInputDemand(consumedCommands)
        && !pendingNotificationInput) {
      throw new IllegalArgumentException(
          "INPUT plan requires at least one queued message or notification");
    }
    return consumedCommands;
  }

  /** 归约本轮 SET_*；子线程没有环境选择，本次 turn 仅冻结规划时执行根的环境。 */
  private BranchSettings plannedSettings(
      UUID threadId,
      BranchSettings baseSettings,
      List<ThreadCommand> consumedCommands,
      boolean childThread,
      String rootEnvironmentName) {
    BranchSettings settings = harvestReducer.reduce(threadId, baseSettings, consumedCommands);
    if (childThread) {
      // 历史调用不随之后的切换改变，因此把执行根环境冻结进本次 turn 的配置。
      settings = settings.withEnvironmentName(rootEnvironmentName);
    }
    return settings;
  }

  /** INPUT 先追加历史规范化 suffix，再追加挂在 suffix 末尾的 TURN_START。 */
  private static UUID appendTurnStart(
      CandidateEntries candidates,
      EntryPath sourcePath,
      TurnStartReason reason,
      BranchSettings settings,
      UUID threadId,
      CompactionPreparation preparation) {
    if (reason == TurnStartReason.INPUT) {
      candidates.appendNormalization(sourcePath);
    }
    return candidates.append(
        new TurnStartPayload(
            reason,
            settings,
            threadId,
            null,
            null,
            preparation == null ? null : preparation.pendingStart(),
            null));
  }

  /** 消息与通知按 consumed 顺序追加并记录 applied Entry；设置类命令的 applied 坐标回落到 TURN_START。 */
  private static Map<Long, UUID> appendConsumedCommands(
      CandidateEntries candidates,
      TurnStartReason reason,
      List<ThreadCommand> consumedCommands,
      UUID turnStartEntryId) {
    Map<Long, UUID> appliedEntryIds = new LinkedHashMap<>();
    if (carriesMessages(reason)) {
      for (ThreadCommand command : consumedCommands) {
        switch (command.payload()) {
          case UserMessageCommandPayload value -> candidates.append(
              new MessagePayload(value.message(), null, null));
          case CustomMessageCommandPayload value -> candidates.append(
              new CustomMessagePayload(
                  CustomMessagePayload.CORE_CONTRIBUTOR_ID,
                  CustomMessagePayload.CORE_CUSTOM_TYPE,
                  CustomMessagePayload.CORE_RENDERER_KEY,
                  value.message(),
                  CustomMessagePayload.CORE_DETAILS_JSON));
          case NotificationCommandPayload value -> appliedEntryIds.put(
              command.sequence(),
              candidates.append(
                  new NotificationPayload(
                      value.notificationId(),
                      value.kind(),
                      value.sourceThreadId(),
                      value.message())));
          case SetExecutionCommandPayload ignored -> {}
          case SetEnvironmentCommandPayload ignored -> {}
        }
      }
    }
    for (ThreadCommand command : consumedCommands) {
      appliedEntryIds.putIfAbsent(command.sequence(), turnStartEntryId);
    }
    return appliedEntryIds;
  }

  /** 只有这些 reason 会把消息/通知物化进 candidate。 */
  private static boolean carriesMessages(TurnStartReason reason) {
    return reason == TurnStartReason.INPUT
        || reason == TurnStartReason.CONTINUATION
        || reason == TurnStartReason.COMPACTION_BRANCH_INPUT
        || reason == TurnStartReason.COMPACTION_BRANCH_CONTINUATION;
  }

  /** candidate Entry 的追加器：统一分配 ID、维护 parent 链，追加顺序即 candidate 顺序。 */
  private static final class CandidateEntries {

    private final UUID sessionId;
    private final Supplier<UUID> idAllocator;
    private final Instant now;
    private final List<Entry> entries = new ArrayList<>();
    private UUID parentId;

    CandidateEntries(UUID sessionId, UUID parentId, Supplier<UUID> idAllocator, Instant now) {
      this.sessionId = sessionId;
      this.parentId = parentId;
      this.idAllocator = idAllocator;
      this.now = now;
    }

    UUID append(EntryPayload payload) {
      UUID entryId = idAllocator.get();
      entries.add(new Entry(entryId, sessionId, parentId, payload, now));
      parentId = entryId;
      return entryId;
    }

    void appendNormalization(EntryPath sourcePath) {
      for (Entry entry : HistoryNormalization.suffix(sourcePath, idAllocator, now)) {
        entries.add(entry);
        parentId = entry.id();
      }
    }

    List<Entry> entries() {
      return entries;
    }

    UUID parentId() {
      return parentId;
    }

    EntryPath extend(EntryPath sourcePath) {
      List<Entry> fullPath = new ArrayList<>(sourcePath.entries());
      fullPath.addAll(entries);
      return new EntryPath(fullPath);
    }
  }

  /** INPUT 消费全部 queued 快照；CONTINUATION 只消费设置；COMPACTION 消费零 Command。 */
  static boolean isConsumed(TurnStartReason reason, ThreadCommand command) {
    if (reason == TurnStartReason.INPUT) {
      return true;
    }
    if (reason == TurnStartReason.COMPACTION_BRANCH_INPUT) {
      return command.type() == ThreadCommandType.CUSTOM_MESSAGE;
    }
    if (reason == TurnStartReason.COMPACTION
        || reason == TurnStartReason.COMPACTION_BRANCH_CONTINUATION) {
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
