package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.HarnessStoreInvocationContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

class PostgresqlInvocationTest extends HarnessStoreInvocationContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
