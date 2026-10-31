package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;

/**
 * 执行树节点摘要：当前 Thread 状态、分支设置、运行状态、outcome 与路径计数。
 *
 * <p>不携带历史 Entry。计数来自 root-to-head 聚合，不是可重建路径。
 */
public record ThreadTreeNode(
    ThreadState thread,
    BranchSettings branchSettings,
    ThreadRuntimeStatus status,
    TurnEndOutcome outcome,
    EntryPathMetrics metrics) {

  public ThreadTreeNode {
    thread = Objects.requireNonNull(thread, "thread");
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
    status = Objects.requireNonNull(status, "status");
    metrics = Objects.requireNonNull(metrics, "metrics");
  }
}
