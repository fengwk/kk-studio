package fun.fengwk.kkstudio.platform.agenttask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

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
      new BranchSettings("worker", new ModelSelection("provider", "model", "v1"), null);

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
    when(runtime.spawn(any())).thenReturn(accepted);
    AgentTaskRequest request = request(null, null, null);

    AgentTaskHandle handle = service.submit(request);

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture());
    SpawnRequest spawned = spawn.getValue();
    assertEquals(SpawnTrigger.CONTROLLER, spawned.trigger());
    assertEquals(JoinDelivery.RECEIPT_ONLY, spawned.delivery());
    assertFalse(spawned.countsTowardQuota());
    assertNull(spawned.parentThreadId());
    assertNull(spawned.expectedParentHeadEntryId());
    assertEquals(ThreadConstraints.NONE, spawned.constraints());
    assertEquals(request.ticketId(), spawned.ticketId());
    assertEquals(request.requestHash(), spawned.requestHash());
    assertEquals(accepted.thread().id(), handle.threadId());
    assertSame(accepted, handle.accepted());
  }

  @Test
  void childTaskNotifiesItsParentAndCountsTowardTheQuota() {
    AcceptedCommands accepted = accepted(UUID.randomUUID());
    when(runtime.spawn(any())).thenReturn(accepted);
    UUID parentId = UUID.randomUUID();
    UUID parentHead = UUID.randomUUID();

    service.submit(request(parentId, parentHead, null));

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    verify(runtime).spawn(spawn.capture());
    assertEquals(JoinDelivery.NOTIFY_PARENT, spawn.getValue().delivery());
    assertTrue(spawn.getValue().countsTowardQuota());
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
            false,
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
  void unknownTicketObservesEmptyAndStopFailsNotFound() {
    UUID ticketId = UUID.randomUUID();
    when(runtime.findJoin(ticketId)).thenReturn(Optional.empty());

    assertTrue(service.observe(ticketId).isEmpty());
    assertTrue(service.result(ticketId).isEmpty());
    assertThrows(HarnessRuntimeNotFoundException.class, () -> service.stop(ticketId));
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void stopStopsTheTicketedChildAndRetriesOnlyStaleVersion() {
    UUID ticketId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    when(runtime.findJoin(ticketId)).thenReturn(Optional.of(join(ticketId, childId)));
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    ThreadState thread = thread(childId);
    when(snapshot.thread()).thenReturn(thread);
    when(runtime.getThreadSnapshot(childId)).thenReturn(snapshot);
    when(runtime.stop(any(StopCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale"))
        .thenReturn(null);

    service.stop(ticketId);

    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(2)).stop(stop.capture());
    assertEquals(childId, stop.getValue().threadId());
    assertEquals(thread.version(), stop.getValue().expectedVersion());
    verify(runtime, times(2)).getThreadSnapshot(childId);
  }

  @Test
  void stopToleratesAnAlreadyGoneThread() {
    UUID threadId = UUID.randomUUID();
    when(runtime.getThreadSnapshot(threadId))
        .thenThrow(new HarnessRuntimeNotFoundException("gone"));

    service.stopThread(threadId);

    verify(runtime, never()).stop(any(StopCommand.class));
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
        0L,
        NOW,
        NOW);
  }

  private static ThreadJoin join(UUID ticketId, UUID childId) {
    return new ThreadJoin(
        ticketId,
        HASH,
        UUID.randomUUID(),
        childId,
        1,
        "worker",
        null,
        0,
        null,
        null,
        null,
        NOW,
        NOW,
        JoinDelivery.NOTIFY_PARENT,
        true,
        null);
  }
}
