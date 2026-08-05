package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryInvocationTest extends HarnessStoreInvocationContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryHarnessStore();
  }
}
