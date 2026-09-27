package fun.fengwk.kkstudio.platform.harness.oneshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.testing.TestThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OneShot 的 root join、固定回执观察、取消/超时/中断边界回归。
 *
 * <p>测试意图：one-shot 只用固定 ticket 的 receipt 判定结果（绝不据最新 head 推测），且 deadline 前的最终权威读要能兜住丢失的唤醒信号。
 */
class HarnessOneShotServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("worker", new ModelSelection("provider", "model", "default"), null);

  private HarnessRuntime runtime;
  private AgentBranchSettingsMaterializer materializer;
  private TestThreadChangeSource changeSource;
  private HarnessOneShotService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    materializer = mock(AgentBranchSettingsMaterializer.class);
    ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(materializer.materialize(any())).thenReturn(SETTINGS);
    changeSource = new TestThreadChangeSource();
    service = new HarnessOneShotService(runtimes, materializer, changeSource);
  }

  private OneShotTicket stubSubmit() {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    Session session = mock(Session.class);
    when(session.id()).thenReturn(UUID.randomUUID());
    when(accepted.session()).thenReturn(session);
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);
    return service.submit("worker", "system", AgentMessage.user("prompt"));
  }

  @Test
  void submitAcceptsSourcePromptAndRootTicketInOneTransaction() {
    // root completion ticket：parent 为空（不投递父消息），但源 prompt 仍必须与 join 原子接受。
    OneShotTicket ticket = stubSubmit();

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    ArgumentCaptor<ThreadJoinRequest> join = ArgumentCaptor.forClass(ThreadJoinRequest.class);
    verify(runtime)
        .acceptCommandsAndJoin(command.capture(), join.capture(), eq(AcceptancePreflight.IDENTITY));

    AcceptCommandsTarget.NewSession target =
        (AcceptCommandsTarget.NewSession) command.getValue().target();
    assertEquals(ticket.threadId(), target.threadId());
    assertEquals(null, target.parentThreadId());
    assertEquals(null, join.getValue().parentThreadId());
    assertEquals(null, join.getValue().expectedParentHeadEntryId());
    assertEquals(ticket.invocationId(), join.getValue().invocationId());
    assertEquals(SETTINGS, target.rootSettings());
    // 唯一命令是携带可信 system reminder 前缀的 USER 消息。
    assertEquals(1, command.getValue().commands().size());
    AgentMessage submitted =
        ((CustomMessageCommandPayload) command.getValue().commands().get(0).payload()).message();
    assertEquals(AgentMessageRole.USER, submitted.role());
    assertEquals(
        "<system-reminder>\nsystem\n</system-reminder>\n\n",
        ((TextMessageContent) submitted.contents().getFirst()).text());
    assertEquals("prompt", ((TextMessageContent) submitted.contents().get(1)).text());
    // 重启后只凭持久化的 threadId 就能恢复同一 ticket。
    assertEquals(ticket, OneShotTicket.forThread(ticket.threadId()));
    assertEquals(join.getValue().requestHash(), command.getValue().commands().get(0).requestHash());
  }

  /** 空白 system 文本不得伪造提醒段，调用方消息原样提交。 */
  @Test
  void omitsReminderWhenTrustedSystemTextIsBlank() {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    when(accepted.session()).thenReturn(mock(Session.class));
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);
    service.submit(
        "worker",
        "  ",
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("only"))));

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommandsAndJoin(command.capture(), any(), any());
    AgentMessage submitted =
        ((CustomMessageCommandPayload) command.getValue().commands().get(0).payload()).message();
    assertEquals(1, submitted.contents().size());
    assertEquals("only", ((TextMessageContent) submitted.contents().getFirst()).text());
  }

  @Test
  void submitForwardsCustomPreflightUnchanged() {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    when(accepted.session()).thenReturn(mock(Session.class));
    when(runtime.acceptCommandsAndJoin(any(), any(), any())).thenReturn(accepted);
    AcceptancePreflight preflight = (tx, session, commands) -> List.copyOf(commands);
    service.submit("worker", "system", AgentMessage.user("prompt"), preflight);

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommandsAndJoin(command.capture(), any(), eq(preflight));
    List<NewThreadCommand> prepared =
        preflight.prepare(
            null, new Session(UUID.randomUUID(), "s", NOW), command.getValue().commands());
    assertEquals(command.getValue().commands(), prepared);
  }

  @Test
  void awaitReturnsReceiptReportWithoutReadingAnyThreadHead() {
    // 终态优先：订阅后的首次权威读取已匹配时立即返回，不等待任何信号。
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(ticket.invocationId()))
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "final answer", null, null)));

    assertEquals("final answer", service.await(ticket, Duration.ofSeconds(1), () -> true));

    verify(runtime).projectJoinReceipt(ticket.invocationId());
    // 固定回执即权威事实：绝不读子线程最新 head 去推测结果。
    verify(runtime, never()).getThreadSnapshot(any());
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()), "success must release");
  }

  @Test
  void awaitReReadsOnlyTheFixedReceiptOnVersionSignal() throws Exception {
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(ticket.invocationId()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "later answer", null, null)));

    CompletableFuture<String> awaited =
        CompletableFuture.supplyAsync(
            () -> service.await(ticket, Duration.ofSeconds(5), () -> true));
    waitForSubscription(ticket.threadId());
    changeSource.signal(ticket.threadId());

    assertEquals("later answer", awaited.join());
    verify(runtime, times(2)).projectJoinReceipt(ticket.invocationId());
    verify(runtime, never()).getThreadSnapshot(any());
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }

  /**
   * 唤醒信号可能丢失：deadline 前的最终权威读必须发现已匹配的结果，而不是误报 timeout。
   *
   * <p>这里第一次读为空（模拟漏通知），随后结果已落定，await 仍需在超时边界前返回它。
   */
  @Test
  void awaitFindsResultAtDeadlineWhenWakeSignalWasLost() {
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(ticket.invocationId()))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "recovered answer", null, null)));

    assertEquals("recovered answer", service.await(ticket, Duration.ofNanos(1), () -> true));

    verify(runtime, never()).stop(any(StopCommand.class));
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }

  @Test
  void awaitReportsNonCompletedOutcomesWithTheirDetail() {
    OneShotTicket failed = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(failed.invocationId()))
        .thenReturn(
            Optional.of(receipt(ThreadJoinOutcome.ERROR, null, "partial", "provider boom")));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.await(failed, Duration.ofSeconds(1), () -> true));
    assertTrue(error.getMessage().contains("ERROR"), error.getMessage());
    assertTrue(error.getMessage().contains("provider boom"), error.getMessage());

    OneShotTicket cancelled = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(cancelled.invocationId()))
        .thenReturn(
            Optional.of(receipt(ThreadJoinOutcome.CANCELLED, null, null, "Cancelled by user.")));
    assertThrows(
        IllegalStateException.class,
        () -> service.await(cancelled, Duration.ofSeconds(1), () -> true));

    // 完成但报告为空白：明确失败而不是返回空串。
    OneShotTicket blank = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(blank.invocationId()))
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "  ", null, null)));
    assertThrows(
        IllegalStateException.class, () -> service.await(blank, Duration.ofSeconds(1), () -> true));
  }

  @Test
  void timeoutStopsOnlyItsOwnThreadAndReleasesSubscription() {
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(ticket.invocationId())).thenReturn(Optional.empty());
    when(runtime.getThreadSnapshot(ticket.threadId())).thenReturn(idle(ticket.threadId()));

    assertThrows(
        IllegalStateException.class, () -> service.await(ticket, Duration.ofNanos(1), () -> true));

    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(stop.capture());
    assertEquals(ticket.threadId(), stop.getValue().threadId());
    assertEquals(
        0,
        changeSource.activeSubscriptions(ticket.threadId()),
        "timeout must release the subscription");
  }

  @Test
  void noDurableReadWithoutSignalAndCallerInactiveStopsAndReleases() throws Exception {
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch firstRead = new CountDownLatch(1);
    when(runtime.projectJoinReceipt(ticket.invocationId()))
        .thenAnswer(
            invocation -> {
              reads.incrementAndGet();
              firstRead.countDown();
              return Optional.empty();
            });
    when(runtime.getThreadSnapshot(ticket.threadId())).thenReturn(idle(ticket.threadId()));
    AtomicBoolean keepWaiting = new AtomicBoolean(true);
    CompletableFuture<String> awaited =
        CompletableFuture.supplyAsync(
            () -> service.await(ticket, Duration.ofSeconds(5), keepWaiting::get));

    assertTrue(firstRead.await(5, TimeUnit.SECONDS));
    keepWaiting.set(false);

    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> awaited.get(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals("one-shot caller is no longer active", failure.getCause().getMessage());
    verify(runtime).stop(any(StopCommand.class));
    assertEquals(1, reads.get(), "no durable read without a version signal");
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }

  @Test
  void interruptionReleasesSubscription() throws Exception {
    OneShotTicket ticket = OneShotTicket.forThread(UUID.randomUUID());
    when(runtime.projectJoinReceipt(ticket.invocationId())).thenReturn(Optional.empty());
    when(runtime.getThreadSnapshot(ticket.threadId())).thenReturn(idle(ticket.threadId()));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    Thread awaiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    service.await(ticket, Duration.ofSeconds(5), () -> true);
                  } catch (Throwable error) {
                    failure.set(error);
                  } finally {
                    done.countDown();
                  }
                });
    waitForSubscription(ticket.threadId());
    awaiter.interrupt();

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.get());
    assertEquals("one-shot observation thread was interrupted", failure.get().getMessage());
    assertEquals(0, changeSource.activeSubscriptions(ticket.threadId()));
  }

  @Test
  void validatesInputsAndBestEffortStopToleratesRaces() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submit(
                "worker",
                "system",
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("x")))));
    assertThrows(
        NullPointerException.class, () -> service.await(null, Duration.ofSeconds(1), () -> true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.await(OneShotTicket.forThread(UUID.randomUUID()), Duration.ZERO, () -> true));
    assertThrows(NullPointerException.class, () -> service.stop(null));
    verify(runtime, never()).getThreadSnapshot(null);

    // stop 的版本竞争与已消失线程都必须被容忍。
    UUID threadId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(idle(threadId))
        .thenThrow(new HarnessRuntimeNotFoundException("gone"));
    doThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale"))
        .when(runtime)
        .stop(any(StopCommand.class));
    service.stop(threadId);
  }

  private void waitForSubscription(UUID threadId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!changeSource.isSubscribed(threadId) && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(changeSource.isSubscribed(threadId), "await must subscribe before observing");
  }

  private static ThreadJoinReceipt receipt(
      ThreadJoinOutcome outcome, String report, String partialResult, String error) {
    return new ThreadJoinReceipt(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "worker",
        outcome,
        "prompt",
        report,
        partialResult,
        error);
  }

  private static ThreadSnapshot idle(UUID threadId) {
    ThreadState thread =
        new ThreadState(
            threadId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "0".repeat(64),
            "oneshot",
            false,
            ThreadLifecycleStatus.IDLE,
            1L,
            0L,
            NOW,
            NOW);
    return new ThreadSnapshot(thread, mock(EntryPath.class), List.of(), null, List.of(), List.of());
  }
}
