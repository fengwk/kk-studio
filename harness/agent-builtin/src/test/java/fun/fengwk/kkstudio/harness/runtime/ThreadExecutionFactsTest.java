package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.approveTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.parkForInput;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedAskUserBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedContinuationChain;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedTerminalModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnEndEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.unknownTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * {@link ThreadSnapshot#executionFacts()} 的统一执行事实投影测试。
 *
 * <p>全部用例都经真实 {@link InMemoryHarnessStore} 种子与 {@link HarnessRuntime#getThreadSnapshot} 读取，命中真实
 * {@code ThreadContextClassifier} 分支：排队命令、Model 活动/终态、工具 READY/RUNNING/等待/UNKNOWN 及混合、 owner 归属
 * continuation、尾部控制 Entry 与 STOPPED 仍在途。断言直接给出三个正交事实，覆盖全部状态矩阵。
 */
class ThreadExecutionFactsTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void idleThreadHasNoExecutionFacts() {
    // 测试意图：本地空闲且无排队命令时三个事实全为 false。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertEquals(new ThreadExecutionFacts(false, false, false), facts(baseline.threadId()));
  }

  @Test
  void queuedCommandsAloneConstituteWork() {
    // 测试意图：只要存在尚未发动的排队命令即构成工作，与本地上下文无关。
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadState before = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(baseline.threadId()),
            List.of(userMessageCommand(TestIds.id(1), "queued"))),
        AcceptancePreflight.IDENTITY);
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(baseline.threadId()));
  }

  @Test
  void liveModelStatusesConstituteWork() {
    // 测试意图：本 Thread 合法的 READY/RUNNING Model 都是工作，且不引入等待或 UNKNOWN。
    for (ModelInvocationStatus status :
        List.of(ModelInvocationStatus.READY, ModelInvocationStatus.RUNNING)) {
      HarnessRuntimeTestSupport.ModelBaseline baseline = seedModel(store, status);
      assertEquals(
          new ThreadExecutionFacts(true, false, false),
          facts(baseline.threadId()),
          "status=" + status);
    }
  }

  @Test
  void terminalModelAwaitingApplyIsNotWork() {
    // 测试意图：终态但结果尚未物化的 Model 属于正常待 apply 状态，不再构成工作。
    HarnessRuntimeTestSupport.ModelBaseline baseline = seedTerminalModel(store);
    assertEquals(new ThreadExecutionFacts(false, false, false), facts(baseline.threadId()));
  }

  @Test
  void runningToolsConstituteWork() {
    // 测试意图：本地 READY/RUNNING 工具都是工作。
    HarnessRuntimeTestSupport.ToolBaseline ready = seedToolBaseline(store);
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(ready.threadId()));

    HarnessRuntimeTestSupport.ToolBaseline approved = seedToolBaseline(store);
    approveTool(store, approved.toolId());
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(approved.threadId()));

    HarnessRuntimeTestSupport.ToolBaseline running = seedToolBaseline(store);
    approveTool(store, running.toolId());
    markRunningTool(store, running.toolId());
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(running.threadId()));
  }

  @Test
  void waitingApprovalToolIsHumanWaitNotWork() {
    // 测试意图：等待审批的工具只表达人工等待，不构成推进工作。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    assertEquals(new ThreadExecutionFacts(false, true, false), facts(baseline.threadId()));
  }

  @Test
  void waitingInputToolIsHumanWaitNotWork() {
    // 测试意图：ask_user 冻结等待用户回答同样只表达人工等待，不构成推进工作。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    assertEquals(new ThreadExecutionFacts(false, true, false), facts(baseline.threadId()));
  }

  @Test
  void unknownToolOutcomeIsUncertainNotWork() {
    // 测试意图：唯一工具已收敛为 UNKNOWN 时只报告不确定结果，不构成工作也不构成人工等待。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    unknownTool(store, baseline.toolId());
    assertEquals(new ThreadExecutionFacts(false, false, true), facts(baseline.threadId()));
  }

  @Test
  void terminalCancelledToolsAreNotWork() {
    // 测试意图：全部 terminal CANCELLED 且 outcome 尚未物化是待 apply 状态，三个事实全为 false。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    cancelTool(store, baseline);
    assertEquals(new ThreadExecutionFacts(false, false, false), facts(baseline.threadId()));
  }

  @Test
  void mixedToolStatusesProjectOrthogonally() {
    // 测试意图：同一批兄弟工具可同时给出工作、人工等待与 UNKNOWN 三个正交事实。
    HarnessRuntimeTestSupport.MultiToolBaseline baseline = seedToolBaseline(store, 3);
    approveTool(store, baseline.toolIds().get(0));
    markRunningTool(store, baseline.toolIds().get(0));
    setWaitingApproval(store, baseline.toolIds().get(1));
    unknownTool(store, baseline.toolIds().get(2));
    assertEquals(new ThreadExecutionFacts(true, true, true), facts(baseline.threadId()));
  }

  @Test
  void stoppedControlDoesNotHideInFlightModelOrTool() {
    // 测试意图：持久 STOPPED 与 status 正交；外部 Model/Tool 仍在途时 status 为 RUNNING，且工作事实仍为 true，停止方据此判断未收敛。
    HarnessRuntimeTestSupport.ModelBaseline model = seedModel(store, ModelInvocationStatus.RUNNING);
    markControl(model.threadId(), ThreadExecutionControl.STOPPED);
    assertEquals(
        ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(model.threadId()).runtimeStatus());
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(model.threadId()));

    HarnessRuntimeTestSupport.ToolBaseline tool = seedToolBaseline(store);
    approveTool(store, tool.toolId());
    markRunningTool(store, tool.toolId());
    markControl(tool.threadId(), ThreadExecutionControl.STOPPED);
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(tool.threadId()));
  }

  @Test
  void ownedContinuationIsWork() {
    // 测试意图：head 为 continueModel=true 且归属本 Thread 的 TURN_END 时构成续写工作。
    HarnessRuntimeTestSupport.ContinuationBaseline baseline = seedContinuationChain(store, true);
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(baseline.threadId()));
  }

  @Test
  void continuationNotAtHeadIsNotWork() {
    // 测试意图：continueModel TURN_END 不在当前 head 路径上时不承担续写义务。
    HarnessRuntimeTestSupport.ContinuationBaseline baseline = seedContinuationChain(store, false);
    assertEquals(new ThreadExecutionFacts(false, false, false), facts(baseline.threadId()));
  }

  @Test
  void foreignContinuationIsNotLocalWork() {
    // 测试意图：被引用 TURN_START 的 ownerThreadId 不属于本 Thread 时，该 continuation 义务不归本 Thread。
    UUID foreignOwner = TestIds.id(900);
    UUID threadId = seedForeignContinuation(foreignOwner);
    assertEquals(new ThreadExecutionFacts(false, false, false), facts(threadId));
  }

  @Test
  void trailingControlEntryDoesNotHideOwnedContinuation() {
    // 测试意图：回合之间的控制 Entry（settings 快照）append 在 TURN_END 之后时，仍须看到其之前的续写义务。
    UUID threadId = seedOwnedContinuationWithTrailingSettings();
    assertEquals(new ThreadExecutionFacts(true, false, false), facts(threadId));
  }

  private ThreadExecutionFacts facts(UUID threadId) {
    return runtime.getThreadSnapshot(threadId).executionFacts();
  }

  /** 在真实 Session 上种一条 owner 属于另一 Thread 的 continueModel continuation，本 Thread head 指向该 TURN_END。 */
  private UUID seedForeignContinuation(UUID foreignOwnerThreadId) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID turnStartId = tx.nextId();
          UUID userId = tx.nextId();
          UUID assistantId = tx.nextId();
          UUID turnEndId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartId, sessionId, rootId, T3, foreignOwnerThreadId));
          tx.insertEntry(userMessageEntry(userId, sessionId, turnStartId, T3));
          tx.insertEntry(assistantEntry(assistantId, sessionId, userId, T3));
          tx.insertEntry(turnEndEntry(turnEndId, sessionId, assistantId, T3, turnStartId, true));
          tx.insertThread(thread(threadId, sessionId, turnEndId));
          return threadId;
        });
  }

  /** 本 Thread 拥有续写义务，但在 TURN_END 之后又 append 了 SETTINGS 控制 Entry 并把 head 推进到它。 */
  private UUID seedOwnedContinuationWithTrailingSettings() {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID turnStartId = tx.nextId();
          UUID userId = tx.nextId();
          UUID assistantId = tx.nextId();
          UUID turnEndId = tx.nextId();
          UUID settingsId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartId, sessionId, rootId, T3, threadId));
          tx.insertEntry(userMessageEntry(userId, sessionId, turnStartId, T3));
          tx.insertEntry(assistantEntry(assistantId, sessionId, userId, T3));
          tx.insertEntry(turnEndEntry(turnEndId, sessionId, assistantId, T3, turnStartId, true));
          Entry settingsEntry =
              new Entry(
                  settingsId, sessionId, turnEndId, new SettingsPayload(settings(), threadId), T3);
          tx.insertEntry(settingsEntry);
          tx.insertThread(thread(threadId, sessionId, settingsId));
          return threadId;
        });
  }

  /** 设置 Thread 的持久执行控制（种子 fixture 默认 RUNNABLE）。 */
  private void markControl(UUID threadId, ThreadExecutionControl control) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(thread.changeExecutionControl(control, T5));
          return null;
        });
  }
}
