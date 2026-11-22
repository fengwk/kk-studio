package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.compaction.CompactToolName;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;

import java.util.Objects;

/**
 * 压缩启动的判定：在什么历史与 Thread 状态下允许一次自动压缩，以及压缩切分事实。
 *
 * <p>只读取 path 与 Thread 的冻结状态，不写任何 durable 状态；触发压缩的 Turn 写入由 {@link ThreadProcessor} 在同一事务内完成。
 */
final class CompactionGate {

  private final CompactionConfigProvider compactionConfigProvider;

  CompactionGate(CompactionConfigProvider compactionConfigProvider) {
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
  }

  /**
   * 失败请求若由上下文溢出导致，返回启动一次 OVERFLOW 压缩所需的切分事实。
   *
   * <p>压缩后的重试再次溢出时不再压缩，保留真实失败；返回 {@code null} 表示本次失败不触发压缩。
   */
  CompactionPreparation overflowRecovery(ThreadState thread, EntryPath path) {
    if (path.openTurnStart().isPresent() || !thread.constraints().allowAutoCompaction()) {
      return null;
    }
    CompactionHistory.ClosedTurn latest = CompactionHistory.latestClosedTurn(path);
    if (latest == null) {
      return null;
    }
    TurnStartPayload start = (TurnStartPayload) latest.start().payload();
    if (!thread.id().equals(start.ownerThreadId()) || start.contextWindow() == null) {
      return null;
    }
    Entry result = CompactionHistory.turnResultEntry(path, latest.start());
    if (!CompactionHistory.isContextWall(start, result)
        || CompactionHistory.isPostCompactionRetry(
            path, CompactionHistory.previousClosedTurn(path, latest))) {
      return null;
    }
    return preparation(path, CompactionTrigger.OVERFLOW, start.contextWindow());
  }

  /**
   * {@code compact} 工具在最新已关闭 turn 内成功返回后的排队压缩；批次已结束，按手动压缩规则选择切点。
   *
   * <p>返回 {@code null} 表示没有排队请求、当前 Thread 不可压缩或历史已没有可摘要前缀；此时继续普通续作。
   */
  CompactionPreparation compactTool(ThreadState thread, EntryPath path) {
    if (thread.kind() == ThreadKind.COMPACTION || !thread.constraints().allowAutoCompaction()) {
      return null;
    }
    CompactionHistory.ClosedTurn latest = CompactionHistory.latestClosedTurn(path);
    if (latest == null || !completedCompactTool(path, latest)) {
      return null;
    }
    CompactionHistory.ClosedTurn candidate = latest;
    while (candidate != null) {
      TurnStartPayload start = (TurnStartPayload) candidate.start().payload();
      if (!thread.id().equals(start.ownerThreadId())) {
        return null;
      }
      if (start.contextWindow() != null) {
        return preparation(path, CompactionTrigger.MANUAL, start.contextWindow());
      }
      candidate = CompactionHistory.previousClosedTurn(path, candidate);
    }
    return null;
  }

  CompactionPreparation preparation(EntryPath path, CompactionTrigger trigger, int contextWindow) {
    return new CompactionPlanner(compactionConfigProvider.compactionConfig())
        .prepare(path, trigger, contextWindow)
        .orElse(null);
  }

  /** 该已关闭 turn 内是否有内置 {@code compact} 工具的成功结果。 */
  private static boolean completedCompactTool(EntryPath path, CompactionHistory.ClosedTurn turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.start().id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      if (entry.payload() instanceof TurnEndPayload) {
        return false;
      }
      if (entry.payload() instanceof MessagePayload message) {
        ToolResultMetadata metadata = message.toolResultMetadata();
        if (metadata != null
            && CompactToolName.NAME.equals(metadata.toolName())
            && metadata.status() == ToolResultStatus.SUCCEEDED) {
          return true;
        }
      }
    }
    return false;
  }
}
