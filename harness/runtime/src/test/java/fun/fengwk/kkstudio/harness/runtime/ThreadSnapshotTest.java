package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.approveTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedOpenTurn;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnEndEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.FailureProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationStatus;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver.FakeLockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link ThreadSnapshot#runtimeStatus()} 统一三态运行时状态投影测试。
 *
 * <p>使用 {@link InMemoryBuiltinHarnessStore} 种子读取真实本地上下文，并构造直接子 Join 与输入需求事实验证快照投影。 真正破坏不变量的形状（例如
 * Model 不属于本 Thread）才 fail-closed。
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
  void stoppedExecutionControlDoesNotDirectlyOverwriteStatus() {
    // 测试意图：executionControl 与 status 正交；Thread 本地处于空闲时即使被 STOPPED，status 仍为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.STOPPED);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(ThreadExecutionControl.STOPPED, snapshot.thread().executionControl());
    assertEquals(ThreadRuntimeStatus.IDLE, snapshot.runtimeStatus());
  }

  @Test
  void idleLifecycleMapsToIdleStatus() {
    // 测试意图：本地无活跃上下文且无未完成直接子任务时投影为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertEquals(
        ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithModelMapsToRunningStatus() {
    // 测试意图：Driver generation 活动属于未结束流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    HarnessRuntime active = runtimeWithActivity(baseline, DriverActivity.ACTIVE);
    assertEquals(
        ThreadRuntimeStatus.RUNNING, active.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithTerminalModelMapsToRunning() {
    // 测试意图：终态但结果尚未物化的 Driver generation 属于待 apply 流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    HarnessRuntime pending = runtimeWithActivity(baseline, DriverActivity.RESPONSE_PENDING);
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        pending.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithToolsMapsToRunning() {
    // 测试意图：审批、问卷、运行中及就绪工具均属于本地未结束流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.MultiToolBaseline waitingApproval = seedToolBaseline(store, 4);
    approveTool(store, waitingApproval.toolIds().get(1));
    approveTool(store, waitingApproval.toolIds().get(2));
    markRunningTool(store, waitingApproval.toolIds().get(2));
    setWaitingApproval(store, waitingApproval.toolIds().get(3));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        runtime.getThreadSnapshot(waitingApproval.threadId()).runtimeStatus());

    HarnessRuntimeTestSupport.MultiToolBaseline running = seedToolBaseline(store, 3);
    approveTool(store, running.toolIds().get(1));
    approveTool(store, running.toolIds().get(2));
    markRunningTool(store, running.toolIds().get(2));
    assertEquals(
        ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(running.threadId()).runtimeStatus());

    HarnessRuntimeTestSupport.ToolBaseline ready = seedToolBaseline(store);
    assertEquals(
        ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(ready.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithTerminalToolsMapsToRunning() {
    // 测试意图：全部 sibling 都已终态但 outcome 尚未物化属于待 apply 流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    cancelTool(store, baseline);
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void activeLifecycleWithContinuationMapsToRunning() {
    // 测试意图：head 为 continueModel=true 且归属本 Thread 的 TURN_END 时属于续作流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.ContinuationBaseline baseline = seedContinuationChain(store, true);
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void runnableWithoutLocalContextOrQueuedCommandsMapsToIdleStatus() {
    // 测试意图：执行控制 RUNNABLE 但本地没有 Model/Tool/continuation，且没有排队命令与未完成子任务时投影为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.RUNNABLE);
    assertEquals(
        ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
  }

  @Test
  void acceptingUserCommandMakesThreadRunning() {
    // 测试意图：存在排队命令属于未结束流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(baseline.threadId()),
            List.of(userMessageCommand(TestIds.id(1), "queued"))),
        AcceptancePreflight.IDENTITY);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(1, snapshot.queuedCommands().size());
    assertEquals(ThreadRuntimeStatus.RUNNING, snapshot.runtimeStatus());
  }

  @Test
  void setOnlyQueuedCommandsDoNotMakeThreadRunning() {
    // 测试意图：SET_* 控制命令不创建模型任务义务，也不构成 turn 需求，在 RUNNABLE 下快照状态仍为 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadCommand setCommand =
        new ThreadCommand(
            baseline.threadId(),
            1L,
            new SetAgentCommandPayload("assistant"),
            TestIds.id(1),
            "0".repeat(64),
            null,
            null,
            null,
            T5);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    ThreadSnapshot setOnlySnapshot =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(setCommand),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.IDLE, setOnlySnapshot.runtimeStatus());
  }

  @Test
  void queuedCustomMessageOrNotificationMakesThreadRunning() {
    // 测试意图：QUEUED 的 CUSTOM_MESSAGE 与 NOTIFICATION 均代表真实待启动输入需求，在 RUNNABLE 下快照状态为 RUNNING。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());

    ThreadCommand customMsgCommand =
        new ThreadCommand(
            baseline.threadId(),
            1L,
            new CustomMessageCommandPayload(AgentMessage.user("custom-input")),
            TestIds.id(1),
            "0".repeat(64),
            null,
            null,
            null,
            T5);
    ThreadSnapshot customMsgSnapshot =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(customMsgCommand),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.RUNNING, customMsgSnapshot.runtimeStatus());

    ThreadCommand notificationCommand =
        new ThreadCommand(
            baseline.threadId(),
            2L,
            new NotificationCommandPayload(
                TestIds.id(100),
                NotificationKind.SUBAGENT_RESULT,
                TestIds.id(101),
                AgentMessage.user("notification-input")),
            TestIds.id(2),
            "0".repeat(64),
            null,
            null,
            null,
            T5);
    ThreadSnapshot notificationSnapshot =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(notificationCommand),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.RUNNING, notificationSnapshot.runtimeStatus());
  }

  @Test
  void unconsumedAppliedNotificationWatermarkMakesThreadRunningWhenRunnable() {
    // 测试意图：APPLIED 通知超过输入水位（unconsumedAppliedNotifications=true）在 RUNNABLE 下表示待启动输入，投影为 RUNNING。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    ThreadSnapshot runningWithWatermark =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            true,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.RUNNING, runningWithWatermark.runtimeStatus());
  }

  @Test
  void idleThreadWithIncompleteChildJoinsMapsToWaiting() {
    // 测试意图：本地已让出执行且存在未返回的直接 NOTIFY_PARENT 子任务，投影为 WAITING。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    ThreadSnapshot waiting =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            1,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.WAITING, waiting.runtimeStatus());
  }

  @Test
  void waitingThreadWithLocalTurnEndDoesNotExposeCompletedOutcome() {
    // 测试意图：非 IDLE outcome 不得声明 COMPLETED；本地回合虽已到达 TURN_END，但子任务未完成时 outcome 为 null。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = completedTurnSnapshot(baseline);
    assertEquals(TurnEndOutcome.COMPLETED, base.outcome());
    ThreadSnapshot waitingWithTurnEnd =
        new ThreadSnapshot(
            base.thread(),
            EntryCurrentState.from(base.requireFullPath()),
            new EntrySlice(null, base.requireFullPath().entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            1,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.WAITING, waitingWithTurnEnd.runtimeStatus());
    assertNull(waitingWithTurnEnd.outcome());
  }

  @Test
  void stoppedThreadWithNotificationDemandDoesNotSelfStartAsRunning() {
    // 测试意图：executionControl 正交；STOPPED 下有未消费通知不得显示为待启动 RUNNING（保留通知不自启，明确义务与控制关系）。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.STOPPED);
    ThreadSnapshot stoppedBase = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(ThreadExecutionControl.STOPPED, stoppedBase.thread().executionControl());

    // 1. 存在已 APPLIED 但未被消费的通知水位（watermark > inputThroughSequence）
    ThreadSnapshot stoppedWithWatermark =
        new ThreadSnapshot(
            stoppedBase.thread(),
            EntryCurrentState.from(stoppedBase.requireFullPath()),
            new EntrySlice(null, stoppedBase.requireFullPath().entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            true,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.IDLE, stoppedWithWatermark.runtimeStatus());

    // 2. 存在排队中的系统通知命令
    ThreadSnapshot stoppedWithQueuedNotification =
        new ThreadSnapshot(
            stoppedBase.thread(),
            EntryCurrentState.from(stoppedBase.requireFullPath()),
            new EntrySlice(null, stoppedBase.requireFullPath().entries()),
            List.of(
                new ThreadCommand(
                    baseline.threadId(),
                    1L,
                    new NotificationCommandPayload(
                        TestIds.id(100),
                        NotificationKind.SUBAGENT_RESULT,
                        TestIds.id(101),
                        AgentMessage.user("child result")),
                    TestIds.id(102),
                    "0".repeat(64),
                    null,
                    null,
                    null,
                    T5)),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.IDLE, stoppedWithQueuedNotification.runtimeStatus());
  }

  @Test
  void stoppedThreadWithIncompleteOwnerChildJoinStaysRunning() {
    // 测试意图：STOPPED 只表达执行控制；尚未冻结的 owner 压缩结算义务仍投影为 RUNNING，不能提前 IDLE。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    markActive(baseline.threadId(), ThreadExecutionControl.STOPPED);
    ThreadSnapshot stoppedBase = runtime.getThreadSnapshot(baseline.threadId());

    ThreadSnapshot awaitingOwnerSettlement =
        new ThreadSnapshot(
            stoppedBase.thread(),
            EntryCurrentState.from(stoppedBase.requireFullPath()),
            new EntrySlice(null, stoppedBase.requireFullPath().entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            true);
    assertEquals(ThreadRuntimeStatus.RUNNING, awaitingOwnerSettlement.runtimeStatus());
  }

  @Test
  void generationOwnedByAnotherThreadIsRejected() {
    // 测试意图：generation 仅是中立诊断投影，但仍必须校验其 TURN_START/request head 归属；
    // 把另一个 Thread 的 generation 塞进本快照必须 fail-closed，而不是降级为 IDLE。
    HarnessRuntimeTestSupport.TurnBaseline owner = seedOpenTurn(store);
    HarnessRuntime active = runtimeWithGeneration(owner);
    ThreadSnapshot ownerSnapshot = active.getThreadSnapshot(owner.threadId());
    UUID otherThreadId = seedThreadAt(store, ownerSnapshot.thread().headEntryId());
    ThreadSnapshot otherSnapshot = runtime.getThreadSnapshot(otherThreadId);
    assertThrows(
        IllegalStateException.class,
        () ->
            new ThreadSnapshot(
                otherSnapshot.thread(),
                EntryCurrentState.from(otherSnapshot.requireFullPath()),
                new EntrySlice(null, otherSnapshot.requireFullPath().entries()),
                List.of(),
                ownerSnapshot.generation(),
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
  }

  private enum InvalidGenerationScope {
    CLOSED_TURN,
    FOREIGN_TURN
  }

  @ParameterizedTest
  @EnumSource(InvalidGenerationScope.class)
  void generationNotBelongingToOpenTurnIsRejected(InvalidGenerationScope invalidScope) {
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    ThreadSnapshot active = runtimeWithGeneration(baseline).getThreadSnapshot(baseline.threadId());
    GenerationProjection original = active.generation();
    GenerationProjection candidate =
        new GenerationProjection(
            original.generationId(),
            invalidScope == InvalidGenerationScope.FOREIGN_TURN
                ? UUID.randomUUID()
                : original.turnStartEntryId(),
            original.requestHeadEntryId(),
            original.status(),
            original.attempt(),
            original.sequence(),
            original.outputContents(),
            original.error(),
            original.resultEntryId(),
            original.createdAt(),
            original.updatedAt(),
            original.failures());
    ThreadSnapshot basis =
        invalidScope == InvalidGenerationScope.CLOSED_TURN
            ? completedTurnSnapshot(seedBaseline(store))
            : active;
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> withGeneration(basis, candidate));
    String expected =
        switch (invalidScope) {
          case CLOSED_TURN -> "requires an open TURN_START";
          case FOREIGN_TURN -> "does not belong to thread";
        };
    assertTrue(error.getMessage().contains(expected));
  }

  @Test
  void generationRequestHeadNotOnPathRaisesIseInSnapshot() {
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    UUID offPath = UUID.randomUUID();
    GenerationProjection generation =
        new GenerationProjection(
            TestIds.id(700),
            baseline.turnStartEntryId(),
            offPath,
            GenerationStatus.RUNNING,
            1,
            1,
            List.of(),
            null,
            null,
            T5,
            T5,
            List.of());
    HarnessRuntime rt =
        runtimeWithState(
            new FakeLockedDriverState(
                DriverActivity.ACTIVE, baseline.turnStartEntryId(), null, generation, null));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> rt.getThreadSnapshot(baseline.threadId()));
    assertTrue(
        error
            .getMessage()
            .contains("driver generation request head " + offPath + " is not on the thread path"));
  }

  @Test
  void generationResultEntryNotOnPathRaisesIseInSnapshot() {
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    UUID offPath = UUID.randomUUID();
    GenerationProjection generation =
        new GenerationProjection(
            TestIds.id(700),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            GenerationStatus.RUNNING,
            1,
            1,
            List.of(),
            null,
            offPath,
            T5,
            T5,
            List.of());
    HarnessRuntime rt =
        runtimeWithState(
            new FakeLockedDriverState(
                DriverActivity.ACTIVE, baseline.turnStartEntryId(), null, generation, null));
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> rt.getThreadSnapshot(baseline.threadId()));
    assertTrue(
        error
            .getMessage()
            .contains("driver generation result entry " + offPath + " is not on the thread path"));
  }

  @Test
  void generationFailuresAreFrozenAndExposed() {
    // The public snapshot exposes the projection's immutable failure history, not the source list.
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    ThreadSnapshot active = runtimeWithGeneration(baseline).getThreadSnapshot(baseline.threadId());
    GenerationProjection original = active.generation();
    FailureProjection failure =
        new FailureProjection(1, 1, "text", "thinking", "ERROR", "failed", T5, T5);
    List<FailureProjection> mutableFailures = new ArrayList<>(List.of(failure));
    GenerationProjection projection =
        new GenerationProjection(
            original.generationId(),
            original.turnStartEntryId(),
            original.requestHeadEntryId(),
            original.status(),
            original.attempt(),
            original.sequence(),
            original.outputContents(),
            original.error(),
            original.resultEntryId(),
            original.createdAt(),
            original.updatedAt(),
            mutableFailures);
    ThreadSnapshot snapshot = withGeneration(active, projection);
    mutableFailures.clear();
    assertEquals(List.of(failure), snapshot.generationFailures());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.generationFailures().clear());
  }

  /** 以固定 Driver 活动投影读取当前 open turn。 */
  private HarnessRuntime runtimeWithActivity(
      HarnessRuntimeTestSupport.TurnBaseline baseline, DriverActivity activity) {
    return runtimeWithState(
        new FakeLockedDriverState(activity, baseline.turnStartEntryId(), null, null, null));
  }

  /** 构造带稳定 generation 投影的 Driver 活动状态。 */
  private HarnessRuntime runtimeWithGeneration(HarnessRuntimeTestSupport.TurnBaseline baseline) {
    GenerationProjection generation =
        new GenerationProjection(
            TestIds.id(700),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            GenerationStatus.RUNNING,
            1,
            1,
            List.of(),
            null,
            null,
            T5,
            T5,
            List.of());
    return runtimeWithState(
        new FakeLockedDriverState(
            DriverActivity.ACTIVE, baseline.turnStartEntryId(), null, generation, null));
  }

  private HarnessRuntime runtimeWithState(FakeLockedDriverState state) {
    return runtime(
        store,
        Clock.fixed(T5, ZoneOffset.UTC),
        new FakeAgentDriver().withState(state),
        HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER);
  }

  private static ThreadSnapshot withGeneration(
      ThreadSnapshot base, GenerationProjection generation) {
    return new ThreadSnapshot(
        base.thread(),
        EntryCurrentState.from(base.requireFullPath()),
        new EntrySlice(null, base.requireFullPath().entries()),
        base.queuedCommands(),
        generation,
        base.toolSiblings(),
        base.liveState(),
        base.stopReceipts(),
        base.incompleteChildJoins(),
        base.unconsumedAppliedNotifications(),
        base.unconfirmedExecutions(),
        base.hasIncompleteOwnerChildJoin());
  }

  @Test
  void snapshotRejectsNegativeIncompleteChildJoins() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread(),
                EntryCurrentState.from(base.requireFullPath()),
                new EntrySlice(null, base.requireFullPath().entries()),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                -1,
                false,
                List.of(),
                false));
  }

  @Test
  void turnCountAndToolCallCountCountPathEntriesAccurately() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(0, EntryPathMetrics.from(base.requireFullPath().entries()).turnCount());
    assertEquals(0, EntryPathMetrics.from(base.requireFullPath().entries()).toolCallCount());
    assertNull(base.outcome());

    // 1. 已有 tool 调用的真实快照上验证 toolCallCount
    HarnessRuntimeTestSupport.ToolBaseline toolBaseline = seedToolBaseline(store);
    ThreadSnapshot toolActiveSnapshot = runtime.getThreadSnapshot(toolBaseline.threadId());
    assertEquals(
        1, EntryPathMetrics.from(toolActiveSnapshot.requireFullPath().entries()).toolCallCount());

    // 2. 构造一个普通的纯文本已完成回合
    ThreadSnapshot completedSnapshot = completedTurnSnapshot(baseline);
    assertEquals(
        1, EntryPathMetrics.from(completedSnapshot.requireFullPath().entries()).turnCount());
    assertEquals(ThreadRuntimeStatus.IDLE, completedSnapshot.runtimeStatus());
    assertEquals(TurnEndOutcome.COMPLETED, completedSnapshot.outcome());
  }

  private ThreadSnapshot completedTurnSnapshot(HarnessRuntimeTestSupport.Baseline baseline) {
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    UUID turnStartId = UUID.randomUUID();
    UUID userMsgId = UUID.randomUUID();
    UUID assistantMsgId = UUID.randomUUID();
    UUID turnEndId = UUID.randomUUID();
    Entry turnStart =
        turnStartEntry(
            turnStartId, baseline.sessionId(), baseline.rootEntryId(), T5, baseline.threadId());
    Entry userMsg = userMessageEntry(userMsgId, baseline.sessionId(), turnStartId, T5);
    Entry assistantMsg = assistantEntry(assistantMsgId, baseline.sessionId(), userMsgId, T5);
    Entry turnEnd =
        turnEndEntry(turnEndId, baseline.sessionId(), assistantMsgId, T5, turnStartId, false);
    EntryPath completedPath =
        new EntryPath(
            List.of(base.requireFullPath().head(), turnStart, userMsg, assistantMsg, turnEnd));
    return new ThreadSnapshot(
        base.thread().advanceHead(turnEndId, T5),
        EntryCurrentState.from(completedPath),
        new EntrySlice(null, completedPath.entries()),
        List.of(),
        null,
        List.of(),
        ThreadLiveState.IDLE_OR_HISTORICAL,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }

  @Test
  void outcomeRequiresActualHeadToBeTurnEnd() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    UUID turnStartId = UUID.randomUUID();
    UUID userMsgId = UUID.randomUUID();
    UUID assistantMsgId = UUID.randomUUID();
    UUID turnEndId = UUID.randomUUID();
    UUID settingsId = UUID.randomUUID();
    Entry turnStart =
        turnStartEntry(
            turnStartId, baseline.sessionId(), baseline.rootEntryId(), T5, baseline.threadId());
    Entry userMsg = userMessageEntry(userMsgId, baseline.sessionId(), turnStartId, T5);
    Entry assistantMsg = assistantEntry(assistantMsgId, baseline.sessionId(), userMsgId, T5);
    Entry turnEnd =
        turnEndEntry(turnEndId, baseline.sessionId(), assistantMsgId, T5, turnStartId, false);
    Entry trailingSettings =
        new Entry(
            settingsId,
            baseline.sessionId(),
            turnEndId,
            new SettingsPayload(HarnessRuntimeTestSupport.settings(), UUID.randomUUID()),
            T5);
    EntryPath trailingPath =
        new EntryPath(
            List.of(
                base.requireFullPath().head(),
                turnStart,
                userMsg,
                assistantMsg,
                turnEnd,
                trailingSettings));
    ThreadSnapshot trailingSnapshot =
        new ThreadSnapshot(
            base.thread().advanceHead(settingsId, T5),
            EntryCurrentState.from(trailingPath),
            new EntrySlice(null, trailingPath.entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertEquals(ThreadRuntimeStatus.IDLE, trailingSnapshot.runtimeStatus());
    assertNull(trailingSnapshot.outcome());
  }

  @Test
  void incrementalSnapshotRejectsRequireFullPath() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    ThreadSnapshot incremental =
        new ThreadSnapshot(
            base.thread(),
            base.entryState(),
            new EntrySlice(base.thread().headEntryId(), List.of()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    assertThrows(IllegalStateException.class, incremental::requireFullPath);
  }

  @Test
  void snapshotRejectsSliceMismatchingHead() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadSnapshot base = runtime.getThreadSnapshot(baseline.threadId());
    UUID differentId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread(),
                base.entryState(),
                new EntrySlice(differentId, List.of()),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread(),
                base.entryState(),
                new EntrySlice(null, List.of()),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
    Entry otherEntry =
        userMessageEntry(differentId, baseline.sessionId(), base.thread().headEntryId(), T5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread(),
                base.entryState(),
                new EntrySlice(null, List.of(otherEntry)),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread().advanceHead(differentId, T5),
                base.entryState(),
                base.entrySlice(),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadSnapshot(
                base.thread(),
                base.entryState(),
                base.entrySlice(),
                List.of(),
                null,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                -1,
                false,
                List.of(),
                false));
  }

  /** 设置 Thread 的执行控制（种子 fixture 默认 RUNNABLE）。 */
  private void markActive(UUID threadId, ThreadExecutionControl status) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          tx.threads().update(thread.changeExecutionControl(status, T5));
          return null;
        });
  }
}
