package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 speculative turn plan 的不可变快照：计划事务内锁 Thread、读取 queued Command 快照与 cutoff、校验 claim 后构造， 不写任何
 * durable 状态。
 *
 * <p>{@code candidatePath} 是 source path + candidate Entries 的完整合法 root-to-head 链（candidate Entry
 * 已分配 稳定 ID 但尚未持久化），交给 {@link fun.fengwk.kkstudio.harness.runtime.port.TurnResolver} 使用；提交事务必须重新校验
 * source head / cutoff 内 Command 精确快照 / claim ownership 后才能原子提交；Thread YOLO 始终以第二事务锁到的当前值 为准，不进入
 * plan。{@code preparation} 非空当且仅当 {@code reason == COMPACTION}：压缩 turn 的切分事实由纯 planner 在 plan
 * 事务内冻结，Resolver 与 ResolvedRequestValidator 据此构造并严格校验压缩请求。
 */
record TurnPlan(
    UUID threadId,
    UUID sessionId,
    UUID sourceHeadEntryId,
    long cutoffSequence,
    List<ThreadCommand> plannedCommands,
    List<ThreadCommand> consumedCommands,
    List<Entry> candidateEntries,
    EntryPath candidatePath,
    UUID turnStartEntryId,
    UUID candidateHeadEntryId,
    TurnStartReason reason,
    CompactionPreparation preparation) {

  TurnPlan {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(sourceHeadEntryId, "sourceHeadEntryId");
    if (cutoffSequence < 0) {
      throw new IllegalArgumentException("cutoffSequence must not be negative");
    }
    plannedCommands = List.copyOf(plannedCommands);
    consumedCommands = List.copyOf(consumedCommands);
    candidateEntries = List.copyOf(candidateEntries);
    candidatePath = Objects.requireNonNull(candidatePath, "candidatePath");
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    Objects.requireNonNull(candidateHeadEntryId, "candidateHeadEntryId");
    reason = Objects.requireNonNull(reason, "reason");
    if ((reason == TurnStartReason.COMPACTION) != (preparation != null)) {
      throw new IllegalArgumentException(
          "compaction preparation must be present iff reason is COMPACTION");
    }
  }

  /** continuation/compaction 保留的真实 user-like 命令仍 queued 时需要一次显式 THREAD wake。 */
  boolean hasDeferredUserMessages() {
    for (ThreadCommand command : plannedCommands) {
      if (command.type().isMessage() && !consumedCommands.contains(command)) {
        return true;
      }
    }
    return false;
  }
}
