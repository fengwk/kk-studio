package fun.fengwk.kkstudio.harness.builtin.runtime;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

/** 内存 Store 运行执行树查询契约。 */
class HarnessRuntimeThreadTreeTest extends HarnessRuntimeThreadTreeContract {

  @Override
  protected HarnessStore createStore() {
    return new InMemoryBuiltinHarnessStore();
  }
}
