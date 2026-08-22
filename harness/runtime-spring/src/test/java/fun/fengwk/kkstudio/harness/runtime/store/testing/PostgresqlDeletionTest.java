package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlDeletionTest extends HarnessStoreDeletionContract {

  @Override
  HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
