package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;

import java.util.Objects;

/**
 * {@link ThreadContextLock} 的 package-private 结果：同一 transaction 中锁定的 root-to-head {@link
 * EntryPath}、 纯 {@link ThreadContext} 分类与 Driver 中立视图。
 */
record LockedThreadContext(EntryPath path, ThreadContext context, LockedDriverState driverState) {

  LockedThreadContext {
    path = Objects.requireNonNull(path, "path");
    context = Objects.requireNonNull(context, "context");
    driverState = Objects.requireNonNull(driverState, "driverState");
  }
}
