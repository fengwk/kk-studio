package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 根唯一 YOLO 与直接 Follow 策略的创建面契约：子 Session 在树锁内派生真实执行根；独立 fork 创建新根并初始化自身开关；creation 幂等 hash
 * 只由稳定策略派生，不随根 toggle 变化。
 */
class HarnessRuntimeYoloPolicyTest {

  private static final String JOIN_HASH = "a".repeat(64);

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  /** 子 Session 创建在树锁内派生真实执行根：子与孙都直接 FOLLOW 根，不跟随中间节点，也不携带自身开关。 */
  @Test
  void childSessionCreationDerivesFollowToExecutionRoot() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);

    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            childSession(root.threadId(), TestIds.id(101), TestIds.id(102)),
            join(TestIds.id(1001), root.threadId(), root.rootEntryId()),
            AcceptancePreflight.IDENTITY);
    ThreadState childThread = thread(TestIds.id(102));
    assertEquals(ThreadYoloPolicy.follow(root.threadId()), childThread.yoloPolicy());
    assertFalse(childThread.yoloPolicy().isEnabled());

    runtime.acceptCommandsAndJoin(
        childSession(child.thread().id(), TestIds.id(103), TestIds.id(104)),
        join(TestIds.id(1002), child.thread().id(), child.thread().headEntryId()),
        AcceptancePreflight.IDENTITY);
    ThreadState grandChildThread = thread(TestIds.id(104));
    // 孙代直接 FOLLOW 执行根，而不是它的直接父（中间节点）。
    assertEquals(ThreadYoloPolicy.follow(root.threadId()), grandChildThread.yoloPolicy());
    assertNotEquals(ThreadYoloPolicy.follow(child.thread().id()), grandChildThread.yoloPolicy());
    assertEquals(childThread.id(), grandChildThread.parentThreadId());
  }

  /** 独立 fork（NEW_THREAD）创建新执行根并初始化自身开关：从子来源 fork 也不混入既有 FOLLOW 策略。 */
  @Test
  void independentForkCreatesRootWithItsOwnSwitch() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            childSession(root.threadId(), TestIds.id(201), TestIds.id(202)),
            join(TestIds.id(2001), root.threadId(), root.rootEntryId()),
            AcceptancePreflight.IDENTITY);
    ThreadState childThread = thread(TestIds.id(202));
    assertTrue(childThread.yoloPolicy().isFollow());

    // 复用子 Session 的 ROOT entry fork：新 Thread 无父、自带开关，绝不继承子线程的 FOLLOW。
    runtime.acceptCommands(
        fork(child.session().id(), child.rootEntry().id(), TestIds.id(203), true),
        AcceptancePreflight.IDENTITY);
    ThreadState enabledFork = thread(TestIds.id(203));
    assertEquals(ThreadYoloPolicy.root(true), enabledFork.yoloPolicy());
    assertFalse(enabledFork.yoloPolicy().isFollow());

    runtime.acceptCommands(
        fork(child.session().id(), child.rootEntry().id(), TestIds.id(204), false),
        AcceptancePreflight.IDENTITY);
    assertEquals(ThreadYoloPolicy.root(false), thread(TestIds.id(204)).yoloPolicy());
  }

  /** 创建幂等 hash 不冻结 effective 开关：根 toggle 后重放同一子创建仍命中 replay，子策略与 version 不变。 */
  @Test
  void childCreationReplaySurvivesRootToggle() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptCommandsCommand command = childSession(root.threadId(), TestIds.id(301), TestIds.id(302));
    ThreadJoinRequest ticket = join(TestIds.id(3001), root.threadId(), root.rootEntryId());

    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(command, ticket, AcceptancePreflight.IDENTITY);
    ThreadState created = thread(TestIds.id(302));
    String creationHash = created.creationRequestHash();
    long childVersion = created.version();

    long rootVersionBefore = thread(root.threadId()).version();
    runtime.setThreadYolo(new SetThreadYoloCommand(root.threadId(), true));
    assertEquals(rootVersionBefore + 1, thread(root.threadId()).version());

    AcceptedCommands replay =
        runtime.acceptCommandsAndJoin(command, ticket, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(first.thread().id(), replay.thread().id());
    ThreadState replayed = thread(TestIds.id(302));
    assertEquals(ThreadYoloPolicy.follow(root.threadId()), replayed.yoloPolicy());
    assertEquals(creationHash, replayed.creationRequestHash());
    assertEquals(childVersion, replayed.version());
  }

  /** 创建边界一致性校验：祖先链存在错根 policy（FOLLOW 指向无关树）时立即 fail closed，不在损坏树上创建新子代理。 */
  @Test
  void childCreationUnderMisrootedAncestorFailsClosed() {
    HarnessRuntimeTestSupport.Baseline rootA = HarnessRuntimeTestSupport.seedBaseline(store);
    HarnessRuntimeTestSupport.Baseline rootB = HarnessRuntimeTestSupport.seedBaseline(store);
    UUID parentThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  new ThreadState(
                      id,
                      rootA.sessionId(),
                      rootA.threadId(),
                      rootA.rootEntryId(),
                      HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                      "misrooted",
                      ThreadYoloPolicy.follow(rootB.threadId()),
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      T0,
                      T0));
              return id;
            });

    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                childSession(parentThreadId, TestIds.id(401), TestIds.id(402)),
                join(TestIds.id(4001), parentThreadId, rootA.rootEntryId()),
                AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(402)).isEmpty()));
    assertTrue(runtime.findJoin(TestIds.id(4001)).isEmpty());
  }

  /**
   * 坏存储读投影（fail-closed）：子 Session 创建在锁内读取的执行根返回 FOLLOW 策略（存储读不一致）。创建边界必须立即拒绝，且不创建子 Thread / Join /
   * Command。
   */
  @Test
  void childCreationRejectsFollowExecutionRootProjection() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);
    ThreadState realRoot = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    ThreadState projectedRoot = spy(realRoot);
    doReturn(ThreadYoloPolicy.follow(TestIds.id(900))).when(projectedRoot).yoloPolicy();
    HarnessRuntime projectedRuntime =
        HarnessRuntimeTestSupport.runtime(
            wrapRootReads(store, root.threadId(), projectedRoot), Clock.fixed(T0, ZoneOffset.UTC));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                projectedRuntime.acceptCommandsAndJoin(
                    childSession(root.threadId(), TestIds.id(501), TestIds.id(502)),
                    join(TestIds.id(5001), root.threadId(), root.rootEntryId()),
                    AcceptancePreflight.IDENTITY));

    assertEquals(
        "execution root " + root.threadId() + " must not follow another thread",
        failure.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(502)).isEmpty()));
    assertTrue(projectedRuntime.findJoin(TestIds.id(5001)).isEmpty());
    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(TestIds.id(502)).isEmpty()));
  }

  /**
   * 坏存储读投影注入：委托真实 store 与事务，执行根的 {@code findThread} 返回不一致投影；{@code lockThread} 先调用真实锁保留取锁
   * 语义再返回同一投影，其余读取与锁全部走真实委托。
   */
  private static HarnessStore wrapRootReads(
      InMemoryHarnessStore store, UUID rootThreadId, ThreadState projectedRoot) {
    return new HarnessStore() {
      @Override
      public <T> T transaction(Function<HarnessStore.Transaction, T> callback) {
        return store.transaction(
            realTx -> {
              HarnessStore.Transaction injected =
                  mock(HarnessStore.Transaction.class, delegatesTo(realTx));
              doReturn(Optional.of(projectedRoot)).when(injected).findThread(rootThreadId);
              doAnswer(ignored -> realTx.lockThread(rootThreadId).map(locked -> projectedRoot))
                  .when(injected)
                  .lockThread(rootThreadId);
              return callback.apply(injected);
            });
      }

      @Override
      public void afterCommit(Runnable action) {
        store.afterCommit(action);
      }

      @Override
      public void assertNoAmbientTransaction() {
        store.assertNoAmbientTransaction();
      }
    };
  }

  private static AcceptCommandsCommand childSession(
      UUID parentThreadId, UUID sessionId, UUID threadId) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewChildSession(sessionId, threadId, settings(), parentThreadId),
        List.of(userMessageCommand(TestIds.id(parentThreadId.hashCode()), "child work")));
  }

  private static ThreadJoinRequest join(UUID invocationId, UUID parentThreadId, UUID parentHead) {
    return new ThreadJoinRequest(
        invocationId, parentThreadId, parentHead, JOIN_HASH, "assistant", 3, 3, 2, 3);
  }

  private static AcceptCommandsCommand fork(
      UUID sessionId, UUID startEntryId, UUID threadId, boolean yoloEnabled) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewThread(
            sessionId, startEntryId, threadId, "fork-branch", yoloEnabled),
        List.of(userMessageCommand(TestIds.id(threadId.hashCode()), "fork work")));
  }

  private ThreadState thread(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }
}
