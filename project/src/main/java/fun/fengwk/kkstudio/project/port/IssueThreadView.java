package fun.fengwk.kkstudio.project.port;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;

import java.util.Objects;
import java.util.UUID;

/**
 * Project 消费的单个 Thread 完整事实切片：权威身份与游标（sessionId / headEntryId / version / nextCommandSequence）、当前
 * root-to-head {@link EntryPath} 与统一 {@link ThreadExecutionFacts}。
 *
 * <p>由持久真实事实一次性投影而成，不含线程执行树的兄弟节点、工作队列、失败列表或任何运行时聚合引用；复用不可变 EntryPath /
 * ThreadExecutionFacts，不复制领域状态算法。
 */
public record IssueThreadView(
    UUID sessionId,
    UUID headEntryId,
    long version,
    long nextCommandSequence,
    EntryPath entryPath,
    ThreadExecutionFacts executionFacts) {

  public IssueThreadView {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(entryPath, "entryPath");
    Objects.requireNonNull(executionFacts, "executionFacts");
  }
}
