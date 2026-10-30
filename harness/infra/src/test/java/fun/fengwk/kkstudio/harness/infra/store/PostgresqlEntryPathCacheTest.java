package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL 事务内 EntryPath 局部缓存集成测试。
 *
 * <p>通过轻量 JDK Dynamic Proxy 监控 PreparedStatement 实际执行，精确计数 {@code with recursive entry_path} 查询。
 */
class PostgresqlEntryPathCacheTest {

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

  /** 测试意图：已持久化 head 在同事务重复 load 只产生 1 次 CTE；随后从已缓存 parent append child 并读取 child 不增加 CTE。 */
  @Test
  void repeatedLoadAndAppendFromCachedParentReusesEntryPath() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));
    cteCounter.set(0);

    store.transaction(
        tx -> {
          EntryPath loaded1 = tx.entries().loadPath(turnStartId);
          EntryPath loaded2 = tx.entries().loadPath(turnStartId);
          assertEquals(1, cteCounter.get(), "首次加载执行 1 次 CTE，重复读取不增加计数");
          assertEquals(List.of(baseline.rootEntryId(), turnStartId), entryIds(loaded1));
          assertEquals(entryIds(loaded1), entryIds(loaded2));

          UUID userMsgId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userMsgId, baseline.sessionId(), turnStartId, userMessagePayload(), T2));

          EntryPath childPath = tx.entries().loadPath(userMsgId);
          assertEquals(1, cteCounter.get(), "从已缓存 parent append child 后读取 child 不增加 CTE");
          assertEquals(
              List.of(baseline.rootEntryId(), turnStartId, userMsgId), entryIds(childPath));
          return null;
        });
  }

  /** 测试意图：同事务新建 Session+ROOT+连续 children 全程 0 CTE；下一新事务隔离独立，首次读取 1 次 CTE，重复读取仍为 1 次。 */
  @Test
  void newSessionContinuousAppendExecutesZeroCteAndIsolatesFromNextTransaction() {
    cteCounter.set(0);
    List<UUID> createdIds =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              UUID turnStartId = tx.nextId();
              UUID userMsgId = tx.nextId();

              tx.sessions().insert(session(sessionId));
              tx.entries().insert(rootEntry(rootId, sessionId));
              tx.entries()
                  .insert(
                      new Entry(
                          turnStartId,
                          sessionId,
                          rootId,
                          resolvedTurnStartPayload(tx.nextId()),
                          T1));
              tx.entries()
                  .insert(new Entry(userMsgId, sessionId, turnStartId, userMessagePayload(), T2));

              EntryPath path = tx.entries().loadPath(userMsgId);
              assertEquals(0, cteCounter.get(), "同事务连续写入并读取全程 0 CTE");
              assertEquals(List.of(rootId, turnStartId, userMsgId), entryIds(path));
              return List.of(rootId, turnStartId, userMsgId);
            });

    cteCounter.set(0);
    UUID headId = createdIds.get(createdIds.size() - 1);
    store.transaction(
        tx -> {
          EntryPath first = tx.entries().loadPath(headId);
          assertEquals(1, cteCounter.get(), "新事务首次读取独立执行 1 次 CTE");
          EntryPath second = tx.entries().loadPath(headId);
          assertEquals(1, cteCounter.get(), "新事务内部重复读取命中自身缓存，不增加 CTE");
          assertEquals(createdIds, entryIds(first));
          assertEquals(createdIds, entryIds(second));
          return null;
        });
  }

  /** 测试意图：deleteEntries 驱逐本事务缓存，同事务后续再读已删 head 失败。 */
  @Test
  void deleteEntriesEvictsSessionCache() {
    UUID sessionId = UUID.randomUUID();
    UUID rootId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.sessions().insert(session(sessionId));
          tx.entries().insert(rootEntry(rootId, sessionId));
          return null;
        });

    store.transaction(
        tx -> {
          EntryPath path = tx.entries().loadPath(rootId);
          assertEquals(rootId, path.head().id());

          int deleted = tx.entries().delete(sessionId);
          assertEquals(1, deleted);
          assertThrows(IllegalArgumentException.class, () -> tx.entries().loadPath(rootId));
          return null;
        });
  }

  private static List<UUID> entryIds(EntryPath path) {
    return path.entries().stream().map(Entry::id).toList();
  }
}
