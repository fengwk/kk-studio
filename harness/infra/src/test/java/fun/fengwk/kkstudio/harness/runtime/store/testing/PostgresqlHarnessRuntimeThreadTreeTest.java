package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeThreadTreeContract;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

/** 真实 PostgreSQL 上复用关系树查询契约，覆盖多节点锁序与只读投影。 */
class PostgresqlHarnessRuntimeThreadTreeTest extends HarnessRuntimeThreadTreeContract {

  @Override
  protected HarnessStore createStore() {
    return PostgresqlHarnessStoreFixture.resetAndCreate();
  }
}
