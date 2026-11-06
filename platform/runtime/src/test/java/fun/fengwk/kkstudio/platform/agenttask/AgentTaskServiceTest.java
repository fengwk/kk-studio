package fun.fengwk.kkstudio.platform.agenttask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台 Agent 任务入口的行为契约：提交只翻译为统一 spawn（交付方式由父关系决定），观察只投影票据冻结结果，停止按票据收敛。
 *
 * <p>Runtime 以窄 mock 表达；本测试断言服务自己的翻译、状态投影与 CAS 重试语义，不重复 Runtime 的事务/额度测试。
 */
class AgentTaskServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final BranchSettings SETTINGS =
      new BranchSettings("worker", BuiltinExecution.of(ModelSelection.model("model", "v1")), null);

  private HarnessRuntime runtime;
  private AgentTaskService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service = new AgentTaskService(runtimes);
  }

  @Test
  void rootTaskSpawnsAsAReceiptOnlyControllerRun() {
    AcceptedCommands accepted = accepted(UUID.randomUUID());
    when(runtime.spawn(any(), any())).thenReturn(accepted);
    AgentTaskRequest request = request(null, null, null);

    AgentTaskHandle handle = service.submit(request);

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    ArgumentCaptor<AcceptancePreflight> preflight =
        ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).spawn(spawn.capture(), preflight.capture());
    SpawnRequest spawned = spawn.getValue();
    assertEquals(SpawnTrigger.CONTROLLER, spawned.trigger());
    assertEquals(JoinDelivery.RECEIPT_ONLY, spawned.delivery());
    assertNull(spawned.parentThreadId());
    assertNull(spawned.expectedParentHeadEntryId());
    assertEquals(ThreadConstraints.NONE, spawned.constraints());
    assertEquals(request.ticketId(), spawned.ticketId());
    assertEquals(request.requestHash(), spawned.requestHash());
    // 物化端口不在值对象里：同一个 preflight 以调用参数原样传给运行时。
    assertSame(request.preflight(), preflight.getValue());
    assertEquals(accepted.thread().id(), handle.threadId());
    assertSame(accepted, handle.accepted());
  }

  @Test
  void childTaskNotifiesItsParent() {
    AcceptedCommands accepted = accepted(UUID.randomUUID());
    when(runtime.spawn(any(), any())).thenReturn(accepted);
    UUID parentId = UUID.randomUUID();
    UUID parentHead = UUID.randomUUID();

    service.submit(request(parentId, parentHead, null));

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture(), any());
    assertEquals(JoinDelivery.NOTIFY_PARENT, spawn.getValue().delivery());
    assertEquals(parentId, spawn.getValue().parentThreadId());
    assertEquals(parentHead, spawn.getValue().expectedParentHeadEntryId());
  }

  @Test
  void observationProjectsTheFrozenReceiptAndNeverInfersFromThreadState() {
    UUID ticketId = UUID.randomUUID();
    ThreadJoin join =
        new ThreadJoin(
            ticketId,
            HASH,
            null,
            UUID.randomUUID(),
            1,
            "worker",
            4,
            0,
            null,
            null,
            null,
            NOW,
            NOW,
            JoinDelivery.RECEIPT_ONLY,
            null);
    when(runtime.findJoin(ticketId)).thenReturn(Optional.of(join));
    when(runtime.projectJoinReceipt(ticketId)).thenReturn(Optional.empty());

    AgentTaskStatus running = service.observe(ticketId).orElseThrow();
    assertEquals(AgentTaskStatus.State.RUNNING, running.state());
    assertNull(running.receipt());

    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            ticketId,
            join.childThreadId(),
            "worker",
            ThreadJoinOutcome.COMPLETED,
            "prompt",
            "final",
            null,
            null);
    when(runtime.projectJoinReceipt(ticketId)).thenReturn(Optional.of(receipt));

    AgentTaskStatus completed = service.observe(ticketId).orElseThrow();
    assertEquals(AgentTaskStatus.State.COMPLETED, completed.state());
    assertSame(receipt, completed.receipt());
    assertSame(receipt, service.result(ticketId).orElseThrow());
  }

  @Test
  void supersededTicketObservesATerminalStateWithoutBorrowingALaterRunsResult() {
    // 旧票据不会再被匹配：接管事实本身必须终止轮询，不能显示 RUNNING 或借用后续 Run 的结果。
    UUID ticketId = UUID.randomUUID();
    ThreadJoin ticket = join(ticketId, UUID.randomUUID(), false, true);
    when(runtime.findJoin(ticketId)).thenReturn(Optional.of(ticket));

    AgentTaskStatus superseded = service.observe(ticketId).orElseThrow();

    assertEquals(AgentTaskStatus.State.SUPERSEDED, superseded.state());
    assertEquals(ticketId, superseded.ticketId());
    assertEquals(ticket.childThreadId(), superseded.threadId());
    assertNull(superseded.receipt());
    verify(runtime, never()).projectJoinReceipt(any());
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void onlyResultBearingTaskStatesAcceptAReceipt() {
    // SUPERSEDED 不对应执行结果；仍在执行的票据也不能提前带回执，正常终态则必须有回执。
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            ticketId,
            threadId,
            "worker",
            ThreadJoinOutcome.COMPLETED,
            "prompt",
            "final",
            null,
            null);
    for (AgentTaskStatus.State state :
        List.of(AgentTaskStatus.State.RUNNING, AgentTaskStatus.State.SUPERSEDED)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AgentTaskStatus(ticketId, threadId, "worker", state, receipt));
    }
    for (AgentTaskStatus.State state :
        List.of(
            AgentTaskStatus.State.COMPLETED,
            AgentTaskStatus.State.ERROR,
            AgentTaskStatus.State.CANCELLED)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AgentTaskStatus(ticketId, threadId, "worker", state, null));
    }
  }

  @Test
  void unknownTicketObservesEmptyAndStopFailsNotFound() {
    UUID ticketId = UUID.randomUUID();
    when(runtime.findJoin(ticketId)).thenReturn(Optional.empty());

    assertTrue(service.observe(ticketId).isEmpty());
    assertTrue(service.result(ticketId).isEmpty());
    DomainException error = assertThrows(DomainException.class, () -> service.stop(ticketId));
    assertEquals(DomainErrorCode.NOT_FOUND, error.code());
    assertEquals("agent_task", error.resource());
    assertEquals("agent task " + ticketId + " does not exist", error.getMessage());
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void stopStopsTheTicketedChildAndRetriesOnlyStaleVersionWithFreshSnapshots() {
    UUID ticketId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    when(runtime.findJoin(ticketId)).thenReturn(Optional.of(join(ticketId, childId, false, false)));
    ThreadSnapshot first = mock(ThreadSnapshot.class);
    when(first.thread()).thenReturn(thread(childId, 0L));
    ThreadSnapshot second = mock(ThreadSnapshot.class);
    when(second.thread()).thenReturn(thread(childId, 1L));
    when(runtime.getThreadSnapshot(childId)).thenReturn(first).thenReturn(second);
    when(runtime.stop(any(StopCommand.class)))
        .thenThrow(DomainException.conflict("STALE_VERSION", "stale"))
        .thenReturn(null);

    service.stop(ticketId);

    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(2)).stop(stop.capture());
    assertEquals(childId, stop.getValue().threadId());
    assertEquals(1L, stop.getValue().expectedVersion());
    verify(runtime, times(2)).getThreadSnapshot(childId);
  }

  @Test
  void stopIsNoOpForMatchedOrSupersededTickets() {
    UUID matchedId = UUID.randomUUID();
    UUID matchedChild = UUID.randomUUID();
    when(runtime.findJoin(matchedId))
        .thenReturn(Optional.of(join(matchedId, matchedChild, true, false)));
    service.stop(matchedId);

    UUID supersededId = UUID.randomUUID();
    UUID supersededChild = UUID.randomUUID();
    when(runtime.findJoin(supersededId))
        .thenReturn(Optional.of(join(supersededId, supersededChild, false, true)));
    service.stop(supersededId);

    verify(runtime, never()).getThreadSnapshot(any());
    verify(runtime, never()).stop(any(StopCommand.class));
  }

  @Test
  void stopSkipsWhenTicketFinishesOrIsSupersededBetweenSnapshotAndStop() {
    UUID completedId = UUID.randomUUID();
    UUID completedChild = UUID.randomUUID();
    when(runtime.findJoin(completedId))
        .thenReturn(Optional.of(join(completedId, completedChild, false, false)))
        .thenReturn(Optional.of(join(completedId, completedChild, true, false)));
    ThreadSnapshot completedSnapshot = mock(ThreadSnapshot.class);
    when(completedSnapshot.thread()).thenReturn(thread(completedChild, 0L));
    when(runtime.getThreadSnapshot(completedChild)).thenReturn(completedSnapshot);
    service.stop(completedId);

    UUID supersededId = UUID.randomUUID();
    UUID supersededChild = UUID.randomUUID();
    when(runtime.findJoin(supersededId))
        .thenReturn(Optional.of(join(supersededId, supersededChild, false, false)))
        .thenReturn(Optional.of(join(supersededId, supersededChild, false, true)));
    ThreadSnapshot supersededSnapshot = mock(ThreadSnapshot.class);
    when(supersededSnapshot.thread()).thenReturn(thread(supersededChild, 0L));
    when(runtime.getThreadSnapshot(supersededChild)).thenReturn(supersededSnapshot);
    service.stop(supersededId);

    verify(runtime, never()).stop(any(StopCommand.class));
  }

  @Test
  void stopToleratesAnAlreadyGoneThread() {
    UUID threadId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(threadId)).thenThrow(DomainException.notFound("thread", "gone"));

    service.stopThread(threadId);

    verify(runtime, never()).stop(any(StopCommand.class));
  }

  @Test
  void stopThreadRetriesOnlyDomainStaleConflictAndPropagatesOtherFailures() {
    // 迁移后只容忍 not-found 或精确 stale reason；其他领域失败原样抛出。
    UUID threadId = UUID.randomUUID();
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread(threadId, 0L));
    when(runtime.getThreadSnapshot(threadId)).thenReturn(snapshot);
    when(runtime.stop(any(StopCommand.class)))
        .thenThrow(DomainException.conflict("STALE_VERSION", "stale"))
        .thenReturn(null);
    service.stopThread(threadId);
    verify(runtime, times(2)).stop(any(StopCommand.class));

    DomainException otherConflict = DomainException.conflict("THREAD_ID_REUSED", "other");
    when(runtime.stop(any(StopCommand.class))).thenThrow(otherConflict);
    assertSame(
        otherConflict, assertThrows(DomainException.class, () -> service.stopThread(threadId)));
    DomainException wrongCode = DomainException.validationReason("STALE_VERSION", "not a conflict");
    doThrow(wrongCode).when(runtime).stop(any(StopCommand.class));
    assertSame(wrongCode, assertThrows(DomainException.class, () -> service.stopThread(threadId)));
    doThrow(DomainException.notFound("thread", "gone")).when(runtime).stop(any(StopCommand.class));
    service.stopThread(threadId);
    DomainException validation = DomainException.validation("thread", "invalid");
    when(runtime.getThreadSnapshot(threadId)).thenThrow(validation);
    assertSame(validation, assertThrows(DomainException.class, () -> service.stopThread(threadId)));
    doThrow(DomainException.notFound("thread", "gone")).when(runtime).getThreadSnapshot(threadId);
    service.stopThread(threadId);
  }

  @Test
  void stopTicketedHandlesDomainNotFoundAndDoesNotSwallowOtherReasons() {
    UUID ticketId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    when(runtime.findJoin(ticketId)).thenReturn(Optional.of(join(ticketId, childId, false, false)));
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread(childId, 0L));
    when(runtime.getThreadSnapshot(childId)).thenReturn(snapshot);
    when(runtime.stop(any(StopCommand.class)))
        .thenThrow(DomainException.conflict("STALE_VERSION", "stale"))
        .thenThrow(DomainException.notFound("thread", "gone"));
    service.stop(ticketId);
    verify(runtime, times(2)).stop(any(StopCommand.class));

    DomainException other = DomainException.conflict("STOP_REQUEST_ID_REUSED", "other");
    doThrow(other).when(runtime).stop(any(StopCommand.class));
    assertSame(other, assertThrows(DomainException.class, () -> service.stop(ticketId)));

    DomainException invalid = DomainException.validation("thread", "invalid");
    doThrow(invalid).when(runtime).getThreadSnapshot(childId);
    assertSame(invalid, assertThrows(DomainException.class, () -> service.stop(ticketId)));
    doThrow(DomainException.notFound("thread", "gone")).when(runtime).getThreadSnapshot(childId);
    service.stop(ticketId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingRuntimeFailsDeterministically() {
    ObjectProvider<HarnessRuntime> missing = mock(ObjectProvider.class);
    when(missing.getIfAvailable()).thenReturn(null);
    AgentTaskService unbound = new AgentTaskService(missing);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> unbound.submit(request(null, null, null)));
    assertEquals("harness runtime is not available in this deployment", failure.getMessage());
    assertThrows(IllegalStateException.class, () -> unbound.observe(UUID.randomUUID()));
    assertThrows(IllegalStateException.class, () -> unbound.stop(UUID.randomUUID()));
  }

  @Test
  void submitExternalReplaysExistingTicketWithoutCallingSupplierOrSpawning() {
    UUID ticketId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    when(runtime.findJoin(ticketId))
        .thenReturn(Optional.of(join(ticketId, childThreadId, false, false, hash)));

    UUID result =
        service.submitExternal(
            ticketId,
            hash,
            () -> {
              throw new AssertionError("supplier must not be called when ticket exists");
            });

    assertEquals(childThreadId, result);
    verify(runtime, never()).spawn(any(SpawnRequest.class), any());
  }

  @Test
  void submitExternalWithExistingTicketMismatchedHashThrowsIllegalArgumentException() {
    UUID ticketId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    String existingHash = AgentTaskRequest.requestHash(ticketId, "worker", "work1", null);
    String newHash = AgentTaskRequest.requestHash(ticketId, "worker", "work2", null);
    when(runtime.findJoin(ticketId))
        .thenReturn(Optional.of(join(ticketId, childThreadId, false, false, existingHash)));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.submitExternal(
                    ticketId,
                    newHash,
                    () -> {
                      throw new AssertionError("supplier must not be called on hash mismatch");
                    }));

    assertEquals("join invocation identity reused", ex.getMessage());
    verify(runtime, never()).spawn(any(SpawnRequest.class), any());
  }

  @Test
  void submitExternalValidatesSupplierReturnedRequest() {
    UUID ticketId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", 4);
    when(runtime.findJoin(ticketId)).thenReturn(Optional.empty());

    // 1. supplier 返回 null
    assertThrows(
        NullPointerException.class, () -> service.submitExternal(ticketId, hash, () -> null));

    // 2. ticketId 不一致
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submitExternal(
                ticketId,
                hash,
                () -> {
                  AgentTaskRequest req = request(null, null, null);
                  return new AgentTaskRequest(
                      UUID.randomUUID(),
                      req.threadId(),
                      req.sessionId(),
                      req.settings(),
                      req.commands(),
                      req.preflight(),
                      req.trigger(),
                      req.parentThreadId(),
                      req.expectedParentHeadEntryId(),
                      req.continuation(),
                      req.yoloEnabled(),
                      req.budget(),
                      hash);
                }));

    // 3. requestHash 不一致
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submitExternal(
                ticketId,
                hash,
                () -> {
                  AgentTaskRequest req = request(null, null, null);
                  return new AgentTaskRequest(
                      ticketId,
                      req.threadId(),
                      req.sessionId(),
                      req.settings(),
                      req.commands(),
                      req.preflight(),
                      req.trigger(),
                      req.parentThreadId(),
                      req.expectedParentHeadEntryId(),
                      req.continuation(),
                      req.yoloEnabled(),
                      req.budget(),
                      "different-hash");
                }));

    // 4. 不能有 parentThreadId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submitExternal(
                ticketId,
                hash,
                () -> {
                  AgentTaskRequest req = request(UUID.randomUUID(), null, null);
                  return new AgentTaskRequest(
                      ticketId,
                      req.threadId(),
                      req.sessionId(),
                      req.settings(),
                      req.commands(),
                      req.preflight(),
                      req.trigger(),
                      UUID.randomUUID(),
                      req.expectedParentHeadEntryId(),
                      req.continuation(),
                      req.yoloEnabled(),
                      req.budget(),
                      hash);
                }));
  }

  @Test
  void submitExternalReplaysOnThreadIdReusedConflictWhenTicketMatches() {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    AgentTaskRequest req =
        new AgentTaskRequest(
            ticketId,
            threadId,
            UUID.randomUUID(),
            SETTINGS,
            List.of(
                new NewThreadCommand(
                    new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID())),
            AcceptancePreflight.IDENTITY,
            SpawnTrigger.CONTROLLER,
            null,
            null,
            null,
            false,
            new SpawnRequest.Budget(null, 1, 1, 10),
            hash);

    // 首次 findJoin 为空；spawn 遇到并发冲突 THREAD_ID_REUSED；二次查 findJoin 命中已提交的票据
    when(runtime.findJoin(ticketId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(join(ticketId, threadId, false, false, hash)));
    when(runtime.spawn(any(SpawnRequest.class), any()))
        .thenThrow(DomainException.conflict("THREAD_ID_REUSED", "conflict"));

    UUID result = service.submitExternal(ticketId, hash, () -> req);
    assertEquals(threadId, result);
  }

  @Test
  void submitExternalReplaysOnDomainThreadIdReusedButNotOtherDomainErrors() {
    // DomainException 不再由 Java 异常类型区分：code 与 reason 必须一起匹配。
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    AgentTaskRequest req =
        new AgentTaskRequest(
            ticketId,
            threadId,
            UUID.randomUUID(),
            SETTINGS,
            List.of(
                new NewThreadCommand(
                    new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID())),
            AcceptancePreflight.IDENTITY,
            SpawnTrigger.CONTROLLER,
            null,
            null,
            null,
            false,
            new SpawnRequest.Budget(null, 1, 1, 10),
            hash);
    when(runtime.findJoin(ticketId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(join(ticketId, threadId, false, false, hash)));
    when(runtime.spawn(any(SpawnRequest.class), any()))
        .thenThrow(DomainException.conflict("THREAD_ID_REUSED", "conflict"));
    assertEquals(threadId, service.submitExternal(ticketId, hash, () -> req));

    when(runtime.findJoin(ticketId)).thenReturn(Optional.empty());
    DomainException failure = DomainException.conflict("STALE_VERSION", "stale");
    doThrow(failure).when(runtime).spawn(any(SpawnRequest.class), any());
    assertSame(
        failure,
        assertThrows(
            DomainException.class, () -> service.submitExternal(ticketId, hash, () -> req)));
    verify(runtime, times(3)).findJoin(ticketId);
  }

  @Test
  void submitExternalThrowsWhenThreadIdReusedConflictTicketMismatches() {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    String differentHash = AgentTaskRequest.requestHash(ticketId, "worker", "other", null);
    AgentTaskRequest req =
        new AgentTaskRequest(
            ticketId,
            threadId,
            UUID.randomUUID(),
            SETTINGS,
            List.of(
                new NewThreadCommand(
                    new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID())),
            AcceptancePreflight.IDENTITY,
            SpawnTrigger.CONTROLLER,
            null,
            null,
            null,
            false,
            new SpawnRequest.Budget(null, 1, 1, 10),
            hash);

    when(runtime.findJoin(ticketId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(join(ticketId, threadId, false, false, differentHash)));
    when(runtime.spawn(any(SpawnRequest.class), any()))
        .thenThrow(DomainException.conflict("THREAD_ID_REUSED", "conflict"));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.submitExternal(ticketId, hash, () -> req));
    assertEquals("join invocation identity reused", ex.getMessage());
  }

  @Test
  void submitExternalRethrowsOtherConflicts() {
    UUID ticketId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    String hash = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    AgentTaskRequest req =
        new AgentTaskRequest(
            ticketId,
            threadId,
            UUID.randomUUID(),
            SETTINGS,
            List.of(
                new NewThreadCommand(
                    new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID())),
            AcceptancePreflight.IDENTITY,
            SpawnTrigger.CONTROLLER,
            null,
            null,
            null,
            false,
            new SpawnRequest.Budget(null, 1, 1, 10),
            hash);

    when(runtime.findJoin(ticketId)).thenReturn(Optional.empty());
    when(runtime.spawn(any(SpawnRequest.class), any()))
        .thenThrow(DomainException.conflict("STALE_VERSION", "stale"));

    DomainException ex =
        assertThrows(
            DomainException.class, () -> service.submitExternal(ticketId, hash, () -> req));
    assertEquals(DomainErrorCode.CONFLICT, ex.code());
    assertEquals("STALE_VERSION", ex.reason());
  }

  @Test
  void requestHashIsDeterministicAndDistinguishesMaxTurns() {
    UUID ticketId = UUID.randomUUID();
    String hash1 = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    String hash2 = AgentTaskRequest.requestHash(ticketId, "worker", "work", null);
    assertEquals(hash1, hash2, "identical inputs must yield identical hash");

    String hash3 = AgentTaskRequest.requestHash(ticketId, "worker", "work", 5);
    assertFalse(hash1.equals(hash3), "explicit maxTurns must differ from null maxTurns");

    String hash4 = AgentTaskRequest.requestHash(ticketId, "worker", "work", 6);
    assertFalse(hash3.equals(hash4), "different maxTurns values must differ");

    String hashDiffAgent = AgentTaskRequest.requestHash(ticketId, "other", "work", null);
    assertFalse(hash1.equals(hashDiffAgent), "different agent must differ");

    String hashDiffPrompt = AgentTaskRequest.requestHash(ticketId, "worker", "other", null);
    assertFalse(hash1.equals(hashDiffPrompt), "different prompt must differ");

    assertThrows(
        IllegalArgumentException.class,
        () -> AgentTaskRequest.requestHash(ticketId, " ", "work", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentTaskRequest.requestHash(ticketId, "worker", " ", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentTaskRequest.requestHash(ticketId, "worker", "work", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentTaskRequest.requestHash(ticketId, "worker", "work", -1));
  }

  private AgentTaskRequest request(UUID parentId, UUID parentHead, SpawnRequest.Continuation cont) {
    NewThreadCommand command =
        new NewThreadCommand(
            new CustomMessageCommandPayload(AgentMessage.user("work")), UUID.randomUUID());
    return new AgentTaskRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        cont == null ? UUID.randomUUID() : null,
        SETTINGS,
        List.of(command),
        AcceptancePreflight.IDENTITY,
        SpawnTrigger.CONTROLLER,
        parentId,
        parentHead,
        cont,
        false,
        new SpawnRequest.Budget(5, 3, 2, 10),
        HASH);
  }

  private static AcceptedCommands accepted(UUID threadId) {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    ThreadState thread = thread(threadId);
    when(accepted.thread()).thenReturn(thread);
    return accepted;
  }

  private static ThreadState thread(UUID threadId) {
    return thread(threadId, 0L);
  }

  private static ThreadState thread(UUID threadId, long version) {
    return new ThreadState(
        threadId,
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        HASH,
        "task",
        threadId,
        false,
        SpawnTrigger.CONTROLLER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        version,
        NOW,
        NOW);
  }

  private static ThreadJoin join(UUID ticketId, UUID childId, boolean matched, boolean superseded) {
    return join(ticketId, childId, matched, superseded, HASH);
  }

  private static ThreadJoin join(
      UUID ticketId, UUID childId, boolean matched, boolean superseded, String requestHash) {
    return new ThreadJoin(
        ticketId,
        requestHash,
        UUID.randomUUID(),
        childId,
        1,
        "worker",
        null,
        0,
        matched ? UUID.randomUUID() : null,
        null,
        null,
        NOW,
        NOW,
        JoinDelivery.NOTIFY_PARENT,
        superseded ? UUID.randomUUID() : null);
  }
}
