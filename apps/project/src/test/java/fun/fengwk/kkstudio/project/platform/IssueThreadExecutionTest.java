package fun.fengwk.kkstudio.project.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IssueThreadExecution} 的单元测试：只验证它对宿主运行时的精确转发与窄事实投影。
 *
 * <p>测试意图：读取只投影六个消费字段（身份/游标/历史路径/执行事实），其余方法原样转发且不改变返回对象身份；运行时异常原样传播，不加静默兜底。
 */
class IssueThreadExecutionTest {

  private static final UUID THREAD_ID = UUID.randomUUID();

  private final HarnessRuntime runtime = mock(HarnessRuntime.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);

  private final IssueThreadExecution port =
      new IssueThreadExecution(runtimes, new AgentTaskService(runtimes));

  @BeforeEach
  void runtimeAvailable() {
    when(runtimes.getIfAvailable()).thenReturn(runtime);
  }

  /** 读取只投影确切六字段：身份与游标来自 Thread，历史路径与执行事实来自快照。 */
  @Test
  void readThreadProjectsExactlySixFields() {
    UUID sessionId = UUID.randomUUID();
    UUID headEntryId = UUID.randomUUID();
    EntryPath entryPath = mock(EntryPath.class);
    ThreadExecutionFacts facts = new ThreadExecutionFacts(true, false, true);
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(thread.headEntryId()).thenReturn(headEntryId);
    when(thread.version()).thenReturn(7L);
    when(thread.nextCommandSequence()).thenReturn(9L);
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    when(snapshot.requireFullPath()).thenReturn(entryPath);
    when(snapshot.executionFacts()).thenReturn(facts);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot);

    IssueThreadView view = port.readThread(THREAD_ID);

    assertEquals(sessionId, view.sessionId());
    assertEquals(headEntryId, view.headEntryId());
    assertEquals(7L, view.version());
    assertEquals(9L, view.nextCommandSequence());
    assertSame(entryPath, view.entryPath());
    assertSame(facts, view.executionFacts());
    verify(runtime).getThreadSnapshot(THREAD_ID);
  }

  /** 读取失败原样传播宿主异常，不吞掉也不换成空视图。 */
  @Test
  void readThreadPropagatesMissingThread() {
    HarnessRuntimeNotFoundException missing = new HarnessRuntimeNotFoundException("thread missing");
    when(runtime.getThreadSnapshot(THREAD_ID)).thenThrow(missing);

    assertSame(
        missing,
        assertThrows(HarnessRuntimeNotFoundException.class, () -> port.readThread(THREAD_ID)));
  }

  /** 部署缺少运行时（平台只装配上下文）时确定失败，绝不退回本地假执行或空视图。 */
  @Test
  void readThreadWithoutRuntimeFailsDeterministically() {
    when(runtimes.getIfAvailable()).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> port.readThread(THREAD_ID));
  }

  /** 命令接受原样转发命令与 preflight，并返回宿主结果对象本身。 */
  @Test
  void acceptCommandsForwardsExactly() {
    AcceptCommandsCommand command = mock(AcceptCommandsCommand.class);
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    when(runtime.acceptCommands(command, AcceptancePreflight.IDENTITY)).thenReturn(accepted);

    assertSame(accepted, port.acceptCommands(command, AcceptancePreflight.IDENTITY));
    verify(runtime).acceptCommands(command, AcceptancePreflight.IDENTITY);
  }

  /** Run 任务经 Agent 任务服务提交：控制器发起、无父回执票据以 Run id 为身份，续用稳定 Thread 时携带冻结 cursor。 */
  @Test
  void submitRunTaskSpawnsAControllerTaskWithTheRunTicket() {
    UUID runId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID head = UUID.randomUUID();
    BranchSettings settings =
        new BranchSettings("worker", ModelSelection.model("model", "v1"), null);
    NewThreadCommand input =
        new NewThreadCommand(
            new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID());
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(accepted.thread()).thenReturn(thread);
    when(runtime.spawn(any(SpawnRequest.class))).thenReturn(accepted);

    assertSame(
        accepted,
        port.submitRunTask(
            new IssueRunTask(
                runId,
                threadId,
                null,
                settings,
                false,
                new SpawnRequest.Continuation(head, 4L),
                List.of(input),
                input.requestHash())));

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture());
    SpawnRequest request = spawn.getValue();
    assertEquals(runId, request.ticketId());
    assertEquals(threadId, request.threadId());
    assertEquals(SpawnTrigger.CONTROLLER, request.trigger());
    assertEquals(JoinDelivery.RECEIPT_ONLY, request.delivery());
    assertNull(request.parentThreadId());
    assertEquals(4L, request.continuation().expectedNextCommandSequence());
    assertEquals(input.requestHash(), request.requestHash());
  }

  /** 停止只回传权威 head UUID，不把完整 ThreadState 暴露给 Project。 */
  @Test
  void stopReturnsAuthoritativeHead() {
    StopCommand command = mock(StopCommand.class);
    UUID headEntryId = UUID.randomUUID();
    ThreadState stopped = mock(ThreadState.class);
    when(stopped.headEntryId()).thenReturn(headEntryId);
    when(runtime.stop(command)).thenReturn(new StopResult(false, stopped, List.of()));

    assertEquals(headEntryId, port.stop(command));
    verify(runtime).stop(command);
  }

  /** join 查询与命令重放查询原样转发 Optional 结果。 */
  @Test
  void readQueriesForwardOptionalResults() {
    UUID invocationId = UUID.randomUUID();
    ThreadJoin join = mock(ThreadJoin.class);
    ThreadJoinReceipt receipt = mock(ThreadJoinReceipt.class);
    ThreadCommand threadCommand = mock(ThreadCommand.class);
    UUID idempotencyKey = UUID.randomUUID();
    when(runtime.findJoin(invocationId)).thenReturn(Optional.of(join));
    when(runtime.projectJoinReceipt(invocationId)).thenReturn(Optional.of(receipt));
    when(runtime.findThreadCommand(THREAD_ID, idempotencyKey))
        .thenReturn(Optional.of(threadCommand));

    assertSame(join, port.findJoin(invocationId).orElseThrow());
    assertSame(receipt, port.projectJoinReceipt(invocationId).orElseThrow());
    assertSame(threadCommand, port.findThreadCommand(THREAD_ID, idempotencyKey).orElseThrow());
    verify(runtime).findJoin(invocationId);
    verify(runtime).projectJoinReceipt(invocationId);
    verify(runtime).findThreadCommand(THREAD_ID, idempotencyKey);
  }

  /** 未匹配的 join / command 返回空而不制造占位对象。 */
  @Test
  void missingReadsReturnEmptyOptional() {
    UUID invocationId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    when(runtime.findJoin(any())).thenReturn(Optional.empty());
    when(runtime.projectJoinReceipt(any())).thenReturn(Optional.empty());
    when(runtime.findThreadCommand(any(), any())).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), port.findJoin(invocationId));
    assertEquals(Optional.empty(), port.projectJoinReceipt(invocationId));
    assertEquals(Optional.empty(), port.findThreadCommand(THREAD_ID, idempotencyKey));
  }

  /** 构造适配器不会提前解析运行时，避免把执行依赖拉进宿主装配顺序。 */
  @Test
  @SuppressWarnings("unchecked")
  void constructionDoesNotResolveRuntime() {
    ObjectProvider<HarnessRuntime> lazyRuntime = mock(ObjectProvider.class);
    new IssueThreadExecution(lazyRuntime, new AgentTaskService(lazyRuntime));
    verifyNoInteractions(lazyRuntime);
  }
}
