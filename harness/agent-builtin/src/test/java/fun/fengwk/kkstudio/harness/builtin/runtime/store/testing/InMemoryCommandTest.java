package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreCommandContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class InMemoryCommandTest extends HarnessStoreCommandContract {

  @Override
  protected HarnessStore createStore() {
    return new InMemoryBuiltinHarnessStore();
  }
}
