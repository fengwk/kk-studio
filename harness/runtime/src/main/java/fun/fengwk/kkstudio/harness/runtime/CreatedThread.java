package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;

/**
 * Immutable result of {@link HarnessRuntime#createThread}: the three durable facts created in one
 * atomic transaction — the Session, its ROOT Entry and the Thread whose head points at that ROOT.
 */
public record CreatedThread(Session session, Entry rootEntry, ThreadState thread) {

  public CreatedThread {
    session = Objects.requireNonNull(session, "session");
    rootEntry = Objects.requireNonNull(rootEntry, "rootEntry");
    thread = Objects.requireNonNull(thread, "thread");
  }
}
