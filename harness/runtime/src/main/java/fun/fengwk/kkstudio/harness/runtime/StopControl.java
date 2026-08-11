package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 特性本地的同步 Stop transaction。
 *
 * <p>这并非 Store use-case 方法，也不是通用 workflow。它承担 Stop 所需的唯一一次原子分支收尾： thread-scoped 精确 replay、Command
 * 取消、Model/Tool 收敛、Entry append、Thread revision 一次递增 以及最终的 Work fencing。
 */
final class StopControl {

  private static final String CANCELLED_MESSAGE = "Cancelled by user";
  private static final ToolInvocationError TOOL_CANCELLED_ERROR =
      new ToolInvocationError(
          "USER_STOP", "Stopped by user; no further execution or deliverable result is available");
  private static final ToolInvocationError TOOL_UNKNOWN_ERROR =
      new ToolInvocationError(
          "USER_STOP_UNCERTAIN", "Stopped by user while the tool execution outcome was uncertain");
  private static final Comparator<WorkTarget> WORK_TARGET_ORDER =
      Comparator.comparingInt((WorkTarget target) -> workTypeRank(target.type()))
          .thenComparing(WorkTarget::id, UuidOrder.COMPARATOR);

  private final HarnessStore store;
  private final Clock clock;
  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();

  StopControl(HarnessStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
  }

  Commit stop(StopCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(tx -> stop(tx, command));
  }

  private Commit stop(HarnessStore.Transaction tx, StopCommand command) {
    ThreadState thread =
        tx.lockThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    StopResult replay = findReplay(path, command.stopRequestId(), thread);
    if (replay != null) {
      return new Commit(replay, null, List.of());
    }
    if (thread.revision() != command.expectedRevision()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_REVISION,
          "thread "
              + thread.id()
              + " revision "
              + thread.revision()
              + " does not match expected "
              + command.expectedRevision());
    }

    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    LockedThreadContext locked = ThreadContextLock.load(tx, thread, path);
    ThreadContext context = locked.context();
    if (context instanceof ThreadContext.ModelTerminalPending
        || context instanceof ThreadContext.ToolTerminalPending) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.TERMINAL_APPLY_PENDING,
          "thread " + thread.id() + " has a terminal invocation waiting to be applied");
    }

    List<WorkTarget> workTargets = workTargets(thread, context);
    for (WorkTarget target : workTargets) {
      tx.lockWork(target);
    }
    Instant now = effectiveNow(clock.instant(), thread, path, queued, context);

    List<ThreadCommand> cancelledCommands =
        queued.stream().map(commandToCancel -> commandToCancel.cancel(now)).toList();
    if (!cancelledCommands.isEmpty()) {
      tx.updateCommands(cancelledCommands);
    }
    UUID modelExecutionId =
        context instanceof ThreadContext.ModelActive active ? active.model().id() : null;
    List<UUID> toolExecutionIds =
        context instanceof ThreadContext.ToolActive active
            ? active.siblings().stream()
                .filter(sibling -> !sibling.status().isTerminal())
                .map(ToolInvocation::id)
                .toList()
            : List.of();

    StopResult result =
        switch (context) {
          case ThreadContext.IdleOrHistorical ignored -> stopIdle(
              tx, thread, cancelledCommands.size(), now);
          case ThreadContext.ContinuationDue ignored -> stopContinuation(
              tx, thread, path, command.stopRequestId(), cancelledCommands.size(), now);
          case ThreadContext.ModelActive active -> stopModel(
              tx,
              thread,
              path,
              active.model(),
              command.stopRequestId(),
              cancelledCommands.size(),
              now);
          case ThreadContext.ToolActive active -> stopTools(
              tx, thread, path, active, command.stopRequestId(), cancelledCommands.size(), now);
          case ThreadContext.ModelTerminalPending ignored -> throw new IllegalStateException(
              "terminal Model context escaped the Stop guard");
          case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
              "terminal Tool context escaped the Stop guard");
        };
    for (WorkTarget target : workTargets) {
      tx.deleteWork(target);
    }
    return new Commit(result, modelExecutionId, toolExecutionIds);
  }

  private StopResult findReplay(EntryPath path, UUID stopRequestId, ThreadState thread) {
    Entry match = null;
    TurnEndPayload matchedEnd = null;
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof TurnEndPayload end
          && stopRequestId.equals(end.closeRequestId())) {
        if (match != null) {
          throw new IllegalStateException(
              "stop request id " + stopRequestId + " appears more than once on the current path");
        }
        match = entry;
        matchedEnd = end;
      }
    }
    if (match == null) {
      return null;
    }
    if (matchedEnd.outcome() != TurnEndOutcome.STOPPED
        || matchedEnd.reason() != TurnEndReason.USER_STOP) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STOP_REQUEST_ID_REUSED,
          "stop request id " + stopRequestId + " was used by another close operation");
    }
    return new StopResult(StopResult.Status.REPLAYED, thread, match.id(), 0);
  }

  private static List<WorkTarget> workTargets(ThreadState thread, ThreadContext context) {
    List<WorkTarget> targets = new ArrayList<>();
    targets.add(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (context instanceof ThreadContext.ModelActive active) {
      targets.add(new WorkTarget(WorkTargetType.MODEL, active.model().id()));
    } else if (context instanceof ThreadContext.ToolActive active) {
      targets.add(new WorkTarget(WorkTargetType.MODEL, active.model().id()));
      for (ToolInvocation sibling : active.siblings()) {
        targets.add(new WorkTarget(WorkTargetType.TOOL, sibling.id()));
      }
    }
    targets.sort(WORK_TARGET_ORDER);
    return List.copyOf(targets);
  }

  /**
   * 为每次 Stop mutation 提供一个不回退的时间戳。
   *
   * <p>在所有锁之后读取本地 Clock 可避免 lock-wait 引发的过期；而对已锁定的 durable fact 取 max 又能容忍跨节点时钟偏差与 wall-clock 回滚。
   */
  private static Instant effectiveNow(
      Instant clockNow,
      ThreadState thread,
      EntryPath path,
      List<ThreadCommand> queued,
      ThreadContext context) {
    Instant now = Objects.requireNonNull(clockNow, "clockNow");
    now = max(now, thread.updatedAt());
    now = max(now, path.head().createdAt());
    for (ThreadCommand command : queued) {
      now = max(now, command.createdAt());
    }
    if (context instanceof ThreadContext.ModelActive active) {
      now = max(now, active.model().updatedAt());
    } else if (context instanceof ThreadContext.ToolActive active) {
      now = max(now, active.model().updatedAt());
      for (ToolInvocation sibling : active.siblings()) {
        now = max(now, sibling.updatedAt());
      }
    }
    return now;
  }

  private static Instant max(Instant left, Instant right) {
    return right.isAfter(left) ? right : left;
  }

  private static int workTypeRank(WorkTargetType type) {
    return switch (type) {
      case THREAD -> 0;
      case MODEL -> 1;
      case TOOL -> 2;
    };
  }

  private static StopResult stopIdle(
      HarnessStore.Transaction tx, ThreadState thread, int cancelledCommandCount, Instant now) {
    ThreadState current = thread;
    if (cancelledCommandCount > 0) {
      current = thread.touchRevision(now);
      tx.updateThread(current);
    }
    return new StopResult(StopResult.Status.IDLE, current, null, cancelledCommandCount);
  }

  private static StopResult stopContinuation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      int cancelledCommandCount,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartId,
            sessionId,
            path.head().id(),
            new TurnStartPayload(TurnStartReason.CONTINUATION, path.baseSettings()),
            now));
    UUID barrierId = tx.nextId();
    tx.insertEntry(
        new Entry(
            barrierId,
            sessionId,
            turnStartId,
            new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE)),
            now));
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, barrierId, stoppedTurnEnd(turnStartId, stopRequestId), now));
    ThreadState stopped = thread.advanceHead(turnEndId, thread.yoloEnabled(), now);
    tx.updateThread(stopped);
    return new StopResult(StopResult.Status.STOPPED, stopped, turnEndId, cancelledCommandCount);
  }

  private StopResult stopModel(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      UUID stopRequestId,
      int cancelledCommandCount,
      Instant now) {
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    EntryPayload barrier = modelStopBarrier(checkpoint);
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, CANCELLED_MESSAGE);
    UUID barrierId = tx.nextId();
    tx.insertEntry(new Entry(barrierId, path.root().sessionId(), path.head().id(), barrier, now));
    ModelInvocation cancelled = model.cancel(error, now).attachResultEntry(barrierId, now);
    tx.updateModelInvocation(cancelled);
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            barrierId,
            stoppedTurnEnd(model.turnStartEntryId(), stopRequestId),
            now));
    ThreadState stopped = thread.advanceHead(turnEndId, thread.yoloEnabled(), now);
    tx.updateThread(stopped);
    return new StopResult(StopResult.Status.STOPPED, stopped, turnEndId, cancelledCommandCount);
  }

  private StopResult stopTools(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ThreadContext.ToolActive active,
      UUID stopRequestId,
      int cancelledCommandCount,
      Instant now) {
    List<ToolInvocation> updated = new ArrayList<>(active.siblings().size());
    UUID parentId = active.assistant().id();
    for (ToolInvocation sibling : active.siblings()) {
      ToolInvocation terminal =
          switch (sibling.status()) {
            case WAITING_APPROVAL, READY -> sibling.cancel(TOOL_CANCELLED_ERROR, now);
            case DISPATCHING, RUNNING -> sibling.unknown(TOOL_UNKNOWN_ERROR, now);
            case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> sibling;
          };
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(tx, path.root().sessionId(), parentId, terminal, now);
      updated.add(applied.invocation());
      parentId = applied.headEntryId();
    }
    tx.updateToolInvocations(updated);
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            parentId,
            stoppedTurnEnd(active.model().turnStartEntryId(), stopRequestId),
            now));
    ThreadState stopped = thread.advanceHead(turnEndId, thread.yoloEnabled(), now);
    tx.updateThread(stopped);
    return new StopResult(StopResult.Status.STOPPED, stopped, turnEndId, cancelledCommandCount);
  }

  private static EntryPayload modelStopBarrier(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE));
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (checkpoint.thinking() != null && !checkpoint.thinking().isBlank()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (checkpoint.text() != null && !checkpoint.text().isBlank()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    if (contents.isEmpty()) {
      return new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE));
    }
    return new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  private static TurnEndPayload stoppedTurnEnd(UUID turnStartEntryId, UUID stopRequestId) {
    return new TurnEndPayload(
        turnStartEntryId, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, stopRequestId);
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }

  /** Durable Stop 结果，加上 transaction 提交后需取消的 process-local execution。 */
  record Commit(StopResult result, UUID modelExecutionId, List<UUID> toolExecutionIds) {

    Commit {
      result = Objects.requireNonNull(result, "result");
      toolExecutionIds = List.copyOf(Objects.requireNonNull(toolExecutionIds, "toolExecutionIds"));
      if (modelExecutionId != null && !toolExecutionIds.isEmpty()) {
        throw new IllegalArgumentException("a Stop commit cannot cancel Model and Tool executions");
      }
    }
  }
}
