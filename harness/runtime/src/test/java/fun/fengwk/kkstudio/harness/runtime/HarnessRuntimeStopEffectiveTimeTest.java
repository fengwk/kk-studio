package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Stop 将一个有效时间戳钳制到所有已锁定的 durable 事实，本地时钟回滚或跨节点时钟偏差 都不能让 Command、Entry、Thread、Model 或 Tool 的时间倒退。
 */
class HarnessRuntimeStopEffectiveTimeTest {

  @Test
  void modelStopUsesTheNewestCommandTimestampWhenTheLocalClockIsBehind() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      baseline.threadId(),
                      1,
                      userMessagePayload("late"),
                      TestIds.id(1),
                      ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("late")),
                      null,
                      null,
                      T6)));
          ModelInvocation model = tx.lockModelInvocation(baseline.modelId()).orElseThrow();
          tx.updateModelInvocation(withUpdatedAt(model, T5));
          return null;
        });

    StopResult result =
        new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC))
            .stop(new StopCommand(baseline.threadId(), TestIds.id(2), 0));

    assertEquals(T6, result.thread().updatedAt());
    ModelInvocation stopped =
        store.transaction(tx -> tx.findModelInvocation(baseline.modelId()).orElseThrow());
    assertEquals(T6, stopped.updatedAt());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow());
    assertEquals(T6, command.cancelledAt());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()));
    assertEquals(T6, path.entries().get(path.entries().size() - 2).createdAt());
    assertEquals(T6, path.head().createdAt());
  }

  @Test
  void toolStopUsesTheNewestLockedSiblingTimestampWhenTheLocalClockIsBehind() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 2);
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockModelInvocation(baseline.modelId()).orElseThrow();
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolIds().get(0)).orElseThrow();
          tx.updateToolInvocations(List.of(tool.markApprovalNotRequired(T6)));
          return null;
        });

    StopResult result =
        new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC))
            .stop(new StopCommand(baseline.threadId(), TestIds.id(2), 1));

    assertEquals(T6, result.thread().updatedAt());
    for (UUID toolId : baseline.toolIds()) {
      ToolInvocation tool = store.transaction(tx -> tx.findToolInvocation(toolId).orElseThrow());
      assertEquals(T6, tool.updatedAt());
      assertEquals(
          T6,
          store.transaction(tx -> tx.findEntry(tool.resultEntryId()).orElseThrow()).createdAt());
    }
  }

  @Test
  void continuationStopNeverPredatesItsDurableHead() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ContinuationBaseline baseline = seedContinuationChain(store, true);
    ThreadState before = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    Instant headCreatedAt =
        store.transaction(tx -> tx.findEntry(before.headEntryId()).orElseThrow()).createdAt();

    StopResult result =
        new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC))
            .stop(new StopCommand(baseline.threadId(), TestIds.id(2), 1));

    EntryPath path = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()));
    for (int i = path.entries().size() - 3; i < path.entries().size(); i++) {
      assertEquals(headCreatedAt, path.entries().get(i).createdAt());
    }
    assertEquals(max(before.updatedAt(), headCreatedAt), result.thread().updatedAt());
  }

  private static ModelInvocation withUpdatedAt(ModelInvocation model, Instant updatedAt) {
    return new ModelInvocation(
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
        model.failedAttempts(),
        model.createdAt(),
        updatedAt);
  }

  private static Instant max(Instant left, Instant right) {
    return right.isAfter(left) ? right : left;
  }
}
