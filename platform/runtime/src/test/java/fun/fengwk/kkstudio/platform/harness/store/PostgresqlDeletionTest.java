package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.HarnessStoreDeletionContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class PostgresqlDeletionTest extends HarnessStoreDeletionContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
