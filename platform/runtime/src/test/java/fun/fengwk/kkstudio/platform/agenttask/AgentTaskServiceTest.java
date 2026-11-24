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

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
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
 * 平台 Agent 任务入口的行为契约：提交只翻译为统一 spawn（交付方式由父关系决定），结果只投影票据冻结回执。
 *
 * <p>Runtime 以窄 mock 表达；本测试断言服务自己的翻译与回执投影语义，不重复 Runtime 的事务/额度测试。
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
  void rootTaskSpawnsAsAReceiptOnlyPluginRun() {
    AcceptedCommands accepted = accepted(UUID.randomUUID());
    when(runtime.spawn(any(), any())).thenReturn(accepted);
    AgentTaskRequest request = request(null, null, null);

    AgentTaskHandle handle = service.submit(request);

    ArgumentCaptor<SpawnRequest> spawn = ArgumentCaptor.forClass(SpawnRequest.class);
    ArgumentCaptor<AcceptancePreflight> preflight =
        ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime).spawn(spawn.capture(), preflight.capture());
    SpawnRequest spawned = spawn.getValue();
    assertEquals(ThreadKind.PLUGIN_TASK, spawned.kind());
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
  void resultProjectsOnlyTheFrozenReceipt() {
    UUID ticketId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(ticketId)).thenReturn(Optional.empty());
    assertTrue(service.result(ticketId).isEmpty());

    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            ticketId,
            childId,
            "worker",
            ThreadJoinOutcome.COMPLETED,
            "prompt",
            "final",
            null,
            null);
    when(runtime.projectJoinReceipt(ticketId)).thenReturn(Optional.of(receipt));

    assertSame(receipt, service.result(ticketId).orElseThrow());
    verify(runtime, never()).findJoin(any());
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void unknownTicketReturnsEmptyResult() {
    UUID ticketId = UUID.randomUUID();
    when(runtime.projectJoinReceipt(ticketId)).thenReturn(Optional.empty());

    assertTrue(service.result(ticketId).isEmpty());
    verify(runtime, never()).findJoin(any());
    verify(runtime, never()).getThreadSnapshot(any());
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
    DomainException unrelatedMissing = DomainException.notFound("session", "gone");
    doThrow(unrelatedMissing).when(runtime).stop(any(StopCommand.class));
    assertSame(
        unrelatedMissing, assertThrows(DomainException.class, () -> service.stopThread(threadId)));
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
  @SuppressWarnings("unchecked")
  void missingRuntimeFailsDeterministically() {
    ObjectProvider<HarnessRuntime> missing = mock(ObjectProvider.class);
    when(missing.getIfAvailable()).thenReturn(null);
    AgentTaskService unbound = new AgentTaskService(missing);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> unbound.submit(request(null, null, null)));
    assertEquals("harness runtime is not available in this deployment", failure.getMessage());
    assertThrows(IllegalStateException.class, () -> unbound.result(UUID.randomUUID()));
    assertThrows(IllegalStateException.class, () -> unbound.stopThread(UUID.randomUUID()));
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
        ThreadKind.PLUGIN_TASK,
        parentId,
        parentHead,
        cont,
        false,
        parentId != null,
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
        ThreadKind.PLUGIN_TASK,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        version,
        NOW,
        NOW);
  }
}
