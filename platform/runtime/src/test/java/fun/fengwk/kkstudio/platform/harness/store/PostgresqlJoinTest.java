package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.runtime.store.testing.HarnessStoreJoinContract;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

/** 使用隔离 PostgreSQL 真实约束和事务运行同一 join 契约。 */
class PostgresqlJoinTest extends HarnessStoreJoinContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
