package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL 事务内 EntryPath 局部缓存集成测试。
 *
 * <p>通过 JDK 动态代理监控测试 DataSource 与 Connection 执行的 SQL，精确计数 {@code with recursive entry_path} 递归 CTE
 * 查询次数。验证以下核心契约：
 *
 * <ol>
 *   <li>同一事务重复读取同一 head 只有一次 CTE；
 *   <li>已缓存 parent 后连续 append 多个 child，随后读取新 head，不增加 CTE；
 *   <li>新事务不会复用旧事务 cache，首次读取仍执行一次 CTE；
 *   <li>返回路径仍严格 root-to-head 且连续；
 *   <li>写入失败时不污染事务缓存。
 * </ol>
 */
class PostgresqlEntryPathCacheTest {

  private final AtomicInteger cteCounter = new AtomicInteger();
  private HarnessStore store;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    cteCounter.set(0);
    DataSource countingDataSource =
        wrapWithCteCounting(PostgresqlHarnessStoreFixture.dataSource(), cteCounter);
    store = PostgresqlHarnessStoreFixture.create(countingDataSource);
  }

  /** 测试意图：证明同一事务内重复读取同一 head entry 时只有首次未命中执行递归 CTE，后续读取命中事务内缓存，不产生额外 CTE 查询。 */
  @Test
  void repeatedReadOfSameHeadWithinSameTransactionExecutesCteOnlyOnce() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));

    // 在 setup SQL 完成后重置计数器，仅统计目标 path CTE
    cteCounter.set(0);

    store.transaction(
        tx -> {
          EntryPath path1 = tx.loadEntryPath(turnStartId);
          assertEquals(1, cteCounter.get(), "首次加载 head 时必须执行 1 次递归 CTE 查询");

          EntryPath path2 = tx.loadEntryPath(turnStartId);
          assertEquals(1, cteCounter.get(), "同事务内第二次加载同一 head 不应执行额外的 CTE 查询");

          EntryPath path3 = tx.loadEntryPath(turnStartId);
          assertEquals(1, cteCounter.get(), "同事务内第三次加载同一 head 不应执行额外的 CTE 查询");

          assertSame(path1, path2, "同事务内缓存应返回相同的 EntryPath 实例");
          assertSame(path2, path3);
          assertEquals(turnStartId, path1.head().id());
          assertEquals(baseline.rootEntryId(), path1.root().id());
          assertEquals(2, path1.entries().size());
          return null;
        });
  }

  /** 测试意图：证明在 parent entry 已缓存的前提下，连续 append 多个 child entry，随后读取新 head，整个过程零额外 CTE。 */
  @Test
  void appendChildrenAfterParentCachedDoesNotIncreaseCte() {
    Baseline baseline = seedThreadBaseline(store);

    // 场景 A：从已持久化状态加载 parent (ROOT) 建立缓存，然后连续插入多个 child
    cteCounter.set(0);
    store.transaction(
        tx -> {
          // 1. 首次读取 root 产生 1 次 CTE 并建立缓存
          EntryPath rootPath = tx.loadEntryPath(baseline.rootEntryId());
          assertNotNull(rootPath);
          assertEquals(1, cteCounter.get(), "首次加载 root 产生 1 次 CTE");

          // 重置计数器，统计后续连续追加与读取新 head
          cteCounter.set(0);

          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  baseline.sessionId(),
                  baseline.rootEntryId(),
                  resolvedTurnStartPayload(baseline.threadId()),
                  T1));

          UUID userMsgId = tx.nextId();
          tx.insertEntry(
              new Entry(userMsgId, baseline.sessionId(), turnStartId, userMessagePayload(), T2));

          UUID assistantMsgId = tx.nextId();
          tx.insertEntry(
              new Entry(assistantMsgId, baseline.sessionId(), userMsgId, assistantPayload(), T3));

          // 读取最新 head
          EntryPath headPath = tx.loadEntryPath(assistantMsgId);

          assertEquals(
              0, cteCounter.get(), "已缓存 parent 后连续 append 多个 child 并读取最新 head，不应执行任何递归 CTE");
          assertEquals(4, headPath.entries().size());
          assertEquals(baseline.rootEntryId(), headPath.root().id());
          assertEquals(assistantMsgId, headPath.head().id());
          return null;
        });

    // 场景 B：在同一事务中从新建 Session + ROOT 开始连续追加 child，ROOT 成功插入后自动缓存，后续连续 append 全程 0 CTE
    cteCounter.set(0);
    store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID turnStartId = tx.nextId();
          UUID userMsgId = tx.nextId();
          UUID assistantMsgId = tx.nextId();

          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));

          tx.insertEntry(
              new Entry(turnStartId, sessionId, rootId, resolvedTurnStartPayload(tx.nextId()), T1));
          tx.insertEntry(new Entry(userMsgId, sessionId, turnStartId, userMessagePayload(), T2));
          tx.insertEntry(new Entry(assistantMsgId, sessionId, userMsgId, assistantPayload(), T3));

          EntryPath path = tx.loadEntryPath(assistantMsgId);
          assertEquals(
              0, cteCounter.get(), "从 ROOT 成功插入后同事务连续 append 多个 child 并读取新 head，全程零 CTE 查询");
          assertEquals(4, path.entries().size());
          assertEquals(rootId, path.root().id());
          assertEquals(assistantMsgId, path.head().id());
          return null;
        });
  }

  /** 测试意图：证明新事务不会复用已提交旧事务的 cache，新事务首次读取同一 head 仍必须执行一次递归 CTE，但同事务内后续读取仍受缓存保护。 */
  @Test
  void newTransactionDoesNotReuseOldTransactionCache() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            resolvedTurnStartPayload(baseline.threadId()));

    // 事务 1：首次读取，执行 1 次 CTE
    cteCounter.set(0);
    store.transaction(
        tx1 -> {
          EntryPath path1 = tx1.loadEntryPath(turnStartId);
          assertEquals(turnStartId, path1.head().id());
          assertEquals(1, cteCounter.get(), "事务 1 首次读取应执行 1 次 CTE");
          return null;
        });

    // 事务 2：新事务实例不复用旧事务缓存，首次读取仍执行 1 次 CTE
    cteCounter.set(0);
    store.transaction(
        tx2 -> {
          EntryPath path2 = tx2.loadEntryPath(turnStartId);
          assertEquals(turnStartId, path2.head().id());
          assertEquals(1, cteCounter.get(), "新事务首次读取同一 head 不应复用旧事务缓存，仍执行 1 次 CTE");

          EntryPath path2Again = tx2.loadEntryPath(turnStartId);
          assertEquals(1, cteCounter.get(), "新事务内部第二次读取该 head 应命中自身事务缓存");
          assertSame(path2, path2Again);
          return null;
        });

    // 事务 3：再次验证另一个新事务依然独立
    cteCounter.set(0);
    store.transaction(
        tx3 -> {
          EntryPath path3 = tx3.loadEntryPath(turnStartId);
          assertEquals(turnStartId, path3.head().id());
          assertEquals(1, cteCounter.get(), "事务 3 作为又一个独立事务仍执行 1 次 CTE");
          return null;
        });
  }

  /** 测试意图：证明无论通过缓存直接返回还是经由递归 CTE 首次构建返回，EntryPath 均严格保持从 root 到 head 的拓扑顺序，且 parent 链绝对连续。 */
  @Test
  void returnedPathsAreStrictlyContiguousAndRootToHead() {
    Baseline baseline = seedThreadBaseline(store);
    List<UUID> entryIds = new ArrayList<>();
    entryIds.add(baseline.rootEntryId());

    UUID assistantMsgId =
        store.transaction(
            tx -> {
              UUID turnStartId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      turnStartId,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      resolvedTurnStartPayload(baseline.threadId()),
                      T1));
              entryIds.add(turnStartId);

              UUID userMsgId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      userMsgId, baseline.sessionId(), turnStartId, userMessagePayload(), T2));
              entryIds.add(userMsgId);

              UUID assistantId = tx.nextId();
              tx.insertEntry(
                  new Entry(assistantId, baseline.sessionId(), userMsgId, assistantPayload(), T3));
              entryIds.add(assistantId);

              UUID turnEndId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      turnEndId,
                      baseline.sessionId(),
                      assistantId,
                      new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                      T4));
              entryIds.add(turnEndId);

              // 验证同事务内由 insertEntry 维护的缓存路径
              EntryPath cachedPath = tx.loadEntryPath(turnEndId);
              assertValidContiguousPath(cachedPath, baseline.sessionId(), entryIds);
              return assistantId;
            });

    // 在另一个独立事务中读取（触发递归 CTE），验证从数据库查询并构建的路径同样严格连续且与期望一致
    store.transaction(
        tx -> {
          UUID turnEndId = entryIds.get(entryIds.size() - 1);
          EntryPath freshPath = tx.loadEntryPath(turnEndId);
          assertValidContiguousPath(freshPath, baseline.sessionId(), entryIds);

          // 仅查询中间节点 assistantMsgId，验证其前缀路径同样连续且 root-to-head
          List<UUID> intermediateIds = entryIds.subList(0, 4);
          EntryPath subPath = tx.loadEntryPath(assistantMsgId);
          assertValidContiguousPath(subPath, baseline.sessionId(), intermediateIds);
          return null;
        });
  }

  /** 测试意图：证明当 insertEntry 校验失败或写数据库失败时，未持久化的 candidate 不会进入缓存，避免同事务读到幻象 entry。 */
  @Test
  void failedInsertDoesNotPolluteCache() {
    Baseline baseline = seedThreadBaseline(store);
    cteCounter.set(0);

    store.transaction(
        tx -> {
          // 预加载 root 进缓存
          EntryPath rootPath = tx.loadEntryPath(baseline.rootEntryId());
          assertNotNull(rootPath);

          // 尝试插入一个重复 ID 的 entry（校验即失败）
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  tx.insertEntry(
                      new Entry(
                          baseline.rootEntryId(),
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          userMessagePayload(),
                          T1)));

          // 尝试插入一个指向不存在 session 的 entry
          UUID phantomId = tx.nextId();
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  tx.insertEntry(
                      new Entry(
                          phantomId,
                          tx.nextId(),
                          baseline.rootEntryId(),
                          userMessagePayload(),
                          T1)));

          // 尝试插入一个合法 candidate 但时间戳非法（写库阶段失败）的 entry
          UUID writeFailId = tx.nextId();
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  tx.insertEntry(
                      new Entry(
                          writeFailId,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          resolvedTurnStartPayload(baseline.threadId()),
                          T1.plusNanos(1))));

          // 验证写库失败的 writeFailId 未被放入缓存，loadEntryPath 必须抛出 IllegalArgumentException
          assertThrows(IllegalArgumentException.class, () -> tx.loadEntryPath(writeFailId));

          // 验证失败的 phantomId 未被放入缓存，loadEntryPath 必须抛出 IllegalArgumentException
          assertThrows(IllegalArgumentException.class, () -> tx.loadEntryPath(phantomId));

          // 验证 root 节点的缓存完好无损
          EntryPath reloadedRoot = tx.loadEntryPath(baseline.rootEntryId());
          assertEquals(baseline.rootEntryId(), reloadedRoot.head().id());
          return null;
        });
  }

  /**
   * 测试意图：证明当调用 deleteEntries 或 deleteSession 时，相应 session 的 EntryPath 缓存会被同步驱逐，避免同事务后续返回已删除 entry。
   */
  @Test
  void deleteEntriesAndSessionEvictsCachedPaths() {
    UUID sessionId = UUID.randomUUID();
    UUID rootId = UUID.randomUUID();

    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          return null;
        });

    store.transaction(
        tx -> {
          // 先加载入缓存
          EntryPath path = tx.loadEntryPath(rootId);
          assertEquals(rootId, path.head().id());

          // 删除该 session 的 entries
          int deleted = tx.deleteEntries(sessionId);
          assertEquals(1, deleted);

          // 缓存应被驱逐，再次读取该 head 应抛出异常
          assertThrows(IllegalArgumentException.class, () -> tx.loadEntryPath(rootId));

          // 删除 session 本身
          boolean sessionDeleted = tx.deleteSession(sessionId);
          assertEquals(true, sessionDeleted);
          return null;
        });
  }

  private static void assertValidContiguousPath(
      EntryPath path, UUID expectedSessionId, List<UUID> expectedIds) {
    List<Entry> entries = path.entries();
    assertEquals(expectedIds.size(), entries.size(), "路径长度必须与预期一致");
    assertEquals(expectedIds.get(0), path.root().id(), "root 必须是首节点");
    assertEquals(expectedIds.get(expectedIds.size() - 1), path.head().id(), "head 必须是尾节点");

    for (int i = 0; i < entries.size(); i++) {
      Entry current = entries.get(i);
      assertEquals(expectedIds.get(i), current.id(), "节点 ID 顺序必须严格匹配");
      assertEquals(expectedSessionId, current.sessionId(), "所有节点必须属于同一 session");

      if (i == 0) {
        assertNull(current.parentEntryId(), "ROOT 节点的 parentEntryId 必须为 null");
      } else {
        Entry previous = entries.get(i - 1);
        assertEquals(
            previous.id(), current.parentEntryId(), "非 ROOT 节点的 parentEntryId 必须等于前一节点的 ID");
        assertFalse(current.createdAt().isBefore(previous.createdAt()), "子节点的 createdAt 不应早于父节点");
      }
    }
  }

  private static DataSource wrapWithCteCounting(DataSource target, AtomicInteger counter) {
    return (DataSource)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {DataSource.class},
            new CountingDataSourceInvocationHandler(target, counter));
  }

  private static Connection wrapConnection(Connection target, AtomicInteger counter) {
    return (Connection)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            new CountingConnectionInvocationHandler(target, counter));
  }

  private static PreparedStatement wrapPreparedStatement(
      PreparedStatement target, String sql, AtomicInteger counter) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            new CountingPreparedStatementInvocationHandler(target, sql, counter));
  }

  private static Statement wrapStatement(Statement target, AtomicInteger counter) {
    return (Statement)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {Statement.class},
            new CountingStatementInvocationHandler(target, counter));
  }

  private static boolean isEntryPathCte(String sql) {
    if (sql == null) {
      return false;
    }
    String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    return normalized.contains("with recursive entry_path");
  }

  private static Throwable unwrapTargetException(InvocationTargetException error) {
    Throwable target = error.getTargetException();
    return target != null ? target : error;
  }

  private record CountingDataSourceInvocationHandler(DataSource target, AtomicInteger counter)
      implements InvocationHandler {

    CountingDataSourceInvocationHandler {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(counter, "counter");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      if ("getConnection".equals(methodName)) {
        try {
          Connection connection = (Connection) method.invoke(target, args);
          return wrapConnection(connection, counter);
        } catch (InvocationTargetException error) {
          throw unwrapTargetException(error);
        }
      }
      if ("unwrap".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy)) {
          return proxy;
        }
        if (iface.isInstance(target)) {
          return target;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.unwrap(iface);
        }
      }
      if ("isWrapperFor".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy) || iface.isInstance(target)) {
          return true;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.isWrapperFor(iface);
        }
      }
      if ("equals".equals(methodName) && args != null && args.length == 1) {
        return proxy == args[0];
      }
      if ("hashCode".equals(methodName)) {
        return System.identityHashCode(proxy);
      }
      if ("toString".equals(methodName)) {
        return target.toString();
      }
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw unwrapTargetException(error);
      }
    }
  }

  private record CountingConnectionInvocationHandler(Connection target, AtomicInteger counter)
      implements InvocationHandler {

    CountingConnectionInvocationHandler {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(counter, "counter");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      if ("prepareStatement".equals(methodName)
          && args != null
          && args.length > 0
          && args[0] instanceof String sql) {
        try {
          PreparedStatement ps = (PreparedStatement) method.invoke(target, args);
          return wrapPreparedStatement(ps, sql, counter);
        } catch (InvocationTargetException error) {
          throw unwrapTargetException(error);
        }
      }
      if ("createStatement".equals(methodName)) {
        try {
          Statement stmt = (Statement) method.invoke(target, args);
          return wrapStatement(stmt, counter);
        } catch (InvocationTargetException error) {
          throw unwrapTargetException(error);
        }
      }
      if ("unwrap".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy)) {
          return proxy;
        }
        if (iface.isInstance(target)) {
          return target;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.unwrap(iface);
        }
      }
      if ("isWrapperFor".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy) || iface.isInstance(target)) {
          return true;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.isWrapperFor(iface);
        }
      }
      if ("equals".equals(methodName) && args != null && args.length == 1) {
        return proxy == args[0];
      }
      if ("hashCode".equals(methodName)) {
        return System.identityHashCode(proxy);
      }
      if ("toString".equals(methodName)) {
        return target.toString();
      }
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw unwrapTargetException(error);
      }
    }
  }

  private record CountingPreparedStatementInvocationHandler(
      PreparedStatement target, String sql, AtomicInteger counter) implements InvocationHandler {

    CountingPreparedStatementInvocationHandler {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(counter, "counter");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      if (methodName.startsWith("execute")) {
        if (isEntryPathCte(sql)) {
          counter.incrementAndGet();
        }
      }
      if ("unwrap".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy)) {
          return proxy;
        }
        if (iface.isInstance(target)) {
          return target;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.unwrap(iface);
        }
      }
      if ("isWrapperFor".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy) || iface.isInstance(target)) {
          return true;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.isWrapperFor(iface);
        }
      }
      if ("equals".equals(methodName) && args != null && args.length == 1) {
        return proxy == args[0];
      }
      if ("hashCode".equals(methodName)) {
        return System.identityHashCode(proxy);
      }
      if ("toString".equals(methodName)) {
        return target.toString();
      }
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw unwrapTargetException(error);
      }
    }
  }

  private record CountingStatementInvocationHandler(Statement target, AtomicInteger counter)
      implements InvocationHandler {

    CountingStatementInvocationHandler {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(counter, "counter");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      if (methodName.startsWith("execute")
          && args != null
          && args.length > 0
          && args[0] instanceof String sql) {
        if (isEntryPathCte(sql)) {
          counter.incrementAndGet();
        }
      }
      if ("unwrap".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy)) {
          return proxy;
        }
        if (iface.isInstance(target)) {
          return target;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.unwrap(iface);
        }
      }
      if ("isWrapperFor".equals(methodName) && args != null && args.length == 1) {
        Class<?> iface = (Class<?>) args[0];
        if (iface.isInstance(proxy) || iface.isInstance(target)) {
          return true;
        }
        if (target instanceof Wrapper wrapper) {
          return wrapper.isWrapperFor(iface);
        }
      }
      if ("equals".equals(methodName) && args != null && args.length == 1) {
        return proxy == args[0];
      }
      if ("hashCode".equals(methodName)) {
        return System.identityHashCode(proxy);
      }
      if ("toString".equals(methodName)) {
        return target.toString();
      }
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw unwrapTargetException(error);
      }
    }
  }
}
