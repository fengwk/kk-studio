package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Clock;

/** 测试用本地执行生命周期：使用内存两阶段日志并立即完成首轮恢复。 */
public final class TestExecutionLifecycle {

  private TestExecutionLifecycle() {}

  public static ExecutionLifecycle create(HarnessStore store, Clock clock) {
    return create(store, clock, new MemoryExecutionExitJournal());
  }

  public static ExecutionLifecycle create(
      HarnessStore store, Clock clock, ExecutionExitJournal journal) {
    ExecutionLifecycle lifecycle =
        new ExecutionLifecycle(store, journal, new ExecutionExitConfirmer(store, journal, clock));
    lifecycle.start();
    return lifecycle;
  }
}
