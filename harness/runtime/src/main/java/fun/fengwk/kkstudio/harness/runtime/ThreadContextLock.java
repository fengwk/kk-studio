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
 * Small package-private HarnessStore transaction helper shared by every control-plane entry point
 * (and the later Stop slice), not a framework or repository.
 *
 * <p>Starting from a Thread already locked by the caller it loads the root-to-head {@link
 * EntryPath}, finds the Model only by {@code (threadId, open TURN_START)}, locks that applicable
 * Model, loads/locks the Tool siblings only when that Model's result is exactly the current
 * Assistant head, and then runs the pure {@link ThreadContextClassifier} over the locked rows so
 * classification cannot drift between operations. Callers that also need Commands must lock queued
 * Commands first, preserving the canonical order Thread -&gt; Commands -&gt; Model -&gt; Tool
 * siblings -&gt; Work.
 */
final class ThreadContextLock {

  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private ThreadContextLock() {}

  /** Loads the current root-to-head path of the locked Thread and classifies it under lock. */
  static LockedThreadContext load(HarnessStore.Transaction tx, ThreadState thread) {
    return load(tx, thread, tx.loadEntryPath(thread.headEntryId()));
  }

  /** Classifies under lock, reusing an already loaded path (e.g. MOVE_HEAD's head path). */
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
            && model.resultEntryId() == thread.headEntryId()
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
