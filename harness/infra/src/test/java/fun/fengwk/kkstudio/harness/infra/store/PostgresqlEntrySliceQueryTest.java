package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PostgreSQL loadSlice 的真实查询边界：命中游标时递归在游标停止，不读取 ROOT；部分结果不进入完整路径缓存。
 *
 * <p>计划文本来自生产 {@code entry_slice} 语句自身，不是另写一条对照 SQL，也不是比较 Java 返回值。
 */
class PostgresqlEntrySliceQueryTest {

  private final AtomicInteger fullPathCounter = new AtomicInteger();
  private final AtomicReference<String> slicePlan = new AtomicReference<>("");
  private HarnessStore store;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    fullPathCounter.set(0);
    slicePlan.set("");
    DataSource counting =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), fullPathCounter);
    store =
        PostgresqlHarnessStoreFixture.create(
            PostgresqlEntrySliceProbe.probingDataSource(counting, slicePlan));
  }

  @AfterEach
  void tearDown() {
    PostgresqlHarnessStoreFixture.reset();
  }

  /** 测试意图：冷缓存命中祖先时不执行完整 entry_path CTE，且递归工作表不含 ROOT。 */
  @Test
  void coldAncestorSliceStopsBeforeRoot() {
    Chain chain = seedChain();
    fullPathCounter.set(0);

    EntrySlice slice =
        store.transaction(tx -> tx.entries().loadSlice(chain.headId(), chain.turnStartId()));

    assertEquals(chain.turnStartId(), slice.baseEntryId());
    assertEquals(List.of(chain.userId(), chain.headId()), ids(slice));
    assertEquals(0, fullPathCounter.get(), "命中游标不得先执行完整 entry_path CTE");
    assertTrue(slicePlan.get().contains(chain.turnStartId().toString()), "命中游标的递归过滤必须带上该游标");
    assertTrue(slicePlan.get().contains("<>"), "递归必须在游标处停止");
  }

  /** 测试意图：同事务已有完整路径时直接截取，不再查询；随后冷事务的部分结果不能冒充完整路径缓存。 */
  @Test
  void warmPathIsSlicedWithoutQueryAndPartialResultDoesNotFillPathCache() {
    Chain chain = seedChain();
    fullPathCounter.set(0);

    store.transaction(
        tx -> {
          tx.entries().loadPath(chain.headId());
          assertEquals(1, fullPathCounter.get());
          EntrySlice sliced = tx.entries().loadSlice(chain.headId(), chain.turnStartId());
          assertEquals(chain.turnStartId(), sliced.baseEntryId());
          assertEquals(List.of(chain.userId(), chain.headId()), ids(sliced));
          assertEquals(1, fullPathCounter.get(), "已缓存完整路径时截取不得再查询");
          return null;
        });

    fullPathCounter.set(0);
    store.transaction(tx -> tx.entries().loadSlice(chain.headId(), chain.turnStartId()));
    assertEquals(0, fullPathCounter.get(), "冷增量读取不得回填完整路径");

    fullPathCounter.set(0);
    store.transaction(tx -> tx.entries().loadPath(chain.headId()));
    assertEquals(1, fullPathCounter.get(), "此前的部分结果不得让下一事务跳过完整路径读取");
  }

  /** 测试意图：非祖先仍返回完整链，但使用增量 CTE 而不是先执行完整 entry_path。 */
  @Test
  void coldNonAncestorReturnsFullChainThroughSliceQuery() {
    Chain chain = seedChain();
    UUID otherRoot =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              tx.sessions().insert(session(sessionId));
              tx.entries().insert(rootEntry(rootId, sessionId));
              return rootId;
            });
    fullPathCounter.set(0);

    EntrySlice slice = store.transaction(tx -> tx.entries().loadSlice(chain.headId(), otherRoot));

    assertNull(slice.baseEntryId());
    assertEquals(chain.rootId(), slice.entries().get(0).id());
    assertEquals(EntryType.ROOT, slice.entries().get(0).payload().type());
    assertEquals(chain.headId(), slice.entries().get(slice.entries().size() - 1).id());
    assertEquals(0, fullPathCounter.get(), "非祖先全量也不得先走完整 entry_path CTE");
    assertTrue(slicePlan.get().contains("'ROOT'"), "非祖先必须继续走到 ROOT 才停止");
  }

  private Chain seedChain() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));
    UUID userId = insertChildEntry(store, baseline.sessionId(), turnStartId, userMessagePayload());
    UUID headId = insertChildEntry(store, baseline.sessionId(), userId, userMessagePayload());
    return new Chain(baseline.rootEntryId(), turnStartId, userId, headId);
  }

  private static List<UUID> ids(EntrySlice slice) {
    List<UUID> ids = new ArrayList<>();
    for (Entry entry : slice.entries()) {
      ids.add(entry.id());
    }
    return ids;
  }

  private record Chain(UUID rootId, UUID turnStartId, UUID userId, UUID headId) {}
}
