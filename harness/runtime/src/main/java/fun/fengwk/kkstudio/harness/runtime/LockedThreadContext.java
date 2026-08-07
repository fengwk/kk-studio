package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;

import java.util.Objects;

/**
 * {@link ThreadContextLock} 的 package-private 结果：在同一 transaction 中锁定的行上计算得到的 root-to-head {@link
 * EntryPath} 与纯 {@link ThreadContext} 分类，使 enqueue admission、MOVE_HEAD、 Tool approval 与 snapshot
 * 之间永不发生漂移。
 */
record LockedThreadContext(EntryPath path, ThreadContext context) {

  LockedThreadContext {
    path = Objects.requireNonNull(path, "path");
    context = Objects.requireNonNull(context, "context");
  }
}
