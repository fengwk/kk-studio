package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** PostgreSQL loadContributorCustomEntriesOnPath 窄查询的专项回归测试：冷缓存只走窄 CTE、热缓存复用完整路径。 */
class PostgresqlContributorCustomPathTest {

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

  /** 测试意图：cold cache 条件下执行窄查询只运行窄 CTE，不执行现有 with recursive entry_path 全路径 CTE。 */
  @Test
  void coldCacheNarrowQueryExecutesNarrowCteAndZeroFullPathCte() {
    Baseline baseline = seedThreadBaseline(store);
    UUID customId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("test.contributor", "type.a", 1, "{\"step\":1}"));

    cteCounter.set(0);

    // 独立冷缓存事务执行窄查询：不应触发全路径 CTE（with recursive entry_path）
    List<Entry> entries =
        store.transaction(
            tx -> tx.loadContributorCustomEntriesOnPath(customId, "test.contributor"));
    assertEquals(1, entries.size());
    assertEquals(customId, entries.get(0).id());
    assertEquals(0, cteCounter.get(), "冷缓存窄查询不执行 with recursive entry_path 全路径 CTE");
  }

  /** 测试意图：同事务内已存在完整 path 缓存时，窄查询直接从 entryPathCache 过滤，不产生额外 CTE。 */
  @Test
  void warmCacheNarrowQueryReusesCachedPathAndExecutesZeroAdditionalCte() {
    Baseline baseline = seedThreadBaseline(store);
    UUID customId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("test.contributor", "type.a", 1, "{\"step\":1}"));

    cteCounter.set(0);

    store.transaction(
        tx -> {
          // 先显式加载完整 path，触发 1 次全路径 CTE 写入缓存
          tx.loadEntryPath(customId);
          assertEquals(1, cteCounter.get(), "loadEntryPath 执行 1 次全路径 CTE");

          // 随后执行窄查询，直接从 entryPathCache 过滤，不产生额外的全路径 CTE
          List<Entry> cachedEntries =
              tx.loadContributorCustomEntriesOnPath(customId, "test.contributor");
          assertEquals(1, cachedEntries.size());
          assertEquals(customId, cachedEntries.get(0).id());
          assertEquals(1, cteCounter.get(), "缓存命中时不产生额外的全路径 CTE");
          return null;
        });
  }
}
