package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台侧 task 委派接受的回归基线：权限校验、稳定身份、幂等回放、忙碌子线程追加与错误收敛。
 *
 * <p>测试意图：Runtime 用窄接口 mock 表达；join 身份、命令形状与错误类型是本类的真实职责，而额度/深度/匹配/交付属于 Runtime，不在本测试断言范围。
 */
class SubagentTaskRunnerTest {

  private static final String AGENT = "alpha";
  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private static final ModelSelection MODEL = new ModelSelection("model", "v1");
  private static final HarnessThreadChangeSource NO_THREAD_CHANGES =
      (threadId, onChange) -> () -> {};

  private final UUID parentThreadId = UUID.randomUUID();
  private final UUID invocationId = UUID.randomUUID();
  private final UUID parentHeadEntryId = UUID.randomUUID();
  private final BranchSettings target = new BranchSettings(AGENT, BuiltinExecution.of(MODEL), null);

  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final AgentBranchSettingsMaterializer materializer =
      mock(AgentBranchSettingsMaterializer.class);

  private SubagentTaskRunner runner(SubagentConfig config) {
    return new SubagentTaskRunner(
        () -> runtime,
        NO_THREAD_CHANGES,
        new AgentTaskService(provider(runtime)),
        materializer,
        () -> config);
  }

  private static SubagentTaskAcceptance acceptAsync(
      SubagentTaskRunner runner, SubagentTaskRequest request) {
    return runner.run(request, true, ignored -> {}).acceptance();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<HarnessRuntime> provider(HarnessRuntime runtime) {
    ObjectProvider<HarnessRuntime> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(runtime);
    return provider;
  }

  private static SubagentConfig config() {
    return new SubagentConfig(3, 4, 0, 10);
  }

  /** 父快照：存在冻结的 Model 调用，允许 AGENT 委派，environment 为 env。 */
  private void stubParent(List<String> allowedSubagents) {
    ThreadSnapshot parent = mock(ThreadSnapshot.class);
    ToolInvocation tool = mock(ToolInvocation.class);
    Entry turnStartEntry = mock(Entry.class);
    TurnStartPayload turnStart = mock(TurnStartPayload.class);
    when(parent.thread())
        .thenReturn(
            new ThreadState(
                parentThreadId,
                UUID.randomUUID(),
                null,
                parentHeadEntryId,
                "0".repeat(64),
                "main",
                parentThreadId,
                true,
                ThreadKind.USER,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                5L,
                3L,
                NOW,
                NOW));
    when(parent.toolSiblings()).thenReturn(List.of(tool));
    when(tool.id()).thenReturn(invocationId);
    EntryCurrentState parentState = mock(EntryCurrentState.class);
    when(parent.entryState()).thenReturn(parentState);
    List<SubagentBinding> allowed =
        allowedSubagents.stream().map(name -> new SubagentBinding(name, name)).toList();
    when(turnStartEntry.payload()).thenReturn(turnStart);
    when(turnStart.capabilities()).thenReturn(new TurnCapabilities(allowed));
    when(parentState.openTurnStart()).thenReturn(Optional.of(turnStartEntry));
    when(runtime.getThreadSnapshot(parentThreadId)).thenReturn(parent);
    when(materializer.materialize(AGENT)).thenReturn(target);
  }

  private static AcceptedCommands accepted(UUID sessionId, UUID threadId, boolean replayed) {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn(sessionId);
    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(accepted.session()).thenReturn(session);
    when(accepted.thread()).thenReturn(thread);
    when(accepted.replayed()).thenReturn(replayed);
    return accepted;
  }

  private SubagentTaskRequest request(Integer maxTurns, UUID resumeThreadId) {
    return new SubagentTaskRequest(
        invocationId, parentThreadId, "do the work", AGENT, maxTurns, resumeThreadId);
  }

  @Test
  void synchronousRunWaitsForJoinChangeAndReturnsFrozenReceipt() throws Exception {
    stubParent(List.of(AGENT));
    UUID sessionId = UUID.randomUUID();
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    AcceptedCommands accepted = accepted(sessionId, childThreadId, false);
    when(runtime.spawn(any(), any())).thenReturn(accepted);
    SubagentTaskRequest request = request(null, null);
    UUID terminalId = UUID.randomUUID();
    ThreadJoin pending =
        new ThreadJoin(
            invocationId,
            SubagentTaskRunner.requestHash(request),
            parentThreadId,
            childThreadId,
            1L,
            AGENT,
            10,
            0L,
            null,
            null,
            null,
            NOW,
            NOW,
            JoinDelivery.SETTLE_TOOL_RESULT,
            null);
    ThreadJoin matched = pending.match(terminalId, null, NOW.plusSeconds(1));
    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            invocationId,
            childThreadId,
            AGENT,
            ThreadJoinOutcome.COMPLETED,
            "do the work",
            "done",
            null,
            null);
    when(runtime.findJoin(invocationId))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(pending), Optional.of(matched));
    when(runtime.projectJoinReceipt(invocationId)).thenReturn(Optional.of(receipt));
    CountDownLatch subscribed = new CountDownLatch(1);
    AtomicReference<Runnable> wake = new AtomicReference<>();
    HarnessThreadChangeSource changes =
        (threadId, onChange) -> {
          assertEquals(childThreadId, threadId);
          wake.set(onChange);
          subscribed.countDown();
          return () -> {};
        };
    SubagentTaskRunner runner =
        new SubagentTaskRunner(
            () -> runtime,
            changes,
            new AgentTaskService(provider(runtime)),
            materializer,
            SubagentTaskRunnerTest::config);

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch acceptedSignal = new CountDownLatch(1);
      Future<SubagentTaskResult> running =
          workers.submit(() -> runner.run(request, false, ignored -> acceptedSignal.countDown()));
      if (!acceptedSignal.await(1, TimeUnit.SECONDS)) {
        running.get();
        throw new AssertionError("task was neither accepted nor failed");
      }
      assertTrue(subscribed.await(1, TimeUnit.SECONDS));
      assertFalse(running.isDone());
      wake.get().run();
      assertEquals(receipt, running.get(1, TimeUnit.SECONDS).receipt());
    }
    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture(), any());
    assertEquals(JoinDelivery.SETTLE_TOOL_RESULT, spawn.getValue().delivery());
    assertEquals(parentHeadEntryId, spawn.getValue().expectedParentHeadEntryId());
  }

  @Test
  void newDelegationAcceptsAtomicallyWithStableIdentityAndAdmissionLimits() {
    stubParent(List.of(AGENT));
    UUID sessionId = UUID.randomUUID();
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    AcceptedCommands accepted = accepted(sessionId, childThreadId, false);
    when(runtime.spawn(any(), any())).thenReturn(accepted);

    SubagentTaskAcceptance acceptance = acceptAsync(runner(config()), request(null, null));

    assertEquals(sessionId, acceptance.childSessionId());
    assertEquals(childThreadId, acceptance.childThreadId());
    assertFalse(acceptance.replayed());
    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture(), any());

    SpawnRequest accepted1 = spawn.getValue();
    assertEquals(childThreadId, accepted1.threadId());
    assertEquals(
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/session/"),
        accepted1.sessionId());
    // 执行父关系由 Runtime 建立：请求携带父 Thread，ROOT settings 只由本次物化结果决定。
    assertEquals(parentThreadId, accepted1.parentThreadId());
    assertEquals(target, accepted1.settings());
    // 模型发起的子代理：通知父 Thread，票据即 invocation。
    assertEquals(ThreadKind.SUBAGENT, accepted1.kind());
    assertEquals(JoinDelivery.NOTIFY_PARENT, accepted1.delivery());
    assertEquals(invocationId, accepted1.ticketId());
    // 源 prompt 与票据同一批命令：恰好一条可信调用方 CUSTOM_MESSAGE。
    assertEquals(1, accepted1.commands().size());
    assertTrue(accepted1.commands().get(0).payload() instanceof CustomMessageCommandPayload);

    // 未显式给出 max_turns 时用 policy 默认；额度按 config 快照冻结，0 表示不设树级上限。
    assertEquals(parentHeadEntryId, accepted1.expectedParentHeadEntryId());
    assertEquals(AGENT, accepted1.agent());
    assertEquals(10, accepted1.budget().maxTurns().intValue());
    assertEquals(SubagentTaskRunner.requestHash(request(null, null)), accepted1.requestHash());
    assertEquals(3, accepted1.budget().maxDepth());
    assertEquals(4, accepted1.budget().maxConcurrentChildren());
    assertEquals(Integer.MAX_VALUE, accepted1.budget().maxConcurrentThreads());
  }

  @Test
  void explicitMaxTurnsIsFrozenAndCommandKeysAreDeterministic() {
    stubParent(List.of(AGENT));
    AcceptedCommands accepted = accepted(UUID.randomUUID(), UUID.randomUUID(), false);
    when(runtime.spawn(any(), any())).thenReturn(accepted);

    acceptAsync(runner(config()), request(7, null));

    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture(), any());
    assertEquals(7, command.getValue().budget().maxTurns().intValue());
    // 命令幂等键由 invocation + 槽位稳定派生，重放才能精确命中同一批命令。
    UUID expectedKey =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/command/0/");
    assertEquals(expectedKey, command.getValue().commands().get(0).idempotencyKey());
  }

  @Test
  void acceptedInvocationReplaysWithoutTouchingRuntimeAcceptance() {
    // 已接受的回放不依赖父调用是否仍在执行：父快照已不可用时也必须返回既有子身份。
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    UUID childSessionId = UUID.randomUUID();
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    when(child.thread())
        .thenReturn(
            new ThreadState(
                childThreadId,
                childSessionId,
                parentThreadId,
                UUID.randomUUID(),
                "0".repeat(64),
                "child",
                parentThreadId,
                false,
                ThreadKind.SUBAGENT,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                2L,
                1L,
                NOW,
                NOW));
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(null, null)),
                    AGENT,
                    10)));

    SubagentTaskAcceptance acceptance = acceptAsync(runner(config()), request(null, null));

    assertTrue(acceptance.replayed());
    assertEquals(childThreadId, acceptance.childThreadId());
    assertEquals(childSessionId, acceptance.childSessionId());
    verify(runtime, never()).spawn(any(), any());
    verify(runtime, never()).getThreadSnapshot(parentThreadId);
  }

  @Test
  void replayedInvocationWithAnyDifferentFrozenFactIsRejected() {
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(7, null)),
                    AGENT,
                    7)));

    List<SubagentTaskRequest> otherDelegations =
        List.of(
            // 另一个父 Thread 想复用同一 invocation。
            new SubagentTaskRequest(invocationId, UUID.randomUUID(), "do the work", AGENT, 7, null),
            // 不同 prompt / agent / max_turns 都不是同一次委派。
            new SubagentTaskRequest(invocationId, parentThreadId, "different", AGENT, 7, null),
            new SubagentTaskRequest(invocationId, parentThreadId, "do the work", "beta", 7, null),
            new SubagentTaskRequest(invocationId, parentThreadId, "do the work", AGENT, 9, null),
            new SubagentTaskRequest(invocationId, parentThreadId, "do the work", AGENT, null, null),
            // 目标是继续另一条子 Thread。
            new SubagentTaskRequest(
                invocationId, parentThreadId, "do the work", AGENT, 7, UUID.randomUUID()));

    for (SubagentTaskRequest other : otherDelegations) {
      SubagentTaskRejectedException rejected =
          assertThrows(
              SubagentTaskRejectedException.class, () -> acceptAsync(runner(config()), other));
      assertTrue(rejected.getMessage().contains("different delegation"), rejected.getMessage());
    }
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void defaultMaxTurnsChangeDoesNotRewriteAnAcceptedReceipt() {
    // policy 默认 maxTurns 在两次调用之间变化时，同 invocation 仍必须回放既有接受结果而不是改写回执。
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(null, null)),
                    AGENT,
                    10)));
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    when(child.thread())
        .thenReturn(
            new ThreadState(
                childThreadId,
                UUID.randomUUID(),
                parentThreadId,
                UUID.randomUUID(),
                "0".repeat(64),
                "child",
                parentThreadId,
                false,
                ThreadKind.SUBAGENT,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                2L,
                1L,
                NOW,
                NOW));
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);

    // 默认值从 10 变成 99：请求 hash 不含运行期默认值，回放仍然成立。
    assertTrue(
        acceptAsync(runner(new SubagentConfig(3, 4, 0, 99)), request(null, null)).replayed());
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix() {
    // resume 不要求前一次 join 已交付，也不要求子线程 idle：只追加 agent/model/prompt，绝不改写既有子环境。
    stubParent(List.of(AGENT));
    UUID childThreadId = UUID.randomUUID();
    ThreadSnapshot child = child(childThreadId, parentThreadId, 9L);
    AcceptedCommands accepted = accepted(UUID.randomUUID(), childThreadId, false);
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);
    when(runtime.spawn(any(), any())).thenReturn(accepted);

    SubagentTaskAcceptance acceptance = acceptAsync(runner(config()), request(null, childThreadId));

    assertEquals(childThreadId, acceptance.childThreadId());
    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture(), any());
    assertEquals(childThreadId, command.getValue().threadId());
    assertNull(command.getValue().sessionId());
    assertEquals(9L, command.getValue().continuation().expectedNextCommandSequence());
    List<NewThreadCommand> commands = command.getValue().commands();
    assertEquals(3, commands.size());
    // 续接前缀只由本次目标 settings 决定：SET_AGENT + SET_MODEL + prompt。
    assertEquals(new SetAgentCommandPayload(AGENT), commands.get(0).payload());
    assertEquals(
        new SetExecutionCommandPayload(BuiltinExecution.of(MODEL)), commands.get(1).payload());
    assertTrue(commands.get(2).payload() instanceof CustomMessageCommandPayload);
    // 环境由执行根持有，续接绝不向子线程发送 SET_ENVIRONMENT。
    assertTrue(
        commands.stream().noneMatch(c -> c.payload() instanceof SetEnvironmentCommandPayload),
        commands.toString());
  }

  @Test
  void busyResumeReplayWithChangedAgentIsRejectedInsteadOfRewritingChild() {
    // 同一 invocation 的 busy 续接重放必须命中同一份委派：agent 变更属于另一次委派，
    // 必须拒绝而不是给既有子线程换 agent（resume 的 SET_AGENT 只用于同一委派的重放场景）。
    UUID childThreadId = UUID.randomUUID();
    SubagentTaskRequest original = request(null, childThreadId);
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(original),
                    AGENT,
                    10)));
    SubagentTaskRequest changedAgent =
        new SubagentTaskRequest(
            invocationId, parentThreadId, "do the work", "beta", null, childThreadId);

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class, () -> acceptAsync(runner(config()), changedAgent));

    assertTrue(rejected.getMessage().contains("different delegation"), rejected.getMessage());
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void resumeCursorConflictRetriesWithFreshSnapshot() {
    stubParent(List.of(AGENT));
    UUID childThreadId = UUID.randomUUID();
    UUID firstHead = UUID.randomUUID();
    UUID secondHead = UUID.randomUUID();
    ThreadSnapshot staleChild = child(childThreadId, parentThreadId, firstHead, 9L);
    ThreadSnapshot freshChild = child(childThreadId, parentThreadId, secondHead, 12L);
    AcceptedCommands accepted = accepted(UUID.randomUUID(), childThreadId, false);
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(staleChild).thenReturn(freshChild);
    when(runtime.spawn(any(), any()))
        .thenThrow(DomainException.conflict("STALE_COMMAND_CURSOR", "stale"))
        .thenReturn(accepted);

    SubagentTaskAcceptance acceptance = acceptAsync(runner(config()), request(null, childThreadId));

    assertEquals(childThreadId, acceptance.childThreadId());
    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime, times(2)).spawn(command.capture(), any());
    // 第二次必须使用重新读取的 cursor，而不是第一次的陈旧 cursor。
    assertEquals(secondHead, command.getAllValues().get(1).continuation().expectedHeadEntryId());
  }

  @Test
  void domainCursorConflictRetriesAndOtherDomainErrorsPropagate() {
    // 只能按 conflict + STALE_COMMAND_CURSOR 重试；同 reason 的其他 code 不能转成 tool 拒绝。
    stubParent(List.of(AGENT));
    UUID childThreadId = UUID.randomUUID();
    ThreadSnapshot child = child(childThreadId, parentThreadId, 9L);
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);
    when(runtime.spawn(any(), any()))
        .thenThrow(DomainException.conflict("STALE_COMMAND_CURSOR", "stale"))
        .thenReturn(accepted(UUID.randomUUID(), childThreadId, false));
    assertEquals(
        childThreadId, acceptAsync(runner(config()), request(null, childThreadId)).childThreadId());
    verify(runtime, times(2)).spawn(any(), any());

    DomainException wrongCode =
        DomainException.validationReason("STALE_COMMAND_CURSOR", "not conflict");
    doThrow(wrongCode).when(runtime).spawn(any(), any());
    assertSame(
        wrongCode,
        assertThrows(
            DomainException.class,
            () -> acceptAsync(runner(config()), request(null, childThreadId))));
    DomainException wrongReason = DomainException.conflict("OTHER_MODULE_CONFLICT", "other");
    doThrow(wrongReason).when(runtime).spawn(any(), any());
    assertSame(
        wrongReason,
        assertThrows(
            DomainException.class,
            () -> acceptAsync(runner(config()), request(null, childThreadId))));
    verify(runtime, times(4)).spawn(any(), any());
  }

  @Test
  void unexpectedFailureDoesNotResubmit() {
    // 非游标错误只能复核票据，不得重复提交可能已产生副作用的请求。
    stubParent(List.of(AGENT));
    IllegalStateException failure = new IllegalStateException("store unavailable");
    when(runtime.spawn(any(), any())).thenThrow(failure);

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));

    assertEquals(failure, rejected.getCause());
    verify(runtime, times(1)).spawn(any(), any());
  }

  @Test
  void replayFailureAfterSubmitFailureKeepsTheSubmitFailure() {
    // 提交失败后的票据复核自身也失败时，抛出复核错误并保留提交失败，诊断不丢失首个原因。
    stubParent(List.of(AGENT));
    IllegalStateException submitFailure = new IllegalStateException("store unavailable");
    IllegalStateException replayFailure = new IllegalStateException("join read failed");
    when(runtime.spawn(any(), any())).thenThrow(submitFailure);
    when(runtime.findJoin(invocationId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenThrow(replayFailure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> acceptAsync(runner(config()), request(null, null)));

    assertEquals(replayFailure, thrown);
    assertEquals(List.of(submitFailure), List.of(thrown.getSuppressed()));
    verify(runtime, times(1)).spawn(any(), any());
  }

  @Test
  void nonCursorConflictDoesNotResubmit() {
    // 明确业务冲突没有匹配票据时立即拒绝。
    stubParent(List.of(AGENT));
    when(runtime.spawn(any(), any()))
        .thenThrow(DomainException.conflict("IDEMPOTENCY_KEY_REUSED", "different request"));

    assertThrows(
        SubagentTaskRejectedException.class,
        () -> acceptAsync(runner(config()), request(null, null)));

    verify(runtime, times(1)).spawn(any(), any());
  }

  @Test
  void concurrentDuplicateLosingTheRaceReplaysTheWinnersJoin() {
    stubParent(List.of(AGENT));
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.spawn(any(), any()))
        .thenThrow(DomainException.conflict("IDEMPOTENCY_KEY_REUSED", "winner"));
    // 竞争者先提交成功：本进程第一次提交冲突，随后的重读才看到胜者的 join。
    when(runtime.findJoin(invocationId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(null, null)),
                    AGENT,
                    10)));
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    when(child.thread())
        .thenReturn(
            new ThreadState(
                childThreadId,
                UUID.randomUUID(),
                parentThreadId,
                UUID.randomUUID(),
                "0".repeat(64),
                "child",
                parentThreadId,
                false,
                ThreadKind.SUBAGENT,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                2L,
                1L,
                NOW,
                NOW));
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);

    SubagentTaskAcceptance acceptance = acceptAsync(runner(config()), request(null, null));

    // 只提交一次；第二次循环按 join 回放，不产生部分重放也不会重复入队 prompt。
    assertTrue(acceptance.replayed());
    verify(runtime, times(1)).spawn(any(), any());
  }

  @Test
  void rejectsDetachedOrForeignInvocationWithoutTouchingAcceptance() {
    // 父调用已结束（无 Model）或 invocation 不在冻结的 tool siblings 中：不得凭 invocationId 越权。
    ThreadSnapshot detached = mock(ThreadSnapshot.class);
    when(runtime.getThreadSnapshot(parentThreadId)).thenReturn(detached);
    SubagentTaskRejectedException detachedError =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertTrue(
        detachedError.getMessage().contains("no longer attached"), detachedError.getMessage());

    stubParent(List.of(AGENT));
    ThreadSnapshot parent = runtime.getThreadSnapshot(parentThreadId);
    when(parent.toolSiblings()).thenReturn(List.of());
    SubagentTaskRejectedException missingSibling =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertTrue(
        missingSibling.getMessage().contains("no longer attached"), missingSibling.getMessage());
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void rejectsUnauthorizedSubagentTypeWithAvailableNames() {
    // 多个可选 subagent 时错误必须列出全部名字，便于父 Agent 自我纠正。
    stubParent(List.of("beta", "gamma"));

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));

    assertTrue(rejected.getMessage().contains("is not allowed"), rejected.getMessage());
    assertTrue(rejected.getMessage().contains("beta / gamma"), rejected.getMessage());
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void missingSnapshotsBecomeTypedTaskFailures() {
    // resume 目标已消失：追加命令前重读失败必须收敛为 typed 失败。
    stubParent(List.of(AGENT));
    UUID missingChild = UUID.randomUUID();
    doThrow(DomainException.notFound("thread", "child gone"))
        .when(runtime)
        .getThreadSnapshot(missingChild);
    SubagentTaskRejectedException goneChild =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, missingChild)));
    assertTrue(goneChild.getMessage().contains("was not found"), goneChild.getMessage());

    // 父快照已消失（父线程被删）：同样不得凭 invocationId 继续。
    stubParent(List.of(AGENT));
    doThrow(DomainException.notFound("thread", "parent gone"))
        .when(runtime)
        .getThreadSnapshot(parentThreadId);
    SubagentTaskRejectedException goneParent =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertTrue(goneParent.getMessage().contains("no longer attached"), goneParent.getMessage());

    // 回放路径上子 Thread 已消失：无法解析子 Session，必须报出明确的 not found。
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(null, null)),
                    AGENT,
                    10)));
    doThrow(DomainException.notFound("thread", "child gone"))
        .when(runtime)
        .getThreadSnapshot(childThreadId);
    SubagentTaskRejectedException replayedMissingChild =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertTrue(
        replayedMissingChild.getMessage().contains("was not found"),
        replayedMissingChild.getMessage());

    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void domainMissingSnapshotsBecomeTaskFailuresButOtherCodesPropagate() {
    // 父、续接子线程按 not-found 分类；其他 DomainException 原样抛出。
    stubParent(List.of(AGENT));
    UUID missingChild = UUID.randomUUID();
    DomainException missing = DomainException.notFound("thread", "gone");
    doThrow(missing).when(runtime).getThreadSnapshot(missingChild);
    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, missingChild)));
    assertSame(missing, rejected.getCause());
    assertTrue(rejected.getMessage().contains("was not found"));

    DomainException validation = DomainException.validation("thread", "invalid");
    doThrow(validation).when(runtime).getThreadSnapshot(missingChild);
    assertSame(
        validation,
        assertThrows(
            DomainException.class,
            () -> acceptAsync(runner(config()), request(null, missingChild))));
    doThrow(validation).when(runtime).getThreadSnapshot(parentThreadId);
    assertSame(
        validation,
        assertThrows(
            DomainException.class, () -> acceptAsync(runner(config()), request(null, null))));
    DomainException unrelatedMissing = DomainException.notFound("session", "gone");
    doThrow(unrelatedMissing).when(runtime).getThreadSnapshot(parentThreadId);
    assertSame(
        unrelatedMissing,
        assertThrows(
            DomainException.class, () -> acceptAsync(runner(config()), request(null, null))));
    doThrow(missing).when(runtime).getThreadSnapshot(parentThreadId);
    rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertSame(missing, rejected.getCause());
    assertTrue(rejected.getMessage().contains("no longer attached"));
  }

  @Test
  void domainMissingReplayChildIsNotSwallowed() {
    // 既有票据回放仍需解析子 Session，不存在时保留原始 not-found cause。
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.findJoin(invocationId))
        .thenReturn(
            Optional.of(
                join(
                    parentThreadId,
                    childThreadId,
                    SubagentTaskRunner.requestHash(request(null, null)),
                    AGENT,
                    10)));
    DomainException missing = DomainException.notFound("thread", "gone");
    doThrow(missing).when(runtime).getThreadSnapshot(childThreadId);
    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertSame(missing, rejected.getCause());
    DomainException other = DomainException.validation("thread", "invalid");
    doThrow(other).when(runtime).getThreadSnapshot(childThreadId);
    assertSame(
        other,
        assertThrows(
            DomainException.class, () -> acceptAsync(runner(config()), request(null, null))));
  }

  @Test
  void domainSubmitMissingAndNonCursorConflictBecomeTaskFailures() {
    // 已知 Harness 业务拒绝保留工具层语义，但不会重试非游标冲突。
    stubParent(List.of(AGENT));
    DomainException missing = DomainException.notFound("thread", "gone");
    doThrow(missing).when(runtime).spawn(any(), any());
    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertSame(missing, rejected.getCause());
    assertTrue(rejected.getMessage().contains("parent thread no longer exists"));

    DomainException conflict = DomainException.conflict("IDEMPOTENCY_KEY_REUSED", "different");
    doThrow(conflict).when(runtime).spawn(any(), any());
    rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertSame(conflict, rejected.getCause());
    DomainException unrelatedMissing = DomainException.notFound("session", "gone");
    doThrow(unrelatedMissing).when(runtime).spawn(any(), any());
    assertSame(
        unrelatedMissing,
        assertThrows(
            DomainException.class, () -> acceptAsync(runner(config()), request(null, null))));
    verify(runtime, times(3)).spawn(any(), any());
  }

  @Test
  void materializerDomainErrorsPropagateUnchanged() {
    // 物化配置不属于 Harness 控制面，其他领域失败不能包装成 task 拒绝。
    stubParent(List.of(AGENT));
    DomainException failure = DomainException.notFound("model", "model missing");
    doThrow(failure).when(materializer).materialize(AGENT);
    assertSame(
        failure,
        assertThrows(
            DomainException.class, () -> acceptAsync(runner(config()), request(null, null))));
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void exceptionWithoutMessageStillYieldsTypedFailureWithClassName() {
    // 无 message 的 Runtime 拒绝也必须给出可读原因（退回异常类名），不能吞成空消息。
    stubParent(List.of(AGENT));
    doThrow(new IllegalArgumentException()).when(runtime).spawn(any(), any());

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));

    assertTrue(rejected.getMessage().contains("IllegalArgumentException"), rejected.getMessage());
  }

  @Test
  void rejectsResumingAThreadOfAnotherParent() {
    // 跨父 resume 在提交前直接以确切文案拒绝，不进入重试且不触发任何持久写入。
    stubParent(List.of(AGENT));
    UUID childThreadId = UUID.randomUUID();
    ThreadSnapshot foreignChild = child(childThreadId, UUID.randomUUID(), 3L);
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(foreignChild);

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, childThreadId)));

    assertEquals(
        "This subagent thread is no longer valid. Dispatch a new task without thread_id.",
        rejected.getMessage());
    verify(runtime, never()).spawn(any(), any());
  }

  @Test
  void runtimeUnavailabilityAndRuntimeRejectionBecomeTypedTaskFailures() {
    // 未装配 Runtime 时给出明确错误而不是 NullPointerException。
    SubagentTaskRunner offline =
        new SubagentTaskRunner(
            () -> null,
            NO_THREAD_CHANGES,
            new AgentTaskService(provider(null)),
            materializer,
            SubagentTaskRunnerTest::config);
    SubagentTaskRejectedException unavailable =
        assertThrows(
            SubagentTaskRejectedException.class, () -> acceptAsync(offline, request(null, null)));
    assertTrue(unavailable.getMessage().contains("not available"), unavailable.getMessage());

    // Runtime 的额度/深度等业务拒绝（IllegalArgumentException）必须收敛为 typed task 失败，让 tool 报错而不是抛 NPE。
    stubParent(List.of(AGENT));
    doThrow(new IllegalArgumentException("parent join quota exceeded"))
        .when(runtime)
        .spawn(any(), any());
    SubagentTaskRejectedException quota =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> acceptAsync(runner(config()), request(null, null)));
    assertTrue(quota.getMessage().contains("quota exceeded"), quota.getMessage());

    // 父线程在提交前消失同样收敛为 typed 失败。
    doThrow(DomainException.notFound("thread", "parent gone")).when(runtime).spawn(any(), any());
    assertThrows(
        SubagentTaskRejectedException.class,
        () -> acceptAsync(runner(config()), request(null, null)));
  }

  @Test
  void stableDerivationAndRequestHashDistinguishDelegations() {
    UUID other = UUID.randomUUID();
    assertEquals(
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"),
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"));
    assertNotEquals(
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"),
        SubagentTaskRunner.derive(other, "kk-studio/harness/subagent/thread/"));
    assertEquals(64, SubagentTaskRunner.requestHash(request(null, null)).length());
    // 省略 max_turns 与显式值必须是不同身份；同一请求必须稳定。
    assertEquals(
        SubagentTaskRunner.requestHash(request(null, null)),
        SubagentTaskRunner.requestHash(request(null, null)));
    assertNotEquals(
        SubagentTaskRunner.requestHash(request(null, null)),
        SubagentTaskRunner.requestHash(request(10, null)));
  }

  private static ThreadJoin join(
      UUID parentThreadId, UUID childThreadId, String requestHash, String agent, Integer maxTurns) {
    return new ThreadJoin(
        UUID.randomUUID(),
        requestHash,
        parentThreadId,
        childThreadId,
        1L,
        agent,
        maxTurns,
        0L,
        null,
        null,
        null,
        NOW,
        NOW,
        JoinDelivery.NOTIFY_PARENT,
        null);
  }

  private static ThreadSnapshot child(UUID childThreadId, UUID parentThreadId, long nextSequence) {
    return child(childThreadId, parentThreadId, UUID.randomUUID(), nextSequence);
  }

  private static ThreadSnapshot child(
      UUID childThreadId, UUID parentThreadId, UUID headEntryId, long nextSequence) {
    // 子线程可处于 ACTIVE（忙碌）：resume 只追加命令，不等待旧 join 交付。
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    when(child.thread())
        .thenReturn(
            new ThreadState(
                childThreadId,
                UUID.randomUUID(),
                parentThreadId,
                headEntryId,
                "0".repeat(64),
                "child",
                parentThreadId,
                false,
                ThreadKind.SUBAGENT,
                ThreadConstraints.NONE,
                ThreadExecutionControl.RUNNABLE,
                0L,
                nextSequence,
                7L,
                NOW,
                NOW));
    EntryCurrentState childState = mock(EntryCurrentState.class);
    when(childState.baseSettings())
        .thenReturn(new BranchSettings("child-agent", BuiltinExecution.of(MODEL), "child-env"));
    when(child.entryState()).thenReturn(childState);
    return child;
  }
}
