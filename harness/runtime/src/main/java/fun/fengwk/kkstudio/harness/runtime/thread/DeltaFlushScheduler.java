package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Duration;

/** Delta 批量刷新调度；进程内实现，不持久化。 */
@FunctionalInterface
public interface DeltaFlushScheduler {
  void schedule(Duration delay, Runnable task);
}
