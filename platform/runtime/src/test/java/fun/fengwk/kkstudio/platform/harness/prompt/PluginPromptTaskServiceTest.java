package fun.fengwk.kkstudio.platform.harness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.testing.TestThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
 * Prompt 任务窄端口的宿主实现回归：root join 接受、媒体接收、固定回执观察、取消/超时/中断与 stop 冲突边界。
 *
 * <p>测试意图：任务只用固定 join 的 receipt 判定结果（绝不据最新 head 推测），媒体在接受事务内按捕获位置物化并先 retain 再 release， deadline
 * 前的最终权威读要能兜住丢失的唤醒信号。
 */
class PluginPromptTaskServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("worker", new ModelSelection("model", "default"), null);
  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final UUID UPLOAD_ID = UUID.randomUUID();
  private static final UUID BLOB_ID = UUID.randomUUID();

  private HarnessRuntime runtime;
  private AgentBranchSettingsMaterializer materializer;
  private TestThreadChangeSource changeSource;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;
  private PluginPromptResourceBridge resources;
  private PluginPromptTaskService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    materializer = mock(AgentBranchSettingsMaterializer.class);
    ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    // AgentTaskService 只把统一 spawn 的接受结果包装为句柄：测试用窄 mock 表达接受事实。
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    Session acceptedSession = mock(Session.class);
    ThreadState acceptedThread = mock(ThreadState.class);
    when(acceptedSession.id()).thenReturn(SESSION_ID);
    when(acceptedThread.id()).thenReturn(UUID.randomUUID());
    when(accepted.session()).thenReturn(acceptedSession);
    when(accepted.thread()).thenReturn(acceptedThread);
    when(runtime.spawn(any(SpawnRequest.class))).thenReturn(accepted);
    when(materializer.materialize(any())).thenReturn(SETTINGS);
    changeSource = new TestThreadChangeSource();
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    resources = new PluginPromptResourceBridge(uploadService, refManager);
    service =
        new PluginPromptTaskService(
            new AgentTaskService(runtimes), materializer, changeSource, resources);
  }

  private static List<PluginPromptContent> textContents() {
    return List.of(new PluginPromptContent.Text("prompt"));
  }

  @Test
  void submitAcceptsSourcePromptAndRootTicketInOneTransaction() {
    // root completion ticket：parent 为空（不投递父消息），但源 prompt 仍必须与 join 原子接受。
    UUID taskId = service.submit("worker", "system", textContents());

    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture());

    SpawnRequest target = command.getValue();
    assertEquals(taskId, target.threadId());
    assertEquals(null, target.parentThreadId());
    assertEquals(null, target.expectedParentHeadEntryId());
    assertEquals(SpawnTrigger.PLUGIN, target.trigger());
    assertEquals(JoinDelivery.RECEIPT_ONLY, target.delivery());
    assertEquals(PluginPromptTaskService.invocationId(taskId), target.ticketId());
    assertEquals(SETTINGS, target.settings());
    // 唯一命令是携带可信 system reminder 前缀的 USER 消息。
    assertEquals(1, command.getValue().commands().size());
    AgentMessage submitted =
        ((CustomMessageCommandPayload) command.getValue().commands().get(0).payload()).message();
    assertEquals(AgentMessageRole.USER, submitted.role());
    assertEquals(
        "<system-reminder>\nsystem\n</system-reminder>\n\n",
        ((TextMessageContent) submitted.contents().getFirst()).text());
    assertEquals("prompt", ((TextMessageContent) submitted.contents().get(1)).text());
    // 任务句柄与固定 invocation 的已定义派生关系不能随实现重构而改变。
    assertEquals(
        UUID.nameUUIDFromBytes(
            ("kk-studio/harness/oneshot/" + taskId).getBytes(StandardCharsets.UTF_8)),
        PluginPromptTaskService.invocationId(taskId),
        "the durable task handle must preserve its join identity");
    assertEquals(target.requestHash(), command.getValue().commands().get(0).requestHash());
  }

  /** 空白 system 文本不得伪造提醒段：提交形态与接受事务内的 durable 形态都不注入。 */
  @Test
  void omitsReminderWhenTrustedSystemTextIsBlank() {
    service.submit("worker", "  ", List.of(new PluginPromptContent.Text("only")));

    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture());
    AgentMessage submitted =
        ((CustomMessageCommandPayload) command.getValue().commands().get(0).payload()).message();
    assertEquals(1, submitted.contents().size());
    assertEquals("only", ((TextMessageContent) submitted.contents().getFirst()).text());

    NewThreadCommand raw = command.getValue().commands().getFirst();
    List<NewThreadCommand> prepared =
        command
            .getValue()
            .preflight()
            .prepare(
                mock(HarnessStore.Transaction.class),
                new Session(SESSION_ID, "h3", NOW),
                List.of(raw));
    AgentMessage durable = ((CustomMessageCommandPayload) prepared.getFirst().payload()).message();
    assertEquals(1, durable.contents().size());
    assertEquals("only", ((TextMessageContent) durable.contents().getFirst()).text());
  }

  /**
   * 媒体片段在接受事务内物化为 durable RESOURCE：锁定暂存、以权威文件名建内容、Session 先 retain 再 release upload owner，且保持幂等键与请求
   * hash 不变。
   */
  @Test
  void mediaContentIsMaterializedAsDurableResourceAtAcceptTime() {
    PluginPromptResources.StagedResource staged =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "authoritative.png"));

    service.submit(
        "worker",
        "system",
        List.of(
            new PluginPromptContent.Text("table"),
            new PluginPromptContent.Media(staged),
            new PluginPromptContent.Text("tail")));

    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture());

    NewThreadCommand raw = command.getValue().commands().getFirst();
    List<NewThreadCommand> prepared =
        command
            .getValue()
            .preflight()
            .prepare(
                mock(HarnessStore.Transaction.class),
                new Session(SESSION_ID, "h3", NOW),
                List.of(raw));

    assertEquals(1, prepared.size());
    assertEquals(raw.idempotencyKey(), prepared.getFirst().idempotencyKey());
    assertEquals(raw.requestHash(), prepared.getFirst().requestHash());
    AgentMessage durable = ((CustomMessageCommandPayload) prepared.getFirst().payload()).message();
    assertEquals(4, durable.contents().size());
    assertEquals("table", ((TextMessageContent) durable.contents().get(1)).text());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, durable.contents().get(2));
    assertEquals(BLOB_ID, resource.blobId());
    assertEquals("authoritative.png", resource.name());
    assertEquals("tail", ((TextMessageContent) durable.contents().get(3)).text());

    InOrder retainThenRelease = inOrder(refManager, uploadService);
    retainThenRelease.verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    retainThenRelease.verify(uploadService).delete(UPLOAD_ID);
  }

  /** 部署未绑定 Harness runtime 时必须确定性失败，绝不对着空 provider 猜测可用性。 */
  @Test
  @SuppressWarnings("unchecked")
  void failsDeterministicallyWhenHarnessRuntimeIsNotBound() {
    ObjectProvider<HarnessRuntime> missing = mock(ObjectProvider.class);
    when(missing.getIfAvailable()).thenReturn(null);
    PluginPromptTaskService unbound =
        new PluginPromptTaskService(
            new AgentTaskService(missing), materializer, changeSource, resources);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> unbound.submit("worker", "system", textContents()));
    assertEquals("harness runtime is not available in this deployment", failure.getMessage());
    assertThrows(
        IllegalStateException.class,
        () -> unbound.await(UUID.randomUUID(), Duration.ofSeconds(1), () -> true));
    assertThrows(IllegalStateException.class, () -> unbound.stop(UUID.randomUUID()));
  }

  /** 接受事务只接受本服务产生的唯一 CUSTOM_MESSAGE 批：其它形态必须受控失败，绝不静默改写。 */
  @Test
  void preflightRejectsUnexpectedCommandBatches() {
    service.submit("worker", "system", List.of(new PluginPromptContent.Text("only")));

    ArgumentCaptor<SpawnRequest> command = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(command.capture());
    NewThreadCommand raw = command.getValue().commands().getFirst();

    assertThrows(
        IllegalStateException.class,
        () ->
            command
                .getValue()
                .preflight()
                .prepare(
                    mock(HarnessStore.Transaction.class),
                    new Session(SESSION_ID, "h3", NOW),
                    List.of(raw, raw)));
  }

  /** 同一内容与同一暂存句柄永远得到同一 request hash；durable 形态不参与 hash。 */
  @Test
  void requestHashIsStableForTheSameContentsAndDiffersFromDurableForm() {
    PluginPromptResources.StagedResource staged =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    List<PluginPromptContent> contents =
        List.of(new PluginPromptContent.Text("table"), new PluginPromptContent.Media(staged));

    service.submit("worker", "system", contents);
    service.submit("worker", "system", contents);

    ArgumentCaptor<SpawnRequest> commands = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime, times(2)).spawn(commands.capture());
    String first = commands.getAllValues().get(0).commands().getFirst().requestHash();
    String second = commands.getAllValues().get(1).commands().getFirst().requestHash();
    assertEquals(first, second);
    assertNotEquals(
        ThreadCommandPayloadJsonCodec.requestHash(
            new CustomMessageCommandPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("table"))))),
        first,
        "media placeholder must participate in the request hash");
  }

  @Test
  void submitValidatesItsInputsBeforeAcceptance() {
    assertThrows(NullPointerException.class, () -> service.submit(null, "system", textContents()));
    assertThrows(NullPointerException.class, () -> service.submit("worker", "system", null));
    assertThrows(
        IllegalArgumentException.class, () -> service.submit("worker", "system", List.of()));
    assertThrows(
        NullPointerException.class,
        () -> service.submit("worker", "system", Arrays.asList((PluginPromptContent) null)));
    verify(runtime, never()).spawn(any());
  }

  @Test
  void awaitReturnsReceiptReportWithoutReadingAnyThreadHead() {
    // 终态优先：订阅后的首次权威读取已匹配时立即返回，不等待任何信号。
    UUID taskId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "final answer", null, null)));

    assertEquals("final answer", service.await(taskId, Duration.ofSeconds(1), () -> true));

    verify(runtime).projectJoinReceipt(PluginPromptTaskService.invocationId(taskId));
    // 固定回执即权威事实：绝不读子线程最新 head 去推测结果。
    verify(runtime, never()).getThreadSnapshot(any());
    assertEquals(0, changeSource.activeSubscriptions(taskId), "success must release");
  }

  @Test
  void awaitReReadsOnlyTheFixedReceiptOnVersionSignal() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "later answer", null, null)));

    CompletableFuture<String> awaited =
        CompletableFuture.supplyAsync(
            () -> service.await(taskId, Duration.ofSeconds(5), () -> true));
    waitForSubscription(taskId);
    changeSource.signal(taskId);

    assertEquals("later answer", awaited.join());
    verify(runtime, times(2)).projectJoinReceipt(PluginPromptTaskService.invocationId(taskId));
    verify(runtime, never()).getThreadSnapshot(any());
    assertEquals(0, changeSource.activeSubscriptions(taskId));
  }

  /** 唤醒信号可能丢失：deadline 前的最终权威读必须发现已匹配的结果，而不是误报 timeout。 */
  @Test
  void awaitFindsResultAtDeadlineWhenWakeSignalWasLost() {
    UUID taskId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "recovered answer", null, null)));

    assertEquals("recovered answer", service.await(taskId, Duration.ofNanos(1), () -> true));

    verify(runtime, never()).stop(any(StopCommand.class));
    assertEquals(0, changeSource.activeSubscriptions(taskId));
  }

  /** 非 COMPLETED 只返回受控 outcome：远端详情、凭据与 cause 绝不进入调用方可见错误。 */
  @Test
  void awaitReportsOnlyControlledOutcomeForNonCompletedTerminals() {
    UUID failed = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(failed)))
        .thenReturn(
            Optional.of(
                receipt(
                    ThreadJoinOutcome.ERROR,
                    null,
                    "partial",
                    "provider boom https://evil.example/leak")));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.await(failed, Duration.ofSeconds(1), () -> true));
    assertTrue(error.getMessage().contains("ERROR"), error.getMessage());
    assertFalse(error.getMessage().contains("provider boom"), error.getMessage());
    assertFalse(error.getMessage().contains("evil.example"), error.getMessage());

    UUID cancelled = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(cancelled)))
        .thenReturn(
            Optional.of(receipt(ThreadJoinOutcome.CANCELLED, null, null, "Cancelled by user.")));
    assertThrows(
        IllegalStateException.class,
        () -> service.await(cancelled, Duration.ofSeconds(1), () -> true));

    // 完成但报告为空白：明确失败而不是返回空串。
    UUID blank = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(blank)))
        .thenReturn(Optional.of(receipt(ThreadJoinOutcome.COMPLETED, "  ", null, null)));
    assertThrows(
        IllegalStateException.class, () -> service.await(blank, Duration.ofSeconds(1), () -> true));
  }

  @Test
  void timeoutStopsOnlyItsOwnThreadAndReleasesSubscription() {
    UUID taskId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenReturn(Optional.empty());
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));

    assertThrows(
        IllegalStateException.class, () -> service.await(taskId, Duration.ofNanos(1), () -> true));

    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(stop.capture());
    assertEquals(taskId, stop.getValue().threadId());
    assertEquals(
        0, changeSource.activeSubscriptions(taskId), "timeout must release the subscription");
  }

  @Test
  void callerInactivityStopsAndReleasesWithoutExtraDurableReads() throws Exception {
    UUID taskId = UUID.randomUUID();
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch firstRead = new CountDownLatch(1);
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenAnswer(
            invocation -> {
              reads.incrementAndGet();
              firstRead.countDown();
              return Optional.empty();
            });
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));
    AtomicBoolean keepWaiting = new AtomicBoolean(true);
    CompletableFuture<String> awaited =
        CompletableFuture.supplyAsync(
            () -> service.await(taskId, Duration.ofSeconds(5), keepWaiting::get));

    assertTrue(firstRead.await(5, TimeUnit.SECONDS));
    keepWaiting.set(false);

    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> awaited.get(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals("prompt task caller is no longer active", failure.getCause().getMessage());
    verify(runtime).stop(any(StopCommand.class));
    assertEquals(1, reads.get(), "no durable read without a version signal");
    assertEquals(0, changeSource.activeSubscriptions(taskId));
  }

  @Test
  void interruptionReleasesSubscription() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(PluginPromptTaskService.invocationId(taskId)))
        .thenReturn(Optional.empty());
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    Thread awaiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    service.await(taskId, Duration.ofSeconds(5), () -> true);
                  } catch (Throwable error) {
                    failure.set(error);
                  } finally {
                    done.countDown();
                  }
                });
    waitForSubscription(taskId);
    awaiter.interrupt();

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.get());
    assertEquals("prompt task observation thread was interrupted", failure.get().getMessage());
    assertEquals(0, changeSource.activeSubscriptions(taskId));
  }

  @Test
  void validatesAwaitInputs() {
    assertThrows(
        NullPointerException.class, () -> service.await(null, Duration.ofSeconds(1), () -> true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.await(UUID.randomUUID(), Duration.ZERO, () -> true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.await(UUID.randomUUID(), Duration.ofSeconds(-1), () -> true));
    assertThrows(
        NullPointerException.class,
        () -> service.await(UUID.randomUUID(), Duration.ofSeconds(1), null));
    // 超出 nano 表示范围的 deadline 必须显式拒绝，而不是溢出成负数后立刻误判 timeout。
    IllegalArgumentException overflow =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.await(
                    UUID.randomUUID(), Duration.ofSeconds(Long.MAX_VALUE / 2), () -> true));
    assertTrue(overflow.getMessage().contains("nanoseconds"), overflow.getMessage());
  }

  /** stop 容忍已消失的任务，只对本任务发起一次带当前 version 的 CAS 停止。 */
  @Test
  void stopToleratesMissingTasksAndUsesCurrentVersion() {
    assertThrows(NullPointerException.class, () -> service.stop(null));

    UUID gone = UUID.randomUUID();
    when(runtime.getThreadSnapshot(gone)).thenThrow(new HarnessRuntimeNotFoundException("gone"));
    service.stop(gone);
    verify(runtime, never()).stop(any(StopCommand.class));

    UUID live = UUID.randomUUID();
    when(runtime.getThreadSnapshot(live)).thenReturn(idle(live));
    service.stop(live);
    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(stop.capture());
    assertEquals(live, stop.getValue().threadId());
  }

  /** 只有真正的 stale version 才重读重试；重试成功后返回。 */
  @Test
  void stopRetriesOnlyStaleVersionConflict() {
    UUID taskId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));
    // stop 返回 StopResult：第一次 CAS 撞上 stale version，重读后第二次成功。
    when(runtime.stop(any(StopCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale"))
        .thenReturn(null);

    service.stop(taskId);

    verify(runtime, times(2)).stop(any(StopCommand.class));
    verify(runtime, times(2)).getThreadSnapshot(taskId);
  }

  /** stale 重试耗尽必须受控失败，绝不静默返回成功。 */
  @Test
  void stopFailsControlledWhenStaleRetriesAreExhausted() {
    UUID taskId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));
    doThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale"))
        .when(runtime)
        .stop(any(StopCommand.class));

    IllegalStateException exhausted =
        assertThrows(IllegalStateException.class, () -> service.stop(taskId));
    assertTrue(exhausted.getMessage().contains("stale version"), exhausted.getMessage());
    verify(runtime, times(3)).stop(any(StopCommand.class));
  }

  /** 非 stale 冲突是确定性失败：不得重试，也不得吞掉。 */
  @Test
  void stopPropagatesNonStaleConflictsWithoutRetry() {
    UUID taskId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(taskId)).thenReturn(idle(taskId));
    HarnessRuntimeConflictException conflict =
        new HarnessRuntimeConflictException(
            HarnessRuntimeConflictException.Reason.STOP_REQUEST_ID_REUSED, "reused");
    doThrow(conflict).when(runtime).stop(any(StopCommand.class));

    HarnessRuntimeConflictException thrown =
        assertThrows(HarnessRuntimeConflictException.class, () -> service.stop(taskId));
    assertEquals(conflict, thrown);
    verify(runtime, times(1)).stop(any(StopCommand.class));
  }

  private void waitForSubscription(UUID taskId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!changeSource.isSubscribed(taskId) && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(changeSource.isSubscribed(taskId), "await must subscribe before observing");
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
            "prompt-task",
            threadId,
            false,
            SpawnTrigger.USER,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            NOW,
            NOW);
    return new ThreadSnapshot(
        thread,
        mock(EntryPath.class),
        List.of(),
        null,
        List.of(),
        ThreadLiveState.IDLE_OR_HISTORICAL,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }
}
