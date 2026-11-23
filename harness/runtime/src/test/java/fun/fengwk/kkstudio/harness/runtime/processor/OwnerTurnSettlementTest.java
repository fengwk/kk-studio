package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link OwnerTurnSettlement} 的压缩回合结算分支：只有任务内（非 USER_REQUEST）的成功压缩机械续作 owner 模型，失败与取消都不自我唤醒。
 *
 * <p>直接调用 {@code settle}：一次真实事务内锁定 owner Thread、消费已冻结的 SETTLE_OWNER_TURN 票据，并观察 durable
 * 结果——TURN_END payload 与结算后的 THREAD Work 行。
 */
class OwnerTurnSettlementTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String SUMMARY = "summary of the compacted history";
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final long OUTPUT_BUDGET = 4_096L;

  /** 测试意图：内置 compact 触发的 MANUAL 压缩成功后，owner 的模型回合被机械续作（同一 THREAD Work 行推进 wake 后保留）。 */
  @Test
  void manualCompactionContinuationRequestsTheNextModelWake() {
    Fixture fixture = fixture(CompactionTrigger.MANUAL, ChildTerminal.COMPLETED);

    settle(fixture);

    EntryPath path = settledPath(fixture);
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertEquals(true, end.continueModel());
    assertNull(end.reason());
    assertEquals(SUMMARY, compactionPayload(path).summaryText());
    Work wake = threadWork(fixture);
    assertNotNull(wake);
    assertEquals(2L, wake.wakeVersion());
    assertNull(wake.leaseToken());
  }

  /** 测试意图：用户显式发起的摘要维护不产生模型续作义务，成功结算后不再留下 THREAD Work。 */
  @Test
  void userRequestedCompactionCompletesWithoutContinuation() {
    Fixture fixture = fixture(CompactionTrigger.USER_REQUEST, ChildTerminal.COMPLETED);

    settle(fixture);

    EntryPath path = settledPath(fixture);
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertEquals(false, end.continueModel());
    assertNull(end.reason());
    assertNull(threadWork(fixture));
  }

  /** 测试意图：子执行失败的不完整摘要落 ASSISTANT_ERROR，回合 FAILED 且绝不机械唤醒 owner。 */
  @Test
  void failedCompactionChildSettlesWithoutMechanicalWake() {
    Fixture fixture = fixture(CompactionTrigger.MANUAL, ChildTerminal.FAILED);

    settle(fixture);

    EntryPath path = settledPath(fixture);
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(false, end.continueModel());
    assertEquals("COMPACTION_FAILED", errorPayload(path).error().code());
    assertNull(threadWork(fixture));
  }

  /** 测试意图：子执行被停止的压缩回合以 STOPPED 结算，closeRequestId 冻结票据身份，且不机械唤醒。 */
  @Test
  void cancelledCompactionChildSettlesWithoutMechanicalWake() {
    Fixture fixture = fixture(CompactionTrigger.MANUAL, ChildTerminal.CANCELLED);

    settle(fixture);

    EntryPath path = settledPath(fixture);
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals(fixture.joinId(), end.closeRequestId());
    assertEquals(false, end.continueModel());
    assertEquals(AssistantError.CANCELLED_CODE, errorPayload(path).error().code());
    assertNull(threadWork(fixture));
  }

  /** 测试意图：票据尚未冻结时结算只完成 claim 继续等待，不写任何回合结果。 */
  @Test
  void unfrozenTicketCompletesTheClaimWithoutSettlingTheTurn() {
    Fixture fixture = pendingFixture(CompactionTrigger.MANUAL);

    settle(fixture);

    EntryPath path = settledPath(fixture);
    assertEquals(TurnStartReason.COMPACTION, ((TurnStartPayload) path.head().payload()).reason());
    assertNull(threadWork(fixture));
  }

  /** 测试意图：结算票据只属于 owner 自己持有的 COMPACTION Turn，其它 reason 或非 owner 历史都必须返回 null。 */
  @Test
  void ticketIdOnlyBelongsToTheOwnerCompactionTurn() {
    Fixture fixture = fixture(CompactionTrigger.MANUAL, ChildTerminal.COMPLETED);
    CompactionStart compaction =
        new CompactionStart(
            CompactionTrigger.MANUAL,
            new ExecutionSelection("fake", "{}"),
            OUTPUT_BUDGET,
            id(1L),
            id(5_000L),
            fixture.joinId());

    assertNull(
        OwnerTurnSettlement.ticketId(
            ownerThread(fixture),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, fixture.ownerThreadId())));
    assertNull(
        OwnerTurnSettlement.ticketId(
            ownerThread(fixture),
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                SETTINGS,
                id(9_000L),
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                compaction,
                null)));
    assertEquals(
        fixture.joinId(),
        OwnerTurnSettlement.ticketId(
            ownerThread(fixture),
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                SETTINGS,
                fixture.ownerThreadId(),
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                compaction,
                null)));
  }

  private static void settle(Fixture fixture) {
    InMemoryHarnessStore store = fixture.store();
    store.transaction(
        tx -> {
          ThreadState owner = tx.threads().lock(fixture.ownerThreadId()).orElseThrow();
          EntryPath path = tx.entries().loadPath(owner.headEntryId());
          Entry openTurn = path.openTurnStart().orElseThrow();
          TurnStartPayload start = (TurnStartPayload) openTurn.payload();
          assertEquals(fixture.joinId(), OwnerTurnSettlement.ticketId(owner, start));
          new OwnerTurnSettlement()
              .settle(
                  tx, fixture.claim(), owner, path, start, fixture.joinId(), openTurn, tx.now());
          return null;
        });
  }

  private static ThreadState ownerThread(Fixture fixture) {
    return fixture
        .store()
        .transaction(tx -> tx.threads().find(fixture.ownerThreadId()).orElseThrow());
  }

  private static EntryPath settledPath(Fixture fixture) {
    return fixture
        .store()
        .transaction(
            tx ->
                tx.entries()
                    .loadPath(
                        tx.threads().find(fixture.ownerThreadId()).orElseThrow().headEntryId()));
  }

  private static Work threadWork(Fixture fixture) {
    return fixture
        .store()
        .transaction(
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, fixture.ownerThreadId()))
                    .orElse(null));
  }

  private static AssistantErrorPayload errorPayload(EntryPath path) {
    return assertInstanceOf(
        AssistantErrorPayload.class, path.entries().get(path.entries().size() - 2).payload());
  }

  private static CompactionPayload compactionPayload(EntryPath path) {
    return assertInstanceOf(
        CompactionPayload.class, path.entries().get(path.entries().size() - 2).payload());
  }

  private record Fixture(
      InMemoryHarnessStore store, UUID ownerThreadId, UUID joinId, ClaimedWork claim) {}

  private enum ChildTerminal {
    COMPLETED,
    FAILED,
    CANCELLED
  }

  private static Fixture fixture(CompactionTrigger trigger, ChildTerminal terminal) {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    store.setNow(NOW);
    UUID childThreadId = id(5_000L);
    UUID joinId = id(5_001L);
    UUID ownerThreadId = store.transaction(tx -> seedOwner(tx, trigger, childThreadId, joinId));
    store.transaction(
        tx -> {
          seedChild(tx, childThreadId, joinId, ownerThreadId, terminal);
          return null;
        });
    return claimOwner(store, ownerThreadId, joinId);
  }

  /** 票据仍未冻结的 fixture：compress 子执行尚未产出终态，owner 的 claim 被唤醒一次。 */
  private static Fixture pendingFixture(CompactionTrigger trigger) {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    store.setNow(NOW);
    UUID childThreadId = id(5_000L);
    UUID joinId = id(5_001L);
    UUID ownerThreadId = store.transaction(tx -> seedOwner(tx, trigger, childThreadId, joinId));
    store.transaction(
        tx -> {
          seedPendingChild(tx, childThreadId, joinId, ownerThreadId);
          return null;
        });
    return claimOwner(store, ownerThreadId, joinId);
  }

  private static Fixture claimOwner(InMemoryHarnessStore store, UUID ownerThreadId, UUID joinId) {
    store.transaction(
        tx -> {
          tx.threads().lock(ownerThreadId).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.THREAD, ownerThreadId));
          return null;
        });
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            WorkTargetType.THREAD, UUID.randomUUID(), Duration.ofSeconds(30)))
            .orElseThrow();
    assertEquals(ownerThreadId, claim.target().id());
    return new Fixture(store, ownerThreadId, joinId, claim);
  }

  /** 一个已关闭 INPUT turn 之后打开 COMPACTION turn；压缩子执行的票据身份在 TURN_START 上冻结。 */
  private static UUID seedOwner(
      HarnessStore.Transaction tx, CompactionTrigger trigger, UUID childThreadId, UUID joinId) {
    UUID sessionId = tx.nextId();
    UUID rootEntryId = tx.nextId();
    UUID turnStartId = tx.nextId();
    UUID ownerThreadId = tx.nextId();
    tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
    tx.entries().insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), NOW));
    tx.entries()
        .insert(new Entry(turnStartId, sessionId, rootEntryId, turnStart(ownerThreadId), NOW));
    ThreadState owner =
        new ThreadState(
            ownerThreadId,
            sessionId,
            null,
            turnStartId,
            HASH,
            "main",
            ownerThreadId,
            false,
            ThreadKind.USER,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            NOW,
            NOW);
    tx.threads().insert(owner);
    UUID userEntryId = tx.nextId();
    tx.entries()
        .insert(new Entry(userEntryId, sessionId, turnStartId, userMessage("start the task"), NOW));
    UUID answerEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                answerEntryId, sessionId, userEntryId, assistantMessage("task answered"), NOW));
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                sessionId,
                answerEntryId,
                new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                NOW));
    ThreadState afterTurn = owner.advanceHead(turnEndId, NOW);
    tx.threads().update(afterTurn);
    UUID compactionStartId = tx.nextId();
    CompactionStart compaction =
        new CompactionStart(
            trigger,
            new ExecutionSelection("fake", "{}"),
            OUTPUT_BUDGET,
            rootEntryId,
            childThreadId,
            joinId);
    tx.entries()
        .insert(
            new Entry(
                compactionStartId,
                sessionId,
                turnEndId,
                new TurnStartPayload(
                    TurnStartReason.COMPACTION,
                    SETTINGS,
                    ownerThreadId,
                    CONTEXT_WINDOW,
                    MAX_OUTPUT_TOKENS,
                    compaction,
                    null),
                NOW));
    tx.threads().update(afterTurn.advanceHead(compactionStartId, NOW));
    return ownerThreadId;
  }

  /** 压缩子执行：一条已应用的源命令与一个已匹配的 SETTLE_OWNER_TURN 票据。 */
  private static void seedChild(
      HarnessStore.Transaction tx,
      UUID childThreadId,
      UUID joinId,
      UUID ownerThreadId,
      ChildTerminal terminal) {
    UUID sessionId = tx.nextId();
    UUID rootEntryId = tx.nextId();
    tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
    tx.entries().insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), NOW));
    UUID turnStartId = tx.nextId();
    tx.entries()
        .insert(new Entry(turnStartId, sessionId, rootEntryId, turnStart(childThreadId), NOW));
    UUID userEntryId = tx.nextId();
    tx.entries()
        .insert(new Entry(userEntryId, sessionId, turnStartId, userMessage("compact"), NOW));
    UUID resultEntryId = tx.nextId();
    UUID finalAnswerEntryId;
    switch (terminal) {
      case COMPLETED -> {
        tx.entries()
            .insert(
                new Entry(resultEntryId, sessionId, userEntryId, assistantMessage(SUMMARY), NOW));
        finalAnswerEntryId = resultEntryId;
      }
      case FAILED -> {
        tx.entries()
            .insert(
                new Entry(
                    resultEntryId,
                    sessionId,
                    userEntryId,
                    assistantError("CHILD_FAILED", "compaction model failed"),
                    NOW));
        finalAnswerEntryId = null;
      }
      default -> {
        tx.entries()
            .insert(
                new Entry(
                    resultEntryId,
                    sessionId,
                    userEntryId,
                    assistantError(AssistantError.CANCELLED_CODE, "compaction child stopped"),
                    NOW));
        finalAnswerEntryId = null;
      }
    }
    UUID turnEndId = tx.nextId();
    TurnEndPayload end =
        switch (terminal) {
          case COMPLETED -> new TurnEndPayload(
              turnStartId, TurnEndOutcome.COMPLETED, false, null, null);
          case FAILED -> new TurnEndPayload(
              turnStartId, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null);
          case CANCELLED -> new TurnEndPayload(
              turnStartId, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(5_002L));
        };
    tx.entries().insert(new Entry(turnEndId, sessionId, resultEntryId, end, NOW));
    ThreadState child =
        new ThreadState(
            childThreadId,
            sessionId,
            ownerThreadId,
            turnEndId,
            HASH,
            "compaction-child",
            ownerThreadId,
            false,
            ThreadKind.COMPACTION,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            1L,
            2L,
            0L,
            NOW,
            NOW);
    tx.threads().insert(child);
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(AgentMessage.user("compact this conversation"));
    ThreadCommand source = queuedSource(childThreadId, payload);
    tx.commands().insert(List.of(source));
    tx.commands().update(List.of(source.markApplied(turnStartId)));
    ThreadJoin join = ownerTicket(payload, joinId, ownerThreadId, childThreadId);
    tx.joins().insert(join);
    tx.joins().update(join.match(turnEndId, finalAnswerEntryId, NOW));
  }

  /** 尚未产出终态的子执行：只有已排队的源命令与未匹配的 SETTLE_OWNER_TURN 票据。 */
  private static void seedPendingChild(
      HarnessStore.Transaction tx, UUID childThreadId, UUID joinId, UUID ownerThreadId) {
    UUID sessionId = tx.nextId();
    UUID rootEntryId = tx.nextId();
    tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
    tx.entries().insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), NOW));
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(AgentMessage.user("compact this conversation"));
    ThreadState idle =
        new ThreadState(
            childThreadId,
            sessionId,
            ownerThreadId,
            rootEntryId,
            HASH,
            "compaction-child",
            ownerThreadId,
            false,
            ThreadKind.COMPACTION,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            2L,
            0L,
            NOW,
            NOW);
    tx.threads().insert(idle);
    tx.commands().insert(List.of(queuedSource(childThreadId, payload)));
    tx.joins().insert(ownerTicket(payload, joinId, ownerThreadId, childThreadId));
  }

  private static ThreadCommand queuedSource(UUID childThreadId, UserMessageCommandPayload payload) {
    return new ThreadCommand(
        childThreadId,
        1L,
        payload,
        id(5_003L),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        NOW);
  }

  private static ThreadJoin ownerTicket(
      UserMessageCommandPayload payload, UUID joinId, UUID ownerThreadId, UUID childThreadId) {
    return new ThreadJoin(
        joinId,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        ownerThreadId,
        childThreadId,
        1L,
        "compaction",
        null,
        0L,
        null,
        null,
        null,
        NOW,
        NOW,
        JoinDelivery.SETTLE_OWNER_TURN,
        null);
  }

  private static TurnStartPayload turnStart(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT,
        SETTINGS,
        ownerThreadId,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null,
        null);
  }

  private static MessagePayload userMessage(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))), null, null);
  }

  private static MessagePayload assistantMessage(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
        null);
  }

  private static AssistantErrorPayload assistantError(String code, String message) {
    return new AssistantErrorPayload(new AssistantError(code, message), null);
  }
}
