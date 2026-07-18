package fun.fengwk.kkstudio.core.harness.thread.tool;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.CloudToolWorker;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Event-triggered tool port for ThreadProcessor: list unresolved invocations, dispatch due
 * Cloud/Control work for the owning thread, and detect terminal results awaiting entry apply.
 *
 * <p>Pending-apply is decided solely from this thread's head and its own invocations (not tree-wide
 * children), so sibling threads sharing the same assistant head do not block each other.
 */
@Component
public class DatabaseThreadToolPort implements ThreadProcessor.ThreadToolPort {
  private final MysqlToolInvocationStore invocationStore;
  private final ToolInvocationMapper invocationMapper;
  private final CloudToolWorker cloudToolWorker;

  public DatabaseThreadToolPort(
      MysqlToolInvocationStore invocationStore,
      ToolInvocationMapper invocationMapper,
      CloudToolWorker cloudToolWorker) {
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.cloudToolWorker = Objects.requireNonNull(cloudToolWorker, "cloudToolWorker");
  }

  @Override
  public List<ToolInvocation> listNonTerminal(long threadId) {
    return invocationStore.listByThread(threadId).stream()
        .filter(inv -> !inv.status().isTerminal())
        .toList();
  }

  @Override
  public int dispatchDue(long threadId, Instant now) {
    Objects.requireNonNull(now, "now");
    String workerId = "thread-tool-" + UUID.randomUUID();
    return cloudToolWorker.dispatchDueForThread(workerId, threadId);
  }

  @Override
  public boolean hasTerminalResultsPendingApply(long threadId, long headEntryId) {
    if (threadId <= 0 || headEntryId <= 0) {
      return false;
    }
    if (invocationMapper.countNonTerminalByThread(threadId) != 0) {
      return false;
    }
    // Head is still the assistant entry that owns these invocations: applyTerminalToolResults will
    // advance this thread's head after writing tool-result entries.
    return invocationStore.listByThread(threadId).stream()
        .anyMatch(inv -> inv.assistantEntryId() == headEntryId && inv.status().isTerminal());
  }
}
