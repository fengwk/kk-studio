package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 无锁 Invocation 读取与 {@link ThreadContextClassifier} 组合探测器。
 *
 * <p>供 {@code ThreadProcessor}（单 action reducer 决策）与 {@code ManualCompactionControl}（可用性与冲突决策）共用。
 * 与锁定控制面的 {@code ThreadContextLock} 明确区分：本类在无锁条件下读取 Model 与 Tool siblings（Model 仅按 {@code
 * (threadId, open TURN_START)} 查找，Tool siblings 仅在 Model 结果恰为当前 Assistant head 时查找），并交由纯分类器产生
 * {@link ThreadContext}。
 */
public final class ThreadContextProbe {

  private final ThreadContextClassifier classifier = new ThreadContextClassifier();

  public ThreadContextProbe() {}

  /** 探测已锁定 Thread 的上下文，按需要复用传入的 {@link EntryPath}。 */
  public ThreadContext probe(HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");

    ModelInvocation model = null;
    List<ToolInvocation> siblings = List.of();
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent()) {
      Optional<ModelInvocation> found =
          tx.findModelInvocationByTurn(thread.id(), openTurn.get().id());
      if (found.isPresent()) {
        model = found.get();
        if (model.resultEntryId() != null
            && model.resultEntryId().equals(thread.headEntryId())
            && path.head().payload() instanceof MessagePayload message
            && message.message().role() == AgentMessageRole.ASSISTANT) {
          siblings = tx.loadToolInvocationsByAssistantEntryId(path.head().id());
        }
      }
    }
    return classifier.classify(thread, path, model, siblings);
  }

  /** 探测已锁定 Thread 的上下文，内部加载其 root-to-head {@link EntryPath}。 */
  public ThreadContext probe(HarnessStore.Transaction tx, ThreadState thread) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    return probe(tx, thread, tx.loadEntryPath(thread.headEntryId()));
  }
}
