package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.COMPACTION_CONFIG;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.RESOLVE_FAILURE_DELAY;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.work;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** ThreadProcessor 的树内锁边界：Join 交付跨 Thread，但只涉及目标 Thread 与不可变的直接父，绝不回溯整条祖先链。 */
class ThreadProcessorParentLockTest extends ThreadProcessorTestBase {

  @Test
  void terminalJoinDeliveryLocksSelfAndDirectParentOnlyInUuidOrder() {
    // 测试意图：深度 3 的实际 processor 终态 Join 交付只锁 self + 直接父（Session/Thread 均按 UUID 升序），
    // 不锁 grandparent 或 sibling，且父通知/Work 成功而更远祖先 head/version 不变。
    var fixture = fixture();
    UUID rootSession = TestIds.id(100);
    UUID parentSession = TestIds.id(101);
    UUID childSession = TestIds.id(102);
    UUID rootId = TestIds.id(203);
    UUID parentId = TestIds.id(202);
    UUID childId = TestIds.id(201);
    UUID siblingId = TestIds.id(200);

    UUID rootHead = seedRootSession(fixture.store, rootSession);
    insertThread(fixture.store, rootId, rootSession, null, rootHead, rootId, SpawnTrigger.USER);
    UUID parentHead = seedRootSession(fixture.store, parentSession);
    insertThread(
        fixture.store,
        parentId,
        parentSession,
        rootId,
        parentHead,
        rootId,
        SpawnTrigger.MODEL_TOOL);
    UUID childHead = seedRootSession(fixture.store, childSession);
    insertThread(
        fixture.store, childId, childSession, parentId, childHead, rootId, SpawnTrigger.MODEL_TOOL);
    insertThread(
        fixture.store,
        siblingId,
        childSession,
        parentId,
        childHead,
        rootId,
        SpawnTrigger.MODEL_TOOL);

    UUID sourceId =
        ThreadProcessorTestSupport.seedCommand(
            fixture.store, childId, new UserMessageCommandPayload(userMessage("delegated-task")));
    UUID joinId = TestIds.id(500);
    insertJoin(fixture.store, joinId, parentId, childId, 1L);
    ThreadProcessorTestSupport.requestThreadWork(fixture.store, childId);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));
    ThreadState rootBefore = thread(fixture.store, rootId);
    long parentVersionBefore = thread(fixture.store, parentId).version();

    ToolProcessorTestSupport.RecordingLockStore recording =
        new ToolProcessorTestSupport.RecordingLockStore(fixture.store);
    ThreadProcessor processor =
        new ThreadProcessor(
            recording,
            HarnessRuntimeTestSupport.driver(fixture.store, fixture.resolver),
            new ThreadProcessorConfig(RESOLVE_FAILURE_DELAY, () -> COMPACTION_CONFIG));

    assertEquals(
        ThreadProcessResult.COMPLETED,
        processor.process(claimThreadWork(fixture.store, childId, fixture.clock.instant())));

    List<List<String>> locked = recording.threadLockTransactions();
    assertEquals(1, locked.size(), "single rejection transaction locks the thread rows");
    List<String> ops = locked.getFirst();
    assertEquals("lockTree:" + rootId, ops.get(0));
    assertFalse(
        ops.stream().anyMatch(op -> op.startsWith("findAncestorChain")),
        "processor must not walk the ancestor chain: " + ops);
    assertEquals(
        List.of(
            "lockTree:" + rootId,
            "lockSessionForKeyShare:" + parentSession,
            "lockSessionForKeyShare:" + childSession,
            "lockThread:" + childId,
            "lockThread:" + parentId),
        ops.stream()
            .filter(
                op ->
                    op.startsWith("lockTree:")
                        || op.startsWith("lockSessionForKeyShare:")
                        || op.startsWith("lockThread:"))
            .toList());

    ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());
    assertEquals(
        ThreadCommandState.APPLIED,
        ThreadProcessorTestSupport.command(fixture.store, childId, sourceId).state());

    List<ThreadCommand> parentCommands =
        fixture.store.transaction(tx -> loadCommandsByThread(tx, parentId));
    assertEquals(1, parentCommands.size());
    assertTrue(parentCommands.getFirst().payload() instanceof NotificationCommandPayload);
    assertEquals(ThreadCommandState.QUEUED, parentCommands.getFirst().state());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, parentId)),
        "runnable direct parent must be woken for the delivered notification");

    ThreadState rootAfter = thread(fixture.store, rootId);
    assertEquals(rootBefore.headEntryId(), rootAfter.headEntryId());
    assertEquals(rootBefore.version(), rootAfter.version());
    ThreadState parentAfter = thread(fixture.store, parentId);
    assertEquals(parentVersionBefore + 1, parentAfter.version());
    assertEquals(parentHead, parentAfter.headEntryId());
  }

  private static UUID seedRootSession(InMemoryBuiltinHarnessStore store, UUID sessionId) {
    return store.transaction(
        tx -> {
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          return rootEntryId;
        });
  }

  private static void insertThread(
      InMemoryBuiltinHarnessStore store,
      UUID threadId,
      UUID sessionId,
      UUID parentThreadId,
      UUID headEntryId,
      UUID rootThreadId,
      SpawnTrigger spawnTrigger) {
    store.transaction(
        tx -> {
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  CREATION_REQUEST_HASH,
                  "thread-" + threadId,
                  rootThreadId,
                  false,
                  spawnTrigger,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  NOW,
                  NOW));
          return null;
        });
  }

  private static void insertJoin(
      InMemoryBuiltinHarnessStore store,
      UUID joinId,
      UUID parentId,
      UUID childId,
      long sourceSequence) {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  sourceSequence,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  JoinDelivery.NOTIFY_PARENT,
                  null));
          return null;
        });
  }
}
