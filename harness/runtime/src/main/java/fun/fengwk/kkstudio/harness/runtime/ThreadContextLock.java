package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Optional;

/**
 * 小型 package-private HarnessStore transaction 辅助工具，被所有 control-plane 入口（以及后续的 Stop slice）共用，不是框架或
 * repository。
 *
 * <p>从调用方已锁定的 Thread 出发，加载 root-to-head {@link EntryPath}，仅通过 {@code (threadId, open TURN_START)}
 * 查找 Model 并锁定该适用 Model；只有当该 Model 的 result 恰为 当前 Assistant head 时，才加载/锁定 Tool
 * siblings；随后在已锁定的行上运行纯 {@link ThreadContextClassifier}，从而保证各操作之间的分类不发生漂移。同时需要 Commands 的调用方必须
 * 先锁定已入队 Commands，保持规范顺序 Thread -&gt; Commands -&gt; Model -&gt; Tool siblings -&gt; Work。
 */
final class ThreadContextLock {

  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private ThreadContextLock() {}

  /** 加载已锁定 Thread 的当前 root-to-head path，并在锁内对其进行分类。 */
  static LockedThreadContext load(HarnessStore.Transaction tx, ThreadState thread) {
    return load(tx, thread, tx.loadEntryPath(thread.headEntryId()));
  }

  /** 在锁内分类，复用已加载的 path（例如 MOVE_HEAD 的 head path）。 */
  static LockedThreadContext load(HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    ModelInvocation model = null;
    List<ToolInvocation> siblings = List.of();
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent()) {
      Optional<ModelInvocation> found =
          tx.findModelInvocationByTurn(thread.id(), openTurn.get().id());
      if (found.isPresent()) {
        model =
            tx.lockModelInvocation(found.get().id())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "model "
                                + found.get().id()
                                + " could not be locked for thread "
                                + thread.id()));
        if (model.resultEntryId() != null
            && model.resultEntryId().equals(thread.headEntryId())
            && path.head().payload() instanceof MessagePayload message
            && message.message().role() == AgentMessageRole.ASSISTANT) {
          siblings = tx.lockToolInvocationsByAssistantEntryId(path.head().id());
        }
      }
    }
    ThreadContext context = CLASSIFIER.classify(thread, path, model, siblings);
    return new LockedThreadContext(path, context);
  }
}
