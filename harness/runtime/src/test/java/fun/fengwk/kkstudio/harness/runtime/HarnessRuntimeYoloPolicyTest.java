package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.acceptAndJoin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.Child;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.Ticket;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 根唯一 YOLO 与子代理继承执行根的创建面契约：子 Session 在树锁内派生真实执行根；独立 fork 创建新根并初始化自身开关；creation 幂等 hash 只由稳定策略派生，不随根
 * toggle 变化。
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

  /** 子 Session 创建在树锁内派生真实执行根：子与孙都直接 执行根 根，不跟随中间节点，也不携带自身开关。 */
  @Test
  void childSessionCreationInheritsTheExecutionRoot() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);

    AcceptedCommands child =
        acceptAndJoin(
            runtime,
            childSession(root.threadId(), TestIds.id(101), TestIds.id(102)),
            join(TestIds.id(1001), root.threadId(), root.rootEntryId()),
            AcceptancePreflight.IDENTITY);
    ThreadState childThread = thread(TestIds.id(102));
    assertEquals(root.threadId(), childThread.rootThreadId());
    assertFalse(childThread.yoloEnabled());

    acceptAndJoin(
        runtime,
        childSession(child.thread().id(), TestIds.id(103), TestIds.id(104)),
        join(TestIds.id(1002), child.thread().id(), child.thread().headEntryId()),
        AcceptancePreflight.IDENTITY);
    ThreadState grandChildThread = thread(TestIds.id(104));
    // 孙代记录执行根，而不是它的直接父（中间节点）。
    assertEquals(root.threadId(), grandChildThread.rootThreadId());
    assertEquals(childThread.id(), grandChildThread.parentThreadId());
  }

  /** 独立 fork（NEW_THREAD）创建新执行根并初始化自身开关：从子来源 fork 也不混入既有 执行根 策略。 */
  @Test
  void independentForkCreatesRootWithItsOwnSwitch() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptedCommands child =
        acceptAndJoin(
            runtime,
            childSession(root.threadId(), TestIds.id(201), TestIds.id(202)),
            join(TestIds.id(2001), root.threadId(), root.rootEntryId()),
            AcceptancePreflight.IDENTITY);
    ThreadState childThread = thread(TestIds.id(202));
    assertEquals(root.threadId(), childThread.rootThreadId());

    // 复用子 Session 的 ROOT entry fork：新 Thread 无父、自带开关，绝不继承子线程的 执行根。
    runtime.acceptCommands(
        fork(child.session().id(), child.rootEntry().id(), TestIds.id(203), true),
        AcceptancePreflight.IDENTITY);
    ThreadState enabledFork = thread(TestIds.id(203));
    assertTrue(enabledFork.yoloEnabled());
    assertEquals(enabledFork.id(), enabledFork.rootThreadId());

    runtime.acceptCommands(
        fork(child.session().id(), child.rootEntry().id(), TestIds.id(204), false),
        AcceptancePreflight.IDENTITY);
    assertFalse(thread(TestIds.id(204)).yoloEnabled());
  }

  /** 创建幂等 hash 不冻结 effective 开关：根 toggle 后重放同一子创建仍命中 replay，子策略与 version 不变。 */
  @Test
  void childCreationReplaySurvivesRootToggle() {
    HarnessRuntimeTestSupport.Baseline root = HarnessRuntimeTestSupport.seedBaseline(store);
    Child command = childSession(root.threadId(), TestIds.id(301), TestIds.id(302));
    Ticket ticket = join(TestIds.id(3001), root.threadId(), root.rootEntryId());

    AcceptedCommands first = acceptAndJoin(runtime, command, ticket, AcceptancePreflight.IDENTITY);
    ThreadState created = thread(TestIds.id(302));
    String creationHash = created.creationRequestHash();
    long childVersion = created.version();

    long rootVersionBefore = thread(root.threadId()).version();
    runtime.setThreadYolo(new SetThreadYoloCommand(root.threadId(), true));
    assertEquals(rootVersionBefore + 1, thread(root.threadId()).version());

    AcceptedCommands replay = acceptAndJoin(runtime, command, ticket, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(first.thread().id(), replay.thread().id());
    ThreadState replayed = thread(TestIds.id(302));
    assertEquals(root.threadId(), replayed.rootThreadId());
    assertEquals(creationHash, replayed.creationRequestHash());
    assertEquals(childVersion, replayed.version());
  }

  private static Child childSession(UUID parentThreadId, UUID sessionId, UUID threadId) {
    return new Child(
        sessionId,
        threadId,
        parentThreadId,
        List.of(userMessageCommand(TestIds.id(parentThreadId.hashCode()), "child work")));
  }

  private static Ticket join(UUID invocationId, UUID parentThreadId, UUID parentHead) {
    return Ticket.task(
        invocationId, parentThreadId, parentHead, JOIN_HASH, "assistant", 3, 3, 2, 3);
  }

  private static AcceptCommandsCommand fork(
      UUID sessionId, UUID startEntryId, UUID threadId, boolean yoloEnabled) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewThread(
            sessionId, startEntryId, threadId, "fork-" + threadId, yoloEnabled),
        List.of(userMessageCommand(TestIds.id(threadId.hashCode()), "fork work")));
  }

  private ThreadState thread(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }
}
