package fun.fengwk.kkstudio.harness.runtime.join;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ThreadJoinProjector} 的只读凭据投影与切片语义测试。
 *
 * <p>测试意图：
 *
 * <ol>
 *   <li>未匹配的 ThreadJoin 返回 empty；
 *   <li>已匹配的 ThreadJoin 严格以其冻结的 resultHeadEntryId 为 head 加载路径；
 *   <li>执行切片从源命令的 appliedTurnStartEntryId 起算，绝不取切片之前的旧助手输出；
 *   <li>执行前被取消（未产生 turnStart 或已被取消）结算为 CANCELLED 且 partial/report 为空；
 *   <li>COMPLETED、FAILED、STOPPED、CANCELLED 四种终态的 report/partial/error 映射与契约完全一致；
 *   <li>多轮次/多命令时，后续轮次的推进不影响旧 Join 的固定凭据（fixed head snapshot）。
 * </ol>
 */
class ThreadJoinProjectorTest {

  private static final String VALID_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");

  private InMemoryHarnessStore store;
  private UUID sessionId;
  private UUID childThreadId;
  private UUID rootEntryId;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    sessionId = id(1);
    childThreadId = id(2);
    rootEntryId = id(3);

    store.transaction(
        tx -> {
          tx.insertSession(new Session(sessionId, "test-session", T0));
          tx.insertEntry(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), T0));
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  sessionId,
                  null,
                  rootEntryId,
                  VALID_HASH,
                  "test-thread",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1L,
                  0L,
                  T0,
                  T0));
          return null;
        });
  }

  @Test
  void unmatchedJoinReturnsEmpty() {
    // 测试意图：验证尚未匹配（matchedIdleVersion 为 null）的 join 投影返回 empty。
    ThreadJoin join =
        new ThreadJoin(
            id(10),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            null,
            null,
            null,
            T0,
            T0);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> receipt = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertFalse(receipt.isPresent());
          return null;
        });
  }

  @Test
  void missingSourceCommandThrowsIllegalStateException() {
    // 测试意图：验证如果数据库中缺失 join 所引用的 sourceCommandSequence，抛出 IllegalStateException。
    ThreadJoin join =
        new ThreadJoin(
            id(10),
            VALID_HASH,
            id(11),
            childThreadId,
            99L,
            0L,
            "coder",
            10,
            0L,
            1L,
            rootEntryId,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          assertThrows(
              IllegalStateException.class, () -> ThreadJoinProjector.INSTANCE.project(tx, join));
          return null;
        });
  }

  @Test
  void cancelledBeforeExecutionProducesCancelledOutcomeWithoutOlderAssistant() {
    // 测试意图：验证在执行前被取消的命令投影为 CANCELLED 终态，且绝不误取更早轮次的历史助手回复。
    UUID turnStart1 = id(100);
    UUID user1 = id(101);
    UUID assistant1 = id(102);
    UUID turnEnd1 = id(103);

    store.transaction(
        tx -> {
          // 第 1 轮正常完成
          tx.insertEntry(
              new Entry(
                  turnStart1,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user1, sessionId, turnStart1, "first prompt", T0));
          tx.insertEntry(
              new Entry(
                  assistant1, sessionId, user1, assistantPayload("older assistant reply"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  assistant1,
                  new TurnEndPayload(turnStart1, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          // 命令 1（已应用）与 命令 2（执行前已取消）
          seedAppliedCommand(tx, childThreadId, 1L, "first prompt", turnStart1);
          seedCancelledCommand(tx, childThreadId, 2L, "second prompt", id(999));
          return null;
        });

    ThreadJoin join2 =
        new ThreadJoin(
            id(20),
            VALID_HASH,
            id(11),
            childThreadId,
            2L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd1,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join2);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals(id(20), receipt.invocationId());
          assertEquals(childThreadId, receipt.childThreadId());
          assertEquals("coder", receipt.agent());
          assertEquals(ThreadJoinOutcome.CANCELLED, receipt.outcome());
          assertEquals("second prompt", receipt.prompt());
          assertNull(receipt.report());
          assertNull(receipt.partialResult());
          assertEquals("Cancelled before execution.", receipt.error());
          return null;
        });
  }

  @Test
  void appliedTurnStartMissingFromHeadPathProducesCancelledOutcome() {
    // 测试意图：验证当 appliedTurnStartEntryId 不在 resultHeadEntryId 路径上时判定为执行前取消/历史截断。
    UUID turnStartOrphan = id(200);
    UUID userOrphan = id(201);
    UUID assistantOrphan = id(202);
    UUID turnEndOrphan = id(203);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStartOrphan,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(userOrphan, sessionId, turnStartOrphan, "task prompt", T0));
          tx.insertEntry(
              new Entry(
                  assistantOrphan, sessionId, userOrphan, assistantPayload("orphan reply"), T0));
          tx.insertEntry(
              new Entry(
                  turnEndOrphan,
                  sessionId,
                  assistantOrphan,
                  new TurnEndPayload(turnStartOrphan, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          seedAppliedCommand(tx, childThreadId, 1L, "task prompt", turnStartOrphan);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(30),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            rootEntryId,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertTrue(projected.isPresent());
          assertEquals(ThreadJoinOutcome.CANCELLED, projected.get().outcome());
          assertEquals("Cancelled before execution.", projected.get().error());
          return null;
        });
  }

  @Test
  void completedTurnProducesCompletedOutcomeWithLastReport() {
    // 测试意图：验证正常 COMPLETED 的 Turn 提取切片内最后一段助手文本作为 report，partialResult 与 error 为 null。
    UUID turnStart1 = id(300);
    UUID user = id(301);
    UUID draftAssistant = id(302);
    UUID turnEnd1 = id(303);
    UUID turnStartContinuation = id(304);
    UUID finalAssistant = id(305);
    UUID turnEnd2 = id(306);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart1,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart1, "implement feature", T0));
          tx.insertEntry(
              new Entry(draftAssistant, sessionId, user, assistantPayload("first draft"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  draftAssistant,
                  new TurnEndPayload(turnStart1, TurnEndOutcome.COMPLETED, true, null, null),
                  T0));
          tx.insertEntry(
              new Entry(
                  turnStartContinuation,
                  sessionId,
                  turnEnd1,
                  new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS, childThreadId),
                  T1));
          tx.insertEntry(
              new Entry(
                  finalAssistant,
                  sessionId,
                  turnStartContinuation,
                  assistantPayload("final solution"),
                  T1));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  finalAssistant,
                  new TurnEndPayload(
                      turnStartContinuation, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          seedAppliedCommand(tx, childThreadId, 1L, "implement feature", turnStart1);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(40),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd2,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals(ThreadJoinOutcome.COMPLETED, receipt.outcome());
          assertEquals("implement feature", receipt.prompt());
          assertEquals("final solution", receipt.report());
          assertNull(receipt.partialResult());
          assertNull(receipt.error());

          String xml = receipt.renderCompletionXml();
          assertTrue(xml.contains("state=\"completed\""), xml);
          assertTrue(xml.contains("<result>\nfinal solution\n</result>"), xml);
          return null;
        });
  }

  @Test
  void failedTurnProducesErrorOutcomeWithPartialReport() {
    // 测试意图：验证 FAILED 终态正确分离 error 与 partialResult，且 error 包含失败 reason。
    UUID turnStart = id(400);
    UUID user = id(401);
    UUID assistantError = id(402);
    UUID turnEnd = id(403);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart, "run task", T0));
          tx.insertEntry(
              new Entry(
                  assistantError,
                  sessionId,
                  user,
                  new AssistantErrorPayload(new AssistantError("TURN_FAILED", "rate limit"), null),
                  T0));
          tx.insertEntry(
              new Entry(
                  turnEnd,
                  sessionId,
                  assistantError,
                  new TurnEndPayload(
                      turnStart, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                  T0));

          seedAppliedCommand(tx, childThreadId, 1L, "run task", turnStart);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(50),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals(ThreadJoinOutcome.ERROR, receipt.outcome());
          assertEquals("run task", receipt.prompt());
          assertNull(receipt.report());
          assertEquals("rate limit", receipt.partialResult());
          assertEquals("subagent turn failed: TURN_FAILED", receipt.error());
          return null;
        });
  }

  @Test
  void stoppedTurnProducesCancelledOutcomeWithPartialReport() {
    // 测试意图：验证 STOPPED 终态（USER_STOP）映射为 CANCELLED，保留 AssistantAbortedPayload 文本为 partialResult。
    UUID turnStart = id(500);
    UUID user = id(501);
    UUID aborted = id(502);
    UUID turnEnd = id(503);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart, "long task", T0));
          tx.insertEntry(
              new Entry(
                  aborted,
                  sessionId,
                  user,
                  new AssistantAbortedPayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("halfway done")))),
                  T0));
          tx.insertEntry(
              new Entry(
                  turnEnd,
                  sessionId,
                  aborted,
                  new TurnEndPayload(
                      turnStart, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(888)),
                  T0));

          seedAppliedCommand(tx, childThreadId, 1L, "long task", turnStart);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(60),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals(ThreadJoinOutcome.CANCELLED, receipt.outcome());
          assertEquals("long task", receipt.prompt());
          assertNull(receipt.report());
          assertEquals("halfway done", receipt.partialResult());
          assertEquals("Cancelled by user.", receipt.error());
          return null;
        });
  }

  @Test
  void cancelledTurnProducesCancelledOutcomeWithPartialReport() {
    // 测试意图：验证 CANCELLED 终态映射为 CANCELLED，错误信息为 Cancelled before completion.。
    UUID turnStart = id(600);
    UUID user = id(601);
    UUID assistant = id(602);
    UUID turnEnd = id(603);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart, "task x", T0));
          tx.insertEntry(
              new Entry(assistant, sessionId, user, assistantPayload("some progress"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd,
                  sessionId,
                  assistant,
                  new TurnEndPayload(
                      turnStart, TurnEndOutcome.CANCELLED, false, TurnEndReason.CANCELLED, null),
                  T0));

          seedAppliedCommand(tx, childThreadId, 1L, "task x", turnStart);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(70),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd,
            null,
            T0,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals(ThreadJoinOutcome.CANCELLED, receipt.outcome());
          assertEquals("task x", receipt.prompt());
          assertNull(receipt.report());
          assertEquals("some progress", receipt.partialResult());
          assertEquals("Cancelled before completion.", receipt.error());
          return null;
        });
  }

  @Test
  void sourceRangeSlicingIgnoresOlderTurns() {
    // 测试意图：验证同一子线程多轮执行时，第 2 轮 Join 仅切片第 2 轮条目，完全隔离第 1 轮的旧助手回复。
    UUID turnStart1 = id(700);
    UUID user1 = id(701);
    UUID assistant1 = id(702);
    UUID turnEnd1 = id(703);

    UUID turnStart2 = id(704);
    UUID user2 = id(705);
    UUID assistant2 = id(706);
    UUID turnEnd2 = id(707);

    store.transaction(
        tx -> {
          // 轮次 1 (T0)
          tx.insertEntry(
              new Entry(
                  turnStart1,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user1, sessionId, turnStart1, "prompt 1", T0));
          tx.insertEntry(
              new Entry(assistant1, sessionId, user1, assistantPayload("reply turn 1"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  assistant1,
                  new TurnEndPayload(turnStart1, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          // 轮次 2 (T1)
          tx.insertEntry(
              new Entry(
                  turnStart2,
                  sessionId,
                  turnEnd1,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T1));
          tx.insertEntry(userEntry(user2, sessionId, turnStart2, "prompt 2", T1));
          tx.insertEntry(
              new Entry(assistant2, sessionId, user2, assistantPayload("reply turn 2"), T1));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  assistant2,
                  new TurnEndPayload(turnStart2, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          seedAppliedCommand(tx, childThreadId, 1L, "prompt 1", turnStart1);
          seedAppliedCommand(tx, childThreadId, 2L, "prompt 2", turnStart2);
          return null;
        });

    ThreadJoin join2 =
        new ThreadJoin(
            id(80),
            VALID_HASH,
            id(11),
            childThreadId,
            2L,
            0L,
            "coder",
            10,
            0L,
            2L,
            turnEnd2,
            null,
            T1,
            T1);

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join2);
          assertTrue(projected.isPresent());
          ThreadJoinReceipt receipt = projected.get();

          assertEquals("prompt 2", receipt.prompt());
          assertEquals("reply turn 2", receipt.report());
          assertFalse(receipt.report().contains("turn 1"));
          return null;
        });
  }

  @Test
  void fixedHeadSnapshotIgnoresSubsequentTurns() {
    // 测试意图：验证 Join 冻结的 resultHeadEntryId 在子线程后续推进产生新轮次时保持固定，投影结果不受未来轮次影响。
    UUID turnStart1 = id(800);
    UUID user1 = id(801);
    UUID assistant1 = id(802);
    UUID turnEnd1 = id(803);

    UUID turnStart2 = id(804);
    UUID user2 = id(805);
    UUID assistant2 = id(806);
    UUID turnEnd2 = id(807);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart1,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user1, sessionId, turnStart1, "task 1", T0));
          tx.insertEntry(
              new Entry(assistant1, sessionId, user1, assistantPayload("first answer"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  assistant1,
                  new TurnEndPayload(turnStart1, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          seedAppliedCommand(tx, childThreadId, 1L, "task 1", turnStart1);
          return null;
        });

    // Join 1 在 turnEnd1 冻结匹配
    ThreadJoin join1 =
        new ThreadJoin(
            id(90),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd1,
            null,
            T0,
            T0);

    // 子线程后续继续执行了第 2 轮并推进了 head (T2)
    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart2,
                  sessionId,
                  turnEnd1,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T2));
          tx.insertEntry(userEntry(user2, sessionId, turnStart2, "task 2", T2));
          tx.insertEntry(
              new Entry(assistant2, sessionId, user2, assistantPayload("second answer"), T2));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  assistant2,
                  new TurnEndPayload(turnStart2, TurnEndOutcome.COMPLETED, false, null, null),
                  T2));

          seedAppliedCommand(tx, childThreadId, 2L, "task 2", turnStart2);
          return null;
        });

    store.transaction(
        tx -> {
          Optional<ThreadJoinReceipt> projected = ThreadJoinProjector.INSTANCE.project(tx, join1);
          assertTrue(projected.isPresent());
          assertEquals("task 1", projected.get().prompt());
          assertEquals("first answer", projected.get().report());
          return null;
        });
  }

  @Test
  void handlesMultiInputAndCustomCommandPayloads() {
    // 测试意图：验证 UserMessage、CustomMessage、GoalCommand 以及多段 contents（Text + Json）等 prompt 提取与多输入场景。
    UUID turnStart = id(900);
    UUID user = id(901);
    UUID assistant = id(902);
    UUID turnEnd = id(903);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(
              new Entry(
                  user,
                  sessionId,
                  turnStart,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(
                              new TextMessageContent("multi text "),
                              new JsonMessageContent("{\"input\":1}"))),
                      null,
                      null),
                  T0));
          tx.insertEntry(
              new Entry(
                  assistant,
                  sessionId,
                  user,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new TextMessageContent("part A "),
                              new JsonMessageContent("{\"key\":\"value\"}"))),
                      assistantMetadata(),
                      null),
                  T0));
          tx.insertEntry(
              new Entry(
                  turnEnd,
                  sessionId,
                  assistant,
                  new TurnEndPayload(turnStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          // 包含多段内容的 USER 命令、CUSTOM_MESSAGE 命令与 GOAL 命令
          tx.lockThread(childThreadId);
          ThreadCommand cmdUser =
              new ThreadCommand(
                  childThreadId,
                  1L,
                  new UserMessageCommandPayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(
                              new TextMessageContent("multi text "),
                              new JsonMessageContent("{\"input\":1}")))),
                  id(910),
                  VALID_HASH,
                  null,
                  null,
                  null,
                  T0);
          ThreadCommand cmdCustom =
              new ThreadCommand(
                  childThreadId,
                  2L,
                  new CustomMessageCommandPayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("custom instruction")))),
                  id(911),
                  VALID_HASH,
                  null,
                  null,
                  null,
                  T0);
          ThreadCommand cmdGoal =
              new ThreadCommand(
                  childThreadId,
                  3L,
                  new GoalCommandPayload("reach milestone"),
                  id(912),
                  VALID_HASH,
                  null,
                  null,
                  null,
                  T0);
          tx.insertCommands(List.of(cmdUser, cmdCustom, cmdGoal));
          tx.updateCommands(
              List.of(
                  cmdUser.markApplied(turnStart),
                  cmdCustom.markApplied(turnStart),
                  cmdGoal.markApplied(turnStart)));
          return null;
        });

    store.transaction(
        tx -> {
          ThreadJoin joinUser =
              new ThreadJoin(
                  id(91),
                  VALID_HASH,
                  id(11),
                  childThreadId,
                  1L,
                  0L,
                  "coder",
                  10,
                  0L,
                  1L,
                  turnEnd,
                  null,
                  T0,
                  T0);
          ThreadJoin joinCustom =
              new ThreadJoin(
                  id(92),
                  VALID_HASH,
                  id(11),
                  childThreadId,
                  2L,
                  0L,
                  "coder",
                  10,
                  0L,
                  1L,
                  turnEnd,
                  null,
                  T0,
                  T0);
          ThreadJoin joinGoal =
              new ThreadJoin(
                  id(93),
                  VALID_HASH,
                  id(11),
                  childThreadId,
                  3L,
                  0L,
                  "coder",
                  10,
                  0L,
                  1L,
                  turnEnd,
                  null,
                  T0,
                  T0);

          ThreadJoinReceipt receiptUser =
              ThreadJoinProjector.INSTANCE.project(tx, joinUser).orElseThrow();
          assertEquals("multi text {\"input\":1}", receiptUser.prompt());
          assertEquals("part A {\"key\":\"value\"}", receiptUser.report());

          ThreadJoinReceipt receiptCustom =
              ThreadJoinProjector.INSTANCE.project(tx, joinCustom).orElseThrow();
          assertEquals("custom instruction", receiptCustom.prompt());

          ThreadJoinReceipt receiptGoal =
              ThreadJoinProjector.INSTANCE.project(tx, joinGoal).orElseThrow();
          assertEquals("reach milestone", receiptGoal.prompt());
          return null;
        });
  }

  @Test
  void handlesOtherCommandPayloadTypesWithoutTextPrompt() {
    // 测试意图：验证对于非消息命令（如 SetAgentCommandPayload）投影时 prompt 为空字符串。
    UUID turnStart = id(960);
    UUID user = id(961);
    UUID assistant = id(962);
    UUID turnEnd = id(963);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart, "ignored", T0));
          tx.insertEntry(
              new Entry(
                  assistant, sessionId, user, assistantPayload("report after agent set"), T0));
          tx.insertEntry(
              new Entry(
                  turnEnd,
                  sessionId,
                  assistant,
                  new TurnEndPayload(turnStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T0));

          tx.lockThread(childThreadId);
          ThreadCommand cmdAgent =
              new ThreadCommand(
                  childThreadId,
                  1L,
                  new SetAgentCommandPayload("special-agent"),
                  id(965),
                  VALID_HASH,
                  null,
                  null,
                  null,
                  T0);
          tx.insertCommands(List.of(cmdAgent));
          tx.updateCommands(List.of(cmdAgent.markApplied(turnStart)));
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(97),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            turnEnd,
            null,
            T0,
            T0);

    store.transaction(
        tx -> {
          ThreadJoinReceipt receipt = ThreadJoinProjector.INSTANCE.project(tx, join).orElseThrow();
          assertEquals("", receipt.prompt());
          assertEquals("report after agent set", receipt.report());
          assertEquals(ThreadJoinOutcome.COMPLETED, receipt.outcome());
          return null;
        });
  }

  @Test
  void nonTurnEndHeadThrowsIllegalStateException() {
    // 测试意图：验证如果 resultHeadEntryId 指向非 TurnEndPayload 的条目，抛出 IllegalStateException。
    UUID turnStart = id(950);
    UUID user = id(951);
    UUID assistant = id(952);

    store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  turnStart,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  T0));
          tx.insertEntry(userEntry(user, sessionId, turnStart, "task", T0));
          tx.insertEntry(
              new Entry(assistant, sessionId, user, assistantPayload("hanging reply"), T0));
          seedAppliedCommand(tx, childThreadId, 1L, "task", turnStart);
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            id(96),
            VALID_HASH,
            id(11),
            childThreadId,
            1L,
            0L,
            "coder",
            10,
            0L,
            1L,
            assistant,
            null,
            T0,
            T0);

    store.transaction(
        tx -> {
          assertThrows(
              IllegalStateException.class, () -> ThreadJoinProjector.INSTANCE.project(tx, join));
          return null;
        });
  }

  @Test
  void validatesNullArguments() {
    // 测试意图：验证 tx 与 join 的非空防御。
    assertThrows(
        NullPointerException.class,
        () ->
            ThreadJoinProjector.INSTANCE.project(null, initialJoin(id(1), id(11), childThreadId)));

    store.transaction(
        tx -> {
          assertThrows(
              NullPointerException.class, () -> ThreadJoinProjector.INSTANCE.project(tx, null));
          return null;
        });
  }

  private static Entry userEntry(
      UUID id, UUID sessionId, UUID parentEntryId, String text, Instant createdAt) {
    return new Entry(
        id,
        sessionId,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
            null,
            null),
        createdAt);
  }

  private static void seedAppliedCommand(
      HarnessStore.Transaction tx, UUID threadId, long sequence, String prompt, UUID turnStartId) {
    tx.lockThread(threadId);
    ThreadCommand queued =
        new ThreadCommand(
            threadId,
            sequence,
            new UserMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(prompt)))),
            id(sequence * 100),
            VALID_HASH,
            null,
            null,
            null,
            T0);
    tx.insertCommands(List.of(queued));
    tx.updateCommands(List.of(queued.markApplied(turnStartId)));
  }

  private static void seedCancelledCommand(
      HarnessStore.Transaction tx,
      UUID threadId,
      long sequence,
      String prompt,
      UUID stopRequestId) {
    tx.lockThread(threadId);
    ThreadCommand queued =
        new ThreadCommand(
            threadId,
            sequence,
            new UserMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(prompt)))),
            id(sequence * 100),
            VALID_HASH,
            null,
            null,
            null,
            T0);
    tx.insertCommands(List.of(queued));
    tx.updateCommands(List.of(queued.cancel(stopRequestId, T0)));
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static MessagePayload assistantPayload(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        assistantMetadata(),
        null);
  }

  private ThreadJoin initialJoin(UUID invocationId, UUID parentThreadId, UUID childThreadId) {
    return new ThreadJoin(
        invocationId,
        VALID_HASH,
        parentThreadId,
        childThreadId,
        1L,
        0L,
        "test-agent",
        10,
        0L,
        null,
        null,
        null,
        T0,
        T0);
  }
}
