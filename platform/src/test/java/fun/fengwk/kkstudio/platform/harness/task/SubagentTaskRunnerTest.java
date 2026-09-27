package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 task 委派的持久接受语义：稳定派生与幂等、接受命令形状（新建/继续）、以及在写入事务内加锁判定额度的并发安全边界。
 *
 * <p>测试意图：这是平台侧"接受"子阶段的回归基线。Runtime/store 用窄接口 mock 表达，其中 mock 的 {@code acceptCommands} 忠实回放真实语义
 * ——在同一事务内调用 preflight，使 preflight 的异常与回滚后果与生产一致。真实 advisory 锁、部分唯一索引与外键删除行为需要 PostgreSQL
 * 集成测试覆盖，本测试不声称覆盖这些。
 */
class SubagentTaskRunnerTest {

  private static final String AGENT = "alpha";
  private static final String OTHER_AGENT = "beta";
  private static final ModelSelection MODEL = new ModelSelection("provider", "model", "v1");

  @Test
  void newChildAcceptsDurableTaskAndRecordsOpenExecution() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "alpha agent")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));

    SubagentTaskAcceptance acceptance = fixture.accept(null);

    AcceptCommandsTarget.NewSession target =
        assertInstanceOf(AcceptCommandsTarget.NewSession.class, fixture.target());
    assertEquals(target.sessionId(), acceptance.childSessionId());
    assertEquals(target.threadId(), acceptance.childThreadId());
    assertFalse(acceptance.replayed());
    // 子 Session 冻结归属：父 Thread、树根 Thread、本次 invocation 与深度都写进 ROOT。
    assertEquals(fixture.parentThreadId, target.subagentContext().parentThreadId());
    assertEquals(fixture.parentThreadId, target.subagentContext().rootThreadId());
    assertEquals(fixture.invocationId, target.subagentContext().taskInvocationId());
    // 普通根 Thread 的深度为 1，其子 Session 从 2 开始。
    assertEquals(2, target.subagentContext().depth());
    assertEquals(new BranchSettings(AGENT, MODEL, "env"), target.rootSettings());
    assertEquals(1, fixture.commands().size());
    assertInstanceOf(UserMessageCommandPayload.class, fixture.commands().get(0).payload());

    // preflight 写入委派记录：执行边界取接受时子 Thread 的 head，状态 OPEN，prompt/maxTurns 原样持久。
    ArgumentCaptor<SubagentTaskDraft> draft = ArgumentCaptor.forClass(SubagentTaskDraft.class);
    verify(fixture.repository).insert(draft.capture());
    assertEquals(fixture.invocationId, draft.getValue().invocationId());
    assertEquals(fixture.parentThreadId, draft.getValue().parentThreadId());
    assertEquals(fixture.parentThreadId, draft.getValue().rootThreadId());
    assertEquals(target.sessionId(), draft.getValue().childSessionId());
    assertEquals(target.threadId(), draft.getValue().childThreadId());
    assertEquals(fixture.boundaryEntryId, draft.getValue().sourceHeadEntryId());
    assertEquals(AGENT, draft.getValue().agent());
    assertEquals("do the work", draft.getValue().prompt());
    assertEquals(7, draft.getValue().maxTurns().intValue());
    assertEquals(SubagentTaskStatus.OPEN, draft.getValue().status());
    assertEquals(0L, draft.getValue().reminderTurn());
  }

  @Test
  void replayedInvocationReturnsPersistedChildWithoutAcceptingAgain() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    UUID childSessionId = fixture.derivedChildSessionId();
    UUID childThreadId = fixture.derivedChildThreadId();
    when(fixture.repository.findByInvocationId(fixture.invocationId))
        .thenReturn(fixture.task(childSessionId, childThreadId, SubagentTaskStatus.OPEN));

    SubagentTaskAcceptance acceptance = fixture.accept(null);

    // 同一次 Tool 调用重试：复用既有子 Thread，不再触达 Runtime、额度与记录写入。
    assertEquals(childSessionId, acceptance.childSessionId());
    assertEquals(childThreadId, acceptance.childThreadId());
    assertTrue(acceptance.replayed());
    verify(fixture.runtime, never()).acceptCommands(any(), any());
    verify(fixture.repository, never()).lockQuota(any(), any());
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void replayedContinueInvocationReturnsPersistedTargetThread() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    UUID childThreadId = UUID.randomUUID();
    when(fixture.repository.findByInvocationId(fixture.invocationId))
        .thenReturn(fixture.task(UUID.randomUUID(), childThreadId, SubagentTaskStatus.SETTLED));

    SubagentTaskAcceptance acceptance =
        fixture.runner.accept(fixture.continueRequest(childThreadId));

    // 继续既有子 Thread 的重放：目标必须就是那条既有子 Thread；已终结记录同样允许重放（身份与终态无关）。
    assertEquals(childThreadId, acceptance.childThreadId());
    assertTrue(acceptance.replayed());
    verify(fixture.runtime, never()).acceptCommands(any(), any());
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void replayedInvocationWithAnyDifferentFrozenFactIsRejected() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    UUID invocationId = fixture.invocationId;
    UUID parentThreadId = fixture.parentThreadId;
    when(fixture.repository.findByInvocationId(invocationId))
        .thenReturn(
            fixture.task(
                fixture.derivedChildSessionId(),
                fixture.derivedChildThreadId(),
                SubagentTaskStatus.OPEN));

    List<SubagentTaskRequest> differentDelegations =
        List.of(
            // 父 Thread 不同：另一次调用（可能来自别的父）想复用这条记录。
            new SubagentTaskRequest(invocationId, UUID.randomUUID(), "do the work", AGENT, 7, null),
            // prompt 不同：绝不是同一次委派。
            new SubagentTaskRequest(invocationId, parentThreadId, "different work", AGENT, 7, null),
            // 目标 Agent 不同。
            new SubagentTaskRequest(
                invocationId, parentThreadId, "do the work", "another-agent", 7, null),
            // max_turns 不同（含显式与缺省之别）。
            new SubagentTaskRequest(invocationId, parentThreadId, "do the work", AGENT, 9, null),
            new SubagentTaskRequest(invocationId, parentThreadId, "do the work", AGENT, null, null),
            // 目标是继续另一条子 Thread，而既有记录是新建派生的子 Thread。
            new SubagentTaskRequest(
                invocationId, parentThreadId, "do the work", AGENT, 7, UUID.randomUUID()));

    for (SubagentTaskRequest different : differentDelegations) {
      SubagentTaskRejectedException rejected =
          assertThrows(SubagentTaskRejectedException.class, () -> fixture.runner.accept(different));
      assertTrue(rejected.getMessage().contains("different delegation"), rejected.getMessage());
    }
    // 越权重放不得泄露既有子身份，也不得触达 Runtime 或写入任何记录。
    verify(fixture.runtime, never()).acceptCommands(any(), any());
    verify(fixture.repository, never()).lockQuota(any(), any());
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void replayedInvocationWithTamperedChildThreadIsRejected() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    // 新建委派的子 Thread 必须等于由 invocation 稳定派生的身份：不一致说明记录并非本次调用产生。
    when(fixture.repository.findByInvocationId(fixture.invocationId))
        .thenReturn(
            fixture.task(
                fixture.derivedChildSessionId(), UUID.randomUUID(), SubagentTaskStatus.OPEN));

    assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    verify(fixture.runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void childIdentityIsStableAndDerivedFromInvocation() {
    UUID invocationId = UUID.randomUUID();

    // 子身份必须由 invocation 稳定派生：同一 invocation 在任何进程/重启后都得到同一 UUID，重放才不会双开子执行。
    UUID childSessionId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/session/");
    UUID childThreadId =
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    assertEquals(
        childSessionId,
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/session/"));
    assertEquals(
        childThreadId,
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"));
    assertNotEquals(childSessionId, childThreadId);

    // 不同 invocation 必须派生出不同子身份（否则并发委派会互相覆盖）。
    UUID other =
        SubagentTaskRunner.derive(UUID.randomUUID(), "kk-studio/harness/subagent/session/");
    assertNotEquals(childSessionId, other);
    assertEquals(
        UUID.nameUUIDFromBytes(
            ("kk-studio/harness/subagent/session/" + invocationId)
                .getBytes(StandardCharsets.UTF_8)),
        childSessionId);
  }

  @Test
  void quotaLockAndCountingPrecedeTaskRecordInsert() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 4, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "alpha agent")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));

    fixture.accept(null);

    // 并发安全的关键：先取额度 advisory 锁，再计数，最后插入，三者同事务。
    InOrder order = inOrder(fixture.repository);
    order.verify(fixture.repository).lockQuota(fixture.parentThreadId, fixture.parentThreadId);
    order.verify(fixture.repository).countOpenByParentThreadId(fixture.parentThreadId);
    order.verify(fixture.repository).countOpenByRootThreadId(fixture.parentThreadId);
    order.verify(fixture.repository).insert(any());
  }

  @Test
  void parentConcurrencyQuotaRejectsBeforeAnyTaskRecord() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 2, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "alpha agent")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    when(fixture.repository.countOpenByParentThreadId(fixture.parentThreadId)).thenReturn(2);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    // 越限即在接受事务内失败：不写记录，子 Session/Thread 一并回滚。
    assertTrue(rejected.getMessage().contains("concurrency limit"));
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void totalConcurrencyQuotaRejectsBeforeAnyTaskRecord() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 5, 3, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "alpha agent")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    when(fixture.repository.countOpenByRootThreadId(fixture.parentThreadId)).thenReturn(3);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("total concurrency limit"));
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void nestedQuotaUsesRootThreadOfDelegationTree() {
    Fixture fixture = new Fixture(new SubagentConfig(3, 3, 2, Duration.ZERO, 10));
    UUID rootThreadId = UUID.randomUUID();
    fixture.stubParentSnapshot(
        List.of(new SubagentBinding(AGENT, "alpha agent")),
        new SubagentContext(fixture.outerThreadId, rootThreadId, UUID.randomUUID(), 2));
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));

    fixture.accept(null);

    // 嵌套委派：树级额度打树根 Thread，父级额度打当前父 Thread。
    verify(fixture.repository).lockQuota(rootThreadId, fixture.parentThreadId);
    verify(fixture.repository).countOpenByRootThreadId(rootThreadId);
    ArgumentCaptor<SubagentTaskDraft> draft = ArgumentCaptor.forClass(SubagentTaskDraft.class);
    verify(fixture.repository).insert(draft.capture());
    assertEquals(rootThreadId, draft.getValue().rootThreadId());
    assertEquals(fixture.parentThreadId, draft.getValue().parentThreadId());
  }

  @Test
  void rejectsDetachedInvocation() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    ThreadSnapshot snapshot = fixture.parentSnapshot(List.of(new SubagentBinding(AGENT, "a")));
    when(snapshot.model()).thenReturn(null);
    when(fixture.runtime.getThreadSnapshot(fixture.parentThreadId)).thenReturn(snapshot);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    // 没有 Model invocation 就不是"正在执行的父调用"，任务调用已失效。
    assertTrue(rejected.getMessage().contains("no longer attached"));
    verify(fixture.runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsInvocationMissingFromToolSiblings() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshotWithoutSiblings(List.of(new SubagentBinding(AGENT, "a")), null);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("no longer attached"));
  }

  @Test
  void rejectsSubagentTypeNotAllowed() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () ->
                fixture.runner.accept(
                    new SubagentTaskRequest(
                        fixture.invocationId,
                        fixture.parentThreadId,
                        "do the work",
                        OTHER_AGENT,
                        null,
                        null)));

    // 允许名单来自父调用的冻结快照，拒绝信息要带上可用项。
    assertTrue(rejected.getMessage().contains("is not allowed"));
    assertTrue(rejected.getMessage().contains(AGENT));
    verify(fixture.runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void rejectsDelegationBeyondMaxDepth() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(
        List.of(new SubagentBinding(AGENT, "a")),
        new SubagentContext(fixture.outerThreadId, UUID.randomUUID(), UUID.randomUUID(), 2));

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("max depth"));
  }

  @Test
  void rejectsParentInvocationThatForbidsDelegation() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(), null);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("does not allow subagent delegation"));
  }

  @Test
  void rejectsWhenChildThreadAlreadyHasOpenExecution() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    when(fixture.repository.insert(any()))
        .thenThrow(new DuplicateKeyException("uk_harness_subagent_task_child_open"));

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    // 部分唯一索引冲突（同子 Thread 已有 OPEN 执行）必须冒泡成可读拒绝并让整个接受事务回滚。
    assertTrue(rejected.getMessage().contains("already has an open execution"));
  }

  @Test
  void rejectsWhenTaskRecordCannotBeCreated() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    when(fixture.repository.insert(any())).thenReturn(false);

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("could not be created"));
  }

  @Test
  void continueChildConvergesSettingsThenQueuesPrompt() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(
        List.of(new SubagentBinding(AGENT, "alpha"), new SubagentBinding(OTHER_AGENT, "beta")),
        null);
    fixture.stubMaterialize(
        new BranchSettings(
            OTHER_AGENT, new ModelSelection("provider", "other-model", "v2"), "env2"));
    UUID childThreadId = UUID.randomUUID();
    UUID childSessionId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        childSessionId,
        fixture.parentThreadId,
        new BranchSettings(AGENT, MODEL, "env"));

    SubagentTaskAcceptance acceptance =
        fixture.accept(fixture.continueRequest(childThreadId, OTHER_AGENT));

    assertEquals(childThreadId, acceptance.childThreadId());
    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, fixture.target());
    assertEquals(childThreadId, target.threadId());
    assertEquals(fixture.childHeadEntryId, target.expectedHeadEntryId());
    assertEquals(fixture.childNextCommandSequence, target.expectedNextCommandSequence());

    // 收敛顺序固定：SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER。
    List<String> payloadTypes =
        fixture.commands().stream().map(step -> step.payload().getClass().getSimpleName()).toList();
    assertEquals(
        List.of(
            SetAgentCommandPayload.class.getSimpleName(),
            SetModelCommandPayload.class.getSimpleName(),
            SetEnvironmentCommandPayload.class.getSimpleName(),
            UserMessageCommandPayload.class.getSimpleName()),
        payloadTypes);

    ArgumentCaptor<SubagentTaskDraft> draft = ArgumentCaptor.forClass(SubagentTaskDraft.class);
    verify(fixture.repository).insert(draft.capture());
    assertEquals(childThreadId, draft.getValue().childThreadId());
    assertEquals(childSessionId, draft.getValue().childSessionId());
    assertEquals(OTHER_AGENT, draft.getValue().agent());
    assertEquals(7, draft.getValue().maxTurns().intValue());
    assertEquals(fixture.boundaryEntryId, draft.getValue().sourceHeadEntryId());
  }

  @Test
  void continueChildRetriesAfterRuntimeConflict() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        UUID.randomUUID(),
        fixture.parentThreadId,
        new BranchSettings(AGENT, MODEL, "env"));
    fixture.stubConflictsThenAcceptance(1);

    SubagentTaskAcceptance acceptance = fixture.accept(fixture.continueRequest(childThreadId));

    // 子 Thread 在读取与接受之间推进：重读最新 cursor 后重试成功，并且只写一行记录。
    assertEquals(childThreadId, acceptance.childThreadId());
    verify(fixture.repository).insert(any());
  }

  @Test
  void continueChildRejectsAfterRepeatedRuntimeConflicts() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        UUID.randomUUID(),
        fixture.parentThreadId,
        new BranchSettings(AGENT, MODEL, "env"));
    fixture.stubConflictsThenAcceptance(Integer.MAX_VALUE);

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> fixture.accept(fixture.continueRequest(childThreadId)));

    // 重试用尽即拒绝，不留下任何记录（子执行从未被真正接受）。
    assertTrue(rejected.getMessage().contains("changed before the task prompt could be queued"));
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void rejectsWhenRuntimeIsUnavailable() {
    SubagentTaskRunner runner =
        new SubagentTaskRunner(
            () -> null,
            mock(AgentBranchSettingsMaterializer.class),
            mock(SubagentConfigProvider.class),
            mock(SubagentTaskRepository.class));

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () ->
                runner.accept(
                    new SubagentTaskRequest(
                        UUID.randomUUID(), UUID.randomUUID(), "do", AGENT, null, null)));

    assertTrue(rejected.getMessage().contains("HarnessRuntime is not available"));
  }

  @Test
  void continueChildSendsOnlyPromptWhenSettingsAlreadyMatch() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        UUID.randomUUID(),
        fixture.parentThreadId,
        new BranchSettings(AGENT, MODEL, "env"));

    fixture.accept(fixture.continueRequest(childThreadId));

    // 设置未变化时不发送冗余 SET_*，只有 USER prompt。
    assertEquals(1, fixture.commands().size());
    assertInstanceOf(UserMessageCommandPayload.class, fixture.commands().get(0).payload());
  }

  @Test
  void continueChildRejectsThreadOwnedByAnotherParent() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BranchSettings(AGENT, MODEL, "env"));

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> fixture.accept(fixture.continueRequest(childThreadId)));

    // 归属以子 Session ROOT 的冻结 SubagentContext 为准，不能跨父继续。
    assertTrue(rejected.getMessage().contains("does not belong to this parent"));
    verify(fixture.runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void continueChildRejectsNonQuiescentThread() {
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    ThreadSnapshot child =
        fixture.stubChildSnapshot(
            childThreadId,
            UUID.randomUUID(),
            fixture.parentThreadId,
            new BranchSettings(AGENT, MODEL, "env"));
    when(child.model()).thenReturn(mock(ModelInvocation.class));

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> fixture.accept(fixture.continueRequest(childThreadId)));

    assertTrue(rejected.getMessage().contains("is not quiescent"));
    verify(fixture.runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void continueChildRejectsThreadWithUndeliveredExecution() {
    // 测试意图：继续委派要求上一次执行已完整结清并交付。子 Thread 上还有未交付执行（OPEN 或 SETTLED）时，
    // 旧结果与本次新 prompt 会在同一子 Thread 上串扰：父子都无法判断哪条结果属于哪次执行，必须在接受事务内拒绝。
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    UUID childThreadId = UUID.randomUUID();
    fixture.stubChildSnapshot(
        childThreadId,
        UUID.randomUUID(),
        fixture.parentThreadId,
        new BranchSettings(AGENT, MODEL, "env"));
    fixture.childHasUndeliveredExecution = true;

    SubagentTaskRejectedException rejected =
        assertThrows(
            SubagentTaskRejectedException.class,
            () -> fixture.accept(fixture.continueRequest(childThreadId)));

    assertTrue(rejected.getMessage().contains("undelivered execution"), rejected.getMessage());
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void acceptsOnlyWhenParentHeadStillMatchesTheDelegationFacts() {
    // 测试意图：接受事务内必须复核父 Thread 仍是这次委派的父：父已推进（例如已被停止并写下停止边界）时，
    // 这次委派是在旧事实上迟到的，必须拒绝而不是留下一个父已不再等待的 OPEN 记录。
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    fixture.stubParentHeadAdvanced();

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("advanced"), rejected.getMessage());
    verify(fixture.repository, never()).insert(any());
  }

  @Test
  void rejectsDelegationFromStoppedParent() {
    // 测试意图：父 Thread 的 head 是显式停止边界时不接受新委派：停止门禁在接受侧同样成立，
    // 否则停止的父会收到一个它明确拒绝的委派链条。
    Fixture fixture = new Fixture(new SubagentConfig(2, 3, 0, Duration.ZERO, 10));
    fixture.stubParentSnapshot(List.of(new SubagentBinding(AGENT, "a")), null);
    fixture.stubMaterialize(new BranchSettings(AGENT, MODEL, "env"));
    fixture.stubStoppedParentHead();

    SubagentTaskRejectedException rejected =
        assertThrows(SubagentTaskRejectedException.class, () -> fixture.accept(null));

    assertTrue(rejected.getMessage().contains("explicitly stopped"), rejected.getMessage());
    verify(fixture.repository, never()).insert(any());
  }

  /**
   * 被测运行器的 mock 装配；mock 的 {@code acceptCommands} 忠实回放真实接受语义：在同一"事务"内调用 preflight，并以 target 中预分配的 id
   * 回显接受结果。
   */
  private static final class Fixture {

    final HarnessRuntime runtime = mock(HarnessRuntime.class);
    final SubagentTaskRepository repository = mock(SubagentTaskRepository.class);
    final AgentBranchSettingsMaterializer materializer =
        mock(AgentBranchSettingsMaterializer.class);
    final SubagentConfigProvider configProvider = mock(SubagentConfigProvider.class);
    final HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    final SubagentTaskRunner runner;
    final UUID parentThreadId = UUID.randomUUID();
    final UUID outerThreadId = UUID.randomUUID();
    final UUID invocationId = UUID.randomUUID();
    final UUID boundaryEntryId = UUID.randomUUID();
    final UUID parentHeadEntryId = UUID.randomUUID();
    final long childNextCommandSequence = 4L;

    UUID childHeadEntryId = UUID.randomUUID();

    /** 接受事务内看到的父 Thread 事实（head 已推进/停止由测试通过辅助方法覆盖）。 */
    ThreadState parentThreadFacts = parentThreadFacts();

    Entry parentHeadEntry = activeParentHead();
    boolean childHasUndeliveredExecution;
    private AcceptCommandsCommand acceptedCommand;
    private UUID childSessionId;

    Fixture(SubagentConfig config) {
      when(configProvider.subagentConfig()).thenReturn(config);
      when(repository.insert(any())).thenReturn(true);
      runner = new SubagentTaskRunner(() -> runtime, materializer, configProvider, repository);
      when(runtime.acceptCommands(any(), any())).thenAnswer(this::acceptWithin);
    }

    /** 冲突重试场景：前 {@code conflicts} 次接受抛类型化冲突，之后按真实语义成功。 */
    void stubConflictsThenAcceptance(int conflicts) {
      AtomicInteger calls = new AtomicInteger();
      // 重新 stubbing 已带 answer 的方法必须用 doAnswer，否则 when(...) 会先执行旧 answer。
      doAnswer(
              invocation -> {
                if (calls.getAndIncrement() < conflicts) {
                  throw new HarnessRuntimeConflictException(
                      HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale cursor");
                }
                return acceptWithin(invocation);
              })
          .when(runtime)
          .acceptCommands(any(), any());
    }

    /** 忠实回放真实 store 的接受语义：在"事务内"调用 preflight，并以 target 的预分配 id 回显结果。 */
    private AcceptedCommands acceptWithin(InvocationOnMock invocation) {
      acceptedCommand = invocation.getArgument(0);
      AcceptancePreflight preflight = invocation.getArgument(1);
      UUID childThreadId = threadIdOf(acceptedCommand.target());
      UUID sessionId =
          acceptedCommand.target() instanceof AcceptCommandsTarget.NewSession target
              ? target.sessionId()
              : childSessionId;
      ThreadState childThread = mock(ThreadState.class);
      when(childThread.headEntryId()).thenReturn(boundaryEntryId);
      when(transaction.findThread(childThreadId)).thenReturn(Optional.of(childThread));
      // 接受事务内的父复核：父 Thread 存在、head 未推进、head 不是停止边界、目标子 Thread 没有未交付旧执行。
      when(transaction.findThread(parentThreadId)).thenReturn(Optional.of(parentThreadFacts));
      when(transaction.findEntry(parentHeadEntryId)).thenReturn(Optional.of(parentHeadEntry));
      when(repository.hasUndeliveredByChildThreadId(any()))
          .thenReturn(childHasUndeliveredExecution);
      // 真实 store 在写入 command 之前调用 preflight：异常直接使整个接受失败。
      preflight.prepare(transaction, mock(Session.class), acceptedCommand.commands());
      AcceptedCommands accepted = mock(AcceptedCommands.class);
      Session session = mock(Session.class);
      ThreadState thread = mock(ThreadState.class);
      when(session.id()).thenReturn(sessionId);
      when(thread.id()).thenReturn(childThreadId);
      when(accepted.session()).thenReturn(session);
      when(accepted.thread()).thenReturn(thread);
      when(accepted.replayed()).thenReturn(false);
      return accepted;
    }

    /** 接受事务内看到父 head 已推进（父在接受前被停止/推进）：委派迟到，必须拒绝。 */
    void stubParentHeadAdvanced() {
      ThreadState advanced = mock(ThreadState.class);
      when(advanced.headEntryId()).thenReturn(UUID.randomUUID());
      parentThreadFacts = advanced;
    }

    /** 接受事务内看到父 head 是显式停止边界。 */
    void stubStoppedParentHead() {
      Entry stopped = mock(Entry.class);
      when(stopped.payload())
          .thenReturn(
              new TurnEndPayload(
                  UUID.randomUUID(),
                  TurnEndOutcome.STOPPED,
                  false,
                  TurnEndReason.USER_STOP,
                  UUID.randomUUID()));
      parentHeadEntry = stopped;
    }

    private ThreadState parentThreadFacts() {
      ThreadState thread = mock(ThreadState.class);
      when(thread.headEntryId()).thenReturn(parentHeadEntryId);
      return thread;
    }

    private static Entry activeParentHead() {
      Entry head = mock(Entry.class);
      when(head.payload()).thenReturn(new RootPayload(new BranchSettings(AGENT, MODEL, "env")));
      return head;
    }

    UUID derivedChildSessionId() {
      return SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/session/");
    }

    UUID derivedChildThreadId() {
      return SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    }

    SubagentTaskAcceptance accept(SubagentTaskRequest request) {
      SubagentTaskRequest effective =
          request != null
              ? request
              : new SubagentTaskRequest(
                  invocationId, parentThreadId, "do the work", AGENT, 7, null);
      return runner.accept(effective);
    }

    SubagentTaskRequest continueRequest(UUID childThreadId) {
      return continueRequest(childThreadId, AGENT);
    }

    SubagentTaskRequest continueRequest(UUID childThreadId, String agent) {
      return new SubagentTaskRequest(
          invocationId, parentThreadId, "do the work", agent, 7, childThreadId);
    }

    AcceptCommandsTarget target() {
      return acceptedCommand.target();
    }

    List<NewThreadCommand> commands() {
      return acceptedCommand.commands();
    }

    void stubMaterialize(BranchSettings settings) {
      when(materializer.materializeSubagent(anyString(), any())).thenReturn(settings);
    }

    void stubParentSnapshot(List<SubagentBinding> bindings, SubagentContext context) {
      stubParentSnapshot(bindings, context, true);
    }

    void stubParentSnapshotWithoutSiblings(
        List<SubagentBinding> bindings, SubagentContext context) {
      stubParentSnapshot(bindings, context, false);
    }

    private void stubParentSnapshot(
        List<SubagentBinding> bindings, SubagentContext context, boolean attached) {
      ThreadSnapshot snapshot = parentSnapshot(bindings);
      // 先构造 sibling 再 when(...)：stubbing 表达式里不能再发起新的 stubbing。
      List<ToolInvocation> tools =
          List.of(toolSibling(attached ? invocationId : UUID.randomUUID()));
      when(snapshot.toolSiblings()).thenReturn(tools);
      // thenReturn 的实参不能包含 mock 调用，否则会被 Mockito 当作一次新的 stubbing。
      BranchSettings base = new BranchSettings(AGENT, MODEL, "env");
      RootPayload rootPayload = new RootPayload(base, context);
      when(snapshot.entryPath().root().payload()).thenReturn(rootPayload);
      when(runtime.getThreadSnapshot(parentThreadId)).thenReturn(snapshot);
    }

    ThreadSnapshot parentSnapshot(List<SubagentBinding> bindings) {
      ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
      ModelInvocation model = mock(ModelInvocation.class);
      ModelRequestSpec spec = mock(ModelRequestSpec.class);
      ThreadState thread = mock(ThreadState.class);
      EntryPath path = mock(EntryPath.class);
      Entry root = mock(Entry.class);
      Entry head = mock(Entry.class);
      when(snapshot.model()).thenReturn(model);
      when(snapshot.entryPath()).thenReturn(path);
      when(snapshot.thread()).thenReturn(thread);
      when(snapshot.queuedCommands()).thenReturn(List.of());
      when(model.requestSpec()).thenReturn(spec);
      when(spec.subagentBindings()).thenReturn(bindings);
      when(thread.id()).thenReturn(parentThreadId);
      when(thread.headEntryId()).thenReturn(parentHeadEntryId);
      when(thread.sessionId()).thenReturn(UUID.randomUUID());
      when(thread.yoloEnabled()).thenReturn(false);
      when(path.root()).thenReturn(root);
      when(path.head()).thenReturn(head);
      when(path.baseSettings()).thenReturn(new BranchSettings(AGENT, MODEL, "env"));
      // 非 TURN_END payload 即"没有未结清 turn"，用真实 ROOT payload 表达，不 mock sealed 接口。
      when(head.payload()).thenReturn(new RootPayload(new BranchSettings(AGENT, MODEL, "env")));
      return snapshot;
    }

    /** 静止的子 Thread 快照：无 Model、无 Tool siblings、无排队命令，ROOT 携带冻结的 SubagentContext。 */
    ThreadSnapshot stubChildSnapshot(
        UUID childThreadId, UUID childSessionId, UUID ownerParentThreadId, BranchSettings base) {
      this.childSessionId = childSessionId;
      ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
      ThreadState thread = mock(ThreadState.class);
      EntryPath path = mock(EntryPath.class);
      Entry root = mock(Entry.class);
      Entry head = mock(Entry.class);
      when(snapshot.model()).thenReturn(null);
      when(snapshot.entryPath()).thenReturn(path);
      when(snapshot.thread()).thenReturn(thread);
      when(snapshot.toolSiblings()).thenReturn(List.of());
      when(snapshot.queuedCommands()).thenReturn(List.of());
      when(thread.id()).thenReturn(childThreadId);
      when(thread.sessionId()).thenReturn(childSessionId);
      when(thread.headEntryId()).thenReturn(childHeadEntryId);
      when(thread.nextCommandSequence()).thenReturn(childNextCommandSequence);
      when(path.root()).thenReturn(root);
      when(path.head()).thenReturn(head);
      when(path.baseSettings()).thenReturn(base);
      when(head.payload()).thenReturn(new RootPayload(base));
      when(root.payload())
          .thenReturn(
              new RootPayload(
                  base,
                  new SubagentContext(
                      ownerParentThreadId, ownerParentThreadId, UUID.randomUUID(), 2)));
      when(runtime.getThreadSnapshot(childThreadId)).thenReturn(snapshot);
      return snapshot;
    }

    SubagentTask task(UUID childSessionId, UUID childThreadId, SubagentTaskStatus status) {
      // 已终结状态必须成对携带终态与终结时间（与数据库 ck_harness_subagent_task_state_shape 同构）。
      boolean settled = status != SubagentTaskStatus.OPEN;
      return new SubagentTask(
          invocationId,
          parentThreadId,
          parentThreadId,
          childSessionId,
          childThreadId,
          boundaryEntryId,
          AGENT,
          "do the work",
          7,
          status,
          settled ? Outcome.COMPLETED : null,
          settled ? "done" : null,
          null,
          null,
          0L,
          settled ? Instant.parse("2026-01-01T00:00:01Z") : null,
          Instant.parse("2026-01-01T00:00:00Z"),
          Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static UUID threadIdOf(AcceptCommandsTarget target) {
      return switch (target) {
        case AcceptCommandsTarget.NewSession newSession -> newSession.threadId();
        case AcceptCommandsTarget.NewThread newThread -> newThread.threadId();
        case AcceptCommandsTarget.Thread thread -> thread.threadId();
      };
    }
  }

  private static ToolInvocation toolSibling(UUID invocationId) {
    ToolInvocation tool = mock(ToolInvocation.class);
    when(tool.id()).thenReturn(invocationId);
    return tool;
  }
}
