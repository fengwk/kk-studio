package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** renameSession / renameThread / getSession 的直接控制面语义（InMemory 之上）。 */
class HarnessRenameTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand newSession(
      UUID sessionId, UUID threadId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            sessionId, threadId, HarnessRuntimeTestSupport.settings(), null, false),
        commands);
  }

  private static NewThreadCommand userText(String text) {
    return HarnessRuntimeTestSupport.userMessageCommand(UUID.randomUUID(), text);
  }

  @Test
  void renameSessionReplacesNameAndKeepsIdentityAndGetSessionSeesIt() {
    // 直接控制面：重命名只替换 name；createdAt 与 id 不变；getSession 返回当前投影。
    UUID sessionId = TestIds.id(101);
    UUID threadId = TestIds.id(102);
    Session created =
        runtime
            .acceptCommands(
                newSession(sessionId, threadId, List.of(userText("hello"))),
                AcceptancePreflight.IDENTITY)
            .session();
    assertEquals("hello", created.name());

    Session renamed = runtime.renameSession(new RenameSessionCommand(sessionId, "  new  name  "));
    assertEquals("new name", renamed.name());
    assertEquals(created.id(), renamed.id());
    assertEquals(created.createdAt(), renamed.createdAt());
    assertEquals(renamed, runtime.getSession(sessionId));
    // Thread 名不受 Session rename 影响。
    assertEquals("main", runtime.listThreadsBySession(sessionId).getFirst().name());
  }

  @Test
  void renameSessionSameNormalizedNameIsNoOp() {
    // 同名（规范化后相同）重命名必须 no-op：不改变任何字段（version-less Session 按对象全等判断）。
    UUID sessionId = TestIds.id(103);
    runtime.acceptCommands(
        newSession(sessionId, TestIds.id(104), List.of(userText("hello"))),
        AcceptancePreflight.IDENTITY);
    Session current = runtime.getSession(sessionId);
    Session again = runtime.renameSession(new RenameSessionCommand(sessionId, " hello "));
    assertEquals(current, again);
  }

  @Test
  void renameSessionMissingSessionThrowsNotFound() {
    // 重命名不存在的 Session 抛 NotFound，不落任何行。
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.renameSession(new RenameSessionCommand(TestIds.id(999), "x")));
    // getSession 查询门面：不存在的 Session 同样抛 NotFound（不暴露 Optional）。
    assertThrows(HarnessRuntimeNotFoundException.class, () -> runtime.getSession(TestIds.id(999)));
  }

  @Test
  void renameThreadBumpsVersionByOneUpdatesUpdatedAtAndPreservesOtherFields() {
    // Thread rename 复用 update/notify 路径：version 精确 +1、updatedAt 推进，其余字段不变。
    HarnessRuntimeTestSupport.TestClock clock = new HarnessRuntimeTestSupport.TestClock(T0);
    runtime = HarnessRuntimeTestSupport.runtime(store, clock);
    Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    ThreadState before = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals("main", before.name());
    Instant beforeUpdatedAt = before.updatedAt();
    clock.advance(T0.plusMillis(5));

    ThreadState renamed =
        runtime.renameThread(new RenameThreadCommand(baseline.threadId(), " new name "));
    assertEquals("new name", renamed.name());
    assertEquals(before.version() + 1L, renamed.version());
    assertTrue(renamed.updatedAt().isAfter(beforeUpdatedAt));
    assertEquals(before.id(), renamed.id());
    assertEquals(before.sessionId(), renamed.sessionId());
    assertEquals(before.headEntryId(), renamed.headEntryId());
    assertEquals(before.creationRequestHash(), renamed.creationRequestHash());
    assertEquals(before.nextCommandSequence(), renamed.nextCommandSequence());
    assertEquals(before.createdAt(), renamed.createdAt());

    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(renamed, stored);
    // Thread rename 是 isolated metadata mutation：Command / Work / Entry tree 全部保持 rename 前状态。
    assertTrue(store.transaction(tx -> tx.loadCommandsByThread(baseline.threadId())).isEmpty());
    assertTrue(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, TestIds.id(1))))
            .isEmpty());
    List<Entry> entries = store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId()));
    assertEquals(1, entries.size());
    assertEquals(baseline.rootEntryId(), entries.getFirst().id());
    List<ThreadState> threads =
        store.transaction(tx -> tx.listThreadsBySession(baseline.sessionId()));
    assertEquals(1, threads.size());
    assertEquals(baseline.threadId(), threads.getFirst().id());
    assertEquals("new name", threads.getFirst().name());
  }

  @Test
  void renameThreadSameNameIsNoOpWithoutTouchingVersion() {
    // 同名（规范化后相同）重命名必须 no-op：version / updatedAt 一律不动。
    Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    ThreadState before = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    ThreadState again = runtime.renameThread(new RenameThreadCommand(baseline.threadId(), "main"));
    assertEquals(before, again);
    assertEquals(before.version(), again.version());
    assertEquals(before.updatedAt(), again.updatedAt());
  }

  @Test
  void renameThreadMissingThreadThrowsNotFound() {
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.renameThread(new RenameThreadCommand(TestIds.id(999), "x")));
  }

  @Test
  void renameCommandsNormalizeButRejectBlankAndOverlongNames() {
    // 命令构造即规范化：空白拒绝；超过 256 码点抛 IllegalArgumentException（绝不截断）。
    assertThrows(
        IllegalArgumentException.class, () -> new RenameSessionCommand(TestIds.id(1), "   "));
    assertThrows(
        IllegalArgumentException.class, () -> new RenameThreadCommand(TestIds.id(1), "\t\n"));
    RenameSessionCommand padded = new RenameSessionCommand(TestIds.id(1), " a\t b ");
    assertEquals("a b", padded.name());
    String overLong = "长".repeat(300);
    assertThrows(
        IllegalArgumentException.class, () -> new RenameThreadCommand(TestIds.id(1), overLong));
    assertThrows(
        IllegalArgumentException.class, () -> new RenameSessionCommand(TestIds.id(1), overLong));
    // 恰好 256 码点可接受（与 Session/Thread 构造器同一共享工具）。
    String exactly256 = "长".repeat(256);
    assertEquals(exactly256, new RenameThreadCommand(TestIds.id(1), exactly256).name());
  }
}
