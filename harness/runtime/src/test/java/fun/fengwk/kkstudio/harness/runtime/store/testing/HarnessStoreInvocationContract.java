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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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
            1,
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

    long assistantEntryId = seedAssistantAndModel();
    ToolInvocation tool =
        toolInvocation(10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2);
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
                  1,
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
                          2,
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
        store.transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), 42)).isEmpty());
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
                            1,
                            999,
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
                          2,
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
                          3,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          999,
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
                          1,
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
                  2,
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
    long turnStartB =
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
                          1,
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
                  3,
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
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload("call-1"));
    long toolResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1"));
    // 链 B：root -> turnStartB -> userB -> errorB
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userB, assistantErrorPayload());
    // 链 C：root -> turnStartC -> userC -> abortedC
    long turnStartC =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userC = insertChildEntry(store, baseline.sessionId(), turnStartC, userMessagePayload());
    long abortedEntryId =
        insertChildEntry(store, baseline.sessionId(), userC, assistantAbortedPayload());

    // ROOT / USER MESSAGE / TOOL MESSAGE 不允许作为 result entry 类型
    for (long rejected : List.of(baseline.rootEntryId(), userEntryId, toolResultEntryId)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inTransaction(
                  store,
                  tx -> {
                    long id = tx.nextId();
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
        4,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        assistantEntryId,
        T1);
    long thread5 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, turnStartB));
              return id;
            });
    insertTerminalModel(
        5, thread5, turnStartB, turnStartB, ModelInvocationStatus.CANCELLED, errorEntryId, T2);
    long thread6 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, turnStartC));
              return id;
            });
    insertTerminalModel(
        6, thread6, turnStartC, turnStartC, ModelInvocationStatus.CANCELLED, abortedEntryId, T3);

    // resultEntryId 在所有 model invocation 之间全局唯一
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long thread7 = tx.nextId();
                  tx.insertThread(thread(thread7, baseline.turnStartEntryId()));
                  tx.insertModelInvocation(
                      modelInvocation(
                          7,
                          thread7,
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T4));
                  tx.lockModelInvocation(7);
                  tx.updateModelInvocation(
                      modelInvocation(
                          7,
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
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long assistantB = insertChildEntry(store, baseline.sessionId(), userB, assistantPayload());
    // 同 session 中跨 turn 的 result 会被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
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
    long otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    long otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    long otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
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
    long userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantA =
        insertChildEntry(store, baseline.sessionId(), userA, assistantPayload("call-1"));
    long toolResultA =
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
                  long id = tx.nextId();
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
    long userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantA = insertChildEntry(store, baseline.sessionId(), userA, assistantPayload());
    // terminal invocation 不带关联 result entry
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.CANCELLED,
                  null,
                  locked.createdAt()));
        });
    ModelInvocation committed = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());
    // 将 thread head 迁移到另一个 session 中无关 branch
    Baseline other = seedThreadBaseline(store);
    long otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    long otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    long otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
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
          tx.lockModelInvocation(1);
          tx.updateModelInvocation(committed);
        });
    // relocation 之后附加 basis/turn path 之外的 entry 仍会被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
                  tx.updateModelInvocation(locked.attachResultEntry(otherAssistant, T3));
                }));
    // relocation 之后在原始 turn 上附加 result entry 仍合法
    inTransaction(
        store,
        tx -> {
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(locked.attachResultEntry(assistantA, T3));
        });
    Long committedResultEntryId =
        store.transaction(tx -> tx.findModelInvocation(1).orElseThrow().resultEntryId());
    assertEquals(assistantA, committedResultEntryId.longValue());
  }

  @Test
  void updateModelInvocationMovesToTerminalWithResultEntry() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload());
    inTransaction(
        store,
        tx -> {
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  locked.id(),
                  locked.threadId(),
                  locked.turnStartEntryId(),
                  locked.basisHeadEntryId(),
                  ModelInvocationStatus.CANCELLED,
                  assistantEntryId,
                  locked.createdAt()));
        });
    ModelInvocation committed = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());
    assertEquals(ModelInvocationStatus.CANCELLED, committed.status());
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
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    ModelInvocation stored = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());

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
                  tx.lockModelInvocation(1);
                  tx.updateModelInvocation(
                      new ModelInvocation(
                          stored.id(),
                          stored.threadId(),
                          stored.turnStartEntryId(),
                          stored.basisHeadEntryId() + 1,
                          stored.request(),
                          stored.status(),
                          stored.attempt(),
                          stored.streamCheckpoint(),
                          stored.result(),
                          stored.error(),
                          stored.resultEntryId(),
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
                  tx.lockModelInvocation(1);
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
                          T2,
                          T2));
                }));
  }

  @Test
  void findAndLockModelInvocationReturnEmptyForMissingIds() {
    assertTrue(store.<Boolean>transaction(tx -> tx.findModelInvocation(1).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockModelInvocation(1).isEmpty()));
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
                          1,
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
                          2,
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
                          T1,
                          T1));
                }));
    // tool insert 时拒绝非 READY status / 正 attempt / 非 null approval
    long assistantEntryId = seedAssistantAndModel();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                1,
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
                                11,
                                1,
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
                                12,
                                1,
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
                      13, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2)));
          tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      13,
                      1,
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
      long id,
      long threadId,
      long turnStartEntryId,
      long basisHeadEntryId,
      ModelInvocationStatus status,
      Long resultEntryId,
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
          ModelInvocation locked = tx.lockModelInvocation(id).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  id,
                  threadId,
                  turnStartEntryId,
                  basisHeadEntryId,
                  status,
                  resultEntryId,
                  locked.createdAt()));
        });
  }

  /**
   * 在一个 transaction 中插入一个 READY tool invocation 并将其推进到带指定 result link 的 terminal status （insert
   * 仅接受初始状态；terminal apply 走 update）。
   */
  private void insertTerminalTool(
      long id,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      ToolInvocationStatus status,
      Long resultEntryId,
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

  /** 合法 turn 链 TURN_START -> USER -> ASSISTANT(call-1..call-3)，并附带一个 terminal model result。 */
  private long seedAssistantAndModel() {
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            userEntryId,
            assistantPayload("call-1", "call-2", "call-3"));
    insertTerminalModel(
        1,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        assistantEntryId,
        T1);
    return assistantEntryId;
  }

  @Test
  void reversedToolBatchIsCanonicalizedAndReverseSiblingLockIsRejected() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        11, 1, assistantEntryId, 1, "call-2", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        10,
                        1,
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
          tx.lockToolInvocation(11).orElseThrow();
          assertThrows(IllegalStateException.class, () -> tx.lockToolInvocation(10));
          return null;
        });
  }

  @Test
  void toolWorkRequiresItsOwningThreadLock() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10,
                        1,
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, 10);

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
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            userEntryId,
            assistantPayloadWithArguments("call-1", argumentsJson));
    insertTerminalModel(
        1,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        assistantEntryId,
        T1);
    ToolInvocation invocation =
        new ToolInvocation(
            10,
            1,
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

    ToolInvocation stored = store.transaction(tx -> tx.findToolInvocation(10)).orElseThrow();
    assertEquals(argumentsJson, stored.request().call().argumentsJson());
  }

  @Test
  void caughtLateToolInsertValidationFailureDoesNotCommitAnEarlierBatchItem() {
    long assistantEntryId = seedAssistantAndModel();
    ToolInvocation first =
        toolInvocation(10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2);
    ToolInvocation duplicateOrdinal =
        toolInvocation(11, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2);

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
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        11,
                        1,
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
                  10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.CANCELLED, null, T2);
          ToolInvocation forgedIdentity =
              toolInvocation(
                  11, 1, assistantEntryId, 2, "call-3", ToolInvocationStatus.READY, null, T2);
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
    long assistantEntryId = seedAssistantAndModel();
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
                                10,
                                999,
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
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantErrorPayload());
    insertTerminalModel(
        1,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
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
                                10,
                                1,
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
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long assistantB =
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
                                10,
                                1,
                                assistantB,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolInvocationUniqueKeysAreEnforced() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        12,
                        1,
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
                                13,
                                1,
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
                                12,
                                1,
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T3)))));
  }

  @Test
  void toolRequestCallMustExactlyMatchTheAssistantToolCallAtTheSameOrdinal() {
    long assistantEntryId = seedAssistantAndModel(); // assistant calls: call-1, call-2, call-3
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
                                10,
                                1,
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
                                11,
                                1,
                                assistantEntryId,
                                5,
                                "call-5",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
    // 精确匹配被接受
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        12,
                        1,
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
  }

  @Test
  void toolResultEntryMustBeMatchingToolMessage() {
    long assistantEntryId = seedAssistantAndModel();
    long resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    // 匹配的 result link 被接受
    insertTerminalTool(
        10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.CANCELLED, resultEntryId0, T2);
    // assistant MESSAGE 不是 ToolResult entry
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
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
                              1,
                              assistantEntryId,
                              1,
                              "call-2",
                              ToolInvocationStatus.CANCELLED,
                              assistantEntryId,
                              T3)));
                }));
    long resultEntryId1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    long resultEntryId2 =
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
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
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
                              1,
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
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
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
                              1,
                              assistantEntryId,
                              1,
                              "call-other",
                              ToolInvocationStatus.CANCELLED,
                              resultEntryId1,
                              T4)));
                }));
    // 匹配的 result entry 被接受
    insertTerminalTool(
        14, 1, assistantEntryId, 2, "call-3", ToolInvocationStatus.CANCELLED, resultEntryId2, T5);
  }

  @Test
  void toolResultStatusMustExactlyMapTheInvocationStatus() {
    long assistantEntryId = seedAssistantAndModel();
    // SUCCEEDED 的 ToolResult 不能关联 CANCELLED 的 invocation
    long succeededResultEntryId =
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
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
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
                              1,
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.CANCELLED,
                              succeededResultEntryId,
                              T2)));
                }));
    // 匹配的 status 被接受
    long cancelledResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            succeededResultEntryId,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    insertTerminalTool(
        12,
        1,
        assistantEntryId,
        1,
        "call-2",
        ToolInvocationStatus.CANCELLED,
        cancelledResultEntryId,
        T3);
  }

  @Test
  void toolResultEntryMustNotBeSynthetic() {
    long assistantEntryId = seedAssistantAndModel();
    long syntheticResultEntryId =
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
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
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
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10, 1, assistantEntryId, 2, "call-3", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        11, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        12,
                        1,
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
    long assistantEntryId = seedAssistantAndModel();
    long resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    long resultEntryId1 =
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
                        10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        11,
                        1,
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
                      1,
                      assistantEntryId,
                      locked.get(0).ordinal(),
                      locked.get(0).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId0,
                      locked.get(0).createdAt()),
                  toolInvocation(
                      locked.get(1).id(),
                      1,
                      assistantEntryId,
                      locked.get(1).ordinal(),
                      locked.get(1).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId1,
                      locked.get(1).createdAt())));
        });
    List<Long> resultEntries =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::resultEntryId)
                    .toList());
    assertEquals(List.of(resultEntryId0, resultEntryId1), resultEntries);
  }

  @Test
  void updateToolInvocationsRequiresLockAndStableIdentity() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10,
                        1,
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    ToolInvocation stored = store.transaction(tx -> tx.findToolInvocation(10).orElseThrow());

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
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10,
                        1,
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
                    11, 1, assistantEntryId, 1, "call-2", ToolInvocationStatus.READY, null, T2)));
    assertThrows(
        NullPointerException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        Arrays.asList(
                            toolInvocation(
                                11,
                                1,
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T2),
                            null))));
  }
}
