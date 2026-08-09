package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * 一次 speculative turn plan 的不可变快照：计划事务内锁 Thread、读取 queued Command 快照与 cutoff、校验 claim 后构造， 不写任何
 * durable 状态。
 *
 * <p>{@code candidatePath} 是 source path + candidate Entries 的完整合法 root-to-head 链（candidate Entry
 * 已分配 稳定 ID 但尚未持久化），交给 {@link fun.fengwk.kkstudio.harness.runtime.port.TurnResolver} 使用；提交事务必须重新校验
 * source head / source YOLO / cutoff 内 Command 精确快照 / claim ownership 后才能原子提交。{@code preparation}
 * 非空当且仅当 {@code reason == COMPACTION}：压缩 turn 的切分事实由纯 planner 在 plan 事务内冻结，Resolver 与
 * ResolvedRequestValidator 据此构造并严格校验压缩请求。
 */
record TurnPlan(
    long threadId,
    long sessionId,
    long sourceHeadEntryId,
    boolean sourceYoloEnabled,
    long cutoffSequence,
    List<ThreadCommand> plannedCommands,
    List<ThreadCommand> consumedCommands,
    List<Entry> candidateEntries,
    EntryPath candidatePath,
    long turnStartEntryId,
    long candidateHeadEntryId,
    boolean finalYoloEnabled,
    TurnStartReason reason,
    CompactionPreparation preparation) {

  TurnPlan {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (sourceHeadEntryId <= 0) {
      throw new IllegalArgumentException("sourceHeadEntryId must be positive");
    }
    if (cutoffSequence < 0) {
      throw new IllegalArgumentException("cutoffSequence must not be negative");
    }
    plannedCommands = List.copyOf(plannedCommands);
    consumedCommands = List.copyOf(consumedCommands);
    candidateEntries = List.copyOf(candidateEntries);
    candidatePath = Objects.requireNonNull(candidatePath, "candidatePath");
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    if (candidateHeadEntryId <= 0) {
      throw new IllegalArgumentException("candidateHeadEntryId must be positive");
    }
    reason = Objects.requireNonNull(reason, "reason");
    if ((reason == TurnStartReason.COMPACTION) != (preparation != null)) {
      throw new IllegalArgumentException(
          "compaction preparation must be present iff reason is COMPACTION");
    }
  }

  /** continuation 保留的 message 命令（USER/CUSTOM 未被消费）仍 queued 时需要一次显式 THREAD wake。 */
  boolean hasDeferredMessages() {
    for (ThreadCommand command : plannedCommands) {
      if (command.type().isMessage() && !consumedCommands.contains(command)) {
        return true;
      }
    }
    return false;
  }
}
