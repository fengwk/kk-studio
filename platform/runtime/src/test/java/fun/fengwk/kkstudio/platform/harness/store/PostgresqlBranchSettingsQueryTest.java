package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import javax.sql.DataSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL loadBranchSettings 窄查询的专项回归测试：冷缓存只走窄 CTE，不物化完整 EntryPath。
 *
 * <p>只读查询依赖该窄读取解析 branch settings。
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
    BranchSettings expectedSettings = branchSettings();
    UUID turnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          id,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          new TurnStartPayload(
                              TurnStartReason.INPUT,
                              expectedSettings,
                              StoreTestSupport.OWNER_THREAD_ID),
                          StoreTestSupport.T1));
              return id;
            });

    cteCounter.set(0);

    BranchSettings settings = store.transaction(tx -> tx.entries().loadBranchSettings(turnStartId));
    assertEquals(expectedSettings, settings);
    assertEquals(0, cteCounter.get(), "冷缓存窄查询不执行 with recursive entry_path 全路径 CTE");

    // ROOT 本身也是一个合法 head：同样只走窄查询。
    cteCounter.set(0);
    assertEquals(
        branchSettings(),
        store.transaction(tx -> tx.entries().loadBranchSettings(baseline.rootEntryId())));
    assertEquals(0, cteCounter.get(), "ROOT head 的窄查询同样不执行全路径 CTE");
  }
}
