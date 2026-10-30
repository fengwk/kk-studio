package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.approveTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
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

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.driver.FailureProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
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

  private InMemoryBuiltinHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryBuiltinHarnessStore();
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
    // 测试意图：本地存在非终态 Model 调用时属于未结束流程，投影为 RUNNING。
    for (ModelInvocationStatus status :
        List.of(ModelInvocationStatus.READY, ModelInvocationStatus.RUNNING)) {
      HarnessRuntimeTestSupport.ModelBaseline baseline = seedModel(store, status);
      assertEquals(
          ThreadRuntimeStatus.RUNNING,
          runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
    }
  }

  @Test
  void activeLifecycleWithTerminalModelMapsToRunning() {
    // 测试意图：终态但结果尚未物化的 Model 属于待 apply 流程，投影为 RUNNING。
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedTerminalModel(store);
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        runtime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
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
            base.entryPath(),
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
            base.entryPath(),
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
            base.entryPath(),
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
            base.entryPath(),
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
            base.entryPath(),
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
            base.entryPath(),
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
            stoppedBase.entryPath(),
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
            stoppedBase.entryPath(),
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
            stoppedBase.entryPath(),
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
    HarnessRuntimeTestSupport.ModelBaseline owner = seedModel(store, ModelInvocationStatus.RUNNING);
    ThreadSnapshot ownerSnapshot = runtime.getThreadSnapshot(owner.threadId());
    UUID otherThreadId = seedThreadAt(store, ownerSnapshot.thread().headEntryId());
    ThreadSnapshot otherSnapshot = runtime.getThreadSnapshot(otherThreadId);
    assertThrows(
        IllegalStateException.class,
        () ->
            new ThreadSnapshot(
                otherSnapshot.thread(),
                otherSnapshot.entryPath(),
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
    REQUEST_OFF_PATH,
    RESULT_OFF_PATH,
    CLOSED_TURN
  }

  @ParameterizedTest
  @EnumSource(InvalidGenerationScope.class)
  void generationOutsideCurrentPathIsRejected(InvalidGenerationScope invalidScope) {
    // Each corruption isolates one boundary: closed turn, foreign request, or foreign result.
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    ThreadSnapshot active = runtime.getThreadSnapshot(baseline.threadId());
    GenerationProjection original = active.generation();
    UUID offPath = TestIds.id(987654);
    GenerationProjection candidate =
        new GenerationProjection(
            original.generationId(),
            original.turnStartEntryId(),
            invalidScope == InvalidGenerationScope.REQUEST_OFF_PATH
                ? offPath
                : original.requestHeadEntryId(),
            original.status(),
            original.attempt(),
            original.sequence(),
            original.outputContents(),
            original.error(),
            invalidScope == InvalidGenerationScope.RESULT_OFF_PATH ? offPath : null,
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
          case REQUEST_OFF_PATH -> "request head";
          case RESULT_OFF_PATH -> "result entry";
          case CLOSED_TURN -> "requires an open TURN_START";
        };
    assertTrue(error.getMessage().contains(expected));
  }

  @Test
  void generationFailuresAreFrozenAndExposed() {
    // The public snapshot exposes the projection's immutable failure history, not the source list.
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.RUNNING);
    ThreadSnapshot active = runtime.getThreadSnapshot(baseline.threadId());
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

  private static ThreadSnapshot withGeneration(
      ThreadSnapshot base, GenerationProjection generation) {
    return new ThreadSnapshot(
        base.thread(),
        base.entryPath(),
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
                base.entryPath(),
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
    assertEquals(0, base.turnCount());
    assertEquals(0, base.toolCallCount());
    assertNull(base.outcome());

    // 1. 已有 tool 调用的真实快照上验证 toolCallCount
    HarnessRuntimeTestSupport.ToolBaseline toolBaseline = seedToolBaseline(store);
    ThreadSnapshot toolActiveSnapshot = runtime.getThreadSnapshot(toolBaseline.threadId());
    assertEquals(1, toolActiveSnapshot.toolCallCount());

    // 2. 构造一个普通的纯文本已完成回合
    ThreadSnapshot completedSnapshot = completedTurnSnapshot(baseline);
    assertEquals(1, completedSnapshot.turnCount());
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
        new EntryPath(List.of(base.entryPath().head(), turnStart, userMsg, assistantMsg, turnEnd));
    return new ThreadSnapshot(
        base.thread().advanceHead(turnEndId, T5),
        completedPath,
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
