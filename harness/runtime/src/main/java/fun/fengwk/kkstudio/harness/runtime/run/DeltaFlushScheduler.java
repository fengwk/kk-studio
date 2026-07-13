package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Duration;

/** Delta 时间阈值调度端口；只触发内存 buffer flush，不承载 Run/retry 调度事实。 */
@FunctionalInterface
public interface DeltaFlushScheduler {
  void schedule(Duration delay, Runnable flushTask);
}
