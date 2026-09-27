package fun.fengwk.kkstudio.platform.harness.oneshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.testing.TestThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

class HarnessOneShotServiceTest {
  private final HarnessRuntime runtime = mock(HarnessRuntime.class);
  private final AgentBranchSettingsMaterializer materializer =
      mock(AgentBranchSettingsMaterializer.class);
  private final TestThreadChangeSource changeSource = new TestThreadChangeSource();
  private final HarnessOneShotService service;

  @SuppressWarnings("unchecked")
  HarnessOneShotServiceTest() {
    ObjectProvider<HarnessRuntime> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(runtime);
    service = new HarnessOneShotService(provider, materializer, changeSource);
  }

  @Test
  void submitAcceptsRootTicketWithThePromptInOneTransaction() {
    // 测试意图：root 无 parent 也需 join，且 source prompt 与 ticket 在同一接受事务内提交。
    when(materializer.materialize("worker"))
        .thenReturn(new BranchSettings("worker", new ModelSelection("p", "m", "v"), null));
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    ThreadState thread = mock(ThreadState.class);
    when(accepted.thread()).thenReturn(thread);
    when(runtime.acceptCommandsAndJoin(any(), any(), any()))
        .thenAnswer(
            inv -> {
              AcceptCommandsCommand command = inv.getArgument(0);
              when(thread.id())
                  .thenReturn(((AcceptCommandsTarget.NewSession) command.target()).threadId());
              return accepted;
            });

    OneShotTicket ticket = service.submit("worker", "system", AgentMessage.user("prompt"));
    ArgumentCaptor<ThreadJoinRequest> join = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime).acceptCommandsAndJoin(any(), join.capture(), any(AcceptancePreflight.class));
    assertEquals(ticket.invocationId(), join.getValue().invocationId());
    assertEquals(null, join.getValue().parentThreadId());
    assertEquals(ticket, OneShotTicket.forThread(ticket.threadId()));
  }

  @Test
  void awaitUsesOnlyFixedReceiptAndReleasesSubscription() {
    // 测试意图：即使最新 head 被后续输入推进，await 只能读取固定 invocation 的 receipt。
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    ThreadJoinReceipt receipt = mock(ThreadJoinReceipt.class);
    when(receipt.outcome()).thenReturn(ThreadJoinReceipt.Outcome.COMPLETED);
    when(receipt.report()).thenReturn("answer");
    when(runtime.projectJoinReceipt(ticket.invocationId())).thenReturn(Optional.of(receipt));

    assertEquals("answer", service.await(ticket, Duration.ofSeconds(1), () -> true));
    verify(runtime).projectJoinReceipt(ticket.invocationId());
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }

  @Test
  void timeoutStopsOnlyTheTicketThread() {
    // 测试意图：未匹配时超时 stop 当前 root，不误取消其他 one-shot。
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    ThreadState thread = mock(ThreadState.class);
    when(snapshot.thread()).thenReturn(thread);
    when(runtime.getThreadSnapshot(ticket.threadId())).thenReturn(snapshot);
    assertThrows(
        IllegalStateException.class, () -> service.await(ticket, Duration.ofNanos(1), () -> true));
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }
}
