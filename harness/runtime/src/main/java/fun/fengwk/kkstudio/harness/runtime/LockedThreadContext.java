package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;

import java.util.Objects;

/**
 * {@link ThreadContextLock} 的 package-private 结果：同一 transaction 中的当前 {@link EntryCurrentState}、纯
 * {@link ThreadContext} 分类与 Driver 中立视图。
 *
 * <p>分类不需要完整历史。需要规划或物化时，调用方在已锁定 Thread 上显式读取 {@code EntryPath}。
 */
record LockedThreadContext(
    EntryCurrentState entryState, ThreadContext context, LockedDriverState driverState) {

  LockedThreadContext {
    entryState = Objects.requireNonNull(entryState, "entryState");
    context = Objects.requireNonNull(context, "context");
    driverState = Objects.requireNonNull(driverState, "driverState");
  }
}
