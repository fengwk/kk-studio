package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.driver.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stop 的 durable 写入：把已锁定的单个节点收敛为一个闭合 Turn，并物化排队中的系统通知。
 *
 * <p>这里只构造 Entry / Command / Tool 行；加锁、状态分类、Join 结算与回执由 {@link StopControl} 编排。所有方法都假定调用方已按 {@code
 * HarnessStore} 的规范锁序持有 Commands / Driver / Tool / Work 行锁，且不得在此新增行锁。
 */
final class StopTurnWrites {

  private static final String CANCELLED_MESSAGE = "Cancelled by user";
  private static final ToolInvocationError TOOL_CANCELLED_ERROR =
      new ToolInvocationError(
          "USER_STOP", "Stopped by user; no further execution or deliverable result is available");
  private static final ToolInvocationError TOOL_UNKNOWN_ERROR =
      new ToolInvocationError(
          "USER_STOP_UNCERTAIN", "Stopped by user while the tool execution outcome was uncertain");

  private StopTurnWrites() {}

  /** 单个节点收敛后的状态与其自身停止边界（共享历史无自有边界时为空）。 */
  record NodeStop(ThreadState thread, UUID stoppedTurnEndEntryId) {}

  record MaterializedNotifications(UUID headEntryId, long inputThroughSequence) {}

  /**
   * 空闲（无 live Invocation）Stop：写一个完整的 STOP barrier Turn —— TURN_START({@code STOP}) →
   * ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED, closeRequestId) —— 并同事务把 head 推进到该
   * TURN_END、恰好递增一次 version、把执行控制置为 STOPPED。共享 open Turn 仅在有排队通知时在本分支做 history cut， 并在 STOP turn
   * 前物化通知；它不消费人工输入、从不调度模型，也不计入模型工作轮数。
   */
  static NodeStop stopIdle(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      List<ThreadCommand> queuedNotifications,
      UUID stopRequestId,
      Instant now) {
    Optional<Entry> openTurn = path.openTurnStart();
    if (isForeignOpenTurn(path, thread.id())) {
      if (queuedNotifications.isEmpty()) {
        return new NodeStop(touchVersionAsStopped(tx, thread, now), null);
      }
      TurnStartPayload start = (TurnStartPayload) openTurn.orElseThrow().payload();
      if (start.reason() == TurnStartReason.STOP) {
        // 公开 fork 入口只接受闭合边界；不为异常共享 STOP 前缀伪造原 owner 的关闭身份。
        throw new IllegalStateException(
            "cannot materialize queued notifications after a foreign open STOP turn");
      }
      // 仅在当前分支补 history cut，不改变原 owner 的 head 或执行状态；通知放在切断后的回合之间。
      EntryPath normalized = normalizeOpenTurn(tx, path, now);
      MaterializedNotifications notifications =
          appendQueuedNotifications(tx, thread, normalized.head().id(), queuedNotifications, now);
      return appendStopBoundaryTurn(
          tx,
          thread,
          tx.entries().loadPath(notifications.headEntryId()),
          stopRequestId,
          now,
          notifications.inputThroughSequence());
    }
    EntryPath base = path;
    if (openTurn.isPresent()) {
      Entry turn = openTurn.get();
      HistoryNormalization.StopClose close = HistoryNormalization.stopClose(path, turn);
      if (close != HistoryNormalization.StopClose.HISTORY_CUT) {
        // 本 Thread 的 open Turn 已不可能再被模型推进，但前缀足以按 STOPPED 合法关闭：已有完整 assistant 结果就直接复用它，
        // 尚无 assistant 结果才追加取消屏障；既不新增第二个 TURN_START，也不伪造模型完成。
        return closeOwnOpenTurnAsStopped(tx, thread, path, turn, close, stopRequestId, now);
      }
      // 前缀不能按 STOPPED 关闭（尚无输入的空 INPUT Turn，或工具结果缺失的 assistant 结果）：先按既有 history normalization
      // 语义收尾（必要时补写 synthetic HISTORY_CUT ToolResult，再以 CANCELLED/HISTORY_CUT 关闭），随后照常写 STOP
      // boundary Turn 承载 durable 停止事实。
      base = normalizeOpenTurn(tx, path, now);
    }
    return appendStopBoundaryTurn(tx, thread, base, stopRequestId, now);
  }

  /** 本 Thread 拥有、且无 live Invocation 的 open Turn：在其内部收尾，绝不新增 TURN_START。 */
  private static NodeStop closeOwnOpenTurnAsStopped(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      Entry openTurn,
      HistoryNormalization.StopClose close,
      UUID stopRequestId,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID parentId = path.head().id();
    if (close == HistoryNormalization.StopClose.APPEND_CANCEL_BARRIER) {
      parentId = insertCancelBarrier(tx, sessionId, parentId, now);
    }
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId, sessionId, parentId, stoppedTurnEnd(openTurn.id(), stopRequestId), now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.threads().update(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 按既有 history cut 语义收尾当前分支的 open Turn，并返回收尾后的 EntryPath。 */
  private static EntryPath normalizeOpenTurn(
      HarnessStore.Transaction tx, EntryPath path, Instant now) {
    List<Entry> suffix = HistoryNormalization.suffix(path, tx::nextId, now);
    for (Entry entry : suffix) {
      tx.entries().insert(entry);
    }
    return tx.entries().loadPath(suffix.getLast().id());
  }

  /**
   * STOP boundary Turn：TURN_START({@code STOP}) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED,
   * closeRequestId)。
   */
  private static NodeStop appendStopBoundaryTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      Instant now) {
    return appendStopBoundaryTurn(
        tx, thread, path, stopRequestId, now, thread.inputThroughSequence());
  }

  private static NodeStop appendStopBoundaryTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      Instant now,
      long inputThroughSequence) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnStartId,
                sessionId,
                path.head().id(),
                new TurnStartPayload(TurnStartReason.STOP, path.baseSettings(), thread.id()),
                now));
    UUID barrierId = insertCancelBarrier(tx, sessionId, turnStartId, now);
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId, sessionId, barrierId, stoppedTurnEnd(turnStartId, stopRequestId), now));
    ThreadState stopped = advanceStopped(thread, turnEndId, inputThroughSequence, now);
    tx.threads().update(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 取消屏障 Entry：ASSISTANT_ERROR(CANCELLED)，不携 provider replay state。 */
  private static UUID insertCancelBarrier(
      HarnessStore.Transaction tx, UUID sessionId, UUID parentId, Instant now) {
    UUID barrierId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                barrierId,
                sessionId,
                parentId,
                new AssistantErrorPayload(
                    new AssistantError(AssistantError.CANCELLED_CODE, CANCELLED_MESSAGE), null),
                now));
    return barrierId;
  }

  static boolean isOwnedBy(Entry openTurn, UUID threadId) {
    return openTurn.payload() instanceof TurnStartPayload start
        && threadId.equals(start.ownerThreadId());
  }

  private static ThreadState touchVersionAsStopped(
      HarnessStore.Transaction tx, ThreadState thread, Instant now) {
    if (thread.executionControl().isStopped()) {
      return thread;
    }
    ThreadState stopped = advanceStopped(thread, thread.headEntryId(), now);
    tx.threads().update(stopped);
    return stopped;
  }

  static boolean isForeignOpenTurn(EntryPath path, UUID threadId) {
    return path.openTurnStart().filter(turn -> !isOwnedBy(turn, threadId)).isPresent();
  }

  /** pending 模型续作的 durable 取消屏障：CONTINUATION Turn 内以 CANCELLED 关闭。 */
  static NodeStop stopContinuation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnStartId,
                sessionId,
                path.head().id(),
                continuationBarrierStart(thread, path),
                now));
    UUID barrierId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                barrierId,
                sessionId,
                turnStartId,
                new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE), null),
                now));
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId, sessionId, barrierId, stoppedTurnEnd(turnStartId, stopRequestId), now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.threads().update(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** Creates the durable cancellation barrier for a pending model continuation. */
  private static TurnStartPayload continuationBarrierStart(ThreadState thread, EntryPath path) {
    return new TurnStartPayload(TurnStartReason.CONTINUATION, path.baseSettings(), thread.id());
  }

  /** 活动 generation 的停止边界：由 Driver 提供 barrier，缺失时只允许复用已有 assistant 结果。 */
  static NodeStop stopGeneration(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      DriverStopResult driverStop,
      UUID stopRequestId,
      Instant now) {
    Entry turn =
        path.openTurnStart()
            .orElseThrow(() -> new IllegalStateException("model stop requires an open TURN_START"));
    UUID barrierId = driverStop.barrierEntryId();
    if (barrierId == null) {
      if (!isOwnedBy(turn, thread.id())
          || HistoryNormalization.stopClose(path, turn)
              != HistoryNormalization.StopClose.REUSE_ASSISTANT_RESULT) {
        throw new IllegalStateException(
            "active driver generation must provide a stop barrier for thread " + thread.id());
      }
      barrierId = path.head().id();
    }
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                path.root().sessionId(),
                barrierId,
                stoppedTurnEnd(turn.id(), stopRequestId),
                now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.threads().update(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 活动 Tool batch 的结果收敛：每个 sibling 落成终态 ToolResult，并删除 child 行。 */
  static UUID settleTools(
      HarnessStore.Transaction tx, EntryPath path, ThreadContext.ToolActive active, Instant now) {
    UUID parentId = active.assistant().id();
    for (ToolInvocation sibling : active.siblings()) {
      ToolInvocation terminal =
          switch (sibling.status()) {
            case WAITING_APPROVAL, WAITING_INPUT, WAITING_CHILD, READY -> sibling.cancel(
                TOOL_CANCELLED_ERROR, now);
            case RUNNING -> sibling.call().toolName().equals("task")
                ? sibling.cancel(TOOL_CANCELLED_ERROR, now)
                : sibling.unknown(TOOL_UNKNOWN_ERROR, now);
            case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> sibling;
          };
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(tx, path.root().sessionId(), parentId, terminal, now);
      parentId = applied.headEntryId();
    }
    tx.tools().deleteByIds(active.siblings().stream().map(ToolInvocation::id).toList());
    return parentId;
  }

  static NodeStop stopTools(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID parentId,
      UUID stopRequestId,
      Instant now) {
    UUID turnStartEntryId =
        path.openTurnStart()
            .orElseThrow(() -> new IllegalStateException("tool stop requires an open TURN_START"))
            .id();
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                path.root().sessionId(),
                parentId,
                stoppedTurnEnd(turnStartEntryId, stopRequestId),
                now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.threads().update(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 把 Thread 置为 STOPPED 并推进 head；输入水位、nextCommandSequence 不变，version 严格 +1。 */
  private static ThreadState advanceStopped(ThreadState thread, UUID headEntryId, Instant now) {
    return advanceStopped(thread, headEntryId, thread.inputThroughSequence(), now);
  }

  private static ThreadState advanceStopped(
      ThreadState thread, UUID headEntryId, long inputThroughSequence, Instant now) {
    ThreadState next =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.parentThreadId(),
            headEntryId,
            thread.creationRequestHash(),
            thread.name(),
            thread.rootThreadId(),
            thread.yoloEnabled(),
            thread.kind(),
            thread.constraints(),
            ThreadExecutionControl.STOPPED,
            inputThroughSequence,
            thread.nextCommandSequence(),
            Math.addExact(thread.version(), 1L),
            thread.createdAt(),
            now);
    ThreadState.validateTransition(thread, next);
    return next;
  }

  /**
   * 停止节点时保留已排队的系统通知：按 sequence 升序物化为历史 {@link NotificationPayload} Entry，并把命令标记为已应用（应用坐标指向 自身
   * Entry），输入水位同步覆盖这些已物化通知；不唤醒模型。STOPPED 节点上的通知只作为历史输入，待显式新输入恢复后的下一个 INPUT 接纳。返回推进 head 后的 STOPPED
   * Thread。
   */
  static ThreadState materializeQueuedNotifications(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<ThreadCommand> notifications,
      Instant now) {
    if (notifications.isEmpty()) {
      return thread;
    }
    MaterializedNotifications materialized =
        appendQueuedNotifications(tx, thread, thread.headEntryId(), notifications, now);
    ThreadState advanced =
        thread.advanceHeadAndInputThroughSequence(
            materialized.headEntryId(), materialized.inputThroughSequence(), now);
    tx.threads().update(advanced);
    return advanced;
  }

  private static MaterializedNotifications appendQueuedNotifications(
      HarnessStore.Transaction tx,
      ThreadState thread,
      UUID head,
      List<ThreadCommand> notifications,
      Instant now) {
    long inputThroughSequence = thread.inputThroughSequence();
    List<ThreadCommand> applied = new ArrayList<>(notifications.size());
    for (ThreadCommand command : notifications) {
      NotificationCommandPayload payload = (NotificationCommandPayload) command.payload();
      UUID entryId = tx.nextId();
      tx.entries()
          .insert(
              new Entry(
                  entryId,
                  thread.sessionId(),
                  head,
                  new NotificationPayload(
                      payload.notificationId(),
                      payload.kind(),
                      payload.sourceThreadId(),
                      payload.message()),
                  now));
      applied.add(command.markApplied(entryId));
      head = entryId;
      inputThroughSequence = Math.max(inputThroughSequence, command.sequence());
    }
    if (!applied.isEmpty()) {
      tx.commands().update(applied);
    }
    return new MaterializedNotifications(head, inputThroughSequence);
  }

  private static TurnEndPayload stoppedTurnEnd(UUID turnStartEntryId, UUID stopRequestId) {
    return new TurnEndPayload(
        turnStartEntryId, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, stopRequestId);
  }
}
