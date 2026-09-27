package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class SubagentTaskRunnerTest {
  private final UUID parentId = UUID.randomUUID();
  private final UUID invocationId = UUID.randomUUID();
  private final UUID headId = UUID.randomUUID();
  private final BranchSettings settings =
      new BranchSettings("coder", new ModelSelection("provider", "model", "default"), "env");
  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final AgentBranchSettingsMaterializer materializer =
      mock(AgentBranchSettingsMaterializer.class);
  private final SubagentTaskRunner runner =
      new SubagentTaskRunner(
          () -> runtime, materializer, () -> new SubagentConfig(3, 2, 0, Duration.ZERO, 10));

  private SubagentTaskRequest request(UUID resumeId) {
    return new SubagentTaskRequest(invocationId, parentId, "do work", "coder", 7, resumeId);
  }

  private void parent() {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    ThreadState thread = mock(ThreadState.class);
    ModelInvocation model = mock(ModelInvocation.class);
    ModelRequestSpec spec = mock(ModelRequestSpec.class);
    ToolInvocation tool = mock(ToolInvocation.class);
    EntryPath path = mock(EntryPath.class);
    when(thread.headEntryId()).thenReturn(headId);
    when(snapshot.thread()).thenReturn(thread);
    when(snapshot.model()).thenReturn(model);
    when(snapshot.toolSiblings()).thenReturn(List.of(tool));
    when(tool.id()).thenReturn(invocationId);
    when(model.requestSpec()).thenReturn(spec);
    when(spec.subagentBindings()).thenReturn(List.of(new SubagentBinding("coder", "worker")));
    when(snapshot.entryPath()).thenReturn(path);
    when(path.baseSettings()).thenReturn(settings);
    when(runtime.getThreadSnapshot(parentId)).thenReturn(snapshot);
    when(materializer.materializeSubagent("coder", "env")).thenReturn(settings);
  }

  @Test
  void createsChildWithAtomicJoinAndStableSourceIdentity() {
    // 测试意图：子身份、原子 join、父关系与源 prompt 在同一次调用中冻结；不存在平台 task 写入。
    parent();
    Session session = mock(Session.class);
    ThreadState thread = mock(ThreadState.class);
    when(session.id()).thenReturn(UUID.randomUUID());
    when(thread.id())
        .thenReturn(SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"));
    AcceptedCommands result = mock(AcceptedCommands.class);
    when(result.session()).thenReturn(session);
    when(result.thread()).thenReturn(thread);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(result);

    var accepted = runner.accept(request(null));
    ArgumentCaptor<AcceptCommandsCommand> commands =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    ArgumentCaptor<ThreadJoinRequest> join = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime)
        .acceptCommandsAndJoin(commands.capture(), join.capture(), any(AcceptancePreflight.class));
    var target = (AcceptCommandsTarget.NewSession) commands.getValue().target();
    assertEquals(parentId, target.parentThreadId());
    assertEquals(accepted.childThreadId(), target.threadId());
    assertEquals(1, commands.getValue().commands().size());
    assertTrue(
        commands.getValue().commands().getFirst().payload() instanceof UserMessageCommandPayload);
    assertEquals(SubagentTaskRunner.requestHash(request(null)), join.getValue().requestHash());
    assertEquals(2, join.getValue().maxConcurrentChildren());
    assertEquals(Integer.MAX_VALUE, join.getValue().maxConcurrentThreads());
  }

  @Test
  void replayRequiresTheSameParentRequestAndTarget() {
    // 测试意图：碰撞 invocationId 不得泄漏他人子身份；精确重放不重新提交命令。
    SubagentTaskRequest original = request(null);
    UUID childId = SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/");
    ThreadJoin join = mock(ThreadJoin.class);
    when(join.parentThreadId()).thenReturn(parentId);
    when(join.childThreadId()).thenReturn(childId);
    when(join.requestHash()).thenReturn(SubagentTaskRunner.requestHash(original));
    when(join.agent()).thenReturn("coder");
    when(join.maxTurns()).thenReturn(7);
    when(runtime.findJoin(invocationId)).thenReturn(Optional.of(join));
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    ThreadState thread = mock(ThreadState.class);
    when(child.thread()).thenReturn(thread);
    when(thread.sessionId()).thenReturn(UUID.randomUUID());
    when(runtime.getThreadSnapshot(childId)).thenReturn(child);

    assertTrue(runner.accept(original).replayed());
    assertThrows(
        SubagentTaskRejectedException.class,
        () ->
            runner.accept(
                new SubagentTaskRequest(
                    invocationId, UUID.randomUUID(), "do work", "coder", 7, null)));
    assertThrows(
        SubagentTaskRejectedException.class,
        () ->
            runner.accept(
                new SubagentTaskRequest(invocationId, parentId, "different", "coder", 7, null)));
    assertThrows(
        SubagentTaskRejectedException.class, () -> runner.accept(request(UUID.randomUUID())));
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void busyChildMayReceiveNewPromptWithoutPriorJoinDelivery() {
    // 测试意图：resume 只校验永久父关系并追加命令，不查看旧 join 是否交付或要求子 idle。
    parent();
    UUID childId = UUID.randomUUID();
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    ThreadState state = mock(ThreadState.class);
    EntryPath path = mock(EntryPath.class);
    when(child.thread()).thenReturn(state);
    when(state.parentThreadId()).thenReturn(parentId);
    when(state.headEntryId()).thenReturn(UUID.randomUUID());
    when(state.nextCommandSequence()).thenReturn(9L);
    when(child.entryPath()).thenReturn(path);
    when(path.baseSettings()).thenReturn(settings);
    when(runtime.getThreadSnapshot(childId)).thenReturn(child);
    Session session = mock(Session.class);
    when(session.id()).thenReturn(UUID.randomUUID());
    when(state.id()).thenReturn(childId);
    AcceptedCommands result = mock(AcceptedCommands.class);
    when(result.session()).thenReturn(session);
    when(result.thread()).thenReturn(state);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(result);

    runner.accept(request(childId));
    ArgumentCaptor<AcceptCommandsCommand> commands =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommandsAndJoin(commands.capture(), any(), any());
    assertEquals(childId, ((AcceptCommandsTarget.Thread) commands.getValue().target()).threadId());
    assertEquals(1, commands.getValue().commands().size());
  }

  @Test
  void rejectsForeignChildBeforeAtomicAcceptance() {
    // 测试意图：即使拿到其他执行树的 threadId，也不能往其命令队列中追加 prompt。
    parent();
    UUID childId = UUID.randomUUID();
    ThreadSnapshot child = mock(ThreadSnapshot.class);
    ThreadState state = mock(ThreadState.class);
    when(child.thread()).thenReturn(state);
    when(state.parentThreadId()).thenReturn(UUID.randomUUID());
    when(runtime.getThreadSnapshot(childId)).thenReturn(child);
    assertThrows(SubagentTaskRejectedException.class, () -> runner.accept(request(childId)));
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }

  @Test
  void rejectsDetachedParentInvocation() {
    // 测试意图：没有当前冻结 Model Tool sibling 时不允许假借 invocationId 委派。
    ThreadSnapshot detached = mock(ThreadSnapshot.class);
    when(runtime.getThreadSnapshot(parentId)).thenReturn(detached);
    assertThrows(SubagentTaskRejectedException.class, () -> runner.accept(request(null)));
    verify(runtime, never()).acceptCommandsAndJoin(any(), any(), any());
  }
}
