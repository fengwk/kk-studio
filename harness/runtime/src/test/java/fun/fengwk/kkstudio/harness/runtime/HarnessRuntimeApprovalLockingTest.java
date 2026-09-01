package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.storeAdvancingClockOnThreadLock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.toolError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** decideToolApproval 加锁：规范加锁顺序、锁定的行基准与锁漂移不变量。 */
class HarnessRuntimeApprovalLockingTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T5);
    runtime = new HarnessRuntime(store, clock);
  }

  private ToolApprovalCommand allow(UUID threadId, UUID toolId) {
    return new ToolApprovalCommand(
        threadId, toolId, ToolApprovalDecision.ALLOWED, TestIds.id(1), "alice", null);
  }

  /**
   * 已决定 approval 的 replay 必须按 canonical Thread -&gt; Model -&gt; Tool 顺序锁定 owning Model 与目标
   * Tool，并返回锁定后的当前行：find 的过期探针（updatedAt=T5）被忽略，ToolProcessor 推进后的 RUNNING/T6 行原样返回。
   */
  @Test
  void decidedReplayLocksModelThenToolAndReturnsTheLockedCurrentRow() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    // ToolProcessor 风格的后续推进：READY -> DISPATCHING -> RUNNING（decided approval 保持不变）。
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          tx.updateToolInvocations(List.of(tool.beginDispatch(T6)));
          ToolInvocation running = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          tx.updateToolInvocations(List.of(running.markRunning(T6)));
          return null;
        });
    ToolInvocation current =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    ToolInvocation staleProbe =
        new ToolInvocation(
            current.id(),
            current.modelInvocationId(),
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            current.status(),
            current.attempt(),
            current.approval(),
            current.result(),
            current.error(),
            current.createdAt(),
            T5);
    List<String> locks = new ArrayList<>();
    HarnessRuntime lockedRuntime =
        new HarnessRuntime(
            recordingProbeStore(
                store, locks, Map.of("findToolInvocation", args -> Optional.of(staleProbe))),
            clock);
    ToolInvocation replay =
        lockedRuntime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    assertEquals(
        List.of(
            "lockModelInvocation:" + baseline.modelId(), "lockToolInvocation:" + baseline.toolId()),
        locks);
    // 返回锁定后的当前行，而不是未加锁读取的过期探针。
    assertEquals(ToolInvocationStatus.RUNNING, replay.status());
    assertEquals(T6, replay.updatedAt());
    assertEquals(T5, replay.approval().decidedAt());
  }

  /**
   * 未决定 approval 的转换必须基于锁定 siblings 中的当前行：find 返回的 CANCELLED 伪探针被忽略，决策基于 WAITING_APPROVAL 的锁定
   * sibling 成功；且不单独 lockToolInvocation（siblings 统一按 callIndex 锁定）。
   */
  @Test
  void undecidedDecisionTransitionsTheLockedSiblingNotTheProbe() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolInvocation current =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    ToolInvocation cancelledProbe =
        new ToolInvocation(
            current.id(),
            current.modelInvocationId(),
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            ToolInvocationStatus.CANCELLED,
            current.attempt(),
            current.approval(),
            null,
            toolError(),
            current.createdAt(),
            T5);
    List<String> locks = new ArrayList<>();
    HarnessRuntime lockedRuntime =
        new HarnessRuntime(
            recordingProbeStore(
                store, locks, Map.of("findToolInvocation", args -> Optional.of(cancelledProbe))),
            clock);
    ToolInvocation decided =
        lockedRuntime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    assertEquals(ToolInvocationStatus.READY, decided.status());
    // siblings 统一锁定（callIndex 序），绝不对目标单独加锁。
    assertTrue(
        locks.contains("lockToolInvocationsByAssistantEntryId:" + baseline.assistantEntryId()));
    assertFalse(locks.contains("lockToolInvocation:" + baseline.toolId()));
    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(decided, stored);
  }

  /**
   * 未决定路径对锁定 sibling 的状态检查以锁定行为准：即使 find 探针声称 WAITING_APPROVAL，锁定 siblings 中的 READY 行仍按业务冲突
   * APPROVAL_NOT_APPLICABLE 拒绝。
   */
  @Test
  void undecidedDecisionChecksTheLockedSiblingStatus() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolInvocation current =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    ToolInvocation readySibling =
        new ToolInvocation(
            current.id(),
            current.modelInvocationId(),
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            ToolInvocationStatus.READY,
            current.attempt(),
            new ToolApproval(
                true,
                ToolApprovalDecision.ALLOWED,
                TestIds.id(1),
                "alice",
                null,
                current.approval().requestedAt(),
                T5),
            null,
            null,
            current.createdAt(),
            T5);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of(
                                "lockToolInvocationsByAssistantEntryId",
                                args -> List.of(readySibling))),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
  }

  /**
   * 未决定路径要求目标必须位于锁定 siblings 内：find 命中的工具不在当前 TOOL_ACTIVE 上下文（等价于另一 turn 的工具）时按业务冲突
   * APPROVAL_NOT_APPLICABLE 拒绝。
   */
  @Test
  void undecidedDecisionForAToolOutsideTheLockedSiblingsIsNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolInvocation current =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    // 伪造当前 assistant 的 siblings 为另一把工具（满足分类器序/归属不变量）：目标工具不在其中。
    ToolInvocation otherSibling =
        new ToolInvocation(
            TestIds.id(987),
            current.modelInvocationId(),
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            ToolInvocationStatus.READY,
            current.attempt(),
            null,
            null,
            null,
            current.createdAt(),
            T5);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of(
                                "lockToolInvocationsByAssistantEntryId",
                                args -> List.of(otherSibling))),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
  }

  /** find 之后锁不到行属于持久化不变量破坏：model/tool vanish、attachment 改变或 approval 丢失都以 ISE 表达， 而不是降级为业务冲突。 */
  @Test
  void decidedReplayLockDriftIsAnInvariantIse() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    ToolInvocation current =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());

    IllegalStateException vanishedModel =
        assertThrows(
            IllegalStateException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of("lockModelInvocation", args -> Optional.empty())),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertTrue(vanishedModel.getMessage().contains("could not be locked"));

    IllegalStateException vanishedTool =
        assertThrows(
            IllegalStateException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of("lockToolInvocation", args -> Optional.empty())),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertTrue(vanishedTool.getMessage().contains("could not be locked"));

    ToolInvocation detachedTool =
        new ToolInvocation(
            current.id(),
            TestIds.id(999), // 伪造：同 id 但 modelInvocationId 改变。
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            current.status(),
            current.attempt(),
            current.approval(),
            current.result(),
            current.error(),
            current.createdAt(),
            current.updatedAt());
    IllegalStateException detached =
        assertThrows(
            IllegalStateException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of("lockToolInvocation", args -> Optional.of(detachedTool))),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertTrue(detached.getMessage().contains("changed its model attachment"));

    ToolInvocation lostApproval =
        new ToolInvocation(
            current.id(),
            current.modelInvocationId(),
            current.assistantEntryId(),
            current.callIndex(),
            current.call(),
            current.binding(),
            current.status(),
            current.attempt(),
            ToolApproval.notRequired(),
            current.result(),
            current.error(),
            current.createdAt(),
            current.updatedAt());
    IllegalStateException approvalDrift =
        assertThrows(
            IllegalStateException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of("lockToolInvocation", args -> Optional.of(lostApproval))),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertTrue(approvalDrift.getMessage().contains("lost its required decided approval"));
  }

  /** 锁定后的 owning Model 若不属于请求线程，仍然是业务冲突 APPROVAL_NOT_APPLICABLE（不是不变量 ISE）。 */
  @Test
  void decidedReplayLockedModelOwnershipMismatchIsNotApplicable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    runtime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    ModelInvocation model =
        store.transaction(tx -> tx.findModelInvocation(baseline.modelId()).orElseThrow());
    ModelInvocation foreignModel =
        new ModelInvocation(
            model.id(),
            TestIds.id(999), // 伪造：同 id 但 threadId 属于另一线程。
            model.turnStartEntryId(),
            model.requestHeadEntryId(),
            model.requestSpec(),
            model.status(),
            model.attempt(),
            model.streamCheckpoint(),
            model.result(),
            model.error(),
            model.resultEntryId(),
            model.failedAttempts(),
            model.createdAt(),
            model.updatedAt());
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                new HarnessRuntime(
                        recordingProbeStore(
                            store,
                            new ArrayList<>(),
                            Map.of("lockModelInvocation", args -> Optional.of(foreignModel))),
                        clock)
                    .decideToolApproval(allow(baseline.threadId(), baseline.toolId())));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
  }

  /**
   * 决策时间戳在 Thread 锁获取之后读取：store 代理在 lockThread 时把可变时钟从 T5 推进到 T6，决定与 version bump
   * 必须使用推进后的时间（锁前捕获会留下 decidedAt/updatedAt=T5）。
   */
  @Test
  void approvalTimestampIsCapturedAfterTheThreadLock() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    HarnessRuntime lockedRuntime =
        new HarnessRuntime(storeAdvancingClockOnThreadLock(store, clock, T6), clock);
    ToolInvocation decided =
        lockedRuntime.decideToolApproval(allow(baseline.threadId(), baseline.toolId()));
    assertEquals(T6, decided.approval().decidedAt());
    assertEquals(T6, decided.updatedAt());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(T6, thread.updatedAt());
  }

  /**
   * Test-only delegating store proxy（与 snapshot ISE 测试同模式）：记录每次 lockModelInvocation /
   * lockToolInvocation / lockToolInvocationsByAssistantEntryId 调用，并可选覆盖单个事务方法（例如伪造的
   * findToolInvocation 行或返回 empty 的锁），从而证明控制面锁定当前行而不是信任未加锁探针。
   */
  private static HarnessStore recordingProbeStore(
      InMemoryHarnessStore delegate,
      List<String> lockCalls,
      Map<String, Function<Object[], Object>> overrides) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, Object> callback =
                    (Function<HarnessStore.Transaction, Object>) args[0];
                return delegate.transaction(
                    tx -> {
                      HarnessStore.Transaction wrapped =
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    String name = transactionMethod.getName();
                                    if (name.equals("lockModelInvocation")
                                        || name.equals("lockToolInvocation")
                                        || name.equals("lockToolInvocationsByAssistantEntryId")) {
                                      lockCalls.add(name + ":" + transactionArgs[0]);
                                    }
                                    Function<Object[], Object> override = overrides.get(name);
                                    if (override != null) {
                                      return override.apply(transactionArgs);
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }
}
