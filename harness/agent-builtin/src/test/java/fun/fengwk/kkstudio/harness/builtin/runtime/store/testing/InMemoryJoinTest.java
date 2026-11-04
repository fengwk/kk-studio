package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreJoinContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class InMemoryJoinTest extends HarnessStoreJoinContract {

  @Override
  protected HarnessStore createStore() {
    return new InMemoryBuiltinHarnessStore();
  }
}
