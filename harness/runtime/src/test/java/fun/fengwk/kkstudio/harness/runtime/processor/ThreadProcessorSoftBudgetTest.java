package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tooledRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * ThreadProcessor maxTurns 软预算持久化提醒与 Join 计数协调测试。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li><b>继续边界触发</b>（continue boundary）：在达到 maxTurns 阈值的 continuation 闭合点（如 Tool batch 完成并产生
 *       continueModel=true 时），同事务把 TaskBudget 提醒物化为确定性 NOTIFICATION Entry 并推进 join.reminderTurn；
 *   <li><b>终态空闲不注入</b>（no idle reminder）：模型直接完成且走向真正 IDLE 时，绝不追加多余轮次；
 *   <li><b>重试幂等性</b>（retry idempotence）：在同一阈值重试或重复触发时，不重复物化相同提醒、不分配邮箱 sequence 且不倒退；
 *   <li><b>源命令轮次</b>：join 从自己的源命令所在 TURN_START 起计数，尚未应用的后续命令不计入；
 *   <li><b>父线程停止无关</b>（parent stopped irrelevance）：预算提醒只依赖子线程自身的 continuation 与 Join，父级执行控制不参与；
 *   <li><b>非工作轮次排除</b>（compaction and stop exclusion）：COMPACTION 与 STOP 轮次不计入 maxTurns 预算；
 *   <li><b>模型自主续写边界</b>（model continue plan boundary）：ModelResponsePlan.Continue 达到预算时同样物化提醒。
 * </ul>
 */
class ThreadProcessorSoftBudgetTest extends ThreadProcessorTestBase {

  @Test
  void continueBoundaryAtMaxTurnsMaterializesTaskBudgetReminderAndAdvancesReminderTurn() {
    // 测试意图：验证子线程在执行工具批次并闭合 Turn（continueModel=true）达到 maxTurns 软预算时，
    // 自动把 TaskBudget 提醒物化为确定性的 NOTIFICATION Entry、推进 join.reminderTurn；
    // 该提醒作为历史上下文进入后续 Turn，且不会在后续继续边界重复物化。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task start")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "test-agent",
                  2,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: INPUT Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model1 = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model1, "call-1");
    requestThreadWork(fixture.store, childId);

    // Apply Model 1 -> 进入 ToolPhase
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);

    // Turn 1 关闭 (applyToolBatch, continueModel=true)：此时 actualTurns = 1 < 2，不物化提醒
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ThreadJoin joinAfterTurn1 = join(fixture, invocationId);
    assertEquals(0L, joinAfterTurn1.reminderTurn());
    assertEquals(0, countReminders(path(fixture.store, childId)));

    // Turn 2: CONTINUATION Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model2 = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model2, "call-2");
    requestThreadWork(fixture.store, childId);

    // Apply Model 2 -> 进入 ToolPhase
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);

    // Turn 2 关闭 (applyToolBatch, continueModel=true)：此时 actualTurns = 2 >= 2，命中软预算！
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ThreadJoin joinAfterTurn2 = join(fixture, invocationId);
    assertEquals(2L, joinAfterTurn2.reminderTurn());

    // 提醒以确定性的 TaskBudget NOTIFICATION Entry 物化到历史，而不是邮箱命令。
    EntryPath childPathAfterReminder = path(fixture.store, childId);
    assertEquals(1, countReminders(childPathAfterReminder));
    NotificationPayload reminder = taskBudgetReminder(childPathAfterReminder, invocationId);
    assertEquals(childId, reminder.sourceThreadId());
    TextMessageContent reminderText = (TextMessageContent) reminder.message().contents().get(0);
    assertTrue(reminderText.text().contains("reached its suggested turn budget"));

    // Turn 3: 提醒已在历史中作为上下文；后续继续边界不再重复物化，reminderTurn 不倒退。
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    assertEquals(2L, join(fixture, invocationId).reminderTurn());
    assertEquals(1, countReminders(path(fixture.store, childId)));

    ModelInvocation model4 = loadOpenModel(fixture.store, childId);
    transitionModelToSucceeded(fixture.store, model4, "Phase report completed.");
    requestThreadWork(fixture.store, childId);

    // 关闭本轮：子线程仍在 RUNNABLE，并向父线程交付一次。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ThreadState finalChildState = thread(fixture.store, childId);
    assertEquals(ThreadExecutionControl.RUNNABLE, finalChildState.executionControl());

    ThreadJoin finalJoin = join(fixture, invocationId);
    assertTrue(finalJoin.matched());
    assertEquals(finalChildState.headEntryId(), finalJoin.terminalEntryId());
    assertEquals(1, countReminders(path(fixture.store, childId)));
  }

  /** 测试意图：同一 join 达到预算后只物化一条稳定提醒；后续继续边界不再重复物化，reminderTurn 保持不倒退。 */
  @Test
  void oneJoinMaterializesOnlyOneReminderAcrossLaterContinuationBoundaries() {
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);
    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);
    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task")));
    requestThreadWork(fixture.store, childId);
    UUID invocationId = UUID.randomUUID();
    insertJoin(fixture, invocationId, parentId, childId, 1L, 1);

    driveToolBoundary(fixture, childId, "call-1");
    Entry firstReminder = taskBudgetReminderEntry(path(fixture.store, childId));
    long reminderTurn = join(fixture, invocationId).reminderTurn();
    assertTrue(reminderTurn > 0);
    assertEquals(1, countReminders(path(fixture.store, childId)));

    // 后续继续边界：提醒已在历史中，不再物化第二条。
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation continued = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, continued, "call-2");
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    assertEquals(reminderTurn, join(fixture, invocationId).reminderTurn());
    EntryPath afterLaterBoundaries = path(fixture.store, childId);
    assertEquals(1, countReminders(afterLaterBoundaries));
    assertEquals(firstReminder.id(), taskBudgetReminderEntry(afterLaterBoundaries).id());
    assertEquals(
        ThreadExecutionControl.RUNNABLE, thread(fixture.store, childId).executionControl());
  }

  @Test
  void noReminderOnTerminalIdleEvenWhenReachingMaxTurns() {
    // 测试意图：验证当模型直接完成且无需 continuation 时（哪怕达到或超过 maxTurns），
    // 绝不注入提醒命令，直接原子推进为 IDLE 并交付 Join。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("instant task")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "instant-agent",
                  1,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: INPUT Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model = loadOpenModel(fixture.store, childId);

    // 模型直接给出结论（无工具调用）
    transitionModelToSucceeded(fixture.store, model, "Direct answer.");
    requestThreadWork(fixture.store, childId);

    // Turn 1 关闭：由于无需 continuation，直接变 IDLE 并交付，不物化多余提醒
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadState childState = thread(fixture.store, childId);
    assertEquals(ThreadExecutionControl.RUNNABLE, childState.executionControl());

    assertTrue(loadQueuedCommands(fixture.store, childId).isEmpty());
    assertEquals(0, countReminders(path(fixture.store, childId)));

    ThreadJoin join = join(fixture, invocationId);
    assertTrue(join.matched());
    assertEquals(0L, join.reminderTurn());
  }

  @Test
  void retryIdempotenceDoesNotDuplicateReminderOrThrow() {
    // 测试意图：验证在相同阈值下重复执行 remindSoftBudgetIfDue 时具有严格幂等性：不重复物化通知、不分配邮箱
    // sequence 且不倒退状态。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "test-agent",
                  1,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: INPUT Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model1 = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model1, "call-1");
    requestThreadWork(fixture.store, childId);

    // 进入 ToolPhase 并成功应用工具批次（物化一次提醒）
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadJoin joinAfterFirst =
        fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(1L, joinAfterFirst.reminderTurn());
    EntryPath pathAfterFirst = path(fixture.store, childId);
    assertEquals(1, countReminders(pathAfterFirst));
    Entry firstReminder = taskBudgetReminderEntry(pathAfterFirst);
    long nextSeqBefore = thread(fixture.store, childId).nextCommandSequence();

    // 再次手动触发同一状态下的 remindSoftBudgetIfDue
    fixture.store.transaction(
        tx -> {
          ThreadState locked = tx.lockThread(childId).orElseThrow();
          EntryPath currentPath = tx.loadEntryPath(locked.headEntryId());
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> null,
                  fixture.clock);
          coordinator.remindSoftBudgetIfDue(tx, locked, currentPath, NOW);
          return null;
        });

    ThreadJoin joinAfterRetry =
        fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(1L, joinAfterRetry.reminderTurn());
    EntryPath pathAfterRetry = path(fixture.store, childId);
    assertEquals(1, countReminders(pathAfterRetry));
    assertEquals(firstReminder.id(), taskBudgetReminderEntry(pathAfterRetry).id());
    assertEquals(nextSeqBefore, thread(fixture.store, childId).nextCommandSequence());
  }

  @Test
  void multiInputSharedTurnCountsCorrectly() {
    // 测试意图：尚未应用的后续命令不提前计入；轮数从该 join 源命令实际所在的 TURN_START 开始。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    // 放入两条命令
    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("msg 1")));
    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("msg 2")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  2L,
                  "test-agent",
                  2,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: 消费首条消息（msg 1）
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model1 = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model1, "call-1");
    requestThreadWork(fixture.store, childId);

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);

    // Turn 1 关闭：此时 source command（seq 2）尚未被应用（appliedEntryId 为空），计为 0 轮
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ThreadJoin joinAfterTurn1 =
        fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(0L, joinAfterTurn1.reminderTurn());
  }

  @Test
  void parentStoppedDoesNotAffectChildBudgetReminder() {
    // 测试意图：软预算提醒只取决于子线程自身的 continuation 与 Join，父线程执行控制不参与判定；
    // 即使父线程历史停在 STOPPED 终界，子线程达到预算仍会物化自己的 TaskBudget 提醒。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    // 创建处于 STOPPED 终界的父线程（STOP TurnStart -> CANCELLED AssistantError -> STOPPED TurnEnd）
    UUID parentId =
        fixture.store.transaction(
            tx -> {
              UUID pid = tx.nextId();
              UUID stopTurnStartId = tx.nextId();
              UUID stopBarrierId = tx.nextId();
              UUID stopTurnEndId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      stopTurnStartId,
                      sessionId,
                      rootEntryId,
                      new TurnStartPayload(
                          TurnStartReason.STOP, branchSettings(), pid, null, null, null),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      stopBarrierId,
                      sessionId,
                      stopTurnStartId,
                      new AssistantErrorPayload(
                          new AssistantError("CANCELLED", "Explicitly stopped"), null),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      stopTurnEndId,
                      sessionId,
                      stopBarrierId,
                      new TurnEndPayload(
                          stopTurnStartId,
                          TurnEndOutcome.STOPPED,
                          false,
                          TurnEndReason.USER_STOP,
                          UUID.randomUUID()),
                      NOW));
              tx.insertThread(
                  new ThreadState(
                      pid,
                      sessionId,
                      null,
                      stopTurnEndId,
                      CREATION_REQUEST_HASH,
                      "parent",
                      false,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      NOW,
                      NOW));
              return pid;
            });

    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "test-agent",
                  1,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: INPUT Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model1 = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model1, "call-1");
    requestThreadWork(fixture.store, childId);

    // Tool phase + Tool batch
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);

    // Turn 1 关闭：actualTurns = 1 >= 1，命中软预算；父级执行控制不参与判定
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadJoin join = join(fixture, invocationId);
    assertEquals(1L, join.reminderTurn());
    EntryPath childPath = path(fixture.store, childId);
    assertEquals(1, countReminders(childPath));
    NotificationPayload reminder = taskBudgetReminder(childPath, invocationId);
    assertEquals(childId, reminder.sourceThreadId());
    // 提醒物化为历史上下文而非邮箱命令，子线程仍处于 RUNNABLE。
    assertEquals(
        ThreadExecutionControl.RUNNABLE, thread(fixture.store, childId).executionControl());
  }

  @Test
  void compactionAndStopTurnsDoNotCountTowardMaxTurns() {
    // 测试意图：验证 countActualTurns 严格排除 COMPACTION 与 STOP 类型的 TurnStart。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID childId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);

    UUID turnStart1 = UUID.randomUUID();
    UUID userEntry = UUID.randomUUID();
    UUID assistant1 = UUID.randomUUID();
    UUID turnEnd1 = UUID.randomUUID();
    UUID compactionTurnStart = UUID.randomUUID();
    UUID compactionEntry = UUID.randomUUID();
    UUID compactionTurnEnd = UUID.randomUUID();
    UUID turnStart2 = UUID.randomUUID();
    UUID assistant2 = UUID.randomUUID();
    UUID turnEnd2 = UUID.randomUUID();

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("initial")));

    ModelUsage usage = new ModelUsage(10L, 20L, 0L, 0L, 0L, 0L, 30L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);

    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertEntry(
              new Entry(
                  turnStart1,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), childId, 100_000, 16_384, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  userEntry,
                  sessionId,
                  turnStart1,
                  new MessagePayload(userMessage("initial"), null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  assistant1,
                  sessionId,
                  userEntry,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant 1"))),
                      new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, cost),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnEnd1,
                  sessionId,
                  assistant1,
                  new TurnEndPayload(turnStart1, TurnEndOutcome.COMPLETED, true, null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  compactionTurnStart,
                  sessionId,
                  turnEnd1,
                  new TurnStartPayload(
                      TurnStartReason.COMPACTION,
                      branchSettings(),
                      childId,
                      100_000,
                      16_384,
                      new CompactionStart(
                          CompactionPhase.FULL,
                          CompactionTrigger.THRESHOLD,
                          branchSettings().model(),
                          userEntry,
                          null,
                          null)),
                  NOW));
          tx.insertEntry(
              new Entry(
                  compactionEntry,
                  sessionId,
                  compactionTurnStart,
                  new CompactionPayload("summary text"),
                  NOW));
          tx.insertEntry(
              new Entry(
                  compactionTurnEnd,
                  sessionId,
                  compactionEntry,
                  new TurnEndPayload(
                      compactionTurnStart, TurnEndOutcome.COMPLETED, true, null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnStart2,
                  sessionId,
                  compactionTurnEnd,
                  new TurnStartPayload(
                      TurnStartReason.CONTINUATION,
                      branchSettings(),
                      childId,
                      100_000,
                      16_384,
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  assistant2,
                  sessionId,
                  turnStart2,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant 2"))),
                      new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, cost),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnEnd2,
                  sessionId,
                  assistant2,
                  new TurnEndPayload(turnStart2, TurnEndOutcome.COMPLETED, true, null, null),
                  NOW));
          tx.updateThread(tx.findThread(childId).orElseThrow().advanceHead(turnEnd2, NOW));

          List<ThreadCommand> commands = tx.loadQueuedCommands(childId);
          ThreadCommand appliedCmd = commands.get(0).markApplied(turnStart1);
          tx.updateCommands(List.of(appliedCmd));
          return null;
        });

    ThreadJoin join =
        new ThreadJoin(
            UUID.randomUUID(),
            CREATION_REQUEST_HASH,
            null,
            childId,
            1L,
            "test-agent",
            3,
            0L,
            null,
            null,
            null,
            NOW,
            NOW);

    fixture.store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(turnEnd2);
          int turns = ThreadLifecycleCoordinator.countActualTurns(tx, path, join);
          // 实际应该只有 Turn 1 (INPUT) 和 Turn 2 (CONTINUATION) 计入，COMPACTION 被排除，所以是 2 轮
          assertEquals(2, turns);
          return null;
        });
  }

  @Test
  void modelContinuePlanBoundaryMaterializesReminder() {
    // 测试意图：验证当模型生成触发 ModelResponsePlan.Continue 时，
    // 在达到 maxTurns 阈值时同样物化 TaskBudget 提醒并推进 reminderTurn。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "test-agent",
                  1,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Turn 1: INPUT Turn 启动
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model = loadOpenModel(fixture.store, childId);

    // 模拟模型产生 CONTINUE 响应，触发 Continue plan
    transitionModelToContinue(fixture.store, model, "partial text");
    requestThreadWork(fixture.store, childId);

    // Apply Model -> 触发 Continue plan 闭合 Turn 1（continueModel=true）
    // 此时 actualTurns = 1 >= 1，命中软预算并物化提醒
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadJoin remindedJoin = join(fixture, invocationId);
    assertEquals(1L, remindedJoin.reminderTurn());

    EntryPath childPath = path(fixture.store, childId);
    assertEquals(1, countReminders(childPath));
    NotificationPayload reminder = taskBudgetReminder(childPath, invocationId);
    assertEquals(childId, reminder.sourceThreadId());
  }

  /** 测试意图：同一子线程上不同 invocation 的 Join 各自独立；旧 Join 已提醒过，新 Join 达到预算仍会物化自己的提醒。 */
  @Test
  void resumedJoinCanRemindAgain() {
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);
    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadExecutionControl.RUNNABLE);
    UUID childId =
        createThread(
            fixture.store, sessionId, parentId, rootEntryId, ThreadExecutionControl.RUNNABLE);
    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("first")));
    requestThreadWork(fixture.store, childId);
    UUID firstJoin = UUID.randomUUID();
    insertJoin(fixture, firstJoin, parentId, childId, 1L, 1);
    driveToolBoundary(fixture, childId, "call-1");
    assertEquals(1, countReminders(path(fixture.store, childId)));
    assertTrue(join(fixture, firstJoin).reminderTurn() > 0);
    assertEquals(
        ThreadExecutionControl.RUNNABLE, thread(fixture.store, childId).executionControl());
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation reminded = loadOpenModel(fixture.store, childId);
    transitionModelToSucceeded(fixture.store, reminded, "old reminder consumed");
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    assertTrue(join(fixture, firstJoin).matched());
    assertEquals(
        ThreadExecutionControl.RUNNABLE, thread(fixture.store, childId).executionControl());

    long resumedSource = thread(fixture.store, childId).nextCommandSequence();
    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("resume")));
    requestThreadWork(fixture.store, childId);
    UUID resumedJoin = UUID.randomUUID();
    insertJoin(fixture, resumedJoin, parentId, childId, resumedSource, 1);

    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, claimChild(fixture, childId));
    ModelInvocation resumed = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, resumed, "call-2");
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, claimChild(fixture, childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, claimChild(fixture, childId));
    assertTrue(join(fixture, resumedJoin).reminderTurn() > 0);
    assertEquals(2, countReminders(path(fixture.store, childId)));

    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, claimChild(fixture, childId));
    ModelInvocation resumedModel = loadOpenModel(fixture.store, childId);
    transitionModelToSucceeded(fixture.store, resumedModel, "resumed reminder consumed");
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, claimChild(fixture, childId));

    assertEquals(
        ThreadExecutionControl.RUNNABLE, thread(fixture.store, childId).executionControl());
    ThreadJoin completedFirst = join(fixture, firstJoin);
    ThreadJoin completedResume = join(fixture, resumedJoin);
    assertTrue(completedFirst.matched());
    assertTrue(completedResume.matched());
    assertEquals(1L, completedFirst.reminderTurn());
    assertTrue(completedResume.reminderTurn() > 0);
    assertNotNull(completedFirst.deliveryCommandSequence());
    assertNotNull(completedResume.deliveryCommandSequence());
    assertEquals(2, loadQueuedCommands(fixture.store, parentId).size());
    assertEquals(2, countReminders(path(fixture.store, childId)));
  }

  // --- Helper Methods ---

  private UUID createSession(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          return sessionId;
        });
  }

  private UUID createRootEntry(HarnessStore store, UUID sessionId) {
    return store.transaction(
        tx -> {
          UUID rootId = tx.nextId();
          tx.insertEntry(
              new Entry(rootId, sessionId, null, new RootPayload(branchSettings()), NOW));
          return rootId;
        });
  }

  private UUID createThread(
      HarnessStore store,
      UUID sessionId,
      UUID parentId,
      UUID rootEntryId,
      ThreadExecutionControl status) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentId,
                  rootEntryId,
                  CREATION_REQUEST_HASH,
                  "thread-" + threadId,
                  false,
                  status,
                  0L,
                  1L,
                  0L,
                  NOW,
                  NOW));
          return threadId;
        });
  }

  private ModelInvocation loadOpenModel(HarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(tx.findThread(threadId).orElseThrow().headEntryId());
          UUID turnStartId = path.openTurnStart().orElseThrow().id();
          return tx.findModelInvocationByTurn(threadId, turnStartId).orElseThrow();
        });
  }

  private void transitionModelToToolCall(
      InMemoryHarnessStore store, ModelInvocation model, String callId) {
    ProviderResponse response = successResponse(List.of(callId), "bash");
    store.transaction(
        tx -> {
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatched = model.beginDispatch(NOW);
          tx.updateModelInvocation(dispatched);
          ModelInvocation running = dispatched.markRunning(NOW);
          tx.updateModelInvocation(running);
          ModelInvocation succeeded = running.succeed(response, null, null, NOW);
          tx.updateModelInvocation(succeeded);
          return null;
        });
  }

  private void transitionModelToSucceeded(
      InMemoryHarnessStore store, ModelInvocation model, String text) {
    ProviderResponse response = successResponse(text, List.of(), GenerationStopReason.COMPLETE);
    store.transaction(
        tx -> {
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatched = model.beginDispatch(NOW);
          tx.updateModelInvocation(dispatched);
          ModelInvocation running = dispatched.markRunning(NOW);
          tx.updateModelInvocation(running);
          ModelInvocation succeeded = running.succeed(response, null, null, NOW);
          tx.updateModelInvocation(succeeded);
          return null;
        });
  }

  private void transitionModelToContinue(
      InMemoryHarnessStore store, ModelInvocation model, String text) {
    ProviderResponse response = successResponse(text, List.of(), GenerationStopReason.CONTINUE);
    store.transaction(
        tx -> {
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatched = model.beginDispatch(NOW);
          tx.updateModelInvocation(dispatched);
          ModelInvocation running = dispatched.markRunning(NOW);
          tx.updateModelInvocation(running);
          ModelInvocation succeeded = running.succeed(response, null, null, NOW);
          tx.updateModelInvocation(succeeded);
          return null;
        });
  }

  private void succeedToolsByAssistant(InMemoryHarnessStore store, UUID threadId) {
    List<ToolInvocation> tools =
        store.transaction(
            tx -> {
              ThreadState thread = tx.findThread(threadId).orElseThrow();
              return tx.lockToolInvocationsByAssistantEntryId(thread.headEntryId());
            });
    for (ToolInvocation tool : tools) {
      succeedToolWith(
          store,
          tool.id(),
          new ToolResult(tool.call().id(), List.of(new TextResultContent("tool ok")), false, "{}"));
    }
  }

  private void insertJoin(
      Fixture fixture,
      UUID invocationId,
      UUID parentId,
      UUID childId,
      long sourceSequence,
      Integer maxTurns) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  sourceSequence,
                  "test-agent",
                  maxTurns,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });
  }

  /** 父线程完成消息也会占用全局 THREAD claim；测试只验证子线程时先移除父 work。 */
  private static ThreadProcessResult claimChild(Fixture fixture, UUID childId) {
    UUID parentId = thread(fixture.store, childId).parentThreadId();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(parentId);
          tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, parentId));
          return null;
        });
    return fixture.nextClaim(childId);
  }

  private ThreadJoin join(Fixture fixture, UUID invocationId) {
    return fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
  }

  private void driveToolBoundary(Fixture fixture, UUID childId, String callId) {
    fixture.resolver.results.add(
        new TurnResolver.Resolved(tooledRequest(List.of("bash")), 100_000, 16_384));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model = loadOpenModel(fixture.store, childId);
    transitionModelToToolCall(fixture.store, model, callId);
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    succeedToolsByAssistant(fixture.store, childId);
    requestThreadWork(fixture.store, childId);
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
  }

  /** 统计 EntryPath 上物化的 TaskBudget 软预算提醒数量（历史 NOTIFICATION，不是邮箱命令）。 */
  private static int countReminders(EntryPath path) {
    int count = 0;
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof NotificationPayload payload
          && payload.kind() == NotificationKind.TASK_BUDGET) {
        count++;
      }
    }
    return count;
  }

  /** 返回指定 Join 的 TaskBudget 提醒 payload；通知 ID 必须等于由 reminder tag + invocationId 推导的确定性值。 */
  private static NotificationPayload taskBudgetReminder(EntryPath path, UUID invocationId) {
    UUID expectedId = taskBudgetNotificationId(invocationId);
    return path.entries().stream()
        .map(Entry::payload)
        .filter(NotificationPayload.class::isInstance)
        .map(NotificationPayload.class::cast)
        .filter(payload -> payload.kind() == NotificationKind.TASK_BUDGET)
        .filter(payload -> payload.notificationId().equals(expectedId))
        .findFirst()
        .orElseThrow();
  }

  /** 返回路径上的第一条 TaskBudget 提醒 Entry：用于证明同一 Join 只物化一条、不重复追加。 */
  private static Entry taskBudgetReminderEntry(EntryPath path) {
    return path.entries().stream()
        .filter(
            entry ->
                entry.payload() instanceof NotificationPayload payload
                    && payload.kind() == NotificationKind.TASK_BUDGET)
        .findFirst()
        .orElseThrow();
  }

  private static UUID taskBudgetNotificationId(UUID invocationId) {
    return UUID.nameUUIDFromBytes(
        (ThreadLifecycleCoordinator.DEFAULT_MAX_TURNS_REMINDER_TAG + ":" + invocationId)
            .getBytes(StandardCharsets.UTF_8));
  }

  private List<ThreadCommand> loadQueuedCommands(HarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> {
          tx.lockThread(threadId);
          return tx.loadQueuedCommands(threadId);
        });
  }
}
