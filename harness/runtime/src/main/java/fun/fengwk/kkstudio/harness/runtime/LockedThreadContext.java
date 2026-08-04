package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;

import java.util.Objects;

/**
 * Package-private result of {@link ThreadContextLock}: the root-to-head {@link EntryPath} plus the
 * pure {@link ThreadContext} classification computed over rows locked in the same transaction, so
 * enqueue admission, MOVE_HEAD, Tool approval and snapshot never drift from each other.
 */
record LockedThreadContext(EntryPath path, ThreadContext context) {

  LockedThreadContext {
    path = Objects.requireNonNull(path, "path");
    context = Objects.requireNonNull(context, "context");
  }
}
