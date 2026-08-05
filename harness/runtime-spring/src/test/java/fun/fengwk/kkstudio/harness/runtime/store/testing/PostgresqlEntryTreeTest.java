package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlEntryTreeTest extends HarnessStoreEntryTreeContract {

  @Override
  HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
