package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryCommandTest extends HarnessStoreCommandContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryHarnessStore();
  }
}
