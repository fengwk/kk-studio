package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreEntryTreeContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class PostgresqlEntryTreeTest extends HarnessStoreEntryTreeContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
