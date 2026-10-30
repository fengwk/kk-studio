package fun.fengwk.kkstudio.harness.infra.store;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.HarnessStoreDeletionContract;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlDeletionTest extends HarnessStoreDeletionContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
