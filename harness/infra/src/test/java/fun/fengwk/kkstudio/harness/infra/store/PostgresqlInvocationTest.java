package fun.fengwk.kkstudio.harness.infra.store;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.HarnessStoreInvocationContract;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlInvocationTest extends HarnessStoreInvocationContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
