package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentPrompts;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
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
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.platform.harness.configuration.SubagentTaskProperties;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 验证后台结算扫描的交付、停止传播、提醒与公平调度契约。
 *
 * <p>测试意图：把异步 task 的关键语义钉死在单元层——(1) 终态先持久化再交付，CAS 未命中绝不冒充成功；(2) 交付与 DELIVERED 推进同事务，且在事务内复核 "父仍未停止
 * / 内容仍是同一份持久结果"；(3) 父停止时只持久保留结果、不自动唤醒，停止有界且幂等地传播到子执行；(4) 未开始即被取消的执行必须结清，否则记录 永久停在 OPEN；(5)
 * 嵌套子树未结清时不得把中间态当作最终结果；(6) 批次轮转保证无法推进的记录不会饿死后面的记录。
 */
class SubagentTaskSettlementScannerTest {

  private static final UUID INVOCATION_ID = new UUID(0L, 1L);
  private static final UUID PARENT_THREAD_ID = new UUID(0L, 2L);
  private static final UUID CHILD_THREAD_ID = new UUID(0L, 3L);
  private static final UUID CHILD_SESSION_ID = new UUID(0L, 4L);
  private static final UUID ROOT_THREAD_ID = new UUID(0L, 5L);
  private static final UUID BOUNDARY_ENTRY_ID = new UUID(0L, 6L);
  private static final UUID PARENT_HEAD_ENTRY_ID = new UUID(0L, 7L);
  private static final UUID PARENT_TURN_START_ENTRY_ID = new UUID(0L, 8L);
  private static final UUID LIVE_PARENT_ID = new UUID(0L, 9L);
  private static final UUID STOPPED_PARENT_ID = new UUID(0L, 10L);
  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

  private HarnessRuntime runtime;
  private HarnessStore store;
  private SubagentTaskRepository repository;
  private SubagentTaskActivity activity;
  private SubagentTaskProperties properties;
  private SubagentTaskSettlementScanner scanner;

  /** 结算事务内锁定到的子 Thread 事实：默认与投影快照一致，测试可覆盖它以模拟"投影后子执行又推进"。 */
  private ThreadState lockedChildFacts;

  /** 结算事务句柄，供断言锁序。 */
  private HarnessStore.Transaction settleTx;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    repository = mock(SubagentTaskRepository.class);
    activity = mock(SubagentTaskActivity.class);
    store = mock(HarnessStore.class);
    settleTx = mock(HarnessStore.Transaction.class);
    when(settleTx.lockSessionForKeyShare(CHILD_SESSION_ID))
        .thenReturn(Optional.of(mock(Session.class)));
    when(settleTx.lockThread(CHILD_THREAD_ID))
        .thenAnswer(invocation -> Optional.ofNullable(lockedChildFacts));
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, Object> callback = invocation.getArgument(0);
              return callback.apply(settleTx);
            });
    SubagentConfigProvider configProvider =
        () -> new SubagentConfig(2, 3, 10, Duration.ofSeconds(30), 6);
    properties = new SubagentTaskProperties();
    scanner =
        new SubagentTaskSettlementScanner(
            () -> runtime, store, repository, activity, configProvider, properties);
  }

  // ------------------------------------------------------------------ 扫描调度

  @Test
  void settleOnceReturnsZeroWhenRuntimeIsUnavailable() {
    // 测试意图：Runtime 不可用时不触碰任何持久事实，也不把"读不到"当成"没有待办"。
    SubagentTaskSettlementScanner offline =
        new SubagentTaskSettlementScanner(
            () -> null,
            store,
            repository,
            activity,
            () -> new SubagentConfig(2, 3, 10, Duration.ofSeconds(30), 6),
            properties);

    assertEquals(0, offline.settleOnce());
    verifyNoInteractions(repository);
  }

  @Test
  void settleOnceRotatesFairCursorAndWrapsAtTail() {
    // 测试意图：批次取满时游标前进、取不满时回到起点，使无法推进的记录不会永久占据批次前部而饿死后面的记录。
    properties.setSettlementBatchSize(2);
    SubagentTask first = task(new UUID(0L, 11L), CREATED_AT, SubagentTaskStatus.DELIVERED);
    SubagentTask second =
        task(new UUID(0L, 12L), CREATED_AT.plusMillis(1), SubagentTaskStatus.DELIVERED);

    when(repository.listUndeliveredAfter(null, null, 2)).thenReturn(List.of(first, second));
    assertEquals(2, scanner.settleOnce());

    when(repository.listUndeliveredAfter(second.createdAt(), second.invocationId(), 2))
        .thenReturn(List.of());
    assertEquals(0, scanner.settleOnce());

    when(repository.listUndeliveredAfter(null, null, 2)).thenReturn(List.of(first));
    assertEquals(1, scanner.settleOnce());

    InOrder order = inOrder(repository);
    order.verify(repository).listUndeliveredAfter(null, null, 2);
    order.verify(repository).listUndeliveredAfter(second.createdAt(), second.invocationId(), 2);
    order.verify(repository).listUndeliveredAfter(null, null, 2);
  }

  @Test
  void settleOnceIsolatesFailingRecords() {
    // 测试意图：单条记录的故障不得中断整轮扫描，其余记录仍被推进。
    SubagentTask broken = task(new UUID(0L, 21L), CREATED_AT, SubagentTaskStatus.OPEN);
    SubagentTask healthy =
        task(new UUID(0L, 22L), CREATED_AT.plusMillis(1), SubagentTaskStatus.DELIVERED);

    when(repository.listUndeliveredAfter(null, null, 32)).thenReturn(List.of(broken, healthy));
    when(runtime.getThreadSnapshot(broken.childThreadId()))
        .thenThrow(new IllegalStateException("boom"));

    assertEquals(1, scanner.settleOnce());
  }

  @Test
  void fairRotationDeliversTailRowDespiteStoppedParentPrefix() {
    // 测试意图（公平性 + 存活性质）：一批"父已停止"的记录（只保留待交付、本轮无法推进）占据队首时，
    // 轮转游标必须保证队尾的记录在 ceil(N / batch) + 1 轮内被评估并交付；否则停止的父会永久饿死新委派的交付，
    // 同时被挡住的记录本身也必须在回转时被重新评估（父恢复后才能交付）。
    int batch = 3;
    properties.setSettlementBatchSize(batch);
    // 6 条"父已停止"的 SETTLED 记录 + 1 条队尾的健康记录。
    List<SubagentTask> rows = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      rows.add(settledTask(new UUID(0L, 100L + i), CREATED_AT.plusMillis(i), stoppedParentId(i)));
    }
    SubagentTask tail = settledTask(new UUID(0L, 200L), CREATED_AT.plusMillis(6), LIVE_PARENT_ID);
    rows.add(tail);

    List<SubagentTask> undelivered = new ArrayList<>(rows);
    Map<UUID, Integer> visits = new LinkedHashMap<>();
    List<UUID> roundVisits = new ArrayList<>();
    for (SubagentTask row : rows) {
      Entry head = row.parentThreadId().equals(LIVE_PARENT_ID) ? activeHead() : stoppedHead();
      ThreadSnapshot parent = parentSnapshot(head);
      doAnswer(
              invocation -> {
                roundVisits.add(row.parentThreadId());
                return parent;
              })
          .when(runtime)
          .getThreadSnapshot(row.parentThreadId());
    }

    when(repository.listUndeliveredAfter(any(), any(), anyInt()))
        .thenAnswer(
            invocation ->
                page(
                    undelivered,
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(repository.findByInvocationId(any()))
        .thenAnswer(
            invocation ->
                rows.stream()
                    .filter(row -> row.invocationId().equals(invocation.getArgument(0)))
                    .findFirst()
                    .orElse(null));
    when(repository.markDelivered(any()))
        .thenAnswer(
            invocation -> {
              UUID invocationId = invocation.getArgument(0);
              undelivered.removeIf(row -> row.invocationId().equals(invocationId));
              return true;
            });
    stubDeliveryAcceptance(LIVE_PARENT_ID);

    boolean tailDelivered = false;
    int bound = (rows.size() + batch - 1) / batch + 1;
    for (int round = 0; round < bound; round++) {
      roundVisits.clear();
      scanner.settleOnce();
      for (UUID parentId : roundVisits) {
        visits.merge(parentId, 1, Integer::sum);
      }
      if (undelivered.stream().noneMatch(row -> row.invocationId().equals(tail.invocationId()))) {
        tailDelivered = true;
      }
      // 不提前退出：继续跑满上界，验证被挡住的记录在回转后确实被重新评估（父恢复后才能真正交付）。
    }

    // 队尾记录在有界轮次内被交付，且每条记录都被评估过（没有任何记录被永久跳过）。
    assertTrue(tailDelivered, "tail row must be delivered within " + bound + " rounds");
    for (SubagentTask row : rows) {
      assertTrue(
          visits.containsKey(row.parentThreadId()) || visits.containsKey(row.invocationId()),
          "row " + row.invocationId() + " must be evaluated at least once");
    }
    // 被挡住的记录不是一次性跳过：回转后会再次被评估（父恢复后才能交付）。
    assertTrue(
        visits.get(stoppedParentId(0)) >= 2,
        "blocked rows must be revisited after the cursor wraps, visits=" + visits);
  }

  @Test
  void settledRowsUnderStoppedParentsNeverBlockProgressOfLaterRows() {
    // 测试意图：父已停止时 SETTLED 记录只保留待交付（不推进、不注入提醒、不启动新一轮）；同一批里其后可交付的记录
    // 必须仍然在本轮被交付——"父停止"只影响它自己的记录，不能阻断整轮结算。
    SubagentTask blocked = settledTask(new UUID(0L, 301L), CREATED_AT, STOPPED_PARENT_ID);
    SubagentTask healthy =
        settledTask(new UUID(0L, 302L), CREATED_AT.plusMillis(1), LIVE_PARENT_ID);

    when(repository.listUndeliveredAfter(null, null, 32)).thenReturn(List.of(blocked, healthy));
    Entry stoppedHead = stoppedHead();
    ThreadSnapshot stoppedParent = parentSnapshot(stoppedHead);
    when(runtime.getThreadSnapshot(STOPPED_PARENT_ID)).thenReturn(stoppedParent);
    Entry liveHead = activeHead();
    ThreadSnapshot liveParent = parentSnapshot(liveHead);
    when(runtime.getThreadSnapshot(LIVE_PARENT_ID)).thenReturn(liveParent);
    when(repository.findByInvocationId(healthy.invocationId())).thenReturn(healthy);
    when(repository.markDelivered(healthy.invocationId())).thenReturn(true);
    stubDeliveryAcceptance(LIVE_PARENT_ID);

    assertEquals(1, scanner.settleOnce());

    // 被挡住的记录保持待交付（不产生 DELIVERED 推进），健康记录恰好交付一次。
    verify(repository, never()).markDelivered(blocked.invocationId());
    verify(repository).markDelivered(healthy.invocationId());
    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
  }

  // ------------------------------------------------------------------ 结算

  @Test
  void terminalChildExecutionIsSettledThenDelivered() {
    // 测试意图：子执行终结必须先持久化终态（OPEN→SETTLED），再以持久内容交付父；交付内容取自记录而非子历史。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    when(repository.settleResult(INVOCATION_ID, Outcome.COMPLETED, null, null, null))
        .thenReturn(true);
    SubagentTask settled = settledTask(Outcome.COMPLETED, null);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertTrue(scanner.settle(runtime, open));

    InOrder order = inOrder(repository);
    order.verify(repository).settleResult(INVOCATION_ID, Outcome.COMPLETED, null, null, null);
    order.verify(repository).findByInvocationId(INVOCATION_ID);

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime).acceptCommands(command.capture(), any());
    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, command.getValue().target());
    assertEquals(PARENT_THREAD_ID, target.threadId());
    assertEquals(PARENT_HEAD_ENTRY_ID, target.expectedHeadEntryId());
    CustomMessageCommandPayload payload =
        assertInstanceOf(
            CustomMessageCommandPayload.class, command.getValue().commands().getFirst().payload());
    String message = text(payload.message());
    assertTrue(message.contains("thread_id=\"" + CHILD_THREAD_ID + "\""), message);
    assertTrue(message.contains("state=\"completed\""), message);
    assertTrue(message.contains("do the work"), message);
  }

  @Test
  void settleResultCasMissNeverDelivers() {
    // 测试意图：CAS 未命中说明并发方已推进该记录，本事务既不得交付也不得假装成功。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(false);

    assertFalse(scanner.settle(runtime, open));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void deletedChildThreadIsSettledAsExplicitError() {
    // 测试意图：子 Thread 被删除时必须结清为明确错误并通知父，绝不静默丢失委派（否则父被误判空闲）。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("child thread is gone"));
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(repository.settleResult(eq(INVOCATION_ID), eq(Outcome.ERROR), any(), any(), any()))
        .thenReturn(true);
    SubagentTask settled = settledTask(Outcome.ERROR, null);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertTrue(scanner.settle(runtime, open));

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(repository)
        .settleResult(eq(INVOCATION_ID), eq(Outcome.ERROR), any(), any(), error.capture());
    assertTrue(error.getValue().contains("deleted"), error.getValue());
  }

  @Test
  void missingParentThreadIsSettledConservatively() {
    // 测试意图：父 Thread 不存在时没有交付对象，但仍须结清，避免记录永久占用额度。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("parent thread is gone"));
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(true);

    assertTrue(scanner.settle(runtime, open));

    verify(repository).settleResult(eq(INVOCATION_ID), eq(Outcome.ERROR), any(), any(), any());
    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void abortedBeforeStartExecutionIsSettledAsCancelled() {
    // 测试意图：prompt 被消费前取消（子线程静止且边界之后没有任何条目）的执行不可能再产生终态，必须结清为取消，
    // 否则记录永久停在 OPEN：父永久 processing、额度永久占用、父永远收不到通知。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    ThreadSnapshot child = childSnapshot(List.of(boundary));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(true);
    SubagentTask settled = settledTask(Outcome.CANCELLED, null);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertTrue(scanner.settle(runtime, open));

    ArgumentCaptor<Outcome> outcome = ArgumentCaptor.forClass(Outcome.class);
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(repository).settleResult(any(), outcome.capture(), any(), any(), error.capture());
    assertEquals(Outcome.CANCELLED, outcome.getValue());
    assertTrue(error.getValue().contains("cancelled before it started"), error.getValue());
  }

  @Test
  void queuedPromptKeepsRecordOpenInsteadOfBeingTreatedAsCancelled() {
    // 测试意图：委派刚被接受（prompt 仍 QUEUED）时不得被误判为"未开始即取消"，必须保持 OPEN 等子执行推进。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    ThreadSnapshot child = childSnapshot(List.of(boundary), queuedCommand());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);

    assertFalse(scanner.settle(runtime, open));

    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
  }

  @Test
  void nestedDelegationDefersSettlementUntilSubtreeIsSettled() {
    // 测试意图：子线程自身到达终态但其子树仍有会被交付并唤醒它的委派时，本次执行尚未结束，不得把中间态当作最终结果。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(true);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);

    assertFalse(scanner.settle(runtime, open));

    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
    verify(runtime, never()).acceptCommands(any(), any());
  }

  // ------------------------------------------------------------------ 停止传播

  @Test
  void stoppedParentHoldsDeliveryAndPropagatesIdempotentStop() {
    // 测试意图：父显式停止后，晚到结果只持久保留、绝不注入唤醒；停止以稳定幂等键有界传播到未结清子执行。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry stoppedParentHead = stoppedHead();
    ThreadSnapshot stoppedParent = parentSnapshot(stoppedParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(stoppedParent);

    assertTrue(scanner.settle(runtime, open));
    assertTrue(scanner.settle(runtime, open));

    verify(runtime, never()).acceptCommands(any(), any());
    ArgumentCaptor<StopCommand> stop = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime, times(2)).stop(stop.capture());
    // 反复传播必须是同一次停止请求的幂等重放，而不是每轮制造新 stop。
    assertEquals(
        stop.getAllValues().get(0).stopRequestId(), stop.getAllValues().get(1).stopRequestId());
    assertEquals(CHILD_THREAD_ID, stop.getAllValues().get(0).threadId());
  }

  @Test
  void stoppedParentKeepsSettledResultUndelivered() {
    // 测试意图：已终结但父停止的结果只保持待交付，不推进 DELIVERED（父恢复后由下一次扫描交付）。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry stoppedParentHead = stoppedHead();
    ThreadSnapshot stoppedParent = parentSnapshot(stoppedParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(stoppedParent);

    assertFalse(scanner.settle(runtime, settled));

    verify(runtime, never()).acceptCommands(any(), any());
    verify(repository, never()).markDelivered(any());
  }

  // ------------------------------------------------------------------ 交付事务内复核

  @Test
  void deliveryPreflightMarksDeliveredInSameTransaction() {
    // 测试意图：DELIVERED 推进必须与结果消息入队同事务生效，绝不出现"消息已入队但状态未推进"。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    when(repository.markDelivered(INVOCATION_ID)).thenReturn(true);

    assertTrue(scanner.settle(runtime, settled));

    AcceptancePreflight preflight = capturedPreflight();
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    Entry head = activeHead();
    when(tx.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
    List<NewThreadCommand> commands = List.of(newThreadCommand(deliveryPayload()));
    assertSame(commands, preflight.prepare(tx, mock(Session.class), commands));
    verify(repository).markDelivered(INVOCATION_ID);
  }

  @Test
  void deliveryPreflightRejectsExplicitlyStoppedParent() {
    // 测试意图：事务内复核父仍在非停止态；父已停止（含并发停止）时整个接受事务必须回滚，注入不得落地。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertTrue(scanner.settle(runtime, settled));

    AcceptancePreflight preflight = capturedPreflight();
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    Entry head = stoppedHead();
    when(tx.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));

    List<NewThreadCommand> commands = List.of(newThreadCommand(deliveryPayload()));
    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> preflight.prepare(tx, mock(Session.class), commands));
    verify(repository, never()).markDelivered(any());
  }

  @Test
  void deliveryPreflightRejectsChangedResultAndMissingParent() {
    // 测试意图：交付内容以持久事实为准；结果已被改写或父行消失时同样必须回滚，不得交付陈旧内容。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertTrue(scanner.settle(runtime, settled));
    AcceptancePreflight preflight = capturedPreflight();

    HarnessStore.Transaction changed = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    Entry head = activeHead();
    when(changed.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(changed.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
    SubagentTask rewritten = settledTask(Outcome.COMPLETED, "rewritten");
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(rewritten);
    List<NewThreadCommand> commands = List.of(newThreadCommand(deliveryPayload()));
    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> preflight.prepare(changed, mock(Session.class), commands));

    HarnessStore.Transaction parentGone = mock(HarnessStore.Transaction.class);
    when(parentGone.findThread(PARENT_THREAD_ID)).thenReturn(Optional.empty());
    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> preflight.prepare(parentGone, mock(Session.class), commands));
    verify(repository, never()).markDelivered(INVOCATION_ID);
  }

  @Test
  void replayedDeliveryConfirmsDeliveredStatus() {
    // 测试意图：精确重放说明命令与状态推进已在同一事务提交，这里只做幂等确认。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(true);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(deliveredTask());

    assertTrue(scanner.settle(runtime, settled));

    verify(repository, never()).markDelivered(any());
  }

  @Test
  void lostRaceWithoutConvergenceIsReportedInsteadOfSwallowed() {
    // 测试意图：交付丢失竞争且无法收敛时不得把记录当作已交付吞掉；必须报告失败并留给下一轮重试。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(runtime.acceptCommands(any(), any()))
        .thenThrow(new SubagentTaskSettlementConflictException("result changed"));
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    when(repository.markDelivered(INVOCATION_ID)).thenReturn(false);

    assertFalse(scanner.settle(runtime, settled));
  }

  @Test
  void deliveryCursorConflictIsRetriedWithFreshParentSnapshot() {
    // 测试意图：父在读取与接受之间推进时以最新 cursor 重试，重试仍失败则明确报告未交付。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(runtime.acceptCommands(any(), any()))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale cursor"));

    assertFalse(scanner.settle(runtime, settled));

    verify(runtime, times(3)).acceptCommands(any(), any());
  }

  @Test
  void deliverySkipsParentThatNoLongerExists() {
    // 测试意图：父 Thread 不存在时记录随父级联删除，本工作项已不存在，无需再交付也不应误报失败。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("parent is gone"));

    assertTrue(scanner.settle(runtime, settled));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  // ------------------------------------------------------------------ 生命周期与后台调度

  @Test
  void lifecycleStartAndStopAreIdempotentAndSafeBeforeStart() {
    // 测试意图：与 Spring SmartLifecycle 契约一致——未启动时停止是 no-op，重复启动只有一个调度器，扫描相位排在最后
    // （数据库与 Runtime 就绪后才第一次扫描），停止后不再调度。
    assertFalse(scanner.isRunning());
    scanner.stop();
    assertEquals(Integer.MAX_VALUE, scanner.getPhase());
    properties.setSettlementInterval(Duration.ofMillis(20));

    scanner.start();
    scanner.start();
    assertTrue(scanner.isRunning());
    // 后台调度确实在跑（有界等待，不依赖固定 sleep）。
    verify(repository, timeout(2_000)).listUndeliveredAfter(any(), any(), anyInt());

    scanner.stop();
    scanner.stop();
    assertFalse(scanner.isRunning());
  }

  @Test
  void scheduledScanFailureIsContainedSoNextScanStillRuns() {
    // 测试意图：后台扫描抛异常必须被吞掉并记录（不能杀死调度线程），下一轮仍照常执行。
    properties.setSettlementInterval(Duration.ofMillis(20));
    when(repository.listUndeliveredAfter(any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("database is unavailable"));
    scanner.start();
    try {
      verify(repository, timeout(2_000).atLeast(3)).listUndeliveredAfter(any(), any(), anyInt());
    } finally {
      scanner.stop();
    }
  }

  // ------------------------------------------------------------------ 交付失败路径

  @Test
  void deliveryAfterSettleSkipsRecordThatDisappeared() {
    // 测试意图：OPEN→SETTLED 成功后记录可能已被并发方删除（如父级联删除）：此时本工作项已不存在，
    // 既不入队结果也不误报失败。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(true);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(null);

    assertFalse(scanner.settle(runtime, open));

    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void deliveryTreatsAcceptNotFoundAsCompleted() {
    // 测试意图：目标父 Thread 在读取与接受之间消失（记录随父级联删除）时视为本工作项已不存在，
    // 不再重试也不再误报失败。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(runtime.acceptCommands(any(), any()))
        .thenThrow(new HarnessRuntimeNotFoundException("parent is gone"));

    assertTrue(scanner.settle(runtime, settled));
  }

  @Test
  void deliveryPreflightRejectsLostRaceInsideTransaction() {
    // 测试意图：DELIVERED 的 CAS 若在事务内未命中，必须让整个交付事务回滚（结果消息不得入队）；
    // 只有确认并发方已完成交付才算完成。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    AtomicReference<AcceptancePreflight> captured = new AtomicReference<>();
    when(runtime.acceptCommands(any(), any()))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(1));
              return accepted(false);
            });
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    when(repository.markDelivered(INVOCATION_ID)).thenReturn(false);

    assertTrue(scanner.settle(runtime, settled));

    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> captured.get().prepare(parentTransaction(), mock(Session.class), List.of()));
  }

  @Test
  void deliveryGateRejectsInconsistentParentStateInTransaction() {
    // 测试意图：父 Thread 行存在但 head Entry 缺失（不一致的持久状态）时必须在事务内拒绝交付：
    // 无法判定停止状态的目标绝不接收注入。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    AtomicReference<AcceptancePreflight> captured = new AtomicReference<>();
    when(runtime.acceptCommands(any(), any()))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(1));
              return accepted(false);
            });
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);
    when(repository.markDelivered(INVOCATION_ID)).thenReturn(true);

    assertTrue(scanner.settle(runtime, settled));

    HarnessStore.Transaction headMissing = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    when(headMissing.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(headMissing.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.empty());
    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> captured.get().prepare(headMissing, mock(Session.class), List.of()));
  }

  // ------------------------------------------------------------------ 停止传播的失败路径

  @Test
  void stopPropagationTreatsMissingChildAsCompleted() {
    // 测试意图：子 Thread 已不存在视为停止已完成（本就不需要再停止）。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry stoppedParentHead = stoppedHead();
    ThreadSnapshot stoppedParent = parentSnapshot(stoppedParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(stoppedParent);
    when(runtime.stop(any()))
        .thenThrow(new HarnessRuntimeNotFoundException("child thread is gone"));

    assertTrue(scanner.settle(runtime, open));
  }

  @Test
  void stopPropagationGivesUpAfterBoundedConflicts() {
    // 测试意图：持续冲突时以最新 version 有界重试，用尽后明确报告本轮未完成（下一轮重试同一幂等停止请求）。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry stoppedParentHead = stoppedHead();
    ThreadSnapshot stoppedParent = parentSnapshot(stoppedParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(stoppedParent);
    when(runtime.stop(any()))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "stale version"));

    assertFalse(scanner.settle(runtime, open));

    verify(runtime, times(3)).stop(any());
  }

  // ------------------------------------------------------------------ 结算前的事务内锁定复核

  @Test
  void settlementIsSkippedWhenChildExecutionAdvancedAfterTheProjection() {
    // 测试意图：终态是从锁外的子 snapshot 投影的；若子执行在投影之后又被推进（例如子级委派的结果刚被交付进该子 Thread、
    // 产生新 turn、或历史被回退），旧终态不再是这次执行的结局，必须放弃结算等下一轮，绝不能写进 SETTLED。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    ThreadState drifted = mock(ThreadState.class);
    when(drifted.version()).thenReturn(4L);
    when(drifted.headEntryId()).thenReturn(new UUID(0L, 33L));
    when(drifted.nextCommandSequence()).thenReturn(6L);
    lockedChildFacts = drifted;

    assertFalse(scanner.settle(runtime, open));

    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void settlementLocksChildSessionThenChildThreadInOneStoreTransaction() {
    // 测试意图：结算的锁定复核必须按规范锁序（先 Session KEY SHARE，再 Thread FOR UPDATE）在同一 store 事务内完成，
    // CAS 也必须在同一事务内；把结果交付进该子 Thread 的事务锁同一个 Thread 行，因此投影到 SETTLED 之间不可能插入交付。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(true);
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(null);

    scanner.settle(runtime, open);

    InOrder order = inOrder(settleTx, repository);
    order.verify(settleTx).lockSessionForKeyShare(CHILD_SESSION_ID);
    order.verify(settleTx).lockThread(CHILD_THREAD_ID);
    order
        .verify(repository)
        .settleResult(eq(INVOCATION_ID), eq(Outcome.COMPLETED), any(), any(), any());
  }

  @Test
  void settlementIsSkippedWhenChildThreadRowIsGone() {
    // 测试意图：锁定复核时子 Thread 行已不存在（并发删除）时不结算：下一轮由"子缺失"分支给出明确异常终态。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(completedEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(false);
    lockedChildFacts = null;

    assertFalse(scanner.settle(runtime, open));

    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
  }

  @Test
  void stoppedChildWithLiveSubtreePropagatesStopInsteadOfSettling() {
    // 测试意图：子执行自己是被停止的（CANCELLED 终态），但它的子树里还有 OPEN 执行时，停止必须先确认传播到后代：
    // 本轮幂等传播停止并等待，绝不把"子树还在跑"的执行当成已结束结算。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(stoppedChildEntries());
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    when(activity.hasPendingDelegatedWork(CHILD_THREAD_ID)).thenReturn(true);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);

    assertFalse(scanner.settle(runtime, open));

    verify(repository, never()).settleResult(any(), any(), any(), any(), any());
    verify(runtime).stop(any());
  }

  @Test
  void deliveryConflictNeverRepairsStatusOutsideTheTransaction() {
    // 测试意图：交付事务因父刚被停止而回滚时，结果消息并未入队。此时在事务外把记录修补成 DELIVERED 会永久吞掉一次真实交付，
    // 因此 confirm 只按事实判定（未交付即失败），绝不修改状态。
    SubagentTask settled = settledTask(Outcome.COMPLETED, "done");
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    when(runtime.acceptCommands(any(), any()))
        .thenThrow(new SubagentTaskSettlementConflictException("parent just stopped"));
    when(repository.findByInvocationId(INVOCATION_ID)).thenReturn(settled);

    assertFalse(scanner.settle(runtime, settled));

    verify(repository, never()).markDelivered(any());
  }

  // ------------------------------------------------------------------ 软预算提醒

  @Test
  void reminderUsesPreviousThresholdPlusInterval() {
    // 测试意图：第二次提醒的阈值是"上次阈值 + 固定间隔"，且阈值推进必须在提醒事务内 CAS：
    // CAS 未命中说明并发方已推进阈值，本次提醒整体不注入（软预算尽力而为，不影响结算主路径）。
    SubagentTask open = openTaskWithReminder(6L, 6);
    ThreadSnapshot child = childSnapshot(runningEntriesWithTurns(11), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AtomicReference<AcceptancePreflight> captured = new AtomicReference<>();
    when(runtime.acceptCommands(any(), any()))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(1));
              return accepted(false);
            });

    assertFalse(scanner.settle(runtime, open));
    verify(runtime).acceptCommands(any(), any());

    when(repository.updateReminderTurn(INVOCATION_ID, 6L, 11L)).thenReturn(false);
    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> captured.get().prepare(parentTransaction(), mock(Session.class), List.of()));

    when(repository.updateReminderTurn(INVOCATION_ID, 6L, 11L)).thenReturn(true);
    assertEquals(
        List.of(), captured.get().prepare(parentTransaction(), mock(Session.class), List.of()));
    verify(repository, times(2)).updateReminderTurn(INVOCATION_ID, 6L, 11L);
  }

  @Test
  void reminderFallsBackToConfiguredMaxTurnsWhenCallBudgetIsUnset() {
    // 测试意图：调用未给 max_turns 时阈值取当前 policy 默认（配置为 6）：达到即提醒，低于阈值绝不提醒。
    SubagentTask open = openTaskWithReminder(0L, null);
    ThreadSnapshot child = childSnapshot(runningEntriesWithTurns(6), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertFalse(scanner.settle(runtime, open));
    verify(runtime).acceptCommands(any(), any());

    reset(runtime);
    SubagentTask below = openTaskWithReminder(0L, null);
    ThreadSnapshot running = childSnapshot(runningEntriesWithTurns(2), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(running);
    ThreadSnapshot liveParent = parentSnapshot(activeHead());
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(liveParent);

    assertFalse(scanner.settle(runtime, below));
    verify(runtime, never()).acceptCommands(any(), any());
  }

  @Test
  void reminderIsSentOnceAtThresholdWithStableIdempotencyKey() {
    // 测试意图：达到 max_turns 阈值时向仍在执行的子线程注入一次收敛提醒，幂等键按 (invocation, 阈值) 稳定派生；
    // 阈值推进在提醒事务内 CAS，重放不会绕过。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);
    when(repository.updateReminderTurn(INVOCATION_ID, 0L, 1L)).thenReturn(true);

    assertFalse(scanner.settle(runtime, open));
    assertFalse(scanner.settle(runtime, open));

    ArgumentCaptor<AcceptCommandsCommand> command =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(runtime, times(2)).acceptCommands(command.capture(), any());
    List<AcceptCommandsCommand> commands = command.getAllValues();
    assertEquals(
        CHILD_THREAD_ID, ((AcceptCommandsTarget.Thread) commands.get(0).target()).threadId());
    // 同一阈值必须复用同一幂等键：重试命中精确重放而不是产生第二条提醒。
    assertEquals(
        commands.get(0).commands().getFirst().idempotencyKey(),
        commands.get(1).commands().getFirst().idempotencyKey());

    AcceptancePreflight preflight = capturedPreflight();
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    Entry head = activeHead();
    when(tx.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
    List<NewThreadCommand> reminder = List.of(newThreadCommand(reminderPayload()));
    assertEquals(reminder, preflight.prepare(tx, mock(Session.class), reminder));
    verify(repository).updateReminderTurn(INVOCATION_ID, 0L, 1L);
  }

  @Test
  void reminderIsSkippedForIdleChildAndBelowThreshold() {
    // 测试意图：静止子线程与未达阈值的执行都不注入提醒，避免对已完成或刚开始的执行产生噪音。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    ThreadSnapshot child = childSnapshot(List.of(boundary));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry head = activeHead();
    ThreadSnapshot parent = parentSnapshot(head);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(parent);
    when(repository.settleResult(any(), any(), any(), any(), any())).thenReturn(false);

    assertFalse(scanner.settle(runtime, open));
    verify(runtime, never()).acceptCommands(any(), any());
    verify(repository, never()).updateReminderTurn(any(), anyLong(), anyLong());
  }

  @Test
  void reminderPreflightRejectsStoppedParent() {
    // 测试意图：提醒同样是注入，父在事务内被确认停止时不得落地（不唤醒被显式停止的父）。
    SubagentTask open = task(INVOCATION_ID, CREATED_AT, SubagentTaskStatus.OPEN);
    ThreadSnapshot child = childSnapshot(runningEntries(), mock(ModelInvocation.class));
    when(runtime.getThreadSnapshot(CHILD_THREAD_ID)).thenReturn(child);
    Entry activeParentHead = activeHead();
    ThreadSnapshot activeParent = parentSnapshot(activeParentHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(activeParent);
    AcceptedCommands accepted = accepted(false);
    when(runtime.acceptCommands(any(), any())).thenReturn(accepted);

    assertFalse(scanner.settle(runtime, open));

    AcceptancePreflight preflight = capturedPreflight();
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    ThreadState parentState = parentThreadState();
    Entry head = stoppedHead();
    when(tx.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parentState));
    when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
    List<NewThreadCommand> reminder = List.of(newThreadCommand(reminderPayload()));

    assertThrows(
        SubagentTaskSettlementConflictException.class,
        () -> preflight.prepare(tx, mock(Session.class), reminder));
    verify(repository, never()).updateReminderTurn(any(), anyLong(), anyLong());
  }

  // ------------------------------------------------------------------ fixtures

  private AcceptancePreflight capturedPreflight() {
    ArgumentCaptor<AcceptancePreflight> preflight =
        ArgumentCaptor.forClass(AcceptancePreflight.class);
    verify(runtime, atLeastOnce()).acceptCommands(any(), preflight.capture());
    return preflight.getValue();
  }

  private SubagentTask task(UUID invocationId, Instant createdAt, SubagentTaskStatus status) {
    boolean settled = status != SubagentTaskStatus.OPEN;
    return new SubagentTask(
        invocationId,
        PARENT_THREAD_ID,
        ROOT_THREAD_ID,
        CHILD_SESSION_ID,
        CHILD_THREAD_ID,
        BOUNDARY_ENTRY_ID,
        "assistant",
        "do the work",
        1,
        status,
        settled ? Outcome.COMPLETED : null,
        settled ? "done" : null,
        null,
        null,
        0L,
        settled ? createdAt.plusMillis(1) : null,
        createdAt,
        createdAt);
  }

  private SubagentTask settledTask(Outcome outcome, String report) {
    return new SubagentTask(
        INVOCATION_ID,
        PARENT_THREAD_ID,
        ROOT_THREAD_ID,
        CHILD_SESSION_ID,
        CHILD_THREAD_ID,
        BOUNDARY_ENTRY_ID,
        "assistant",
        "do the work",
        1,
        SubagentTaskStatus.SETTLED,
        outcome,
        report,
        null,
        outcome == Outcome.ERROR ? "boom" : null,
        0L,
        CREATED_AT.plusMillis(1),
        CREATED_AT,
        CREATED_AT);
  }

  private SubagentTask deliveredTask() {
    return new SubagentTask(
        INVOCATION_ID,
        PARENT_THREAD_ID,
        ROOT_THREAD_ID,
        CHILD_SESSION_ID,
        CHILD_THREAD_ID,
        BOUNDARY_ENTRY_ID,
        "assistant",
        "do the work",
        1,
        SubagentTaskStatus.DELIVERED,
        Outcome.COMPLETED,
        "done",
        null,
        null,
        0L,
        CREATED_AT.plusMillis(1),
        CREATED_AT,
        CREATED_AT);
  }

  private static Entry entry(UUID id, EntryPayload payload) {
    Entry entry = mock(Entry.class);
    when(entry.id()).thenReturn(id);
    when(entry.payload()).thenReturn(payload);
    return entry;
  }

  /** 已终结的本次执行：边界 + 非 continuation 的 COMPLETED turn 结束边界。 */
  private List<Entry> completedEntries() {
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    Entry turnEnd =
        entry(
            new UUID(0L, 31L),
            new TurnEndPayload(
                PARENT_TURN_START_ENTRY_ID, TurnEndOutcome.COMPLETED, false, null, null));
    return List.of(boundary, turnEnd);
  }

  /** 子执行被停止的本次执行：边界 + STOPPED turn 结束边界。 */
  private List<Entry> stoppedChildEntries() {
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    Entry turnEnd =
        entry(
            new UUID(0L, 34L),
            new TurnEndPayload(
                PARENT_TURN_START_ENTRY_ID,
                TurnEndOutcome.STOPPED,
                false,
                TurnEndReason.USER_STOP,
                new UUID(0L, 35L)));
    return List.of(boundary, turnEnd);
  }

  /** 仍在执行的本次执行：边界之后已有 turn（用于提醒阈值计数）。 */
  private List<Entry> runningEntries() {
    Entry boundary = entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS));
    Entry turnStart =
        entry(
            new UUID(0L, 32L),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, CHILD_THREAD_ID));
    return List.of(boundary, turnStart);
  }

  private ThreadSnapshot childSnapshot(List<Entry> entries) {
    return childSnapshot(entries, List.of(), null);
  }

  private ThreadSnapshot childSnapshot(List<Entry> entries, ModelInvocation model) {
    return childSnapshot(entries, List.of(), model);
  }

  private ThreadSnapshot childSnapshot(List<Entry> entries, List<ThreadCommand> queued) {
    return childSnapshot(entries, queued, null);
  }

  private ThreadSnapshot childSnapshot(
      List<Entry> entries, List<ThreadCommand> queued, ModelInvocation model) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    EntryPath path = mock(EntryPath.class);
    ThreadState thread = mock(ThreadState.class);
    when(snapshot.entryPath()).thenReturn(path);
    when(path.entries()).thenReturn(entries);
    // thenReturn 的实参不得调用 mock 方法（会被 Mockito 视为新的 stubbing）。
    UUID headEntryId = entries.getLast().id();
    when(path.head()).thenReturn(entries.getLast());
    when(snapshot.queuedCommands()).thenReturn(queued);
    when(snapshot.model()).thenReturn(model);
    when(snapshot.toolSiblings()).thenReturn(List.of());
    when(snapshot.thread()).thenReturn(thread);
    when(thread.id()).thenReturn(CHILD_THREAD_ID);
    when(thread.headEntryId()).thenReturn(headEntryId);
    when(thread.version()).thenReturn(3L);
    when(thread.nextCommandSequence()).thenReturn(5L);
    // 结算事务默认锁到与投影一致的事实：漂移场景由测试显式覆盖 lockedChildFacts。
    lockedChildFacts = thread;
    return snapshot;
  }

  private ThreadSnapshot parentSnapshot(Entry head) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    EntryPath path = mock(EntryPath.class);
    // 先构造子 fixture 再 stubbing：stubbing 表达式内部不能再发起新的 stubbing。
    ThreadState thread = parentThreadState();
    when(snapshot.entryPath()).thenReturn(path);
    when(path.head()).thenReturn(head);
    when(snapshot.thread()).thenReturn(thread);
    return snapshot;
  }

  /**
   * 忠实回放真实接受语义：acceptCommands 在"事务内"执行 preflight（真实 store 也是这样），因此 DELIVERED
   * 推进与结果命令入队严格同事务，preflight 内的门禁读取也作用于同一份锁内事实。
   */
  private void stubDeliveryAcceptance(UUID liveParentThreadId) {
    when(runtime.acceptCommands(any(), any()))
        .thenAnswer(
            invocation -> {
              AcceptancePreflight preflight = invocation.getArgument(1);
              ThreadState parent = mock(ThreadState.class);
              when(parent.id()).thenReturn(liveParentThreadId);
              when(parent.headEntryId()).thenReturn(PARENT_HEAD_ENTRY_ID);
              Entry head = activeHead();
              HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
              when(tx.findThread(liveParentThreadId)).thenReturn(Optional.of(parent));
              when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
              preflight.prepare(tx, mock(Session.class), List.of());
              return accepted(false);
            });
  }

  private ThreadState parentThreadState() {
    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(PARENT_THREAD_ID);
    when(thread.headEntryId()).thenReturn(PARENT_HEAD_ENTRY_ID);
    when(thread.version()).thenReturn(9L);
    when(thread.nextCommandSequence()).thenReturn(2L);
    return thread;
  }

  private Entry activeHead() {
    return entry(
        PARENT_HEAD_ENTRY_ID,
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, PARENT_THREAD_ID));
  }

  private Entry stoppedHead() {
    return entry(
        PARENT_HEAD_ENTRY_ID,
        new TurnEndPayload(
            PARENT_TURN_START_ENTRY_ID,
            TurnEndOutcome.STOPPED,
            false,
            TurnEndReason.USER_STOP,
            new UUID(0L, 41L)));
  }

  /** OPEN 记录 + 指定的软提醒阈值与 max_turns 预算（null 表示使用 policy 默认）。 */
  private static SubagentTask openTaskWithReminder(long reminderTurn, Integer maxTurns) {
    return new SubagentTask(
        INVOCATION_ID,
        PARENT_THREAD_ID,
        ROOT_THREAD_ID,
        CHILD_SESSION_ID,
        CHILD_THREAD_ID,
        BOUNDARY_ENTRY_ID,
        "assistant",
        "do the work",
        maxTurns,
        SubagentTaskStatus.OPEN,
        null,
        null,
        null,
        null,
        reminderTurn,
        null,
        CREATED_AT,
        CREATED_AT);
  }

  /** 本次执行边界之后已有 {@code turns} 个非压缩 turn 的子执行历史。 */
  private static List<Entry> runningEntriesWithTurns(int turns) {
    List<Entry> entries = new ArrayList<>();
    entries.add(entry(BOUNDARY_ENTRY_ID, new RootPayload(SETTINGS)));
    for (int i = 0; i < turns; i++) {
      entries.add(
          entry(
              new UUID(0L, 60L + i),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, CHILD_THREAD_ID)));
    }
    return entries;
  }

  /** 事务句柄：父 Thread 与 head Entry 都在（用于直接执行交付/提醒 preflight）。 */
  private HarnessStore.Transaction parentTransaction() {
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    Entry head = activeHead();
    ThreadState parent = parentThreadState();
    when(tx.findThread(PARENT_THREAD_ID)).thenReturn(Optional.of(parent));
    when(tx.findEntry(PARENT_HEAD_ENTRY_ID)).thenReturn(Optional.of(head));
    return tx;
  }

  private static UUID stoppedParentId(int index) {
    return new UUID(0L, 900L + index);
  }

  /** SETTLED 记录：内容固定，父 Thread 决定它本轮能否交付。 */
  private SubagentTask settledTask(UUID invocationId, Instant createdAt, UUID parentThreadId) {
    return new SubagentTask(
        invocationId,
        parentThreadId,
        ROOT_THREAD_ID,
        CHILD_SESSION_ID,
        SubagentTaskRunner.derive(invocationId, "kk-studio/harness/subagent/thread/"),
        BOUNDARY_ENTRY_ID,
        "assistant",
        "do the work",
        1,
        SubagentTaskStatus.SETTLED,
        Outcome.COMPLETED,
        "done",
        null,
        null,
        0L,
        createdAt.plusMillis(1),
        createdAt,
        createdAt);
  }

  /** 按 (created_at, invocation_id) keyset 分页的最小模型，用于验证扫描端的轮转公平性。 */
  private static List<SubagentTask> page(
      List<SubagentTask> rows, Instant afterCreatedAt, UUID afterInvocationId, int limit) {
    Comparator<SubagentTask> order =
        Comparator.comparing(SubagentTask::createdAt)
            .thenComparing(SubagentTask::invocationId, UuidOrder.COMPARATOR);
    return rows.stream()
        .filter(row -> afterCursor(row, afterCreatedAt, afterInvocationId))
        .sorted(order)
        .limit(limit)
        .toList();
  }

  private static boolean afterCursor(
      SubagentTask row, Instant afterCreatedAt, UUID afterInvocationId) {
    if (afterCreatedAt == null) {
      return true;
    }
    int byTime = row.createdAt().compareTo(afterCreatedAt);
    return byTime > 0
        || (byTime == 0 && UuidOrder.COMPARATOR.compare(row.invocationId(), afterInvocationId) > 0);
  }

  private CustomMessageCommandPayload deliveryPayload() {
    return new CustomMessageCommandPayload(AgentMessage.user("result"));
  }

  private CustomMessageCommandPayload reminderPayload() {
    return new CustomMessageCommandPayload(
        SystemReminder.message(SubagentPrompts.maxTurnsReminder()));
  }

  private List<ThreadCommand> queuedCommand() {
    return List.of(mock(ThreadCommand.class));
  }

  private static NewThreadCommand newThreadCommand(ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static AcceptedCommands accepted(boolean replayed) {
    AcceptedCommands accepted = mock(AcceptedCommands.class);
    when(accepted.replayed()).thenReturn(replayed);
    return accepted;
  }

  private static String text(AgentMessage message) {
    StringBuilder text = new StringBuilder();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent value) {
        text.append(value.text());
      }
    }
    return text.toString();
  }
}
