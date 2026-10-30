package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryJoinTest extends HarnessStoreJoinContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryBuiltinHarnessStore();
  }
}
