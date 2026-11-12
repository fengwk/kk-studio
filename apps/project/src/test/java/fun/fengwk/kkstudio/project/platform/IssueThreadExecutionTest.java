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

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
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
    DomainException missing = DomainException.notFound("thread", "thread missing");
    when(runtime.getThreadSnapshot(THREAD_ID)).thenThrow(missing);

    assertSame(missing, assertThrows(DomainException.class, () -> port.readThread(THREAD_ID)));
    assertEquals(DomainErrorCode.NOT_FOUND, missing.code());
    assertNull(missing.reason());
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
        new BranchSettings(
            "worker", BuiltinExecution.of(ModelSelection.model("model", "v1")), null);
    NewThreadCommand input =
        new NewThreadCommand(
            new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID());
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(accepted.thread()).thenReturn(thread);
    when(runtime.spawn(any(SpawnRequest.class), any())).thenReturn(accepted);

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
    verify(runtime).spawn(spawn.capture(), any());
    SpawnRequest request = spawn.getValue();
    assertEquals(runId, request.ticketId());
    assertEquals(threadId, request.threadId());
    assertEquals(ThreadKind.CONTROLLER_TASK, request.kind());
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

  /** join 查询原样转发 Optional 结果。 */
  @Test
  void readQueriesForwardOptionalResults() {
    UUID invocationId = UUID.randomUUID();
    ThreadJoin join = mock(ThreadJoin.class);
    ThreadJoinReceipt receipt = mock(ThreadJoinReceipt.class);
    when(runtime.findJoin(invocationId)).thenReturn(Optional.of(join));
    when(runtime.projectJoinReceipt(invocationId)).thenReturn(Optional.of(receipt));

    assertSame(join, port.findJoin(invocationId).orElseThrow());
    assertSame(receipt, port.projectJoinReceipt(invocationId).orElseThrow());
    verify(runtime).findJoin(invocationId);
    verify(runtime).projectJoinReceipt(invocationId);
  }

  /** 未匹配的 join 返回空而不制造占位对象。 */
  @Test
  void missingReadsReturnEmptyOptional() {
    UUID invocationId = UUID.randomUUID();
    when(runtime.findJoin(any())).thenReturn(Optional.empty());
    when(runtime.projectJoinReceipt(any())).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), port.findJoin(invocationId));
    assertEquals(Optional.empty(), port.projectJoinReceipt(invocationId));
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
