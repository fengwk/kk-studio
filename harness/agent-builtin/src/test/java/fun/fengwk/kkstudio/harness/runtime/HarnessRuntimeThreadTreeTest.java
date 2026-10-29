package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;

/** 内存 Store 运行执行树查询契约。 */
class HarnessRuntimeThreadTreeTest extends HarnessRuntimeThreadTreeContract {

  @Override
  protected HarnessStore createStore() {
    return new InMemoryHarnessStore();
  }
}
