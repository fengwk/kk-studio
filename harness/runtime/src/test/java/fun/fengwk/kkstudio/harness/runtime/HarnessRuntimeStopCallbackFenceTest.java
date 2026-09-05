package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.beginDispatchTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;

/** Stop 删除已 claim 的 Work，使迟到的 Model/Tool callback 立即失去 ownership。 */
class HarnessRuntimeStopCallbackFenceTest {

  @Test
  void modelClaimCannotCommitAfterStop() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    ClaimedWork claim =
        store.transaction(
            tx -> tx.claimNextWork(WorkTargetType.MODEL, T3, "model-lease", T6).orElseThrow());

    runtime(store, Clock.fixed(T5, ZoneOffset.UTC))
        .stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));

    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claim, T5)).isEmpty());
    // stopModel 关闭 turn 后 Model 行被物理删除；迟到 callback 的 claim 也因 Work 已删而 no-op。
    assertTrue(store.transaction(tx -> tx.findModelInvocation(baseline.modelId())).isEmpty());
  }

  @Test
  void toolClaimCannotCommitAfterStop() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    beginDispatchTool(store, baseline.toolId());
    markRunningTool(store, baseline.toolId());
    seedThreadWork(store, baseline.threadId());
    seedToolWork(store, baseline.toolId());
    ClaimedWork claim =
        store.transaction(
            tx -> tx.claimNextWork(WorkTargetType.TOOL, T3, "tool-lease", T6).orElseThrow());

    runtime(store, Clock.fixed(T5, ZoneOffset.UTC))
        .stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claim, T5)).isEmpty());
    // stopTools 关闭 turn 后全部 Tool 行与 parent Model 行被物理删除；迟到 callback 的 claim 也因 Work 已删而 no-op。
    assertTrue(store.transaction(tx -> tx.findToolInvocation(baseline.toolId())).isEmpty());
  }
}
