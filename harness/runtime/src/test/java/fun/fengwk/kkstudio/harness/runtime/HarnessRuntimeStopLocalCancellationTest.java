package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.beginDispatchTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.succeedTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Stop 进程内的本地取消：invocation id 仅在 durable 事务提交后才下发，terminal sibling 被排除， 每次失败都相互隔离，replay/idle
 * 调用绝不会取消某个执行。
 */
class HarnessRuntimeStopLocalCancellationTest {

  @Test
  void modelExecutionIsCancelledOnlyAfterTheDurableCommit() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    List<Long> modelCalls = new ArrayList<>();
    List<Long> toolCalls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            invocationId -> {
              modelCalls.add(invocationId);
              ModelInvocation model =
                  store.transaction(tx -> tx.findModelInvocation(invocationId).orElseThrow());
              assertEquals(ModelInvocationStatus.CANCELLED, model.status());
              assertNotNull(model.resultEntryId());
              assertFalse(
                  store
                      .transaction(
                          tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, invocationId)))
                      .isPresent());
            },
            toolCalls::add);

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));

    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(List.of(baseline.modelId()), modelCalls);
    assertTrue(toolCalls.isEmpty());
  }

  @Test
  void toolCancellationIncludesOnlyOriginallyNonTerminalSiblings() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 3);
    List<Long> ids = baseline.toolIds();
    beginDispatchTool(store, ids.get(1));
    markRunningTool(store, ids.get(1));
    beginDispatchTool(store, ids.get(2));
    markRunningTool(store, ids.get(2));
    succeedTool(store, ids.get(2));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    for (long id : ids) {
      seedToolWork(store, id);
    }
    List<Long> modelCalls = new ArrayList<>();
    List<Long> toolCalls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            modelCalls::add,
            invocationId -> {
              toolCalls.add(invocationId);
              ToolInvocation tool =
                  store.transaction(tx -> tx.findToolInvocation(invocationId).orElseThrow());
              assertTrue(tool.status().isTerminal());
              assertNotNull(tool.resultEntryId());
              assertFalse(
                  store
                      .transaction(
                          tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, invocationId)))
                      .isPresent());
            });

    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 1));

    assertTrue(modelCalls.isEmpty());
    assertEquals(List.of(ids.get(0), ids.get(1)), toolCalls);
    assertEquals(ToolInvocationStatus.SUCCEEDED, storedTool(store, ids.get(2)).status());
  }

  @Test
  void cancellationFailuresAreIsolatedFromTheCommittedStopAndFromEachOther() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 2);
    List<Long> calls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            ignored -> {},
            invocationId -> {
              calls.add(invocationId);
              throw new IllegalStateException("local cancellation failed");
            });

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 1));

    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(baseline.toolIds(), calls);
    for (long id : baseline.toolIds()) {
      ToolInvocation tool = storedTool(store, id);
      assertEquals(ToolInvocationStatus.CANCELLED, tool.status());
      assertNotNull(tool.resultEntryId());
    }
  }

  @Test
  void replayAndIdleStopsDoNotRepeatLocalCancellation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline active = seedModel(store, ModelInvocationStatus.READY);
    List<Long> calls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC), calls::add, ignored -> {});
    runtime.stop(new StopCommand(active.threadId(), "stop-1", 0));
    runtime.stop(new StopCommand(active.threadId(), "stop-1", 0));
    assertEquals(List.of(active.modelId()), calls);

    HarnessRuntimeTestSupport.Baseline idle = seedBaseline(store);
    runtime.stop(new StopCommand(idle.threadId(), "idle-stop", 0));
    assertEquals(List.of(active.modelId()), calls);
  }

  private static ToolInvocation storedTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(tx -> tx.findToolInvocation(toolId).orElseThrow());
  }
}
