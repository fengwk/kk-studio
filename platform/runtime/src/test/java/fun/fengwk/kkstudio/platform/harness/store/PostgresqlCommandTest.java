package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreCommandContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class PostgresqlCommandTest extends HarnessStoreCommandContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
