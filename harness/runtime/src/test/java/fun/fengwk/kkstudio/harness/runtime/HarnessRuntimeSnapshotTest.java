package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedRunningModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/** getThreadSnapshot：每个分类器上下文中事务内一致的投影。 */
class HarnessRuntimeSnapshotTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void rootBaselineSnapshotExposesNoModelOrTools() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(baseline.threadId(), snapshot.thread().id());
    assertEquals(baseline.rootEntryId(), snapshot.thread().headEntryId());
    assertEquals(1, snapshot.entryPath().entries().size());
    assertEquals(baseline.rootEntryId(), snapshot.entryPath().head().id());
    assertTrue(snapshot.queuedCommands().isEmpty());
    assertNull(snapshot.model());
    assertTrue(snapshot.toolSiblings().isEmpty());
  }

  @Test
  void modelActiveSnapshotExposesModelWithoutTools() {
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedRunningModel(store);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(ModelInvocationStatus.RUNNING, snapshot.model().status());
    assertEquals(baseline.modelId(), snapshot.model().id());
    assertTrue(snapshot.toolSiblings().isEmpty());
  }

  @Test
  void modelTerminalPendingSnapshotExposesModelWithoutTools() {
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedTerminalModel(store);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertTrue(snapshot.model().status().isTerminal());
    assertEquals(baseline.modelId(), snapshot.model().id());
    assertTrue(snapshot.toolSiblings().isEmpty());
  }

  @Test
  void toolActiveSnapshotExposesModelAndSiblings() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(baseline.modelId(), snapshot.model().id());
    assertEquals(1, snapshot.toolSiblings().size());
    assertEquals(baseline.toolId(), snapshot.toolSiblings().get(0).id());
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, snapshot.toolSiblings().get(0).status());
  }

  @Test
  void toolTerminalPendingSnapshotExposesModelAndSiblings() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    cancelTool(store, baseline);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(baseline.modelId(), snapshot.model().id());
    assertEquals(1, snapshot.toolSiblings().size());
    assertTrue(snapshot.toolSiblings().get(0).status().isTerminal());
  }

  @Test
  void historicalHeadExposesNoModelOrTools() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    // head 移到 ROOT 后模型/工具仍存在但不 applicable：快照按分类器投影为空。
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(thread.advanceHead(baseline.rootEntryId(), T5));
          return null;
        });
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertNull(snapshot.model());
    assertTrue(snapshot.toolSiblings().isEmpty());
  }

  @Test
  void continuationDueExposesNoModelOrTools() {
    HarnessRuntimeTestSupport.ContinuationBaseline continuation =
        seedContinuationChain(store, true);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(continuation.threadId());
    assertNull(snapshot.model());
    assertTrue(snapshot.toolSiblings().isEmpty());
    assertEquals(continuation.turnEndEntryId(), snapshot.thread().headEntryId());
  }

  @Test
  void anotherThreadOwningTheAssistantExposesNoModelOrTools() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    UUID otherThread = seedThreadAt(store, baseline.assistantEntryId());
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(otherThread);
    assertNull(snapshot.model());
    assertTrue(snapshot.toolSiblings().isEmpty());
    // 原 Thread 的快照不受影响。
    assertEquals(
        baseline.toolId(),
        runtime.getThreadSnapshot(baseline.threadId()).toolSiblings().get(0).id());
  }

  @Test
  void queuedCommandsAreReturnedImmutableAndInSequence() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(
                    userMessageCommand(TestIds.id(1), "a"),
                    userMessageCommand(TestIds.id(2), "b")))),
        AcceptancePreflight.IDENTITY);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(
        List.of(1L, 2L), snapshot.queuedCommands().stream().map(c -> c.sequence()).toList());
    assertEquals(ThreadCommandState.QUEUED, snapshot.queuedCommands().get(0).state());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.queuedCommands().add(null));
  }

  @Test
  void toolSiblingsAreReturnedImmutable() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.toolSiblings().add(null));
  }

  @Test
  void missingThreadIsNotFound() {
    assertThrows(
        HarnessRuntimeNotFoundException.class, () -> runtime.getThreadSnapshot(TestIds.id(999)));
  }

  @Test
  void snapshotDoesNotBumpRevision() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.getThreadSnapshot(baseline.threadId());
    runtime.getThreadSnapshot(baseline.threadId());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.revision());
  }

  @Test
  void concurrentSnapshotAndEnqueueStayConsistent() throws Exception {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      Future<ThreadSnapshot> snapshotFuture =
          pool.submit(
              () -> {
                barrier.await();
                return runtime.getThreadSnapshot(baseline.threadId());
              });
      Future<ThreadCommand> enqueueFuture =
          pool.submit(
              () -> {
                barrier.await();
                return runtime
                    .acceptCommands(
                        new AcceptCommandsCommand(
                            new AcceptCommandsTarget.Thread(
                                baseline.threadId(),
                                baseline.rootEntryId(),
                                1,
                                List.of(userMessageCommand(TestIds.id(1), "a")))),
                        AcceptancePreflight.IDENTITY)
                    .commands()
                    .getFirst();
              });
      snapshotFuture.get();
      assertEquals(1L, enqueueFuture.get().sequence());
    } finally {
      pool.shutdownNow();
    }
    // 终态快照必然看到已入队命令（InMemory monitor 串行化保证线性一致）。
    ThreadSnapshot finalSnapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(1, finalSnapshot.queuedCommands().size());
    assertEquals(TestIds.id(1), finalSnapshot.queuedCommands().get(0).clientCommandId());
  }

  /**
   * 共享锁定上下文辅助的不变量：按 (threadId, open TURN_START) 查到的 Model 必须可锁，锁不到是持久化不变量破坏， 以 ISE
   * 表达而不是降级为业务上下文（测试用反射代理制造该状态）。
   */
  @Test
  void sharedContextHelperRaisesIseWhenFoundModelCannotBeLocked() {
    InMemoryHarnessStore delegate = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedRunningModel(delegate);
    HarnessRuntime sabotagedRuntime =
        new HarnessRuntime(sabotagedStoreLockingNoModel(delegate), Clock.fixed(T5, ZoneOffset.UTC));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> sabotagedRuntime.getThreadSnapshot(baseline.threadId()));
    assertTrue(error.getMessage().contains("could not be locked"));
  }

  /** 仅测试用的委托 store：其事务拒绝锁定任何 Model 行。 */
  private static HarnessStore sabotagedStoreLockingNoModel(InMemoryHarnessStore delegate) {
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
                                    if (transactionMethod.getName().equals("lockModelInvocation")) {
                                      return Optional.empty();
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
