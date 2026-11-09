package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.execution.MemoryExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Clock;

/** Infra 测试共用的退出确认器；journal 只保存在内存中。 */
public final class ExecutionExitTestSupport {

  private ExecutionExitTestSupport() {}

  public static ExecutionExitConfirmer confirmer(HarnessStore store, Clock clock) {
    return new ExecutionExitConfirmer(store, new MemoryExecutionExitJournal(), clock);
  }

  /** 测试用本地物理生命周期：内存 journal、已启动、可排空。 */
  public static ExecutionLifecycle lifecycle(HarnessStore store, Clock clock) {
    return TestExecutionLifecycle.create(store, clock);
  }
}
