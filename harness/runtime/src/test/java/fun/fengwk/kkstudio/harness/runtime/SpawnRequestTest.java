package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SpawnRequest 准入门槛：身份、交付、额度、YOLO、命令批次、continuation cursor 与 budget 上限。 */
class SpawnRequestTest {

  private static final String HASH = "0123456789abcdef".repeat(4);
  private static final UUID THREAD = id(1);
  private static final UUID SESSION = id(2);
  private static final UUID PARENT = id(3);
  private static final UUID HEAD = id(4);
  private static final UUID TICKET = id(5);
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);

  @Test
  void acceptsNewRootChildSettleAndContinuationTickets() {
    // 新执行根：完整 session/thread、只留回执、可开 YOLO；所有非 USER 来源都允许。
    List<NewThreadCommand> commands = List.of(command(10, "root"));
    SpawnRequest root =
        request(
            SESSION,
            null,
            null,
            null,
            true,
            ThreadKind.CONTROLLER_TASK,
            JoinDelivery.RECEIPT_ONLY,
            new SpawnRequest.Budget(10, 4, 8, 16),
            commands,
            HASH);

    assertEquals(THREAD, root.threadId());
    assertEquals(SESSION, root.sessionId());
    assertEquals(SETTINGS, root.settings());
    assertEquals(commands, root.commands());
    assertNull(root.parentThreadId());
    assertNull(root.expectedParentHeadEntryId());
    assertNull(root.continuation());
    assertTrue(root.yoloEnabled());
    assertEquals(ThreadConstraints.NONE, root.constraints());
    assertEquals(ThreadKind.CONTROLLER_TASK, root.kind());
    assertEquals(JoinDelivery.RECEIPT_ONLY, root.delivery());
    assertEquals(10, root.budget().maxTurns());
    assertEquals(TICKET, root.ticketId());
    assertEquals(HASH, root.requestHash());
    assertEquals("agent", root.agent());

    for (ThreadKind trigger : ThreadKind.values()) {
      if (trigger == ThreadKind.USER) {
        continue;
      }
      SpawnRequest ticket =
          request(
              SESSION,
              null,
              null,
              null,
              false,
              trigger,
              JoinDelivery.RECEIPT_ONLY,
              SpawnRequest.Budget.unlimited(),
              List.of(command(11, "trigger")),
              HASH);
      assertEquals(trigger, ticket.kind());
    }

    // 子 Thread：通知父并冻结父 head。
    SpawnRequest child =
        request(
            SESSION,
            null,
            PARENT,
            HEAD,
            false,
            ThreadKind.SUBAGENT,
            JoinDelivery.NOTIFY_PARENT,
            new SpawnRequest.Budget(5, 3, 2, 4),
            List.of(command(12, "child")),
            HASH);
    assertEquals(PARENT, child.parentThreadId());
    assertEquals(HEAD, child.expectedParentHeadEntryId());

    // 压缩类票据：结算 owner turn，不携带父 head。
    SpawnRequest settle =
        request(
            SESSION,
            null,
            PARENT,
            null,
            false,
            ThreadKind.COMPACTION,
            JoinDelivery.SETTLE_OWNER_TURN,
            SpawnRequest.Budget.unlimited(),
            List.of(command(13, "compact")),
            HASH);
    assertEquals(JoinDelivery.SETTLE_OWNER_TURN, settle.delivery());

    // 续用既有 Thread：sessionId 必须为空，continuation 给出精确 cursor。
    SpawnRequest continued =
        request(
            null,
            new SpawnRequest.Continuation(HEAD, 1),
            PARENT,
            HEAD,
            false,
            ThreadKind.PLUGIN_TASK,
            JoinDelivery.NOTIFY_PARENT,
            SpawnRequest.Budget.unlimited(),
            List.of(command(14, "more")),
            HASH);
    assertEquals(HEAD, continued.continuation().expectedHeadEntryId());
    assertEquals(1, continued.continuation().expectedNextCommandSequence());
  }

  @Test
  void rejectsUserTrigger() {
    // 用户创建的 Thread 只能走 acceptCommands；spawn 不接受 USER 来源。
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(
                    SESSION,
                    null,
                    null,
                    null,
                    false,
                    ThreadKind.USER,
                    JoinDelivery.RECEIPT_ONLY,
                    SpawnRequest.Budget.unlimited(),
                    List.of(command(20, "user")),
                    HASH));
    assertEquals("user-created threads are accepted via acceptCommands", error.getMessage());
  }

  @Test
  void rejectsMissingOrMalformedRequestHash() {
    // 票据指纹必须是 64 位小写 SHA-256 hex。
    for (String hash :
        new String[] {null, "A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              request(
                  SESSION,
                  null,
                  null,
                  null,
                  false,
                  ThreadKind.CONTROLLER_TASK,
                  JoinDelivery.RECEIPT_ONLY,
                  SpawnRequest.Budget.unlimited(),
                  List.of(command(21, "hash")),
                  hash));
    }
  }

  @Test
  void requiresExactlyOneOfSessionAndContinuation() {
    // 新 Thread 必须给 sessionId，续用 Thread 必须没有 sessionId 而给出 continuation。
    assertThrows(
        IllegalArgumentException.class,
        () -> newRootRequest(null, null, List.of(command(30, "missing-session"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            newRootRequest(
                SESSION,
                new SpawnRequest.Continuation(HEAD, 1),
                List.of(command(31, "ambiguous"))));
  }

  @Test
  void enforcesDeliveryParentAndHeadConsistency() {
    // RECEIPT_ONLY 只能无父；两种子任务交付与 SETTLE_OWNER_TURN 都必须有父。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                null,
                false,
                ThreadKind.CONTROLLER_TASK,
                JoinDelivery.RECEIPT_ONLY,
                SpawnRequest.Budget.unlimited(),
                List.of(command(40, "receipt-with-parent")),
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                null,
                false,
                ThreadKind.COMPACTION,
                JoinDelivery.SETTLE_OWNER_TURN,
                SpawnRequest.Budget.unlimited(),
                List.of(command(41, "settle-without-parent")),
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                null,
                false,
                ThreadKind.SUBAGENT,
                JoinDelivery.NOTIFY_PARENT,
                SpawnRequest.Budget.unlimited(),
                List.of(command(42, "notify-without-parent")),
                HASH));

    // 两种子任务交付都必须携带父 head；owner 结算与 root receipt 不携带。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                null,
                false,
                ThreadKind.SUBAGENT,
                JoinDelivery.NOTIFY_PARENT,
                SpawnRequest.Budget.unlimited(),
                List.of(command(43, "notify-without-head")),
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                null,
                false,
                ThreadKind.SUBAGENT,
                JoinDelivery.SETTLE_TOOL_RESULT,
                SpawnRequest.Budget.unlimited(),
                List.of(command(46, "sync-without-head")),
                HASH));
    SpawnRequest synchronous =
        request(
            SESSION,
            null,
            PARENT,
            HEAD,
            false,
            ThreadKind.SUBAGENT,
            JoinDelivery.SETTLE_TOOL_RESULT,
            SpawnRequest.Budget.unlimited(),
            List.of(command(47, "sync-with-head")),
            HASH);
    assertEquals(HEAD, synchronous.expectedParentHeadEntryId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                HEAD,
                false,
                ThreadKind.COMPACTION,
                JoinDelivery.SETTLE_OWNER_TURN,
                SpawnRequest.Budget.unlimited(),
                List.of(command(44, "settle-with-head")),
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                HEAD,
                false,
                ThreadKind.CONTROLLER_TASK,
                JoinDelivery.RECEIPT_ONLY,
                SpawnRequest.Budget.unlimited(),
                List.of(command(45, "receipt-with-head")),
                HASH));
  }

  @Test
  void allowsYoloOnlyForNewExecutionRoot() {
    // 子 Thread 与 continuation 沿用既有执行树策略，不能重新打开 YOLO。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                HEAD,
                true,
                ThreadKind.SUBAGENT,
                JoinDelivery.NOTIFY_PARENT,
                SpawnRequest.Budget.unlimited(),
                List.of(command(60, "child-yolo")),
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                null,
                new SpawnRequest.Continuation(HEAD, 1),
                null,
                null,
                true,
                ThreadKind.CONTROLLER_TASK,
                JoinDelivery.RECEIPT_ONLY,
                SpawnRequest.Budget.unlimited(),
                List.of(command(61, "continued-yolo")),
                HASH));
  }

  @Test
  void requiresNonEmptyBatchWithUniqueIdempotencyKeys() {
    // 首批命令必须非空，且同一请求内 idempotencyKey 不能重复。
    assertThrows(
        NullPointerException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                null,
                false,
                ThreadKind.CONTROLLER_TASK,
                JoinDelivery.RECEIPT_ONLY,
                SpawnRequest.Budget.unlimited(),
                null,
                HASH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                null,
                false,
                ThreadKind.CONTROLLER_TASK,
                JoinDelivery.RECEIPT_ONLY,
                SpawnRequest.Budget.unlimited(),
                List.of(),
                HASH));
    NewThreadCommand duplicate = command(70, "same-key");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(
                    SESSION,
                    null,
                    null,
                    null,
                    false,
                    ThreadKind.CONTROLLER_TASK,
                    JoinDelivery.RECEIPT_ONLY,
                    SpawnRequest.Budget.unlimited(),
                    List.of(duplicate, duplicate),
                    HASH));
    assertTrue(error.getMessage().contains("duplicate idempotencyKey"));
  }

  @Test
  void copiesCommandBatchDefensively() {
    // 请求冻结构造时的命令批次：调用方后续修改或直接改返回值都不能影响票据。
    List<NewThreadCommand> commands =
        new ArrayList<>(List.of(command(80, "one"), command(81, "two")));
    SpawnRequest request =
        request(
            SESSION,
            null,
            null,
            null,
            false,
            ThreadKind.CONTROLLER_TASK,
            JoinDelivery.RECEIPT_ONLY,
            SpawnRequest.Budget.unlimited(),
            commands,
            HASH);

    commands.add(command(82, "three"));

    assertEquals(2, request.commands().size());
    assertThrows(
        UnsupportedOperationException.class, () -> request.commands().add(command(83, "four")));
  }

  @Test
  void continuationRequiresHeadAndPositiveNextSequence() {
    // 续用 cursor 必须带 head，下一条 command sequence 从 1 开始。
    assertThrows(NullPointerException.class, () -> new SpawnRequest.Continuation(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Continuation(HEAD, 0));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Continuation(HEAD, -1));

    SpawnRequest.Continuation continuation = new SpawnRequest.Continuation(HEAD, 7);
    assertEquals(HEAD, continuation.expectedHeadEntryId());
    assertEquals(7, continuation.expectedNextCommandSequence());
  }

  @Test
  void budgetRejectsNonPositiveMaxTurnsAndAdmissionLimits() {
    // maxTurns 可空但不可 <=0；深度、同父子任务数与全局子任务数都必须 >=1。
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(-1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, -1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, 1, -1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new SpawnRequest.Budget(null, 1, 1, -1));
  }

  @Test
  void budgetKeepsNormalLimitsAndRepresentsUnlimited() {
    // 正常票据的上限原样保留；unlimited 表示不设软 turn 预算与任何准入上限。
    SpawnRequest.Budget budget = new SpawnRequest.Budget(10, 3, 2, 5);
    assertEquals(10, budget.maxTurns());
    assertEquals(3, budget.maxDepth());
    assertEquals(2, budget.maxConcurrentChildren());
    assertEquals(5, budget.maxConcurrentThreads());

    SpawnRequest.Budget unlimited = SpawnRequest.Budget.unlimited();
    assertNull(unlimited.maxTurns());
    assertEquals(Integer.MAX_VALUE, unlimited.maxDepth());
    assertEquals(Integer.MAX_VALUE, unlimited.maxConcurrentChildren());
    assertEquals(Integer.MAX_VALUE, unlimited.maxConcurrentThreads());
  }

  private static SpawnRequest newRootRequest(
      UUID sessionId, SpawnRequest.Continuation continuation, List<NewThreadCommand> commands) {
    return request(
        sessionId,
        continuation,
        null,
        null,
        false,
        ThreadKind.CONTROLLER_TASK,
        JoinDelivery.RECEIPT_ONLY,
        SpawnRequest.Budget.unlimited(),
        commands,
        HASH);
  }

  private static SpawnRequest request(
      UUID sessionId,
      SpawnRequest.Continuation continuation,
      UUID parentThreadId,
      UUID expectedParentHeadEntryId,
      boolean yoloEnabled,
      ThreadKind trigger,
      JoinDelivery delivery,
      SpawnRequest.Budget budget,
      List<NewThreadCommand> commands,
      String requestHash) {
    return new SpawnRequest(
        THREAD,
        sessionId,
        SETTINGS,
        commands,
        parentThreadId,
        expectedParentHeadEntryId,
        continuation,
        yoloEnabled,
        ThreadConstraints.NONE,
        trigger,
        delivery,
        budget,
        TICKET,
        requestHash);
  }

  private static NewThreadCommand command(long key, String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(AgentMessage.user(text)), id(key), HASH);
  }
}
