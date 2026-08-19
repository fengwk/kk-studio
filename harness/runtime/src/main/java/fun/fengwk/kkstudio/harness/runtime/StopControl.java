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
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelAttemptFailureAppender;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 特性本地的同步 Stop transaction。
 *
 * <p>这并非 Store use-case 方法，也不是通用 workflow。它承担 Stop 所需的唯一一次原子分支收尾： 按被停止 Turn 的 ownerThreadId 做
 * Session 级精确 replay、Command 取消、Model/Tool 收敛、Entry append、Thread revision 一次递增 以及最终的 Work fencing。
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
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final HistoryPayloadMapper payloadMapper = new HistoryPayloadMapper();

  StopControl(
      HarnessStore store,
      Clock clock,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.toolResultHistoryMaterializer = toolResultHistoryMaterializer;
  }

  Commit stop(StopCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(tx -> stop(tx, command));
  }

  private Commit stop(HarnessStore.Transaction tx, StopCommand command) {
    // 规范锁序：immutable 快照只用于定位 Session，随后 KEY SHARE Session -> FOR UPDATE Thread 复核。
    ThreadState immutable =
        tx.findThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));
    Session session =
        tx.lockSessionForKeyShare(immutable.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + immutable.sessionId() + " disappeared while thread existed"));
    ThreadState thread =
        tx.lockThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));
    if (!session.id().equals(thread.sessionId())) {
      throw new IllegalStateException(
          "thread " + thread.id() + " relocated to another session while stopping");
    }
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    StopResult replay = findReplay(tx, thread.sessionId(), command.stopRequestId(), thread);
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
    // Work rows 必须在 Invocation 行删除之前清掉：deleteWork 通过所属 Model/Tool 行校验 owner，而收敛步骤
    // （stopModel/stopTools）会物理删除 Model/Tool 行。整个 Stop 是单事务，提前删除 Work 不改变原子性。
    for (WorkTarget target : workTargets) {
      tx.deleteWork(target);
    }
    Instant now = effectiveNow(clock.instant(), thread, path, queued, context);

    List<ThreadCommand> cancelledCommands =
        queued.stream()
            .map(commandToCancel -> commandToCancel.cancel(command.stopRequestId(), now))
            .toList();
    if (!cancelledCommands.isEmpty()) {
      tx.updateCommands(cancelledCommands);
    }
    List<CancelledUserMessage> cancelledUserMessages = cancelledUserMessages(cancelledCommands);
    int cancelledCommandCount = cancelledCommands.size();
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
              tx, thread, cancelledCommandCount, cancelledUserMessages, now);
          case ThreadContext.ContinuationDue ignored -> stopContinuation(
              tx,
              thread,
              path,
              command.stopRequestId(),
              cancelledCommandCount,
              cancelledUserMessages,
              now);
          case ThreadContext.ModelActive active -> stopModel(
              tx,
              thread,
              path,
              active.model(),
              command.stopRequestId(),
              cancelledCommandCount,
              cancelledUserMessages,
              now);
          case ThreadContext.ToolActive active -> stopTools(
              tx,
              thread,
              path,
              active,
              command.stopRequestId(),
              cancelledCommandCount,
              cancelledUserMessages,
              now);
          case ThreadContext.ModelTerminalPending ignored -> throw new IllegalStateException(
              "terminal Model context escaped the Stop guard");
          case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
              "terminal Tool context escaped the Stop guard");
        };
    return new Commit(result, modelExecutionId, toolExecutionIds);
  }

  /**
   * 在 Thread 锁内做 Stop 的 durable receipt 查找，replay 先于 revision CAS：
   *
   * <ol>
   *   <li>live receipt：Session 级查找 closeRequestId 被引用 TURN_START 的 ownerThreadId == 本 Thread 的
   *       TURN_END；raw id 归另一 Thread 所有时忽略而非冲突。
   *   <li>queued-only receipt：本 Thread 上带该 cancelRequestId 的已取消 Command（未创建 Turn 时的幂等键）。
   * </ol>
   *
   * 命中任一 receipt 时，一并汇总本 Thread 上带同一 cancelRequestId 的已取消 Command，保证 live Stop 首次同时取消 queued
   * commands 后，transport 丢失的 replay 返回一致的 cancelledCommandCount 与 sequence-ordered
   * cancelledUserMessages （不返回 0 / 空）。任一命中都返回 replayed 结果且不写任何 marker。
   */
  private StopResult findReplay(
      HarnessStore.Transaction tx, UUID sessionId, UUID stopRequestId, ThreadState thread) {
    List<Entry> sessionEntries = tx.loadEntriesBySessionId(sessionId);
    Map<UUID, TurnStartPayload> turnStarts = new HashMap<>();
    for (Entry entry : sessionEntries) {
      if (entry.payload() instanceof TurnStartPayload start) {
        turnStarts.put(entry.id(), start);
      }
    }
    Entry match = null;
    TurnEndPayload matchedEnd = null;
    for (Entry entry : sessionEntries) {
      if (!(entry.payload() instanceof TurnEndPayload end)
          || !stopRequestId.equals(end.closeRequestId())) {
        continue;
      }
      TurnStartPayload start = requiredTurnStart(turnStarts, end);
      if (!thread.id().equals(start.ownerThreadId())) {
        // 另一 Thread 拥有的 raw id：忽略而非冲突。
        continue;
      }
      if (match != null) {
        throw new IllegalStateException(
            "stop request id "
                + stopRequestId
                + " appears more than once for thread "
                + thread.id());
      }
      match = entry;
      matchedEnd = end;
    }
    if (match != null) {
      if (matchedEnd.outcome() != TurnEndOutcome.STOPPED
          || matchedEnd.reason() != TurnEndReason.USER_STOP) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.STOP_REQUEST_ID_REUSED,
            "stop request id "
                + stopRequestId
                + " was used by another close operation of thread "
                + thread.id());
      }
      return cancelledReceipt(tx, thread, stopRequestId, match.id());
    }
    // queued-only receipt：未创建 Turn 的先前 Stop 以 (threadId, cancelRequestId) 作幂等键。
    List<ThreadCommand> cancelledWithRequest =
        tx.loadCancelledCommandsByRequest(thread.id(), stopRequestId);
    if (!cancelledWithRequest.isEmpty()) {
      return new StopResult(
          true,
          thread,
          null,
          cancelledWithRequest.size(),
          cancelledUserMessages(cancelledWithRequest));
    }
    return null;
  }

  /** live receipt（TURN_END）也汇总同 stopRequestId 的 queued-cancelled receipt，使 replay 字段与首次接受一致。 */
  private static StopResult cancelledReceipt(
      HarnessStore.Transaction tx,
      ThreadState thread,
      UUID stopRequestId,
      UUID stoppedTurnEndEntryId) {
    List<ThreadCommand> cancelledWithRequest =
        tx.loadCancelledCommandsByRequest(thread.id(), stopRequestId);
    return new StopResult(
        true,
        thread,
        stoppedTurnEndEntryId,
        cancelledWithRequest.size(),
        cancelledUserMessages(cancelledWithRequest));
  }

  /** TURN_END 引用的 TURN_START 必须存在于同一 Session；解析失败是 durable 结构不变量破坏。 */
  private static TurnStartPayload requiredTurnStart(
      Map<UUID, TurnStartPayload> turnStarts, TurnEndPayload end) {
    TurnStartPayload start = turnStarts.get(end.turnStartEntryId());
    if (start == null) {
      throw new IllegalStateException(
          "TURN_END with turnStartEntryId "
              + end.turnStartEntryId()
              + " references a missing TURN_START entry");
    }
    return start;
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
      HarnessStore.Transaction tx,
      ThreadState thread,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      Instant now) {
    ThreadState current = thread;
    if (cancelledCommandCount > 0) {
      current = thread.touchRevision(now);
      tx.updateThread(current);
    }
    return new StopResult(false, current, null, cancelledCommandCount, cancelledUserMessages);
  }

  private static StopResult stopContinuation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartId,
            sessionId,
            path.head().id(),
            new TurnStartPayload(
                TurnStartReason.CONTINUATION, path.baseSettings(), thread.id(), null),
            now));
    UUID barrierId = tx.nextId();
    tx.insertEntry(
        new Entry(
            barrierId,
            sessionId,
            turnStartId,
            new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE), null),
            now));
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, barrierId, stoppedTurnEnd(turnStartId, stopRequestId), now));
    ThreadState stopped = thread.advanceHead(turnEndId, now);
    tx.updateThread(stopped);
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  private StopResult stopModel(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      Instant now) {
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    EntryPayload barrier = modelStopBarrier(checkpoint);
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, CANCELLED_MESSAGE);
    UUID parentId =
        ModelAttemptFailureAppender.append(tx, path.root().sessionId(), path.head().id(), model);
    UUID barrierId = tx.nextId();
    tx.insertEntry(new Entry(barrierId, path.root().sessionId(), parentId, barrier, now));
    // attach 转换触发与 ModelAttemptMaterialization 等价的严格校验（failed attempts 与 terminal 结果逐条比对）。
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
    ThreadState stopped = thread.advanceHead(turnEndId, now);
    tx.updateThread(stopped);
    // 严格校验通过后同事务删除 ModelInvocation（closed turn 不保留 Invocation；Work 由调用方删除）。
    tx.deleteModelInvocation(model.id());
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  private StopResult stopTools(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ThreadContext.ToolActive active,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      Instant now) {
    // 删除 parent 前的严格物化校验：attached Assistant/result 与已物化失败 attempt 前缀必须与 immutable 事实一致。
    ModelAttemptMaterialization.validateAttached(active.model(), path);
    UUID parentId = active.assistant().id();
    for (ToolInvocation sibling : active.siblings()) {
      ToolInvocation terminal =
          switch (sibling.status()) {
            case WAITING_APPROVAL, READY -> sibling.cancel(TOOL_CANCELLED_ERROR, now);
            case DISPATCHING, RUNNING -> sibling.unknown(TOOL_UNKNOWN_ERROR, now);
            case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> sibling;
          };
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(
              tx, path.root().sessionId(), parentId, terminal, now, toolResultHistoryMaterializer);
      parentId = applied.headEntryId();
    }
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            parentId,
            stoppedTurnEnd(active.model().turnStartEntryId(), stopRequestId),
            now));
    ThreadState stopped = thread.advanceHead(turnEndId, now);
    tx.updateThread(stopped);
    // children 先于 parent 删除（FK 顺序）；Work 由调用方删除。
    tx.deleteToolInvocationsByIds(active.siblings().stream().map(ToolInvocation::id).toList());
    tx.deleteModelInvocation(active.model().id());
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  /**
   * 按 sequence 升序还原被取消的 user-like 消息内容（USER_MESSAGE 与 USER role 的 CUSTOM_MESSAGE）；SET_* 与 SYSTEM
   * steering 不返回。
   */
  private static List<CancelledUserMessage> cancelledUserMessages(List<ThreadCommand> cancelled) {
    List<CancelledUserMessage> messages = new ArrayList<>();
    for (ThreadCommand command : cancelled) {
      AgentMessage message =
          switch (command.payload()) {
            case UserMessageCommandPayload user -> user.message();
            case CustomMessageCommandPayload custom -> custom.message().role()
                    == AgentMessageRole.USER
                ? custom.message()
                : null;
            default -> null;
          };
      if (message != null) {
        messages.add(
            new CancelledUserMessage(
                command.sequence(), command.clientCommandId(), message.contents()));
      }
    }
    return List.copyOf(messages);
  }

  private static EntryPayload modelStopBarrier(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE), null);
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    if (contents.isEmpty()) {
      return new AssistantErrorPayload(new AssistantError("CANCELLED", CANCELLED_MESSAGE), null);
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
