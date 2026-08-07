package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;

/**
 * {@link HarnessRuntime#createThread} 的不可变结果：在单个原子 transaction 中创建的三个 durable 事实——Session、其 ROOT
 * Entry，以及 head 指向该 ROOT 的 Thread。
 */
public record CreatedThread(Session session, Entry rootEntry, ThreadState thread) {

  public CreatedThread {
    session = Objects.requireNonNull(session, "session");
    rootEntry = Objects.requireNonNull(rootEntry, "rootEntry");
    thread = Objects.requireNonNull(thread, "thread");
  }
}
