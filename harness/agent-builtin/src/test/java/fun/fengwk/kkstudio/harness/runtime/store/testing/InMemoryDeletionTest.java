package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class InMemoryDeletionTest extends HarnessStoreDeletionContract {

  @Override
  HarnessStore createStore() {
    return new InMemoryHarnessStore();
  }
}
