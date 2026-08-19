package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Stop Work 围栏顺序契约：canonical 预锁 → 立即净化 Work mailbox → 收敛（append + Thread 推进 + Invocation 删除）。 */
class HarnessRuntimeStopWorkOrderTest {

  private static final Set<String> DURABLE_MUTATIONS =
      Set.of(
          "updateCommands",
          "insertEntry",
          "updateModelInvocation",
          "updateToolInvocations",
          "updateThread",
          "deleteWork");

  @Test
  void toolStopPrelocksThenDeletesWorkInCanonicalOrderBeforeConvergence() {
    InMemoryHarnessStore delegate = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(delegate, 3);
    seedThreadWork(delegate, baseline.threadId());
    seedModelWork(delegate, baseline.modelId());
    for (UUID id : baseline.toolIds()) {
      seedToolWork(delegate, id);
    }
    List<StoreCall> calls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(recordingStore(delegate, calls), Clock.fixed(T5, ZoneOffset.UTC));

    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    List<WorkTarget> expected =
        List.of(
            new WorkTarget(WorkTargetType.THREAD, baseline.threadId()),
            new WorkTarget(WorkTargetType.MODEL, baseline.modelId()),
            new WorkTarget(WorkTargetType.TOOL, baseline.toolIds().get(0)),
            new WorkTarget(WorkTargetType.TOOL, baseline.toolIds().get(1)),
            new WorkTarget(WorkTargetType.TOOL, baseline.toolIds().get(2)));
    assertEquals(
        expected,
        calls.stream()
            .filter(call -> call.method().equals("lockWork"))
            .map(StoreCall::target)
            .toList());
    assertEquals(
        expected,
        calls.stream()
            .filter(call -> call.method().equals("deleteWork"))
            .map(StoreCall::target)
            .toList());

    int firstDelete = firstIndex(calls, "deleteWork");
    int lastDelete = lastIndex(calls, "deleteWork");
    int lastWorkLock = lastIndex(calls, "lockWork");
    assertTrue(lastWorkLock >= 0);
    assertTrue(firstDelete > lastWorkLock);
    // Work 净化必须紧邻锁获取且连续完成：deleteWork 的 owner 校验反查 Model/Tool 行，因此必须在收敛
    // （append Entry / advance Thread / 删除 Invocation）前执行。
    for (int i = firstDelete; i <= lastDelete; i++) {
      assertEquals("deleteWork", calls.get(i).method());
    }
    // deleteWork 之后不再出现任何 lockWork / deleteWork：进入收敛段（insertEntry / updateThread / delete 原语）。
    for (int i = lastDelete + 1; i < calls.size(); i++) {
      String method = calls.get(i).method();
      assertTrue(
          !method.equals("lockWork") && !method.equals("deleteWork"), "unexpected " + method);
    }
  }

  @Test
  void failureOnASecondWorkDeleteRollsBackEarlierDeletesAndAllStopMutations() {
    InMemoryHarnessStore delegate = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(delegate, 2);
    seedThreadWork(delegate, baseline.threadId());
    seedModelWork(delegate, baseline.modelId());
    for (UUID id : baseline.toolIds()) {
      seedToolWork(delegate, id);
    }
    HarnessRuntime runtime =
        new HarnessRuntime(storeFailingSecondDelete(delegate), Clock.fixed(T5, ZoneOffset.UTC));

    assertThrows(
        IllegalStateException.class,
        () -> runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1)));

    ThreadState thread =
        delegate.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, thread.revision());
    assertEquals(baseline.assistantEntryId(), thread.headEntryId());
    for (UUID id : baseline.toolIds()) {
      ToolInvocation stored = delegate.transaction(tx -> tx.findToolInvocation(id).orElseThrow());
      assertEquals(ToolInvocationStatus.READY, stored.status());
    }
    List<WorkTarget> expected =
        List.of(
            new WorkTarget(WorkTargetType.THREAD, baseline.threadId()),
            new WorkTarget(WorkTargetType.MODEL, baseline.modelId()),
            new WorkTarget(WorkTargetType.TOOL, baseline.toolIds().get(0)),
            new WorkTarget(WorkTargetType.TOOL, baseline.toolIds().get(1)));
    for (WorkTarget target : expected) {
      assertTrue(delegate.transaction(tx -> tx.findWork(target)).isPresent());
    }
  }

  private static int firstIndex(List<StoreCall> calls, String method) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).method().equals(method)) {
        return i;
      }
    }
    return -1;
  }

  private static int lastIndex(List<StoreCall> calls, String method) {
    for (int i = calls.size() - 1; i >= 0; i--) {
      if (calls.get(i).method().equals(method)) {
        return i;
      }
    }
    return -1;
  }

  private static HarnessStore recordingStore(InMemoryHarnessStore delegate, List<StoreCall> calls) {
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
                                    if (name.equals("lockWork")
                                        || DURABLE_MUTATIONS.contains(name)) {
                                      WorkTarget target =
                                          (name.equals("lockWork") || name.equals("deleteWork"))
                                              ? (WorkTarget) transactionArgs[0]
                                              : null;
                                      calls.add(new StoreCall(name, target));
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  private static HarnessStore storeFailingSecondDelete(InMemoryHarnessStore delegate) {
    AtomicInteger deletes = new AtomicInteger();
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
                                    if (transactionMethod.getName().equals("deleteWork")
                                        && deletes.incrementAndGet() == 2) {
                                      throw new IllegalStateException("second delete failed");
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  private record StoreCall(String method, WorkTarget target) {}
}
