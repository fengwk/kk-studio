package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL loadContributorCustomEntriesOnPath 窄查询的专项回归测试： 包含异常数据 corruption fail-closed 契约与 0 全路径
 * CTE 查询守卫。
 */
class PostgresqlContributorCustomPathTest {

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
              resolvedTurnStartPayload(baseline.threadId()));
      UUID entry2Id =
          insertChildEntry(
              store,
              baseline.sessionId(),
              entry1Id,
              new CustomEntryPayload("test.contributor", "state", 1, "{\"cycle\":true}"));

      // 通过测试 JDBC 绕过领域校验，将 entry1 的 parent 改为 entry2，构成 entry1 <-> entry2 的两节点 cycle
      try (Connection conn = rawDataSource.getConnection();
          PreparedStatement stmt =
              conn.prepareStatement("update harness_entry set parent_entry_id = ? where id = ?")) {
        stmt.setObject(1, entry2Id);
        stmt.setObject(2, entry1Id);
        int updated = stmt.executeUpdate();
        assertEquals(1, updated, "应当成功修改 1 条 entry 作为 corruption 测试夹具");
      }

      // 窄查询必须 fail closed，识别 cycle 或未到达 ROOT
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  tx -> tx.loadContributorCustomEntriesOnPath(entry2Id, "test.contributor")));
    } finally {
      PostgresqlHarnessStoreFixture.reset();
    }
  }

  /** 测试意图：cold cache 条件下执行窄查询只运行窄 CTE，不执行现有 with recursive entry_path 全路径 CTE。 */
  @Test
  void coldCacheNarrowQueryExecutesNarrowCteAndZeroFullPathCte() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));
    UUID customId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            turnStartId,
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
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));
    UUID customId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            turnStartId,
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
