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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    List<UUID> modelCalls = new ArrayList<>();
    List<UUID> toolCalls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            invocationId -> {
              modelCalls.add(invocationId);
              // 本地取消回调在 Stop 事务提交后执行：Model 行已被 stopModel 物理删除。
              assertTrue(store.transaction(tx -> tx.findModelInvocation(invocationId)).isEmpty());
              assertFalse(
                  store
                      .transaction(
                          tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, invocationId)))
                      .isPresent());
            },
            toolCalls::add);

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));

    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(List.of(baseline.modelId()), modelCalls);
    assertTrue(toolCalls.isEmpty());
  }

  @Test
  void toolCancellationIncludesOnlyOriginallyNonTerminalSiblings() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 3);
    List<UUID> ids = baseline.toolIds();
    beginDispatchTool(store, ids.get(1));
    markRunningTool(store, ids.get(1));
    beginDispatchTool(store, ids.get(2));
    markRunningTool(store, ids.get(2));
    succeedTool(store, ids.get(2));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    for (UUID id : ids) {
      seedToolWork(store, id);
    }
    List<UUID> modelCalls = new ArrayList<>();
    List<UUID> toolCalls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            modelCalls::add,
            invocationId -> {
              toolCalls.add(invocationId);
              // 本地取消回调在 Stop 事务提交后执行：stopTools 已删除全部 child Tool 行与 parent Model 行。
              assertTrue(store.transaction(tx -> tx.findToolInvocation(invocationId)).isEmpty());
              assertFalse(
                  store
                      .transaction(
                          tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, invocationId)))
                      .isPresent());
            });

    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertTrue(modelCalls.isEmpty());
    assertEquals(List.of(ids.get(0), ids.get(1)), toolCalls);
    // 已 terminal 的 SUCCEEDED sibling 不在取消列表中；其 ToolResult Entry 仍然存在并标记 SUCCEEDED。
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    List<Entry> toolResults =
        path.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof MessagePayload
                        && ((MessagePayload) e.payload()).toolResultMetadata() != null)
            .toList();
    assertEquals(baseline.toolIds().size(), toolResults.size());
    Entry succeededEntry =
        toolResults.stream()
            .filter(
                e ->
                    "call-2"
                        .equals(((MessagePayload) e.payload()).toolResultMetadata().toolCallId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        ToolResultStatus.SUCCEEDED,
        ((MessagePayload) succeededEntry.payload()).toolResultMetadata().status());
    assertFalse(((MessagePayload) succeededEntry.payload()).toolResultMetadata().synthetic());
  }

  @Test
  void cancellationFailuresAreIsolatedFromTheCommittedStopAndFromEachOther() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 2);
    List<UUID> calls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            ignored -> {},
            invocationId -> {
              calls.add(invocationId);
              throw new IllegalStateException("local cancellation failed");
            });

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertEquals(StopResult.Status.STOPPED, result.status());
    assertEquals(baseline.toolIds(), calls);
    // 本地取消失败仅记日志：durable Stop 仍然删除全部 child Tool 行并 append CANCELLED ToolResult Entries。
    for (UUID id : baseline.toolIds()) {
      assertTrue(store.transaction(tx -> tx.findToolInvocation(id)).isEmpty());
    }
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    List<ToolResultStatus> statuses =
        path.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof MessagePayload
                        && ((MessagePayload) e.payload()).toolResultMetadata() != null)
            .map(e -> ((MessagePayload) e.payload()).toolResultMetadata().status())
            .toList();
    assertEquals(baseline.toolIds().size(), statuses.size());
    assertTrue(statuses.stream().allMatch(s -> s == ToolResultStatus.CANCELLED));
  }

  @Test
  void replayAndIdleStopsDoNotRepeatLocalCancellation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline active = seedModel(store, ModelInvocationStatus.READY);
    List<UUID> calls = new ArrayList<>();
    HarnessRuntime runtime =
        new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC), calls::add, ignored -> {});
    runtime.stop(new StopCommand(active.threadId(), TestIds.id(1), 0));
    runtime.stop(new StopCommand(active.threadId(), TestIds.id(1), 0));
    assertEquals(List.of(active.modelId()), calls);

    HarnessRuntimeTestSupport.Baseline idle = seedBaseline(store);
    runtime.stop(new StopCommand(idle.threadId(), TestIds.id(2), 0));
    assertEquals(List.of(active.modelId()), calls);
  }
}
