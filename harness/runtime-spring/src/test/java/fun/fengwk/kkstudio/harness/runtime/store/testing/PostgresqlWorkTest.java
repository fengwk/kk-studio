package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlWorkTest extends HarnessStoreWorkContract {

  @Override
  HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
