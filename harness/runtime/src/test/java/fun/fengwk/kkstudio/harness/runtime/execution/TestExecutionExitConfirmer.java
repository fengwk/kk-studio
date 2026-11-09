package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Clock;

/** 测试用退出确认器：journal 只保存在内存中，语义与文件实现一致。 */
public final class TestExecutionExitConfirmer {

  private TestExecutionExitConfirmer() {}

  public static ExecutionExitConfirmer create(HarnessStore store, Clock clock) {
    return new ExecutionExitConfirmer(store, new MemoryExecutionExitJournal(), clock);
  }

  public static MemoryExecutionExitJournal memoryJournal() {
    return new MemoryExecutionExitJournal();
  }
}
