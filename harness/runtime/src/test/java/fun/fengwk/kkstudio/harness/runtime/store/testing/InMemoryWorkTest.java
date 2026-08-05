package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryWorkTest extends HarnessStoreWorkContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryHarnessStore();
  }
}
