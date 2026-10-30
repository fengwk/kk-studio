package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryInvocationTest extends HarnessStoreInvocationContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryBuiltinHarnessStore();
  }
}
