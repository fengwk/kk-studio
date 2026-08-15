package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantAbortedPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantErrorPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayloadWithArguments;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.syntheticToolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.turnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.withRendererKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * ModelInvocation / ToolInvocation FK、唯一 key、result-entry 类型 与 branch 约束。
 *
 * <p>Fixture chain 遵循 turn 协议：每个 INPUT TURN_START 都需要一条 USER message 作为前置，再接 assistant
 * result；ToolResult entry 追加在其 assistant entry 之下（ordinal 前缀）。
 */
public abstract class HarnessStoreInvocationContract {

  private HarnessStore store;
  private TurnBaseline baseline;

  @BeforeEach
  void setUp() {
    store = createStore();
    baseline = seedTurnBaseline(store);
  }

  abstract HarnessStore createStore();

  // ---- 模型调用 ----

  @Test
  void modelAndToolTimestampsRejectSubMillisecondPrecision() {
    ModelInvocation model =
        modelInvocation(
            TestIds.id(1),
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            ModelInvocationStatus.READY,
            null,
            T1);
    ModelInvocation nonCanonicalModel =
        new ModelInvocation(
            model.id(),
            model.threadId(),
            model.turnStartEntryId(),
            model.basisHeadEntryId(),
            model.request(),
            model.status(),
            model.attempt(),
            model.streamCheckpoint(),
            model.result(),
            model.error(),
            model.resultEntryId(),
            List.of(),
            model.createdAt().plusNanos(1),
            model.updatedAt().plusNanos(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.insertModelInvocation(nonCanonicalModel);
                }));

    UUID assistantEntryId = seedAssistantAndModel();
    ToolInvocation tool =
        toolInvocation(
            TestIds.id(10),
            TestIds.id(1),
            assistantEntryId,
            0,
            "call-1",
            ToolInvocationStatus.READY,
            null,
            T2);
    ToolInvocation nonCanonicalTool =
        new ToolInvocation(
            tool.id(),
            tool.modelInvocationId(),
            tool.assistantEntryId(),
            tool.ordinal(),
            tool.request(),
            tool.status(),
            tool.attempt(),
            tool.approval(),
            tool.result(),
            tool.error(),
            tool.resultEntryId(),
            tool.createdAt().plusNanos(1),
            tool.updatedAt().plusNanos(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertToolInvocations(List.of(nonCanonicalTool))));
  }

  @Test
  void modelInvocationRoundTripAndUniqueTurnKey() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(2),
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(baseline.threadId(), baseline.turnStartEntryId()))
            .isPresent());
    assertTrue(
        store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), TestIds.id(42)))
            .isEmpty());
    boolean hasTurnInvocation =
        store.transaction(tx -> tx.hasModelInvocationForTurn(baseline.turnStartEntryId()));
    boolean hasMissingTurnInvocation =
        store.transaction(tx -> tx.hasModelInvocationForTurn(TestIds.id(42)));
    assertTrue(hasTurnInvocation);
    assertFalse(hasMissingTurnInvocation);
  }

  /** 非空 failedAttempts 必须在 InMemory/PostgreSQL 两个 Store 实现中无损往返。 */
  @Test
  void modelFailedAttemptsRoundTripAfterRetryTransition() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    ModelAttemptFailure failure =
        new ModelAttemptFailure(
            1,
            7,
            "partial",
            "thinking",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable"),
            T3,
            T4);
    inTransaction(
        store,
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          ModelInvocation dispatching = model.beginDispatch(T2);
          tx.updateModelInvocation(dispatching);
          ModelInvocation running = dispatching.markRunning(T2);
          tx.updateModelInvocation(running);
          tx.updateModelInvocation(running.retryReady(failure, T3));
        });

    ModelInvocation stored =
        store.transaction(tx -> tx.findModelInvocation(TestIds.id(1)).orElseThrow());
    assertEquals(ModelInvocationStatus.READY, stored.status());
    assertEquals(1, stored.attempt());
    assertEquals(List.of(failure), stored.failedAttempts());
    assertThrows(UnsupportedOperationException.class, () -> stored.failedAttempts().add(failure));
  }

  @Test
  void modelInvocationRequiresExistingThreadAndTurnStartAndBasisHead() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertModelInvocation(
                        modelInvocation(
                            TestIds.id(1),
                            TestIds.id(999),
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.READY,
                            null,
                            T1))));
    // ROOT 不是 TURN_START
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(2),
                          baseline.threadId(),
                          baseline.rootEntryId(),
                          baseline.rootEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                }));
    // basisHead 不等于 thread 当前 head，basis CAS 失败
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(3),
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          TestIds.id(999),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                }));
  }

  @Test
  void modelInvocationInsertRequiresBasisEqualToCurrentThreadHead() {
    // 创建时 basis CAS：不是 thread head 的 basis 会被拒绝
    Baseline other = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(other.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(1),
                          other.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    // basis 指向 thread 自身 head 是合法的
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(2),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T2));
        });
  }

  @Test
  void modelInvocationRequiresTurnStartOnBasisPath() {
    // 同一 ROOT 下的第二个 TURN_START 形成独立 branch
    UUID turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    // turnStartB 不在 basis path [root, turnStartA] 上
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(1),
                          baseline.threadId(),
                          turnStartB,
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    // basis 位于 thread 自身 branch 上是合法的
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(3),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T2));
        });
  }

  @Test
  void modelResultEntryTypeAndUniquenessAreEnforced() {
    // 链 A：root -> turnStartA -> userA -> assistantA(call-1) -> toolResult(0, call-1)
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload("call-1"));
    UUID toolResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1"));
    // 链 B：root -> turnStartB -> userB -> errorB
    UUID turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    UUID userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    UUID errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userB, assistantErrorPayload());
    // 链 C：root -> turnStartC -> userC -> abortedC
    UUID turnStartC =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    UUID userC = insertChildEntry(store, baseline.sessionId(), turnStartC, userMessagePayload());
    UUID abortedEntryId =
        insertChildEntry(store, baseline.sessionId(), userC, assistantAbortedPayload());

    // ROOT / USER MESSAGE / TOOL MESSAGE 不允许作为 result entry 类型
    for (UUID rejected : List.of(baseline.rootEntryId(), userEntryId, toolResultEntryId)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inTransaction(
                  store,
                  tx -> {
                    UUID id = tx.nextId();
                    tx.lockThread(baseline.threadId());
                    tx.insertModelInvocation(
                        modelInvocation(
                            id,
                            baseline.threadId(),
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.READY,
                            null,
                            T1));
                    tx.lockModelInvocation(id);
                    tx.updateModelInvocation(
                        modelInvocation(
                            id,
                            baseline.threadId(),
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.CANCELLED,
                            rejected,
                            T1));
                  }));
    }

    // assistant / assistant-error / assistant-aborted 在其自身 turn/branch 上被接受
    insertTerminalModel(
        TestIds.id(4),
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        assistantEntryId,
        T1);
    UUID thread5 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, turnStartB));
              return id;
            });
    insertTerminalModel(
        TestIds.id(5),
        thread5,
        turnStartB,
        turnStartB,
        ModelInvocationStatus.FAILED,
        errorEntryId,
        T2);
    UUID thread6 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, turnStartC));
              return id;
            });
    insertTerminalModel(
        TestIds.id(6),
        thread6,
        turnStartC,
        turnStartC,
        ModelInvocationStatus.CANCELLED,
        abortedEntryId,
        T3);

    // resultEntryId 在所有 model invocation 之间全局唯一
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID thread7 = tx.nextId();
                  tx.insertThread(thread(thread7, baseline.turnStartEntryId()));
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(7),
                          thread7,
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T4));
                  tx.lockModelInvocation(TestIds.id(7));
                  tx.updateModelInvocation(
                      modelInvocation(
                          TestIds.id(7),
                          thread7,
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          assistantEntryId,
                          T4));
                }));
  }

  @Test
  void modelResultEntryMustBeOnTheBasisPathAndInTheSameTurn() {
    // 链 B：root -> turnStartB -> userB -> assistantB
    UUID turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    UUID userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    UUID assistantB = insertChildEntry(store, baseline.sessionId(), userB, assistantPayload());
    // 同 session 中跨 turn 的 result 会被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          assistantB,
                          T1));
                }));
    // 跨 session 的 result 会被拒绝
    Baseline other = seedThreadBaseline(store);
    UUID otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    UUID otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    UUID otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          otherAssistant,
                          T1));
                }));
  }

  @Test
  void modelResultEntryMustBeABasisPathDescendantNotAnAncestor() {
    // 链：root -> turnStartA -> userA -> assistantA(call-1) -> toolResult(0, call-1)
    UUID userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantA =
        insertChildEntry(store, baseline.sessionId(), userA, assistantPayload("call-1"));
    UUID toolResultA =
        insertChildEntry(
            store, baseline.sessionId(), assistantA, toolResultPayload(assistantA, 0, "call-1"));
    // 将 thread head 迁到 tool result 并在那里创建 model
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateThread(new ThreadState(baseline.threadId(), toolResultA, false, 1, 1, T0, T2));
        });
    // assistantA 与 basis 处于同一 turn，但位于 basis 的祖先一侧：sibling branch 仍会被拒绝，
    // 即便它本身是合法的 assistant result entry
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          toolResultA,
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          toolResultA,
                          ModelInvocationStatus.CANCELLED,
                          assistantA,
                          T2));
                }));
  }

  @Test
  void terminalReplayAndAttachStayLegalAfterTheThreadHeadRelocates() {
    // 链：root -> turnStartA -> userA -> assistantA
    UUID userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantA = insertChildEntry(store, baseline.sessionId(), userA, assistantPayload());
    // terminal invocation 不带关联 result entry
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
          ModelInvocation current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.beginDispatch(T2));
          current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.markRunning(T2));
          current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.succeed(successResponse(), T2));
        });
    ModelInvocation committed =
        store.transaction(tx -> tx.findModelInvocation(TestIds.id(1)).orElseThrow());
    // 将 thread head 迁移到另一个 session 中无关 branch
    Baseline other = seedThreadBaseline(store);
    UUID otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    UUID otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    UUID otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateThread(
              new ThreadState(baseline.threadId(), otherAssistant, false, 1, 1, T0, T2));
        });
    // relocation 之后 terminal exact replay 仍合法，不依赖当前 head
    inTransaction(
        store,
        tx -> {
          tx.lockModelInvocation(TestIds.id(1));
          tx.updateModelInvocation(committed);
        });
    // relocation 之后附加 basis/turn path 之外的 entry 仍会被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ModelInvocation locked = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
                  tx.updateModelInvocation(locked.attachResultEntry(otherAssistant, T3));
                }));
    // relocation 之后在原始 turn 上附加 result entry 仍合法
    inTransaction(
        store,
        tx -> {
          ModelInvocation locked = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(locked.attachResultEntry(assistantA, T3));
        });
    UUID committedResultEntryId =
        store.transaction(
            tx -> tx.findModelInvocation(TestIds.id(1)).orElseThrow().resultEntryId());
    assertEquals(assistantA, committedResultEntryId);
  }

  @Test
  void updateModelInvocationMovesToTerminalWithResultEntry() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload());
    inTransaction(
        store,
        tx -> {
          ModelInvocation current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.beginDispatch(T2));
          current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.markRunning(T2));
          current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.succeed(successResponse(), T2));
          current = tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
          tx.updateModelInvocation(current.attachResultEntry(assistantEntryId, T2));
        });
    ModelInvocation committed =
        store.transaction(tx -> tx.findModelInvocation(TestIds.id(1)).orElseThrow());
    assertEquals(ModelInvocationStatus.SUCCEEDED, committed.status());
    assertEquals(assistantEntryId, committed.resultEntryId());
  }

  @Test
  void updateModelInvocationRequiresLockAndStableIdentity() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    ModelInvocation stored =
        store.transaction(tx -> tx.findModelInvocation(TestIds.id(1)).orElseThrow());

    // 未持有锁
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.updateModelInvocation(stored)));
    // 修改了 basisHeadEntryId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockModelInvocation(TestIds.id(1));
                  tx.updateModelInvocation(
                      new ModelInvocation(
                          stored.id(),
                          stored.threadId(),
                          stored.turnStartEntryId(),
                          TestIds.id(888), // changed basisHeadEntryId
                          stored.request(),
                          stored.status(),
                          stored.attempt(),
                          stored.streamCheckpoint(),
                          stored.result(),
                          stored.error(),
                          stored.resultEntryId(),
                          stored.failedAttempts(),
                          stored.createdAt(),
                          T2));
                }));
    // 修改了 createdAt
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockModelInvocation(TestIds.id(1));
                  tx.updateModelInvocation(
                      new ModelInvocation(
                          stored.id(),
                          stored.threadId(),
                          stored.turnStartEntryId(),
                          stored.basisHeadEntryId(),
                          stored.request(),
                          stored.status(),
                          stored.attempt(),
                          stored.streamCheckpoint(),
                          stored.result(),
                          stored.error(),
                          stored.resultEntryId(),
                          stored.failedAttempts(),
                          T2,
                          T2));
                }));
  }

  @Test
  void findAndLockModelInvocationReturnEmptyForMissingIds() {
    UUID missingId = TestIds.id(1);
    assertTrue(store.<Boolean>transaction(tx -> tx.findModelInvocation(missingId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockModelInvocation(missingId).isEmpty()));
  }

  @Test
  void newInvocationInsertAcceptsOnlyTheInitialState() {
    // model insert 时拒绝非 READY status 或 正 attempt
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(1),
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          null,
                          T1));
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      new ModelInvocation(
                          TestIds.id(2),
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          modelRequest(),
                          ModelInvocationStatus.READY,
                          1,
                          null,
                          null,
                          null,
                          null,
                          List.of(
                              new ModelAttemptFailure(
                                  1,
                                  0,
                                  "",
                                  "",
                                  new ModelInvocationError(
                                      ProviderErrorKind.TRANSIENT, "provider unavailable"),
                                  T1,
                                  T1)),
                          T1,
                          T1));
                }));
    // tool insert 时拒绝非 READY status / 正 attempt / 非 null approval
    UUID assistantEntryId = seedAssistantAndModel();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(10),
                                TestIds.id(1),
                                assistantEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.CANCELLED,
                                null,
                                T2)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            new ToolInvocation(
                                TestIds.id(11),
                                TestIds.id(1),
                                assistantEntryId,
                                0,
                                toolRequest("call-1"),
                                ToolInvocationStatus.READY,
                                1,
                                null,
                                null,
                                null,
                                null,
                                T2,
                                T2)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            new ToolInvocation(
                                TestIds.id(12),
                                TestIds.id(1),
                                assistantEntryId,
                                0,
                                toolRequest("call-1"),
                                ToolInvocationStatus.READY,
                                0,
                                new ToolApproval(false, null, null, null, null, null, null),
                                null,
                                null,
                                null,
                                T2,
                                T2)))));
    // 通过 update 走 terminal apply 不受初始状态不变量约束（Stop / apply）
    inTransaction(
        store,
        tx -> {
          tx.insertToolInvocations(
              List.of(
                  toolInvocation(
                      TestIds.id(13),
                      TestIds.id(1),
                      assistantEntryId,
                      0,
                      "call-1",
                      ToolInvocationStatus.READY,
                      null,
                      T2)));
          tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      TestIds.id(13),
                      TestIds.id(1),
                      assistantEntryId,
                      0,
                      "call-1",
                      ToolInvocationStatus.CANCELLED,
                      null,
                      T2)));
        });
  }

  // ---- 工具调用 ----

  /**
   * 在一个 transaction 中插入一个 READY model invocation 并将其推进到带指定 result link 的 terminal status （insert
   * 仅接受初始状态；terminal apply 走 update）。
   */
  private void insertTerminalModel(
      UUID id,
      UUID threadId,
      UUID turnStartEntryId,
      UUID basisHeadEntryId,
      ModelInvocationStatus status,
      UUID resultEntryId,
      Instant createdAt) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId);
          tx.insertModelInvocation(
              modelInvocation(
                  id,
                  threadId,
                  turnStartEntryId,
                  basisHeadEntryId,
                  ModelInvocationStatus.READY,
                  null,
                  createdAt));
          ModelInvocation current = tx.lockModelInvocation(id).orElseThrow();
          if (status == ModelInvocationStatus.SUCCEEDED) {
            tx.updateModelInvocation(current.beginDispatch(createdAt));
            current = tx.lockModelInvocation(id).orElseThrow();
            tx.updateModelInvocation(current.markRunning(createdAt));
            current = tx.lockModelInvocation(id).orElseThrow();
            tx.updateModelInvocation(current.succeed(successResponse(), createdAt));
          } else if (status == ModelInvocationStatus.FAILED) {
            tx.updateModelInvocation(
                current.fail(
                    new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model failed"),
                    createdAt));
          } else if (status == ModelInvocationStatus.CANCELLED) {
            tx.updateModelInvocation(current.beginDispatch(createdAt));
            current = tx.lockModelInvocation(id).orElseThrow();
            tx.updateModelInvocation(current.markRunning(createdAt));
            current = tx.lockModelInvocation(id).orElseThrow();
            tx.updateModelInvocation(
                current.checkpoint(new StreamCheckpoint(1, 1, "partial", ""), createdAt));
            current = tx.lockModelInvocation(id).orElseThrow();
            tx.updateModelInvocation(
                current
                    .cancel(
                        new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled"),
                        createdAt)
                    .attachResultEntry(resultEntryId, createdAt));
            return;
          } else {
            throw new IllegalArgumentException("unsupported terminal model status " + status);
          }
          current = tx.lockModelInvocation(id).orElseThrow();
          tx.updateModelInvocation(current.attachResultEntry(resultEntryId, createdAt));
        });
  }

  /**
   * 在一个 transaction 中插入一个 READY tool invocation 并将其推进到带指定 result link 的 terminal status （insert
   * 仅接受初始状态；terminal apply 走 update）。
   */
  private void insertTerminalTool(
      UUID id,
      UUID modelInvocationId,
      UUID assistantEntryId,
      int ordinal,
      String toolCallId,
      ToolInvocationStatus status,
      UUID resultEntryId,
      Instant createdAt) {
    inTransaction(
        store,
        tx -> {
          tx.insertToolInvocations(
              List.of(
                  toolInvocation(
                      id,
                      modelInvocationId,
                      assistantEntryId,
                      ordinal,
                      toolCallId,
                      ToolInvocationStatus.READY,
                      null,
                      createdAt)));
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      id,
                      modelInvocationId,
                      assistantEntryId,
                      ordinal,
                      toolCallId,
                      status,
                      resultEntryId,
                      createdAt)));
        });
  }

  private ToolInvocation updateTool(UUID invocationId, UnaryOperator<ToolInvocation> transition) {
    return store.transaction(
        tx -> {
          ToolInvocation current = tx.lockToolInvocation(invocationId).orElseThrow();
          ToolInvocation updated = transition.apply(current);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** 合法 turn 链 TURN_START -> USER -> ASSISTANT(call-1..call-3)，并附带一个 terminal model result。 */
  private UUID seedAssistantAndModel() {
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            userEntryId,
            assistantPayload("call-1", "call-2", "call-3"));
    insertTerminalModel(
        TestIds.id(1),
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        assistantEntryId,
        T1);
    return assistantEntryId;
  }

  @Test
  void reversedToolBatchIsCanonicalizedAndReverseSiblingLockIsRejected() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(11),
                        TestIds.id(1),
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2),
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));

    List<ToolInvocation> stored =
        store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId));
    assertEquals(List.of(0, 1), stored.stream().map(ToolInvocation::ordinal).toList());
    store.transaction(
        tx -> {
          tx.lockToolInvocation(TestIds.id(11)).orElseThrow();
          assertThrows(IllegalStateException.class, () -> tx.lockToolInvocation(TestIds.id(10)));
          return null;
        });
  }

  @Test
  void toolWorkRequiresItsOwningThreadLock() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, TestIds.id(10));

    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.requestWork(target, T2)));
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T2);
        });
    assertTrue(store.transaction(tx -> tx.findWork(target)).isPresent());
  }

  @Test
  void toolArgumentsJsonPreservesItsExactLexicalForm() {
    String argumentsJson = "{   }";
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            userEntryId,
            assistantPayloadWithArguments("call-1", argumentsJson));
    insertTerminalModel(
        TestIds.id(1),
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        assistantEntryId,
        T1);
    ToolInvocation invocation =
        new ToolInvocation(
            TestIds.id(10),
            TestIds.id(1),
            assistantEntryId,
            0,
            toolRequest("call-1", argumentsJson),
            ToolInvocationStatus.READY,
            0,
            null,
            null,
            null,
            null,
            T2,
            T2);

    inTransaction(store, tx -> tx.insertToolInvocations(List.of(invocation)));

    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(TestIds.id(10))).orElseThrow();
    assertEquals(argumentsJson, stored.request().call().argumentsJson());
  }

  @Test
  void successfulToolEffectsRoundTripAsOneTerminalFact() {
    UUID assistantEntryId = seedAssistantAndModel();
    ToolInvocation ready =
        toolInvocation(
            TestIds.id(10),
            TestIds.id(1),
            assistantEntryId,
            0,
            "call-1",
            ToolInvocationStatus.READY,
            null,
            T2);
    inTransaction(store, tx -> tx.insertToolInvocations(List.of(ready)));
    updateTool(TestIds.id(10), tool -> tool.markApprovalNotRequired(T2));
    updateTool(TestIds.id(10), tool -> tool.beginDispatch(T2));
    updateTool(TestIds.id(10), tool -> tool.markRunning(T2));
    ToolEffectBatch effects =
        new ToolEffectBatch(
            List.of(
                new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}"),
                new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"verify\"}")));
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new TextToolContent("ok")), false, "{\"done\":true}", false);
    updateTool(TestIds.id(10), tool -> tool.succeed(result, effects, T3));

    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(TestIds.id(10))).orElseThrow();
    assertEquals(ToolInvocationStatus.SUCCEEDED, stored.status());
    assertEquals(result, stored.result());
    assertEquals(effects, stored.effects());
  }

  @Test
  void caughtLateToolInsertValidationFailureDoesNotCommitAnEarlierBatchItem() {
    UUID assistantEntryId = seedAssistantAndModel();
    ToolInvocation first =
        toolInvocation(
            TestIds.id(10),
            TestIds.id(1),
            assistantEntryId,
            0,
            "call-1",
            ToolInvocationStatus.READY,
            null,
            T2);
    ToolInvocation duplicateOrdinal =
        toolInvocation(
            TestIds.id(11),
            TestIds.id(1),
            assistantEntryId,
            0,
            "call-1",
            ToolInvocationStatus.READY,
            null,
            T2);

    store.transaction(
        tx -> {
          try {
            tx.insertToolInvocations(List.of(first, duplicateOrdinal));
          } catch (IllegalArgumentException ignored) {
            // 整个 sibling batch 必须在第一次 insert 之前完成全部校验
          }
          return null;
        });

    assertTrue(
        store
            .transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId))
            .isEmpty());
  }

  @Test
  void caughtLateToolUpdateValidationFailureDoesNotCommitAnEarlierBatchItem() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2),
                    toolInvocation(
                        TestIds.id(11),
                        TestIds.id(1),
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));

    store.transaction(
        tx -> {
          tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          ToolInvocation valid =
              toolInvocation(
                  TestIds.id(10),
                  TestIds.id(1),
                  assistantEntryId,
                  0,
                  "call-1",
                  ToolInvocationStatus.CANCELLED,
                  null,
                  T2);
          ToolInvocation forgedIdentity =
              toolInvocation(
                  TestIds.id(11),
                  TestIds.id(1),
                  assistantEntryId,
                  2,
                  "call-3",
                  ToolInvocationStatus.READY,
                  null,
                  T2);
          try {
            tx.updateToolInvocations(List.of(valid, forgedIdentity));
          } catch (IllegalArgumentException ignored) {
            // 第二个 sibling 身份非法时，不应让第一个 sibling 进入 terminal
          }
          return null;
        });

    List<ToolInvocationStatus> statuses =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::status)
                    .toList());
    assertEquals(List.of(ToolInvocationStatus.READY, ToolInvocationStatus.READY), statuses);
  }

  @Test
  void toolInvocationRequiresModelInvocationAndAssistantMessageEntry() {
    UUID assistantEntryId = seedAssistantAndModel();
    // 缺少 model invocation
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(10),
                                TestIds.id(999),
                                assistantEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolAssistantEntryMustBeAnAssistantMessage() {
    // 一个 result 为 ASSISTANT_ERROR entry 的 model：合法的 model result，但不是 assistant MESSAGE 来源
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantErrorPayload());
    insertTerminalModel(
        TestIds.id(1),
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.FAILED,
        errorEntryId,
        T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(10),
                                TestIds.id(1),
                                errorEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolInvocationRequiresModelResultEntryEqualsAssistantEntry() {
    // model result 是 assistantA，从 assistantB 发起 tool 会被拒绝
    seedAssistantAndModel();
    UUID turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    UUID userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    UUID assistantB =
        insertChildEntry(store, baseline.sessionId(), userB, assistantPayload("call-1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(10),
                                TestIds.id(1),
                                assistantB,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolInvocationUniqueKeysAreEnforced() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(12),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    // 重复的 (assistantEntryId, ordinal)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(13),
                                TestIds.id(1),
                                assistantEntryId,
                                0,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T3)))));
    // 重复的 id
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(12),
                                TestIds.id(1),
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T3)))));
  }

  @Test
  void toolRequestCallMustExactlyMatchTheAssistantToolCallAtTheSameOrdinal() {
    UUID assistantEntryId = seedAssistantAndModel(); // assistant calls: call-1, call-2, call-3
    // 同 ordinal 处 call id 不匹配
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(10),
                                TestIds.id(1),
                                assistantEntryId,
                                1,
                                "call-other",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
    // ordinal 超过 assistant tool calls 范围
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                TestIds.id(11),
                                TestIds.id(1),
                                assistantEntryId,
                                5,
                                "call-5",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
    // rendererKey 必须匹配 Assistant 中冻结的 renderer 身份
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            withRendererKey(
                                toolInvocation(
                                    TestIds.id(13),
                                    TestIds.id(1),
                                    assistantEntryId,
                                    0,
                                    "call-1",
                                    ToolInvocationStatus.READY,
                                    null,
                                    T2),
                                "other-renderer")))));
    // 精确匹配被接受
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(12),
                        TestIds.id(1),
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
  }

  @Test
  void toolResultEntryMustBeMatchingToolMessage() {
    UUID assistantEntryId = seedAssistantAndModel();
    UUID resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    // 匹配的 result link 被接受
    insertTerminalTool(
        TestIds.id(10),
        TestIds.id(1),
        assistantEntryId,
        0,
        "call-1",
        ToolInvocationStatus.CANCELLED,
        resultEntryId0,
        T2);
    UUID rendererMismatchResult =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(
                assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED, "other-renderer"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            insertTerminalTool(
                TestIds.id(11),
                TestIds.id(1),
                assistantEntryId,
                1,
                "call-2",
                ToolInvocationStatus.CANCELLED,
                rendererMismatchResult,
                T3));
    // assistant MESSAGE 不是 ToolResult entry
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              1,
                              "call-2",
                              ToolInvocationStatus.READY,
                              null,
                              T3)));
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              1,
                              "call-2",
                              ToolInvocationStatus.CANCELLED,
                              assistantEntryId,
                              T3)));
                }));
    UUID resultEntryId1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    UUID resultEntryId2 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId1,
            toolResultPayload(assistantEntryId, 2, "call-3", ToolResultStatus.CANCELLED));
    // result metadata 必须匹配本 invocation 的 ordinal/toolCallId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              2,
                              "call-3",
                              ToolInvocationStatus.READY,
                              null,
                              T4)));
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              2,
                              "call-3",
                              ToolInvocationStatus.CANCELLED,
                              resultEntryId1,
                              T4)));
                }));
    // request call 必须匹配同 ordinal 处的 assistant tool call
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              1,
                              "call-other",
                              ToolInvocationStatus.READY,
                              null,
                              T4)));
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              1,
                              "call-other",
                              ToolInvocationStatus.CANCELLED,
                              resultEntryId1,
                              T4)));
                }));
    // 匹配的 result entry 被接受
    insertTerminalTool(
        TestIds.id(14),
        TestIds.id(1),
        assistantEntryId,
        2,
        "call-3",
        ToolInvocationStatus.CANCELLED,
        resultEntryId2,
        T5);
  }

  @Test
  void toolResultStatusMustExactlyMapTheInvocationStatus() {
    UUID assistantEntryId = seedAssistantAndModel();
    // SUCCEEDED 的 ToolResult 不能关联 CANCELLED 的 invocation
    UUID succeededResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.SUCCEEDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.READY,
                              null,
                              T2)));
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.CANCELLED,
                              succeededResultEntryId,
                              T2)));
                }));
    // 匹配的 status 被接受
    UUID cancelledResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            succeededResultEntryId,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    insertTerminalTool(
        TestIds.id(12),
        TestIds.id(1),
        assistantEntryId,
        1,
        "call-2",
        ToolInvocationStatus.CANCELLED,
        cancelledResultEntryId,
        T3);
  }

  @Test
  void toolResultEntryMustNotBeSynthetic() {
    UUID assistantEntryId = seedAssistantAndModel();
    UUID syntheticResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            syntheticToolResultPayload(assistantEntryId, 0, "call-1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              TestIds.id(1),
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.READY,
                              null,
                              T2)));
                  ToolInvocation locked =
                      tx.lockToolInvocationsByAssistantEntryId(assistantEntryId).get(0);
                  // 通过合法 transition 走到 terminal UNKNOWN 状态，再附加 synthetic result entry：
                  // store 必须直接拒绝该 synthetic 关联本身
                  ToolInvocation next =
                      locked
                          .markApprovalNotRequired(T2)
                          .beginDispatch(T2)
                          .markRunning(T2)
                          .unknown(new ToolInvocationError("UNKNOWN", "ownership lost"), T2)
                          .attachResultEntry(syntheticResultEntryId, T2);
                  tx.updateToolInvocations(List.of(next));
                }));
  }

  @Test
  void loadToolInvocationsByAssistantEntryIdReturnsOrdinalOrder() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        2,
                        "call-3",
                        ToolInvocationStatus.READY,
                        null,
                        T2),
                    toolInvocation(
                        TestIds.id(11),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2),
                    toolInvocation(
                        TestIds.id(12),
                        TestIds.id(1),
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    List<Integer> ordinals =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::ordinal)
                    .toList());
    assertEquals(List.of(0, 1, 2), ordinals);
  }

  @Test
  void lockToolInvocationsByAssistantEntryIdAllowsBatchTerminalUpdate() {
    UUID assistantEntryId = seedAssistantAndModel();
    UUID resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    UUID resultEntryId1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2),
                    toolInvocation(
                        TestIds.id(11),
                        TestIds.id(1),
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    inTransaction(
        store,
        tx -> {
          List<ToolInvocation> locked = tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      locked.get(0).id(),
                      TestIds.id(1),
                      assistantEntryId,
                      locked.get(0).ordinal(),
                      locked.get(0).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId0,
                      locked.get(0).createdAt()),
                  toolInvocation(
                      locked.get(1).id(),
                      TestIds.id(1),
                      assistantEntryId,
                      locked.get(1).ordinal(),
                      locked.get(1).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId1,
                      locked.get(1).createdAt())));
        });
    List<UUID> resultEntries =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::resultEntryId)
                    .toList());
    assertEquals(List.of(resultEntryId0, resultEntryId1), resultEntries);
  }

  @Test
  void updateToolInvocationsRequiresLockAndStableIdentity() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(TestIds.id(10)).orElseThrow());

    // 未持有锁
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.updateToolInvocations(List.of(stored))));
    // 修改了 frozen 的 request identity
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          new ToolInvocation(
                              stored.id(),
                              stored.modelInvocationId(),
                              stored.assistantEntryId(),
                              stored.ordinal(),
                              toolRequest("call-other"),
                              stored.status(),
                              stored.attempt(),
                              stored.approval(),
                              stored.result(),
                              stored.error(),
                              stored.resultEntryId(),
                              stored.createdAt(),
                              T3)));
                }));
  }

  @Test
  void toolListResultsAreImmutableAndNullElementsAreRejected() {
    UUID assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        TestIds.id(10),
                        TestIds.id(1),
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    List<ToolInvocation> loaded =
        store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            loaded.add(
                toolInvocation(
                    TestIds.id(11),
                    TestIds.id(1),
                    assistantEntryId,
                    1,
                    "call-2",
                    ToolInvocationStatus.READY,
                    null,
                    T2)));
    assertThrows(
        NullPointerException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        Arrays.asList(
                            toolInvocation(
                                TestIds.id(11),
                                TestIds.id(1),
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T2),
                            null))));
  }

  // ---- 压缩调用 ----

  /** 关闭 baseline INPUT turn 并开一个 COMPACTION turn（head 推进到其 TURN_START）。 */
  private UUID openCompactionTurn() {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID userEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  baseline.sessionId(),
                  baseline.turnStartEntryId(),
                  userMessagePayload(),
                  T1));
          UUID assistantEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  assistantEntryId, baseline.sessionId(), userEntryId, assistantPayload(), T1));
          UUID endEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  endEntryId,
                  baseline.sessionId(),
                  assistantEntryId,
                  new TurnEndPayload(
                      baseline.turnStartEntryId(), TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          UUID start = tx.nextId();
          tx.insertEntry(
              new Entry(
                  start,
                  baseline.sessionId(),
                  endEntryId,
                  new TurnStartPayload(
                      TurnStartReason.COMPACTION, StoreTestSupport.branchSettings()),
                  T1));
          tx.updateThread(
              tx.findThread(baseline.threadId()).orElseThrow().advanceHead(start, false, T2));
          return start;
        });
  }

  /** 在打开的 COMPACTION turn（head == turnStart）下插入 READY compaction invocation，返回 model id。 */
  private UUID insertCompactionInvocation(UUID turnStart, ModelInvocationRequest request) {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID modelId = tx.nextId();
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  baseline.threadId(),
                  turnStart,
                  turnStart,
                  request,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T2,
                  T2));
          return modelId;
        });
  }

  /** 完整压缩 turn 种子：openCompactionTurn + READY invocation + SUCCEEDED result（元数据/引用按需校验）。 */
  private UUID seedCompletedCompactionTurn(
      ModelInvocationRequest request, CompactionPayload resultPayload) {
    UUID turnStart = openCompactionTurn();
    UUID modelId = insertCompactionInvocation(turnStart, request);
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID resultEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(resultEntryId, baseline.sessionId(), turnStart, resultPayload, T2));
          ModelInvocation current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.beginDispatch(T2));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.markRunning(T2));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.succeed(successResponse(), T3));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.attachResultEntry(resultEntryId, T3));
          return modelId;
        });
  }

  private static ProviderResponse successResponse() {
    return new ProviderResponse(
        "summary",
        "",
        List.of(),
        ProviderStopReason.COMPLETED,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        "{}");
  }

  private static ModelInvocationRequest compactionRequest(
      CompactionPhase phase, UUID firstKeptEntryId, UUID cutEntryId, UUID turnPrefixStartEntryId) {
    ModelInvocationRequest base = modelRequest();
    return new ModelInvocationRequest(
        base.environment(),
        base.providerRequest(),
        List.of(),
        List.of(),
        base.yoloEnabled(),
        base.contextWindow(),
        new CompactionRequest(
            phase,
            CompactionTrigger.THRESHOLD,
            500L,
            firstKeptEntryId,
            cutEntryId,
            turnPrefixStartEntryId));
  }

  @Test
  void compactionInvocationAcceptsSucceededResultWithExactFrozenMetadata() {
    ModelInvocationRequest request =
        compactionRequest(CompactionPhase.FULL, TestIds.id(2), TestIds.id(5), null);
    CompactionPayload result =
        new CompactionPayload(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            500L,
            true,
            "summary",
            TestIds.id(2),
            TestIds.id(5),
            null);

    UUID modelId = seedCompletedCompactionTurn(request, result);

    ModelInvocation stored = store.transaction(tx -> tx.findModelInvocation(modelId).orElseThrow());
    assertEquals(ModelInvocationStatus.SUCCEEDED, stored.status());
    assertTrue(stored.resultEntryId() != null);
  }

  @Test
  void compactionResultRejectsNonSucceededStatusAndMetadataDrift() {
    ModelInvocationRequest request =
        compactionRequest(CompactionPhase.FULL, TestIds.id(2), TestIds.id(5), null);
    CompactionPayload result =
        new CompactionPayload(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            500L,
            true,
            "summary",
            TestIds.id(2),
            TestIds.id(5),
            null);

    // FAILED invocation 携带 COMPACTION result -> 拒绝。
    UUID turnStart = openCompactionTurn();
    UUID modelId = insertCompactionInvocation(turnStart, request);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.lockThread(baseline.threadId());
                  UUID resultEntryId = tx.nextId();
                  tx.insertEntry(
                      new Entry(resultEntryId, baseline.sessionId(), turnStart, result, T2));
                  ModelInvocation locked = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(
                      locked.fail(
                          new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "boom"), T3));
                  locked = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(locked.attachResultEntry(resultEntryId, T3));
                  return null;
                }));

    // SUCCEEDED 但 payload 元数据与冻结请求不一致（phase 漂移）-> 拒绝。
    CompactionPayload drifted =
        new CompactionPayload(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            500L,
            true,
            "summary",
            TestIds.id(2),
            TestIds.id(5),
            TestIds.id(3));
    assertThrows(
        IllegalArgumentException.class, () -> seedCompletedCompactionTurn(request, drifted));
  }

  @Test
  void compactionResultReferencesMustPrecedeResultAndRespectOrder() {
    // cut 不在 result 路径上。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedCompletedCompactionTurn(
                compactionRequest(CompactionPhase.FULL, TestIds.id(2), TestIds.id(999), null),
                new CompactionPayload(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "summary",
                    TestIds.id(2),
                    TestIds.id(999),
                    null)));
    // firstKept 与 cut 都存在且位于 result 前，但 firstKept > cut 仍拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedCompletedCompactionTurn(
                compactionRequest(CompactionPhase.FULL, TestIds.id(5), TestIds.id(2), null),
                new CompactionPayload(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "summary",
                    TestIds.id(5),
                    TestIds.id(2),
                    null)));
    // firstKept 是 result Entry 自身（不位于 result 之前）-> 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  ModelInvocationRequest request =
                      compactionRequest(CompactionPhase.FULL, TestIds.id(2), TestIds.id(5), null);
                  UUID turnStart = seedCompactionTurnWithReadyInvocation(tx, request);
                  UUID modelId = lastModelId(tx, turnStart);
                  UUID resultEntryId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          resultEntryId,
                          baseline.sessionId(),
                          turnStart,
                          new CompactionPayload(
                              CompactionPhase.FULL,
                              CompactionTrigger.THRESHOLD,
                              500L,
                              true,
                              "summary",
                              resultEntryId,
                              TestIds.id(5),
                              null),
                          T2));
                  ModelInvocation current = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(current.beginDispatch(T2));
                  current = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(current.markRunning(T2));
                  current = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(current.succeed(successResponse(), T3));
                  current = tx.lockModelInvocation(modelId).orElseThrow();
                  tx.updateModelInvocation(current.attachResultEntry(resultEntryId, T3));
                  return null;
                }));
    // prefix 必须 < cut（相等拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedCompletedCompactionTurn(
                compactionRequest(
                    CompactionPhase.TURN_PREFIX, TestIds.id(2), TestIds.id(5), TestIds.id(5)),
                new CompactionPayload(
                    CompactionPhase.TURN_PREFIX,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "summary",
                    TestIds.id(2),
                    TestIds.id(5),
                    TestIds.id(5))));
    // prefix 不在 result 路径上。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedCompletedCompactionTurn(
                compactionRequest(
                    CompactionPhase.TURN_PREFIX, TestIds.id(2), TestIds.id(5), TestIds.id(999)),
                new CompactionPayload(
                    CompactionPhase.TURN_PREFIX,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "summary",
                    TestIds.id(2),
                    TestIds.id(5),
                    TestIds.id(999))));
  }

  @Test
  void turnStartReasonMustMatchCompactionPurpose() {
    // 普通（非压缩）invocation 不能挂在 COMPACTION TURN_START 下。
    UUID compactionTurnStart = openCompactionTurn();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          TestIds.id(42),
                          baseline.threadId(),
                          compactionTurnStart,
                          compactionTurnStart,
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));

    // 压缩 invocation 必须挂在 COMPACTION TURN_START 下（INPUT TURN_START 拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      new ModelInvocation(
                          TestIds.id(43),
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          compactionRequest(
                              CompactionPhase.FULL, TestIds.id(2), TestIds.id(5), null),
                          ModelInvocationStatus.READY,
                          0,
                          null,
                          null,
                          null,
                          null,
                          List.of(),
                          T2,
                          T2));
                }));
  }

  /** 在给定事务内：开 COMPACTION turn 链 + READY compaction invocation，返回 turnStart id。 */
  private UUID seedCompactionTurnWithReadyInvocation(
      HarnessStore.Transaction tx, ModelInvocationRequest request) {
    tx.lockThread(baseline.threadId());
    UUID userEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            userEntryId,
            baseline.sessionId(),
            baseline.turnStartEntryId(),
            userMessagePayload(),
            T1));
    UUID assistantEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(assistantEntryId, baseline.sessionId(), userEntryId, assistantPayload(), T1));
    UUID endEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            endEntryId,
            baseline.sessionId(),
            assistantEntryId,
            new TurnEndPayload(
                baseline.turnStartEntryId(), TurnEndOutcome.COMPLETED, false, null, null),
            T1));
    UUID start = tx.nextId();
    tx.insertEntry(
        new Entry(
            start,
            baseline.sessionId(),
            endEntryId,
            new TurnStartPayload(TurnStartReason.COMPACTION, StoreTestSupport.branchSettings()),
            T1));
    tx.updateThread(tx.findThread(baseline.threadId()).orElseThrow().advanceHead(start, false, T2));
    UUID modelId = tx.nextId();
    tx.insertModelInvocation(
        new ModelInvocation(
            modelId,
            baseline.threadId(),
            start,
            start,
            request,
            ModelInvocationStatus.READY,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            T2,
            T2));
    return start;
  }

  private UUID lastModelId(HarnessStore.Transaction tx, UUID turnStart) {
    return tx.findModelInvocationByTurn(baseline.threadId(), turnStart).orElseThrow().id();
  }
}
