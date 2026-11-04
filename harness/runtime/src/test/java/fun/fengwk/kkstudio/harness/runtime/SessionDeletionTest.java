package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.EntryStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.JoinStore;
import fun.fengwk.kkstudio.harness.spi.store.SessionStore;
import fun.fengwk.kkstudio.harness.spi.store.ThreadStore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link SessionDeletion} 的直接行为契约：在内存参考 Store 上以真实事务驱动删除闭包本身。
 *
 * <p>覆盖 PG 集成测试无法观察的内部约定：Tree -&gt; Session -&gt; Thread -&gt; Join 的规范锁序、以 Session 为单位推进的闭包（含 task
 * 子 Session 与其内无父 root fork）、关系回调先于 Thread 行删除、引用释放先于本 Session 的 Entry/Session 行删除、空/未匹配输入与回调
 * 失败的整体回滚，以及回调入参不可变且事务句柄不随回调外泄。
 *
 * <p>不重复 Project 模块 {@code SessionDeletionIntegrationTest} 已覆盖的真实 PG 外键行为，也不修改生产实现。
 */
class SessionDeletionTest {

  private static final UUID JOIN_ID = id(900L);

  /** Command 行锁与 Join 删除的记录名，只保留锁定与删除调用，闭包发现期的读取不进入事件流。 */
  private static final Set<String> RECORDED_METHODS =
      Set.of("lockTree", "lock", "lockForUpdate", "delete", "deleteForThreads");

  @Test
  void deletesChildSessionAndParentlessForkInCanonicalOrder() {
    // 测试意图：以 Session 为单位推进才能把 task 子 Session（Thread 在另一 Session）与其内无父 fork 一起删除；
    // 同时固定 Tree -> Session -> (关系回调) -> Thread -> Join 的实际调用顺序：关系回调先于 Thread 行加锁/删除，
    // 引用释放先于本 Session 的 Entry/Session 行删除。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    SessionThread child = seedChildThread(store, owned.threadId());
    SessionThread fork = seedRootThreadIn(store, child.sessionId(), child.rootEntryId(), "fork");
    Baseline survivor = HarnessRuntimeTestSupport.seedBaseline(store);
    seedCommandAndPendingJoin(store, owned.threadId(), child.threadId());

    List<String> events = new ArrayList<>();
    store.transaction(
        tx -> {
          Consumer<List<UUID>> deleteRelations =
              ids -> {
                events.add(line("callback.deleteRelations", ids));
                // 归属关系必须先于 harness Thread 行删除：回调执行时整棵待删树仍然完整。
                for (UUID threadId : List.of(owned.threadId(), child.threadId(), fork.threadId())) {
                  assertTrue(tx.threads().find(threadId).isPresent());
                }
                assertTrue(tx.entries().find(child.rootEntryId()).isPresent());
                assertTrue(tx.joins().find(JOIN_ID).isPresent());
              };
          Consumer<List<UUID>> releaseRefs =
              ids -> {
                events.add(line("callback.releaseRefs", ids));
                assertEquals(1, ids.size());
                // Thread 行已经删除，但本 Session 的 Entry/Session 行尚未删除，生存 Session 也仍完整。
                assertTrue(tx.threads().find(owned.threadId()).isEmpty());
                assertTrue(tx.threads().find(child.threadId()).isEmpty());
                assertTrue(tx.threads().find(fork.threadId()).isEmpty());
                assertTrue(tx.entries().find(child.rootEntryId()).isPresent());
                assertTrue(tx.sessions().find(child.sessionId()).isPresent());
                if (ids.get(0).equals(child.sessionId())) {
                  assertTrue(tx.sessions().find(owned.sessionId()).isEmpty());
                }
              };
          SessionDeletion.delete(
              recording(tx, events, null),
              List.of(owned.sessionId()),
              deleteRelations,
              releaseRefs);
          return null;
        });

    List<UUID> deletedThreads = List.of(owned.threadId(), child.threadId(), fork.threadId());
    assertEquals(
        List.of(
            line("threads.lockTree", owned.threadId()),
            line("threads.lockTree", fork.threadId()),
            line("sessions.lockForUpdate", owned.sessionId()),
            line("sessions.lockForUpdate", child.sessionId()),
            line("callback.deleteRelations", List.of(owned.sessionId(), child.sessionId())),
            line("threads.lock", owned.threadId()),
            line("threads.lock", child.threadId()),
            line("threads.lock", fork.threadId()),
            line("joins.deleteForThreads", deletedThreads),
            line("threads.delete", deletedThreads),
            line("callback.releaseRefs", List.of(owned.sessionId())),
            line("entries.delete", owned.sessionId()),
            line("sessions.delete", owned.sessionId()),
            line("callback.releaseRefs", List.of(child.sessionId())),
            line("entries.delete", child.sessionId()),
            line("sessions.delete", child.sessionId())),
        events);

    assertFalse(sessionExists(store, owned.sessionId()));
    assertFalse(sessionExists(store, child.sessionId()));
    assertFalse(entryExists(store, owned.rootEntryId()));
    assertFalse(entryExists(store, child.rootEntryId()));
    assertFalse(commandExists(store, child.threadId(), 1L));
    assertFalse(joinExists(store, JOIN_ID));
    for (UUID threadId : deletedThreads) {
      assertFalse(threadExists(store, threadId));
    }
    // 生存者：另一个执行树完全不受影响。
    assertTrue(sessionExists(store, survivor.sessionId()));
    assertTrue(threadExists(store, survivor.threadId()));
    assertTrue(entryExists(store, survivor.rootEntryId()));
  }

  @Test
  void relationsCallbackFailureRollsBackAllDeletions() {
    // 测试意图：关系（归属行）清理失败时不得留下半删树，异常原样传播且事务整体回滚。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    SessionThread child = seedChildThread(store, owned.threadId());
    seedCommandAndPendingJoin(store, owned.threadId(), child.threadId());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      SessionDeletion.delete(
                          tx,
                          List.of(owned.sessionId()),
                          ids -> {
                            throw new IllegalStateException("owner relation is still referenced");
                          },
                          ids -> {
                            throw new AssertionError(
                                "releaseRefs must not run when relations fail");
                          });
                      return null;
                    }));

    assertEquals("owner relation is still referenced", thrown.getMessage());
    assertClosureIntact(store, owned, child);
  }

  @Test
  void releaseRefsFailureOnLaterSessionRollsBackEarlierDeletions() {
    // 测试意图：Thread 行与前一个 Session 的 Entry/Session 行已在本事务内删除后，后续 Session 的引用释放失败
    // 必须把整批删除回滚，证明回调失败不会被当作"跳过该 Session"继续。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    SessionThread child = seedChildThread(store, owned.threadId());
    seedCommandAndPendingJoin(store, owned.threadId(), child.threadId());

    List<List<UUID>> released = new ArrayList<>();
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      SessionDeletion.delete(
                          tx,
                          List.of(owned.sessionId()),
                          ids -> {},
                          ids -> {
                            released.add(ids);
                            if (ids.contains(child.sessionId())) {
                              throw new IllegalStateException("blob ref release failed");
                            }
                          });
                      return null;
                    }));

    assertEquals("blob ref release failed", thrown.getMessage());
    assertEquals(
        List.of(List.of(owned.sessionId()), List.of(child.sessionId())), List.copyOf(released));
    assertClosureIntact(store, owned, child);
  }

  @Test
  void abortsWhenClosureGrowsWhileAcquiringSessionLocks() {
    // 测试意图：发现与 Session 行锁之间新增的子 Session 会让闭包扩大；此时必须整体失败回滚，
    // 不能删除"漏掉后代"的集合（内存实现中该并发写入随本事务一起丢弃）。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    List<String> events = new ArrayList<>();
    List<UUID> injected = new ArrayList<>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      SessionDeletion.delete(
                          recording(
                              tx, events, () -> injected.addAll(seedChildOf(tx, owned.threadId()))),
                          List.of(owned.sessionId()),
                          ids -> {
                            throw new AssertionError("relations must not run after closure drift");
                          },
                          ids -> {
                            throw new AssertionError(
                                "releaseRefs must not run after closure drift");
                          });
                      return null;
                    }));

    assertEquals(
        "harness execution tree changed while acquiring its deletion locks", thrown.getMessage());
    assertTrue(
        events.stream()
            .noneMatch(
                event ->
                    event.startsWith("threads.delete")
                        || event.startsWith("entries.delete")
                        || event.startsWith("sessions.delete")));
    // 回滚后原树完整，模拟并发 fork 的写入也不可见。
    assertTrue(sessionExists(store, owned.sessionId()));
    assertTrue(threadExists(store, owned.threadId()));
    assertTrue(entryExists(store, owned.rootEntryId()));
    assertEquals(2, injected.size());
    assertFalse(sessionExists(store, injected.get(0)));
    assertFalse(threadExists(store, injected.get(1)));
  }

  @Test
  void unknownSessionIdFailsClosedBeforeLocksAndCallbacks() {
    // 测试意图：未匹配的 Session ID 不会被静默跳过；闭包发现阶段即失败，失败前不进入删除与回调。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    List<List<UUID>> received = new ArrayList<>();

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      SessionDeletion.delete(
                          tx,
                          List.of(owned.sessionId(), id(999L)),
                          ids -> received.add(ids),
                          ids -> received.add(ids));
                      return null;
                    }));

    assertTrue(thrown.getMessage().contains("does not exist"));
    assertTrue(received.isEmpty());
    assertTrue(sessionExists(store, owned.sessionId()));
    assertTrue(threadExists(store, owned.threadId()));
    assertTrue(entryExists(store, owned.rootEntryId()));
  }

  @Test
  void duplicateSessionIdsAreDeduplicatedBeforeDeletion() {
    // 测试意图：重复入参只推进一次闭包，回调各收到一次且行只删除一次（重复删除会让 store 返回 false）。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    List<List<UUID>> received = new ArrayList<>();

    store.transaction(
        tx -> {
          SessionDeletion.delete(
              tx,
              List.of(owned.sessionId(), owned.sessionId()),
              ids -> received.add(ids),
              ids -> received.add(ids));
          return null;
        });

    assertEquals(List.of(List.of(owned.sessionId()), List.of(owned.sessionId())), received);
    assertFalse(sessionExists(store, owned.sessionId()));
    assertFalse(threadExists(store, owned.threadId()));
    assertFalse(entryExists(store, owned.rootEntryId()));
  }

  @Test
  void callbacksReceiveImmutableSnapshotsAndHandleStaysInsideTransaction() {
    // 测试意图：回调只拿到 List.copyOf 快照（不能反向扩大/修改删除范围，也拿不到事务句柄）；
    // 事务结束后遗留的句柄引用必须失效，避免删除范围之外继续读写。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline owned = HarnessRuntimeTestSupport.seedBaseline(store);
    SessionThread child = seedChildThread(store, owned.threadId());
    List<HarnessStore.Transaction> handles = new ArrayList<>();
    List<List<UUID>> received = new ArrayList<>();

    store.transaction(
        tx -> {
          handles.add(tx);
          SessionDeletion.delete(
              tx, List.of(owned.sessionId()), ids -> received.add(ids), ids -> received.add(ids));
          return null;
        });

    assertEquals(3, received.size());
    List<UUID> relations = received.get(0);
    assertEquals(List.of(owned.sessionId(), child.sessionId()), relations);
    assertThrows(UnsupportedOperationException.class, () -> relations.add(id(999L)));
    assertEquals(List.of(owned.sessionId()), received.get(1));
    assertEquals(List.of(child.sessionId()), received.get(2));

    HarnessStore.Transaction escaped = handles.get(0);
    assertThrows(IllegalStateException.class, escaped::sessions);
    assertThrows(IllegalStateException.class, escaped::threads);
    assertThrows(IllegalStateException.class, escaped::entries);
  }

  @Test
  void emptySessionListSkipsTransactionAndCallbacks() {
    // 测试意图：空输入是已完成的删除，不打开 Harness 事务也不触发任何清理回调。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));

    runtime.deleteSessions(
        List.of(),
        ids -> {
          throw new AssertionError("deleteRelations must not run for an empty session list");
        },
        ids -> {
          throw new AssertionError("releaseRefs must not run for an empty session list");
        });

    assertEquals(0, store.transactionCount());
  }

  // ---------- fixtures ----------

  /** 同一执行树内的 Session + ROOT + Thread：子 Thread 位于独立 Session，父 Thread 指向 {@code parentThreadId}。 */
  private record SessionThread(UUID sessionId, UUID rootEntryId, UUID threadId) {}

  private static SessionThread seedChildThread(InMemoryHarnessStore store, UUID parentThreadId) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(HarnessRuntimeTestSupport.session(sessionId));
          tx.entries().insert(HarnessRuntimeTestSupport.rootEntry(rootEntryId, sessionId));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      parentThreadId,
                      rootEntryId,
                      CREATION_REQUEST_HASH,
                      "task-child",
                      parentThreadId,
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      T0,
                      T0));
          return new SessionThread(sessionId, rootEntryId, threadId);
        });
  }

  /** 在已有 Session 内写入另一条无父执行根 Thread（同一 Session 的多入口 fork）。 */
  private static SessionThread seedRootThreadIn(
      InMemoryHarnessStore store, UUID sessionId, UUID headEntryId, String name) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      null,
                      headEntryId,
                      CREATION_REQUEST_HASH,
                      name,
                      threadId,
                      false,
                      SpawnTrigger.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      T0,
                      T0));
          return new SessionThread(sessionId, headEntryId, threadId);
        });
  }

  /** 子 Thread 上的一条 QUEUED command 与一条 pending join：深删必须先删 join 再删 Thread。 */
  private static void seedCommandAndPendingJoin(
      InMemoryHarnessStore store, UUID parentThreadId, UUID childThreadId) {
    store.transaction(
        tx -> {
          tx.threads().lock(childThreadId).orElseThrow();
          tx.commands().insert(List.of(command(childThreadId, 1L, id(90L))));
          tx.joins()
              .insert(
                  new ThreadJoin(
                      JOIN_ID,
                      CREATION_REQUEST_HASH,
                      parentThreadId,
                      childThreadId,
                      1L,
                      "executor",
                      10,
                      0L,
                      null,
                      null,
                      null,
                      T0,
                      T0,
                      JoinDelivery.NOTIFY_PARENT,
                      null));
          return null;
        });
  }

  /** 模拟并发接受在 Session 行锁期间为 {@code parentThreadId} 新增子 Session Thread；返回其 Session 与 Thread id。 */
  private static List<UUID> seedChildOf(HarnessStore.Transaction tx, UUID parentThreadId) {
    UUID sessionId = tx.nextId();
    UUID rootEntryId = tx.nextId();
    UUID threadId = tx.nextId();
    tx.sessions().insert(HarnessRuntimeTestSupport.session(sessionId));
    tx.entries().insert(HarnessRuntimeTestSupport.rootEntry(rootEntryId, sessionId));
    tx.threads()
        .insert(
            new ThreadState(
                threadId,
                sessionId,
                parentThreadId,
                rootEntryId,
                CREATION_REQUEST_HASH,
                "concurrent-child",
                parentThreadId,
                false,
                SpawnTrigger.MODEL_TOOL,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                1L,
                0L,
                T0,
                T0));
    return List.of(sessionId, threadId);
  }

  /** 断言闭包内所有事实仍完整存在（回滚后无半删状态）。 */
  private static void assertClosureIntact(
      InMemoryHarnessStore store, Baseline owned, SessionThread child) {
    assertTrue(sessionExists(store, owned.sessionId()));
    assertTrue(sessionExists(store, child.sessionId()));
    assertTrue(threadExists(store, owned.threadId()));
    assertTrue(threadExists(store, child.threadId()));
    assertTrue(entryExists(store, owned.rootEntryId()));
    assertTrue(entryExists(store, child.rootEntryId()));
    assertTrue(commandExists(store, child.threadId(), 1L));
    assertTrue(joinExists(store, JOIN_ID));
  }

  private static boolean sessionExists(InMemoryHarnessStore store, UUID sessionId) {
    return store.transaction(tx -> tx.sessions().find(sessionId)).isPresent();
  }

  private static boolean entryExists(InMemoryHarnessStore store, UUID entryId) {
    return store.transaction(tx -> tx.entries().find(entryId)).isPresent();
  }

  private static boolean threadExists(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId)).isPresent();
  }

  private static boolean commandExists(InMemoryHarnessStore store, UUID threadId, long sequence) {
    return store.transaction(tx -> tx.commands().find(threadId, sequence)).isPresent();
  }

  private static boolean joinExists(InMemoryHarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.joins().find(invocationId)).isPresent();
  }

  // ---------- 记录型事务句柄：只观察 SessionDeletion 的加锁/删除调用序列 ----------

  /**
   * 以只读代理记录 {@link SessionDeletion} 对该事务的加锁与删除调用，并在 Session 行锁返回后执行可选的注入动作。
   *
   * <p>代理只转发到同一事务句柄，不改变任何锁与快照语义；回调自身仍使用原始句柄，因此不会污染事件流。
   */
  private static HarnessStore.Transaction recording(
      HarnessStore.Transaction tx, List<String> events, Runnable afterSessionLock) {
    SessionStore sessions =
        recordingView(SessionStore.class, "sessions", tx.sessions(), events, afterSessionLock);
    EntryStore entries = recordingView(EntryStore.class, "entries", tx.entries(), events, null);
    ThreadStore threads = recordingView(ThreadStore.class, "threads", tx.threads(), events, null);
    JoinStore joins = recordingView(JoinStore.class, "joins", tx.joins(), events, null);
    return (HarnessStore.Transaction)
        Proxy.newProxyInstance(
            HarnessStore.Transaction.class.getClassLoader(),
            new Class<?>[] {HarnessStore.Transaction.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "sessions" -> sessions;
                  case "entries" -> entries;
                  case "threads" -> threads;
                  case "joins" -> joins;
                  default -> invoke(tx, method, args);
                });
  }

  private static <T> T recordingView(
      Class<T> type, String name, T target, List<String> events, Runnable afterSessionLock) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (RECORDED_METHODS.contains(method.getName())) {
                events.add(
                    line(name + "." + method.getName(), args == null ? new Object[0] : args));
              }
              if (afterSessionLock != null && "lockForUpdate".equals(method.getName())) {
                afterSessionLock.run();
              }
              return result;
            }));
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** 把一次调用渲染为可断言的单行事件：{@code name(arg,...)}。 */
  private static String line(String name, Object... args) {
    List<String> rendered = new ArrayList<>(args.length);
    for (Object arg : args) {
      rendered.add(render(arg));
    }
    return name + "(" + String.join(",", rendered) + ")";
  }

  /** 事件渲染：UUID 收敛为测试 id 序号，List 按顺序展开，保证断言可读且确定性。 */
  private static String render(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof UUID uuid) {
      return uuid.getMostSignificantBits() == 0L
          ? Long.toString(uuid.getLeastSignificantBits())
          : uuid.toString();
    }
    if (value instanceof List<?> list) {
      List<String> rendered = new ArrayList<>(list.size());
      for (Object element : list) {
        rendered.add(render(element));
      }
      return "[" + String.join(",", rendered) + "]";
    }
    return String.valueOf(value);
  }
}
