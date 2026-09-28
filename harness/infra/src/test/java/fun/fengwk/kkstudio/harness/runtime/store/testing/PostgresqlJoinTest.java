package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

/** 使用隔离 PostgreSQL 真实约束和事务运行同一 join 契约。 */
class PostgresqlJoinTest extends HarnessStoreJoinContract {

  @Override
  HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
