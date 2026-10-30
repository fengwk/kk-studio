package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import javax.sql.DataSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL loadBranchSettings 窄查询的专项回归测试：冷缓存只走窄 CTE，不物化完整 EntryPath。
 *
 * <p>只读 Contributor branch view 依赖该窄读取解析用户 Goal。
 */
class PostgresqlBranchSettingsQueryTest {

  private final AtomicInteger cteCounter = new AtomicInteger();
  private HarnessStore store;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    cteCounter.set(0);
    DataSource countingDataSource =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), cteCounter);
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
}
