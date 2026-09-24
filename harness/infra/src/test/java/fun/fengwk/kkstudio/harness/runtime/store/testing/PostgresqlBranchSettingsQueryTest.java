package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL loadBranchSettings 窄查询的专项回归测试：0 全路径 CTE 查询守卫与 corruption fail-closed 契约。
 *
 * <p>只读 Contributor branch view 依赖该窄读取解析用户 Goal，因此这里既证明它不物化完整 EntryPath，也证明异常数据不会静默返回错误 settings。
 */
class PostgresqlBranchSettingsQueryTest {

  private final AtomicInteger cteCounter = new AtomicInteger();
  private DataSource rawDataSource;
  private HarnessStore store;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    cteCounter.set(0);
    rawDataSource = PostgresqlHarnessStoreFixture.dataSource();
    DataSource countingDataSource =
        PostgresqlEntryPathCteCounter.countingDataSource(rawDataSource, cteCounter);
    store = PostgresqlHarnessStoreFixture.create(countingDataSource);
  }

  @AfterEach
  void tearDown() {
    PostgresqlHarnessStoreFixture.reset();
  }

  /** 测试意图：cold cache 条件下窄查询只运行窄 CTE，不执行现有 with recursive entry_path 全路径 CTE。 */
  @Test
  void coldCacheBranchSettingsQueryExecutesNarrowCteAndZeroFullPathCte() {
    Baseline baseline = seedThreadBaseline(store);
    UUID goalId = TestIds.id(41L);
    BranchSettings goalSettings = branchSettings().withGoal(new GoalSetting(goalId, "ship it"));
    UUID turnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT, goalSettings, StoreTestSupport.OWNER_THREAD_ID),
                      StoreTestSupport.T1));
              return id;
            });

    cteCounter.set(0);

    BranchSettings settings = store.transaction(tx -> tx.loadBranchSettings(turnStartId));
    assertEquals(goalSettings, settings);
    assertEquals(0, cteCounter.get(), "冷缓存窄查询不执行 with recursive entry_path 全路径 CTE");

    // ROOT 本身也是一个合法 head：同样只走窄查询。
    cteCounter.set(0);
    assertEquals(
        branchSettings(), store.transaction(tx -> tx.loadBranchSettings(baseline.rootEntryId())));
    assertEquals(0, cteCounter.get(), "ROOT head 的窄查询同样不执行全路径 CTE");
  }

  /** 测试意图：通过测试 JDBC 将合法链改成两节点 parent cycle，窄查询必须 fail closed 拒绝；并在测试后安全重置。 */
  @Test
  void corruptedTwoNodeParentCycleFailsClosed() throws Exception {
    try {
      Baseline baseline = seedThreadBaseline(store);
      UUID entry1Id =
          insertChildEntry(
              store,
              baseline.sessionId(),
              baseline.rootEntryId(),
              new CustomEntryPayload("test.contributor", "state", 1, "{\"cycle\":1}"));
      UUID entry2Id =
          insertChildEntry(
              store,
              baseline.sessionId(),
              entry1Id,
              new CustomEntryPayload("test.contributor", "state", 1, "{\"cycle\":2}"));

      // 通过测试 JDBC 绕过领域校验，将 entry1 的 parent 改为 entry2，构成 entry1 <-> entry2 的两节点 cycle
      try (Connection conn = rawDataSource.getConnection();
          PreparedStatement stmt =
              conn.prepareStatement("update harness_entry set parent_entry_id = ? where id = ?")) {
        stmt.setObject(1, entry2Id);
        stmt.setObject(2, entry1Id);
        int updated = stmt.executeUpdate();
        assertEquals(1, updated, "应当成功修改 1 条 entry 作为 corruption 测试夹具");
      }

      assertThrows(
          IllegalArgumentException.class,
          () -> store.transaction(tx -> tx.loadBranchSettings(entry2Id)));
    } finally {
      PostgresqlHarnessStoreFixture.reset();
    }
  }
}
