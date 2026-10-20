package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

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
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);

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
            SpawnTrigger.CONTROLLER,
            JoinDelivery.RECEIPT_ONLY,
            false,
            new SpawnRequest.Budget(10, 4, 8, 16),
            commands,
            HASH);

    assertEquals(THREAD, root.threadId());
    assertEquals(SESSION, root.sessionId());
    assertEquals(SETTINGS, root.settings());
    assertEquals(commands, root.commands());
    assertEquals(AcceptancePreflight.IDENTITY, root.preflight());
    assertNull(root.parentThreadId());
    assertNull(root.expectedParentHeadEntryId());
    assertNull(root.continuation());
    assertTrue(root.yoloEnabled());
    assertEquals(ThreadConstraints.NONE, root.constraints());
    assertEquals(SpawnTrigger.CONTROLLER, root.trigger());
    assertEquals(JoinDelivery.RECEIPT_ONLY, root.delivery());
    assertFalse(root.countsTowardQuota());
    assertEquals(10, root.budget().maxTurns());
    assertEquals(TICKET, root.ticketId());
    assertEquals(HASH, root.requestHash());
    assertEquals("agent", root.agent());

    for (SpawnTrigger trigger : SpawnTrigger.values()) {
      if (trigger == SpawnTrigger.USER) {
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
              false,
              SpawnRequest.Budget.unlimited(),
              List.of(command(11, "trigger")),
              HASH);
      assertEquals(trigger, ticket.trigger());
    }

    // 子 Thread：通知父并冻结父 head，同时计入额度。
    SpawnRequest child =
        request(
            SESSION,
            null,
            PARENT,
            HEAD,
            false,
            SpawnTrigger.MODEL_TOOL,
            JoinDelivery.NOTIFY_PARENT,
            true,
            new SpawnRequest.Budget(5, 3, 2, 4),
            List.of(command(12, "child")),
            HASH);
    assertEquals(PARENT, child.parentThreadId());
    assertEquals(HEAD, child.expectedParentHeadEntryId());
    assertTrue(child.countsTowardQuota());

    // 压缩类票据：结算 owner turn，不计额度，因此不携带父 head。
    SpawnRequest settle =
        request(
            SESSION,
            null,
            PARENT,
            null,
            false,
            SpawnTrigger.RUNTIME,
            JoinDelivery.SETTLE_OWNER_TURN,
            false,
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
            SpawnTrigger.PLUGIN,
            JoinDelivery.NOTIFY_PARENT,
            false,
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
                    SpawnTrigger.USER,
                    JoinDelivery.RECEIPT_ONLY,
                    false,
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
                  SpawnTrigger.CONTROLLER,
                  JoinDelivery.RECEIPT_ONLY,
                  false,
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
    // RECEIPT_ONLY 只能无父；NOTIFY_PARENT 与 SETTLE_OWNER_TURN 必须有父。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                null,
                false,
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                false,
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
                SpawnTrigger.RUNTIME,
                JoinDelivery.SETTLE_OWNER_TURN,
                false,
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
                SpawnTrigger.MODEL_TOOL,
                JoinDelivery.NOTIFY_PARENT,
                false,
                SpawnRequest.Budget.unlimited(),
                List.of(command(42, "notify-without-parent")),
                HASH));

    // 只有 NOTIFY_PARENT 携带父 head。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                PARENT,
                null,
                false,
                SpawnTrigger.MODEL_TOOL,
                JoinDelivery.NOTIFY_PARENT,
                false,
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
                HEAD,
                false,
                SpawnTrigger.RUNTIME,
                JoinDelivery.SETTLE_OWNER_TURN,
                false,
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
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                false,
                SpawnRequest.Budget.unlimited(),
                List.of(command(45, "receipt-with-head")),
                HASH));
  }

  @Test
  void rejectsQuotaWithoutParent() {
    // 额度统计的是父 Thread 的未完成子任务，无父票据不能计额。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                SESSION,
                null,
                null,
                null,
                false,
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                true,
                SpawnRequest.Budget.unlimited(),
                List.of(command(50, "quota-without-parent")),
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
                SpawnTrigger.MODEL_TOOL,
                JoinDelivery.NOTIFY_PARENT,
                false,
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
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                false,
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
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                false,
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
                SpawnTrigger.CONTROLLER,
                JoinDelivery.RECEIPT_ONLY,
                false,
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
                    SpawnTrigger.CONTROLLER,
                    JoinDelivery.RECEIPT_ONLY,
                    false,
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
            SpawnTrigger.CONTROLLER,
            JoinDelivery.RECEIPT_ONLY,
            false,
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
        SpawnTrigger.CONTROLLER,
        JoinDelivery.RECEIPT_ONLY,
        false,
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
      SpawnTrigger trigger,
      JoinDelivery delivery,
      boolean countsTowardQuota,
      SpawnRequest.Budget budget,
      List<NewThreadCommand> commands,
      String requestHash) {
    return new SpawnRequest(
        THREAD,
        sessionId,
        SETTINGS,
        commands,
        AcceptancePreflight.IDENTITY,
        parentThreadId,
        expectedParentHeadEntryId,
        continuation,
        yoloEnabled,
        ThreadConstraints.NONE,
        trigger,
        delivery,
        countsTowardQuota,
        budget,
        TICKET,
        requestHash);
  }

  private static NewThreadCommand command(long key, String text) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(AgentMessage.user(text)), id(key), HASH);
  }
}
