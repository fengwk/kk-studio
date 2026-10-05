package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.beginDispatchTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * {@link ThreadSnapshot#runtimeStatus()} 统一细粒度运行时状态投影测试。
 *
 * <p>全部用例都经真实 {@link InMemoryHarnessStore} 种子与 {@link HarnessRuntime#getThreadSnapshot} 读取，保证投影的
 * {@code thread/entryPath/model/toolSiblings} 满足 {@code ThreadContextClassifier} 的输入契约：终态
 * Model/Tools 与 continuation 都是正常可达状态（分别投影 APPLYING / CONTINUATION_DUE），只有真正不匹配的形状（例如 Model 不属于本
 * Thread）才 fail-closed。
 */
class ThreadSnapshotTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void stoppedExecutionControlMapsToStoppedStatus() {
    // 测试意图：执行控制 STOPPED 优先于任何本地上下文，统一投影为 STOPPED。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.STOPPED);
    assertEquals(
        ThreadRuntimeStatus.STOPPED,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void idleLifecycleMapsToIdleStatus() {
    // 测试意图：递归生命周期 IDLE 且本地无适用上下文时投影为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertEquals(
        ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithModelMapsToModelStatus() {
    // 测试意图：ACTIVE 生命周期下，本地活跃 Model 按 invocation 状态细分为 MODEL_READY/DISPATCHING/RUNNING。
    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.READY,
            ModelInvocationStatus.DISPATCHING,
            ModelInvocationStatus.RUNNING)) {
      HarnessRuntimeTestSupport.ModelBaseline baseline = seedModel(store, status);
      ThreadRuntimeStatus expected =
          switch (status) {
            case READY -> ThreadRuntimeStatus.MODEL_READY;
            case DISPATCHING -> ThreadRuntimeStatus.MODEL_DISPATCHING;
            case RUNNING -> ThreadRuntimeStatus.MODEL_RUNNING;
            default -> throw new AssertionError();
          };
      assertEquals(expected, runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
    }
  }

  @Test
  void activeLifecycleWithTerminalModelMapsToApplying() {
    // 测试意图：终态但结果尚未物化的 Model 是正常可达状态（而不是非法形状），投影为 APPLYING。
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedTerminalModel(store);
    assertEquals(
        ThreadRuntimeStatus.APPLYING,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithToolsMapsToToolStatusByPriority() {
    // 测试意图：ACTIVE 生命周期下，非终态 Tool siblings 按 WAITING_APPROVAL > RUNNING > DISPATCHING > READY
    // 细分，优先级最高的 sibling 决定投影。
    HarnessRuntimeTestSupport.MultiToolBaseline waitingApproval = seedToolBaseline(store, 4);
    beginDispatchTool(store, waitingApproval.toolIds().get(1));
    beginDispatchTool(store, waitingApproval.toolIds().get(2));
    markRunningTool(store, waitingApproval.toolIds().get(2));
    setWaitingApproval(store, waitingApproval.toolIds().get(3));
    assertEquals(
        ThreadRuntimeStatus.TOOL_WAITING_APPROVAL,
        runtime.getThreadSnapshot(waitingApproval.threadId()).runtimeStatus());

    HarnessRuntimeTestSupport.MultiToolBaseline running = seedToolBaseline(store, 3);
    beginDispatchTool(store, running.toolIds().get(1));
    beginDispatchTool(store, running.toolIds().get(2));
    markRunningTool(store, running.toolIds().get(2));
    assertEquals(
        ThreadRuntimeStatus.TOOL_RUNNING,
        runtime.getThreadSnapshot(running.threadId()).runtimeStatus());

    HarnessRuntimeTestSupport.MultiToolBaseline dispatching = seedToolBaseline(store, 2);
    beginDispatchTool(store, dispatching.toolIds().get(1));
    assertEquals(
        ThreadRuntimeStatus.TOOL_DISPATCHING,
        runtime.getThreadSnapshot(dispatching.threadId()).runtimeStatus());

    HarnessRuntimeTestSupport.ToolBaseline ready = seedToolBaseline(store);
    assertEquals(
        ThreadRuntimeStatus.TOOL_READY,
        runtime.getThreadSnapshot(ready.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithTerminalToolsMapsToApplying() {
    // 测试意图：全部 sibling 都已终态但 outcome 尚未物化是正常可达状态（而非非法形状），投影为 APPLYING。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    cancelTool(store, baseline);
    assertEquals(
        ThreadRuntimeStatus.APPLYING,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithContinuationMapsToContinuationDue() {
    // 测试意图：ACTIVE 生命周期下，head 为 continueModel=true 且归属本 Thread 的 TURN_END 时投影为
    // CONTINUATION_DUE。
    HarnessRuntimeTestSupport.ContinuationBaseline baseline = seedContinuationChain(store, true);
    assertEquals(
        ThreadRuntimeStatus.CONTINUATION_DUE,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void runnableWithoutLocalContextOrQueuedCommandsMapsToIdleStatus() {
    // 测试意图：执行控制 RUNNABLE 但本地没有 Model/Tool/continuation，且没有排队命令时投影为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.RUNNABLE);
    assertEquals(
        ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void acceptingUserCommandMakesActiveThreadQueued() {
    // 测试意图：接受用户命令会把 Thread 置为 ACTIVE（预留 sequence）但尚未推进 head，因此投影必须是 QUEUED。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadState before = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                baseline.threadId(), before.headEntryId(), before.nextCommandSequence()),
            List.of(userMessageCommand(TestIds.id(1), "queued"))),
        AcceptancePreflight.IDENTITY);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(1, snapshot.queuedCommands().size());
    assertEquals(ThreadRuntimeStatus.QUEUED, snapshot.runtimeStatus());
  }

  @Test
  void activeLifecycleRejectsModelOwnedByAnotherThread() {
    // 测试意图：投影只做统一状态映射，真正的形状不一致（Model 不属于本 Thread）仍 fail-closed 抛
    // IllegalStateException，不降级为业务状态。
    HarnessRuntimeTestSupport.ModelBaseline owner = seedModel(store, ModelInvocationStatus.RUNNING);
    ThreadSnapshot ownerSnapshot = runtime.getThreadSnapshot(owner.threadId());
    UUID otherThreadId = seedThreadAt(store, ownerSnapshot.thread().headEntryId());
    ThreadSnapshot otherSnapshot = runtime.getThreadSnapshot(otherThreadId);
    ThreadSnapshot forged =
        new ThreadSnapshot(
            otherSnapshot.thread(),
            otherSnapshot.entryPath(),
            List.of(),
            ownerSnapshot.model(),
            List.of(),
            List.of(),
            List.of());
    assertThrows(IllegalStateException.class, forged::runtimeStatus);
  }

  /** 设置 Thread 的执行控制（种子 fixture 默认 RUNNABLE）。 */
  private void markActive(UUID threadId, ThreadExecutionControl status) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(thread.changeExecutionControl(status, T5));
          return null;
        });
  }
}
