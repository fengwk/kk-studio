package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.MemoryExecutionExitJournal;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Clock;

/** Infra 测试共用的退出确认器；journal 只保存在内存中。 */
public final class ExecutionExitTestSupport {

  private ExecutionExitTestSupport() {}

  public static ExecutionExitConfirmer confirmer(HarnessStore store, Clock clock) {
    return new ExecutionExitConfirmer(store, new MemoryExecutionExitJournal(), clock);
  }
}
