package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

class PostgresqlInvocationTest extends HarnessStoreInvocationContract {

  @Override
  HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
