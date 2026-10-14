package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台侧 task 委派接受的回归基线：权限校验、稳定身份、幂等回放、忙碌子线程追加与错误收敛。
 *
 * <p>测试意图：Runtime 用窄接口 mock 表达；join 身份、命令形状与错误类型是本类的真实职责，而额度/深度/匹配/交付属于 Runtime，不在本测试断言范围。
 */
class SubagentTaskRunnerTest {

  private static final String AGENT = "alpha";
  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private static final ModelSelection MODEL = new ModelSelection("provider", "model", "v1");

  private final UUID parentThreadId = UUID.randomUUID();
  private final UUID invocationId = UUID.randomUUID();
  private final UUID parentHeadEntryId = UUID.randomUUID();
  private final BranchSettings target = new BranchSettings(AGENT, MODEL, "env");

  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final AgentBranchSettingsMaterializer materializer =
      mock(AgentBranchSettingsMaterializer.class);

  private SubagentTaskRunner runner(SubagentConfig config) {
    return new SubagentTaskRunner(() -> runtime, materializer, () -> config);
  }

  private static SubagentConfig config() {
    return new SubagentConfig(3, 4, 0, 10);
  }

  /** 父快照：存在冻结的 Model 调用，允许 AGENT 委派，environment 为 env。 */
  /** 父快照：存在冻结的 Model 调用，允许 AGENT 委派，environment 为 env。 */
  private void stubParent(List<String> allowedSubagents) {
    ThreadSnapshot parent = mock(ThreadSnapshot.class);
    ModelInvocation model = mock(ModelInvocation.class);
    ModelRequestSpec spec = mock(ModelRequestSpec.class);
    ToolInvocation tool = mock(ToolInvocation.class);
    EntryPath path = mock(EntryPath.class);
    when(parent.thread())
        .thenReturn(
            new ThreadState(
                parentThreadId,
                UUID.randomUUID(),
                null,
                parentHeadEntryId,
                "0".repeat(64),
                "main",
                ThreadYoloPolicy.root(true),
                ThreadExecutionControl.RUNNABLE,
                0L,
                5L,
                3L,
                NOW,
                NOW));
    when(parent.model()).thenReturn(model);
    when(parent.toolSiblings()).thenReturn(List.of(tool));
    when(tool.id()).thenReturn(invocationId);
    when(model.requestSpec()).thenReturn(spec);
    when(spec.subagentBindings())
        .thenReturn(
            allowedSubagents.stream().map(name -> new SubagentBinding(name, name)).toList());
    when(parent.entryPath()).thenReturn(path);
    when(path.baseSettings()).thenReturn(new BranchSettings("parent-agent", MODEL, "parent-env"));
    when(runtime.getThreadSnapshot(parentThreadId)).thenReturn(parent);
    when(materializer.materializeSubagent(AGENT, "parent-env")).thenReturn(target);
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
  void newDelegationAcceptsAtomicallyWithStableIdentityAndAdmissionLimits() {
    stubParent(List.of(AGENT));
    UUID sessionId = UUID.randomUUID();
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    AcceptedCommands accepted = accepted(sessionId, childThreadId, false);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);

    SubagentTaskAcceptance acceptance = runner(config()).accept(request(null, null));

    assertEquals(sessionId, acceptance.childSessionId());
    assertEquals(childThreadId, acceptance.childThreadId());
    assertFalse(acceptance.replayed());
    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    ArgumentCaptor<ThreadJoinRequest> join = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime)
        .acceptCommandsAndJoin(command.capture(), join.capture(), any(AcceptancePreflight.class));

    AcceptCommandsTarget.NewChildSession acceptedTarget =
        (AcceptCommandsTarget.NewChildSession) command.getValue().target();
    assertEquals(childThreadId, acceptedTarget.threadId());
    assertEquals(
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/session/"),
        acceptedTarget.sessionId());
    // 执行父关系由 Runtime 建立：target 携带父 Thread，ROOT settings 只由本次物化结果决定。
    assertEquals(parentThreadId, acceptedTarget.parentThreadId());
    assertEquals(target, acceptedTarget.rootSettings());
    // 子代理 target 不再携带独立开关：Runtime 在树锁内从真实父链派生 FOLLOW(执行根)。
    // 源 prompt 与 join 同一批命令：恰好一条可信调用方 CUSTOM_MESSAGE。
    assertEquals(1, command.getValue().commands().size());
    assertTrue(
        command.getValue().commands().get(0).payload() instanceof CustomMessageCommandPayload);

    // 未显式给出 max_turns 时用 policy 默认；额度按 config 快照冻结，0 表示不设树级上限。
    assertEquals(parentHeadEntryId, join.getValue().expectedParentHeadEntryId());
    assertEquals(AGENT, join.getValue().agent());
    assertEquals(10, join.getValue().maxTurns().intValue());
    assertEquals(
        SubagentTaskRunner.requestHash(request(null, null)), join.getValue().requestHash());
    assertEquals(3, join.getValue().maxDepth());
    assertEquals(4, join.getValue().maxConcurrentChildren());
    assertEquals(Integer.MAX_VALUE, join.getValue().maxConcurrentThreads());
  }

  @Test
  void explicitMaxTurnsIsFrozenAndCommandKeysAreDeterministic() {
    stubParent(List.of(AGENT));
    AcceptedCommands accepted = accepted(UUID.randomUUID(), UUID.randomUUID(), false);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);

    runner(config()).accept(request(7, null));

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    ArgumentCaptor<ThreadJoinRequest> join = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime)
        .acceptCommandsAndJoin(command.capture(), join.capture(), any(AcceptancePreflight.class));
    assertEquals(7, join.getValue().maxTurns().intValue());
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
                ThreadYoloPolicy.follow(parentThreadId),
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

    SubagentTaskAcceptance acceptance = runner(config()).accept(request(null, null));

    assertTrue(acceptance.replayed());
    assertEquals(childThreadId, acceptance.childThreadId());
    assertEquals(childSessionId, acceptance.childSessionId());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
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
          assertThrows(SubagentTaskRejectedException.class, () -> runner(config()).accept(other));
      assertTrue(rejected.getMessage().contains("different delegation"), rejected.getMessage());
    }
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
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
                ThreadYoloPolicy.follow(parentThreadId),
                ThreadExecutionControl.RUNNABLE,
                0L,
                2L,
                1L,
                NOW,
                NOW));
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);

    // 默认值从 10 变成 99：请求 hash 不含运行期默认值，回放仍然成立。
    assertTrue(runner(new SubagentConfig(3, 4, 0, 99)).accept(request(null, null)).replayed());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix() {
    // resume 不要求前一次 join 已交付，也不要求子线程 idle：只追加 agent/model/prompt，绝不改写既有子环境。
    stubParent(List.of(AGENT));
    UUID childThreadId = UUID.randomUUID();
    ThreadSnapshot child = child(childThreadId, parentThreadId, 9L);
    AcceptedCommands accepted = accepted(UUID.randomUUID(), childThreadId, false);
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);

    SubagentTaskAcceptance acceptance = runner(config()).accept(request(null, childThreadId));

    assertEquals(childThreadId, acceptance.childThreadId());
    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommandsAndJoin(command.capture(), any(), any());
    AcceptCommandsTarget.Thread target = (AcceptCommandsTarget.Thread) command.getValue().target();
    assertEquals(childThreadId, target.threadId());
    assertEquals(9L, target.expectedNextCommandSequence());
    List<NewThreadCommand> commands = command.getValue().commands();
    assertEquals(3, commands.size());
    // 续接前缀只由本次目标 settings 决定：SET_AGENT + SET_MODEL + prompt。
    assertEquals(new SetAgentCommandPayload(AGENT), commands.get(0).payload());
    assertEquals(new SetModelCommandPayload(MODEL), commands.get(1).payload());
    assertTrue(commands.get(2).payload() instanceof CustomMessageCommandPayload);
    // 关键契约：既有子 Thread 保留自身环境。父环境 "parent-env" 与子环境 "child-env" 不同，
    // 因此续接绝不重发 SET_ENVIRONMENT，避免把父环境写进既有子。
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
            SubagentTaskRejectedException.class, () -> runner(config()).accept(changedAgent));

    assertTrue(rejected.getMessage().contains("different delegation"), rejected.getMessage());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
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
    when(runtime.acceptCommandsAndJoin(any(), any(), any()))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale"))
        .thenReturn(accepted);

    SubagentTaskAcceptance acceptance = runner(config()).accept(request(null, childThreadId));

    assertEquals(childThreadId, acceptance.childThreadId());
    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime, times(2)).acceptCommandsAndJoin(command.capture(), any(), any());
    // 第二次必须使用重新读取的 cursor，而不是第一次的陈旧 cursor。
    assertEquals(
        secondHead,
        ((AcceptCommandsTarget.Thread) command.getAllValues().get(1).target())
            .expectedHeadEntryId());
  }

  @Test
  void concurrentDuplicateLosingTheRaceReplaysTheWinnersJoin() {
    stubParent(List.of(AGENT));
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    when(runtime.acceptCommandsAndJoin(any(), any(), any()))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.IDEMPOTENCY_KEY_REUSED, "winner"));
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
                ThreadYoloPolicy.follow(parentThreadId),
                ThreadExecutionControl.RUNNABLE,
                0L,
                2L,
                1L,
                NOW,
                NOW));
    when(runtime.getThreadSnapshot(childThreadId)).thenReturn(child);

    SubagentTaskAcceptance acceptance = runner(config()).accept(request(null, null));

    // 只提交一次；第二次循环按 join 回放，不产生部分重放也不会重复入队 prompt。
    assertTrue(acceptance.replayed());
    verify(runtime, times(1)).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void rejectsDetachedOrForeignInvocationWithoutTouchingAcceptance() {
    // 父调用已结束（无 Model）或 invocation 不在冻结的 tool siblings 中：不得凭 invocationId 越权。
    ThreadSnapshot detached = mock(ThreadSnapshot.class);
    when(runtime.getThreadSnapshot(parentThreadId)).thenReturn(detached);
    SubagentTaskRejectedException detachedError =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));
    assertTrue(
        detachedError.getMessage().contains("no longer attached"), detachedError.getMessage());

    stubParent(List.of(AGENT));
    ThreadSnapshot parent = runtime.getThreadSnapshot(parentThreadId);
    when(parent.toolSiblings()).thenReturn(List.of());
    SubagentTaskRejectedException missingSibling =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));
    assertTrue(
        missingSibling.getMessage().contains("no longer attached"), missingSibling.getMessage());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void rejectsUnauthorizedSubagentTypeWithAvailableNames() {
    // 多个可选 subagent 时错误必须列出全部名字，便于父 Agent 自我纠正。
    stubParent(List.of("beta", "gamma"));

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));

    assertTrue(rejected.getMessage().contains("is not allowed"), rejected.getMessage());
    assertTrue(rejected.getMessage().contains("beta / gamma"), rejected.getMessage());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void missingSnapshotsBecomeTypedTaskFailures() {
    // resume 目标已消失：追加命令前重读失败必须收敛为 typed 失败。
    stubParent(List.of(AGENT));
    UUID missingChild = UUID.randomUUID();
    doThrow(new HarnessRuntimeNotFoundException("child gone"))
        .when(runtime)
        .getThreadSnapshot(missingChild);
    SubagentTaskRejectedException goneChild =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, missingChild)));
    assertTrue(goneChild.getMessage().contains("was not found"), goneChild.getMessage());

    // 父快照已消失（父线程被删）：同样不得凭 invocationId 继续。
    stubParent(List.of(AGENT));
    doThrow(new HarnessRuntimeNotFoundException("parent gone"))
        .when(runtime)
        .getThreadSnapshot(parentThreadId);
    SubagentTaskRejectedException goneParent =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));
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
    doThrow(new HarnessRuntimeNotFoundException("child gone"))
        .when(runtime)
        .getThreadSnapshot(childThreadId);
    SubagentTaskRejectedException replayedMissingChild =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));
    assertTrue(
        replayedMissingChild.getMessage().contains("was not found"),
        replayedMissingChild.getMessage());

    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void exceptionWithoutMessageStillYieldsTypedFailureWithClassName() {
    // 无 message 的 Runtime 拒绝也必须给出可读原因（退回异常类名），不能吞成空消息。
    stubParent(List.of(AGENT));
    doThrow(new IllegalArgumentException())
        .when(runtime)
        .acceptCommandsAndJoin(any(), any(), any());

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));

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
            () -> runner(config()).accept(request(null, childThreadId)));

    assertEquals(
        "This subagent thread is no longer valid. Dispatch a new task without thread_id.",
        rejected.getMessage());
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void runtimeUnavailabilityAndRuntimeRejectionBecomeTypedTaskFailures() {
    // 未装配 Runtime 时给出明确错误而不是 NullPointerException。
    SubagentTaskRunner offline =
        new SubagentTaskRunner(() -> null, materializer, SubagentTaskRunnerTest::config);
    SubagentTaskRejectedException unavailable =
        assertThrows(
            SubagentTaskRejectedException.class, () -> offline.accept(request(null, null)));
    assertTrue(unavailable.getMessage().contains("not available"), unavailable.getMessage());

    // Runtime 的额度/深度等业务拒绝（IllegalArgumentException）必须收敛为 typed task 失败，让 tool 报错而不是抛 NPE。
    stubParent(List.of(AGENT));
    doThrow(new IllegalArgumentException("parent join quota exceeded"))
        .when(runtime)
        .acceptCommandsAndJoin(any(), any(), any());
    SubagentTaskRejectedException quota =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> runner(config()).accept(request(null, null)));
    assertTrue(quota.getMessage().contains("quota exceeded"), quota.getMessage());

    // 父线程在提交前消失同样收敛为 typed 失败。
    doThrow(new HarnessRuntimeNotFoundException("parent gone"))
        .when(runtime)
        .acceptCommandsAndJoin(any(), any(), any());
    assertThrows(
        SubagentTaskRejectedException.class, () -> runner(config()).accept(request(null, null)));
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
        JoinPurpose.TASK,
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
                ThreadYoloPolicy.follow(parentThreadId),
                ThreadExecutionControl.RUNNABLE,
                0L,
                nextSequence,
                7L,
                NOW,
                NOW));
    EntryPath path = mock(EntryPath.class);
    when(path.baseSettings()).thenReturn(new BranchSettings("child-agent", MODEL, "child-env"));
    when(child.entryPath()).thenReturn(path);
    return child;
  }
}
