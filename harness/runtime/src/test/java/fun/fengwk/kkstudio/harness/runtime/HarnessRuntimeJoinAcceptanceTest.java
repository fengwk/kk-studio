package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class HarnessRuntimeJoinAcceptanceTest {
  private static final String HASH = "a".repeat(64);
  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand session(int session, int thread, UUID parent) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            TestIds.id(session), TestIds.id(thread), settings(), parent, false),
        List.of(userMessageCommand(TestIds.id(session + 1000), "prompt")));
  }

  private static ThreadJoinRequest request(int invocation, UUID parent, UUID head) {
    return new ThreadJoinRequest(
        TestIds.id(invocation), parent, head, HASH, "assistant", 3, 3, 2, 3);
  }

  @Test
  void rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays() {
    // 测试意图：one-shot 从首条源命令起存在 durable receipt，重放不重复插入。
    AcceptCommandsCommand source = session(10, 11, null);
    ThreadJoinRequest ticket = request(12, null, null);
    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(source, ticket, AcceptancePreflight.IDENTITY);
    ThreadJoin join = runtime.findJoin(ticket.invocationId()).orElseThrow();
    assertEquals(first.thread().version(), join.afterVersion());
    assertEquals(first.acceptedCommands().getLast().sequence(), join.sourceCommandSequence());
    assertEquals(ThreadLifecycleStatus.ACTIVE, first.thread().status());
    assertFalse(join.matched());
    assertTrue(
        runtime.acceptCommandsAndJoin(source, ticket, AcceptancePreflight.IDENTITY).replayed());
    assertEquals(join, runtime.findJoin(ticket.invocationId()).orElseThrow());
  }

  @Test
  void parentAndNestedChildArePermanentAndJoinFailureRollsBackEverything() {
    // 测试意图：建立不可变三层执行树，preflight 失败时子 session/command/join 一并回滚。
    AcceptedCommands root =
        runtime.acceptCommands(session(20, 21, null), AcceptancePreflight.IDENTITY);
    ThreadJoinRequest firstJoin = request(22, root.thread().id(), root.thread().headEntryId());
    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(
            session(23, 24, root.thread().id()), firstJoin, AcceptancePreflight.IDENTITY);
    ThreadJoinRequest secondJoin = request(25, first.thread().id(), first.thread().headEntryId());
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(26, 27, first.thread().id()),
                secondJoin,
                (tx, current, commands) -> {
                  throw new IllegalStateException("reject");
                }));
    assertTrue(runtime.findJoin(secondJoin.invocationId()).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(27)).isEmpty()));
    assertEquals(
        List.of(first.thread().id(), root.thread().id()),
        runtime.findAncestorChain(first.thread().id()));
    AcceptedCommands second =
        runtime.acceptCommandsAndJoin(
            session(26, 27, first.thread().id()), secondJoin, AcceptancePreflight.IDENTITY);
    assertEquals(
        List.of(second.thread().id(), first.thread().id(), root.thread().id()),
        runtime.findAncestorChain(second.thread().id()));
  }

  @Test
  void quotaAndInvocationReuseRejectWithoutOrphanSource() {
    // 测试意图：同一父级未匹配额度拒绝第二子；invocation ID 重用不得写入孤儿源 command。
    AcceptedCommands root =
        runtime.acceptCommands(session(30, 31, null), AcceptancePreflight.IDENTITY);
    UUID parent = root.thread().id();
    runtime.acceptCommandsAndJoin(
        session(32, 33, parent),
        request(34, parent, root.thread().headEntryId()),
        AcceptancePreflight.IDENTITY);
    ThreadJoinRequest limited =
        new ThreadJoinRequest(
            TestIds.id(35), parent, root.thread().headEntryId(), HASH, "assistant", 3, 3, 1, 3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(36, 37, parent), limited, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(37)).isEmpty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(38, 39, parent),
                request(34, parent, root.thread().headEntryId()),
                AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(39)).isEmpty()));
  }
}
