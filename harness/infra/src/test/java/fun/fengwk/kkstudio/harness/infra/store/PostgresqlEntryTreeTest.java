package fun.fengwk.kkstudio.harness.infra.store;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreEntryTreeContract;

class PostgresqlEntryTreeTest extends HarnessStoreEntryTreeContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
