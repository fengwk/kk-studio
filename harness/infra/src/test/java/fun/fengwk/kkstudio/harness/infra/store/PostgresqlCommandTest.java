package fun.fengwk.kkstudio.harness.infra.store;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreCommandContract;

class PostgresqlCommandTest extends HarnessStoreCommandContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
