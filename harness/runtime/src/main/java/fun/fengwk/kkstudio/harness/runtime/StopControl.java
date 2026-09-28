package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
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
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 特性本地的同步 Stop transaction。
 *
 * <p>这并非 Store use-case 方法，也不是通用 workflow。它承担 Stop 所需的唯一一次原子分支收尾： 按被停止 Turn 的 ownerThreadId 做
 * Session 级精确 replay、Command 取消、Model/Tool 收敛、Entry append、Thread version 一次递增 以及最终的 Work fencing。
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
  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

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

  static UUID deriveChildStopRequestId(UUID parentStopRequestId, UUID childThreadId) {
    Objects.requireNonNull(parentStopRequestId, "parentStopRequestId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    return UUID.nameUUIDFromBytes(
        (parentStopRequestId + ":" + childThreadId).getBytes(StandardCharsets.UTF_8));
  }

  private record ThreadStopCandidate(ThreadState thread, UUID stopRequestId, boolean isTarget) {}

  private record ThreadStopContext(
      ThreadState thread,
      UUID stopRequestId,
      boolean isTarget,
      EntryPath path,
      List<ThreadCommand> queued,
      ModelInvocation model,
      List<ToolInvocation> toolSiblings,
      ThreadContext context) {}

  private Commit stop(HarnessStore.Transaction tx, StopCommand command) {
    // 树锁必须在任何业务行锁之前获取
    ThreadTreeLocks.lockForThread(tx, command.threadId());

    ThreadState targetThread =
        tx.findThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));

    // 递归收集目标线程及其所有永久后代线程，并派生确定的子 stopRequestId
    List<ThreadStopCandidate> candidates = new ArrayList<>();
    candidates.add(new ThreadStopCandidate(targetThread, command.stopRequestId(), true));
    Map<UUID, UUID> stopRequestIds = new HashMap<>();
    stopRequestIds.put(targetThread.id(), command.stopRequestId());

    List<UUID> frontier = new ArrayList<>();
    frontier.add(targetThread.id());
    Set<UUID> visited = new HashSet<>();
    visited.add(targetThread.id());

    while (!frontier.isEmpty()) {
      UUID parentId = frontier.remove(0);
      UUID parentStopId = stopRequestIds.get(parentId);
      List<ThreadState> children = tx.listChildren(parentId);
      for (ThreadState child : children) {
        if (visited.add(child.id())) {
          UUID childStopId = deriveChildStopRequestId(parentStopId, child.id());
          stopRequestIds.put(child.id(), childStopId);
          candidates.add(new ThreadStopCandidate(child, childStopId, false));
          frontier.add(child.id());
        }
      }
    }

    // 停止子树之外的祖先全部纳入锁序：settle 阶段可能需要向上收敛其递归状态（WAITING_CHILDREN -> IDLE），
    // 因此必须与停止集合一起按规范锁序提前加锁，不能在已锁 Work 之后再偷锁父级。
    List<UUID> ancestorIds = new ArrayList<>();
    List<UUID> ancestorSessionIds = new ArrayList<>();
    for (UUID ancestorId : tx.findAncestorChain(targetThread.id())) {
      if (visited.contains(ancestorId)) {
        continue;
      }
      ThreadState ancestor = tx.findThread(ancestorId).orElse(null);
      if (ancestor != null) {
        ancestorIds.add(ancestor.id());
        ancestorSessionIds.add(ancestor.sessionId());
      }
    }

    // 规范锁序：Sessions (KEY SHARE) -> Threads (FOR UPDATE, UuidOrder) -> Commands -> Models -> Tools
    // -> Work
    List<UUID> sessionIdsList = new ArrayList<>();
    for (ThreadStopCandidate c : candidates) {
      sessionIdsList.add(c.thread().sessionId());
    }
    sessionIdsList.addAll(ancestorSessionIds);
    List<UUID> sessionIds =
        sessionIdsList.stream().distinct().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sessionIds) {
      tx.lockSessionForKeyShare(sessionId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "session " + sessionId + " disappeared while thread existed"));
    }

    List<UUID> threadIdsToLock = new ArrayList<>();
    for (ThreadStopCandidate c : candidates) {
      threadIdsToLock.add(c.thread().id());
    }
    threadIdsToLock.addAll(ancestorIds);
    List<UUID> sortedThreadIds =
        threadIdsToLock.stream().distinct().sorted(UuidOrder.COMPARATOR).toList();
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID threadId : sortedThreadIds) {
      ThreadState locked =
          tx.lockThread(threadId)
              .orElseThrow(
                  () ->
                      new HarnessRuntimeNotFoundException(
                          "thread " + threadId + " does not exist"));
      lockedThreads.put(threadId, locked);
    }

    ThreadState lockedTarget = lockedThreads.get(command.threadId());
    if (!targetThread.sessionId().equals(lockedTarget.sessionId())) {
      throw new IllegalStateException(
          "thread " + lockedTarget.id() + " relocated to another session while stopping");
    }

    StopResult targetReplay =
        findReplay(tx, lockedTarget.sessionId(), command.stopRequestId(), lockedTarget);
    if (targetReplay != null) {
      return new Commit(targetReplay, List.of(), List.of());
    }

    if (lockedTarget.version() != command.expectedVersion()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_VERSION,
          "thread "
              + lockedTarget.id()
              + " version "
              + lockedTarget.version()
              + " does not match expected "
              + command.expectedVersion());
    }

    // 筛选待停止候选（过滤已在 child stopRequestId 下停止的子线程重放）
    List<ThreadStopCandidate> toStop = new ArrayList<>();
    for (ThreadStopCandidate candidate : candidates) {
      ThreadState locked = lockedThreads.get(candidate.thread().id());
      if (candidate.isTarget()) {
        toStop.add(new ThreadStopCandidate(locked, candidate.stopRequestId(), true));
      } else {
        StopResult childReplay =
            findReplay(tx, locked.sessionId(), candidate.stopRequestId(), locked);
        if (childReplay == null) {
          toStop.add(new ThreadStopCandidate(locked, candidate.stopRequestId(), false));
        }
      }
    }

    // Stage 4: Commands 锁（loadQueuedCommands）
    Map<UUID, List<ThreadCommand>> queuedByThread = new HashMap<>();
    Map<UUID, EntryPath> pathsByThread = new HashMap<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = tx.loadEntryPath(candidate.thread().headEntryId());
      pathsByThread.put(candidate.thread().id(), path);
      List<ThreadCommand> queued = tx.loadQueuedCommands(candidate.thread().id());
      queuedByThread.put(candidate.thread().id(), queued);
    }

    // Stage 5: Models 锁
    Map<UUID, ModelInvocation> modelsByThread = new HashMap<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      Optional<Entry> openTurn = path.openTurnStart();
      if (openTurn.isPresent()) {
        Optional<ModelInvocation> found =
            tx.findModelInvocationByTurn(candidate.thread().id(), openTurn.get().id());
        if (found.isPresent()) {
          ModelInvocation model =
              tx.lockModelInvocation(found.get().id())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "model "
                                  + found.get().id()
                                  + " could not be locked for thread "
                                  + candidate.thread().id()));
          modelsByThread.put(candidate.thread().id(), model);
        }
      }
    }

    // Stage 6: Tools 锁与 ThreadContext 分类
    List<ThreadStopContext> contexts = new ArrayList<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      List<ThreadCommand> queued = queuedByThread.get(candidate.thread().id());
      ModelInvocation model = modelsByThread.get(candidate.thread().id());
      List<ToolInvocation> siblings = List.of();
      if (model != null
          && model.resultEntryId() != null
          && model.resultEntryId().equals(candidate.thread().headEntryId())
          && path.head().payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        siblings = tx.lockToolInvocationsByAssistantEntryId(path.head().id());
      }
      ThreadContext context = CLASSIFIER.classify(candidate.thread(), path, model, siblings);
      if (context instanceof ThreadContext.ModelTerminalPending
          || context instanceof ThreadContext.ToolTerminalPending) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.TERMINAL_APPLY_PENDING,
            "thread "
                + candidate.thread().id()
                + " has a terminal invocation waiting to be applied");
      }
      contexts.add(
          new ThreadStopContext(
              candidate.thread(),
              candidate.stopRequestId(),
              candidate.isTarget(),
              path,
              queued,
              model,
              siblings,
              context));
    }

    // Stage 7: Work 锁与立即删除
    List<WorkTarget> allWorkTargets = new ArrayList<>();
    for (ThreadStopContext ctx : contexts) {
      allWorkTargets.addAll(workTargets(ctx.thread(), ctx.context()));
    }
    allWorkTargets.sort(WORK_TARGET_ORDER);
    for (WorkTarget target : allWorkTargets) {
      tx.lockWork(target);
    }
    for (WorkTarget target : allWorkTargets) {
      tx.deleteWork(target);
    }

    // 收敛阶段：取消 Commands，写 Entry 屏障，推进 Thread head，删除 Model/Tool 行
    StopResult targetResult = null;
    Set<UUID> modelExecutionIds = new LinkedHashSet<>();
    Set<UUID> toolExecutionIds = new LinkedHashSet<>();
    List<ThreadState> stoppedThreads = new ArrayList<>();
    Instant lastNow = null;

    List<ThreadStopContext> bottomUpContexts = contexts.reversed();
    for (ThreadStopContext ctx : bottomUpContexts) {
      Instant now =
          effectiveNow(clock.instant(), ctx.thread(), ctx.path(), ctx.queued(), ctx.context());
      lastNow = now;
      List<ThreadCommand> cancelledCommands =
          ctx.queued().stream()
              .map(commandToCancel -> commandToCancel.cancel(ctx.stopRequestId(), now))
              .toList();
      if (!cancelledCommands.isEmpty()) {
        tx.updateCommands(cancelledCommands);
      }
      List<CancelledUserMessage> cancelledUserMessages = cancelledUserMessages(cancelledCommands);
      int cancelledCommandCount = cancelledCommands.size();

      if (ctx.context() instanceof ThreadContext.ModelActive active) {
        modelExecutionIds.add(active.model().id());
      } else if (ctx.context() instanceof ThreadContext.ToolActive active) {
        for (ToolInvocation sibling : active.siblings()) {
          if (!sibling.status().isTerminal()) {
            toolExecutionIds.add(sibling.id());
          }
        }
      }

      int activeChildren = tx.countActiveChildren(ctx.thread().id());
      ThreadLifecycleStatus nextStatus =
          activeChildren > 0 ? ThreadLifecycleStatus.WAITING_CHILDREN : ThreadLifecycleStatus.IDLE;

      StopResult result =
          switch (ctx.context()) {
            case ThreadContext.IdleOrHistorical ignored -> stopIdle(
                tx,
                ctx.thread(),
                ctx.path(),
                ctx.stopRequestId(),
                cancelledCommandCount,
                cancelledUserMessages,
                nextStatus,
                now);
            case ThreadContext.ContinuationDue ignored -> stopContinuation(
                tx,
                ctx.thread(),
                ctx.path(),
                ctx.stopRequestId(),
                cancelledCommandCount,
                cancelledUserMessages,
                nextStatus,
                now);
            case ThreadContext.ModelActive active -> stopModel(
                tx,
                ctx.thread(),
                ctx.path(),
                active.model(),
                ctx.stopRequestId(),
                cancelledCommandCount,
                cancelledUserMessages,
                nextStatus,
                now);
            case ThreadContext.ToolActive active -> stopTools(
                tx,
                ctx.thread(),
                ctx.path(),
                active,
                ctx.stopRequestId(),
                cancelledCommandCount,
                cancelledUserMessages,
                nextStatus,
                now);
            case ThreadContext.ModelTerminalPending ignored -> throw new IllegalStateException(
                "terminal Model context escaped the Stop guard");
            case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
                "terminal Tool context escaped the Stop guard");
          };

      stoppedThreads.add(result.thread());
      if (ctx.isTarget()) {
        targetResult = result;
      }
    }

    if (targetResult == null) {
      throw new IllegalStateException("target thread result missing after Stop convergence");
    }

    Instant settleNow = lastNow != null ? lastNow : clock.instant();
    List<ThreadState> settledThreads =
        ThreadLifecycleCoordinator.settleStoppedTree(tx, stoppedThreads, settleNow);
    for (ThreadState settled : settledThreads) {
      if (settled.id().equals(command.threadId())) {
        targetResult =
            new StopResult(
                targetResult.replayed(),
                settled,
                targetResult.stoppedTurnEndEntryId(),
                targetResult.cancelledCommandCount(),
                targetResult.cancelledUserMessages());
        break;
      }
    }

    return new Commit(targetResult, List.copyOf(modelExecutionIds), List.copyOf(toolExecutionIds));
  }

  /**
   * 在 Thread 锁内做 Stop 的 durable receipt 查找，replay 先于 version CAS：
   *
   * <ol>
   *   <li>live receipt：Session 级查找 closeRequestId 被引用 TURN_START 的 ownerThreadId == 本 Thread 的
   *       TURN_END；raw id 归另一 Thread 所有时忽略而非冲突。
   *   <li>queued-only receipt：本 Thread 上带该 stop_request_id 的已取消 Command（未创建 Turn 时的幂等键）。
   * </ol>
   *
   * 命中任一 receipt 时，一并汇总本 Thread 上带同一 stopRequestId 的已取消 Command，保证 live Stop 首次同时取消 queued commands
   * 后，transport 丢失的 replay 返回一致的 cancelledCommandCount 与 sequence-ordered cancelledUserMessages
   * （不返回 0 / 空）。任一命中都返回 replayed 结果且不写任何 marker。
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
    // queued-only receipt：未写停止边界的先前 Stop（open Turn 属其它 Thread）以 (threadId, stop_request_id) 作幂等键。
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
    Instant now = HarnessStoreTime.notBefore(clockNow, thread.updatedAt(), path.head().createdAt());
    for (ThreadCommand command : queued) {
      now = HarnessStoreTime.notBefore(now, command.createdAt());
    }
    if (context instanceof ThreadContext.ModelActive active) {
      now = HarnessStoreTime.notBefore(now, active.model().updatedAt());
    } else if (context instanceof ThreadContext.ToolActive active) {
      now = HarnessStoreTime.notBefore(now, active.model().updatedAt());
      for (ToolInvocation sibling : active.siblings()) {
        now = HarnessStoreTime.notBefore(now, sibling.updatedAt());
      }
    }
    return now;
  }

  private static int workTypeRank(WorkTargetType type) {
    return switch (type) {
      case THREAD -> 0;
      case MODEL -> 1;
      case TOOL -> 2;
    };
  }

  /**
   * 空闲（无 live Invocation）Stop：写一个完整的 STOP barrier Turn —— TURN_START({@code STOP}) →
   * ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED, closeRequestId) —— 并同事务把 head 推进到该
   * TURN_END、恰好递增一次 version。它不消费 Command、从不调度模型，也不计入模型工作轮数。
   *
   * <p>STOP Turn 与其它 Stop 同域（TURN_START.ownerThreadId + TURN_END.closeRequestId），因此 {@link
   * #findReplay} 直接复用它做幂等重放。
   *
   * <p>head 停在已关闭 TURN_END / ROOT 的 Thread（例如父 Thread 本地 turn 已结束、仍在等异步子委派）必须拿到这样的 durable
   * 停止边界：没有它，子结果会被照常交付并唤醒父。
   */
  private static StopResult stopIdle(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent() && !isOwnedBy(openTurn.get(), thread.id())) {
      // 共享历史上属于其它 Thread 的 open Turn：本 Thread 没有自己的 live 执行，也无权替换别人的 Turn，因此没有本 Thread
      // 自己的停止边界可写（所有权各自独立），只取消排队 Command。
      return touchVersionForCancelledCommands(
          tx, thread, cancelledCommandCount, cancelledUserMessages, status, now);
    }
    EntryPath base = path;
    if (openTurn.isPresent()) {
      Entry turn = openTurn.get();
      HistoryNormalization.StopClose close = HistoryNormalization.stopClose(path, turn);
      if (close != HistoryNormalization.StopClose.HISTORY_CUT) {
        // 本 Thread 的 open Turn 已不可能再被模型推进，但前缀足以按 STOPPED 合法关闭：已有完整 assistant 结果就直接复用它，
        // 尚无 assistant 结果才追加取消屏障；既不新增第二个 TURN_START，也不伪造模型完成。
        return closeOwnOpenTurnAsStopped(
            tx,
            thread,
            path,
            turn,
            close,
            stopRequestId,
            cancelledCommandCount,
            cancelledUserMessages,
            status,
            now);
      }
      // 前缀不能按 STOPPED 关闭（尚无输入的空 INPUT Turn，或工具结果缺失的 assistant 结果）：先按既有 history normalization
      // 语义收尾（必要时补写 synthetic HISTORY_CUT ToolResult，再以 CANCELLED/HISTORY_CUT 关闭），随后照常写 STOP
      // boundary Turn 承载 durable 停止事实。
      base = normalizeOpenOwnTurn(tx, path, now);
    }
    return appendStopBoundaryTurn(
        tx, thread, base, stopRequestId, cancelledCommandCount, cancelledUserMessages, status, now);
  }

  /** 本 Thread 拥有、且无 live Invocation 的 open Turn：在其内部收尾，绝不新增 TURN_START。 */
  private static StopResult closeOwnOpenTurnAsStopped(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      Entry openTurn,
      HistoryNormalization.StopClose close,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID parentId = path.head().id();
    if (close == HistoryNormalization.StopClose.APPEND_CANCEL_BARRIER) {
      parentId = insertCancelBarrier(tx, sessionId, parentId, now);
    }
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, parentId, stoppedTurnEnd(openTurn.id(), stopRequestId), now));
    ThreadState stopped = advanceHeadWithStatus(thread, turnEndId, status, now);
    tx.updateThread(stopped);
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  /** 本 Thread 的 open Turn 前缀不足以按 STOPPED 关闭：按既有 history cut语义收尾并返回收尾后的 EntryPath。 */
  private static EntryPath normalizeOpenOwnTurn(
      HarnessStore.Transaction tx, EntryPath path, Instant now) {
    List<Entry> suffix = HistoryNormalization.suffix(path, tx::nextId, now);
    for (Entry entry : suffix) {
      tx.insertEntry(entry);
    }
    return tx.loadEntryPath(suffix.getLast().id());
  }

  /**
   * STOP boundary Turn：TURN_START({@code STOP}) → ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED,
   * closeRequestId)。
   */
  private static StopResult appendStopBoundaryTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartId,
            sessionId,
            path.head().id(),
            new TurnStartPayload(TurnStartReason.STOP, path.baseSettings(), thread.id()),
            now));
    UUID barrierId = insertCancelBarrier(tx, sessionId, turnStartId, now);
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, barrierId, stoppedTurnEnd(turnStartId, stopRequestId), now));
    ThreadState stopped = advanceHeadWithStatus(thread, turnEndId, status, now);
    tx.updateThread(stopped);
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  /** 取消屏障 Entry：ASSISTANT_ERROR(CANCELLED)，不携 provider replay state。 */
  private static UUID insertCancelBarrier(
      HarnessStore.Transaction tx, UUID sessionId, UUID parentId, Instant now) {
    UUID barrierId = tx.nextId();
    tx.insertEntry(
        new Entry(
            barrierId,
            sessionId,
            parentId,
            new AssistantErrorPayload(
                new AssistantError(AssistantError.CANCELLED_CODE, CANCELLED_MESSAGE), null),
            now));
    return barrierId;
  }

  /**
   * 仅取消 Command 并推进 version：open Turn 属于其它 Thread 时本 Thread 既没有自己的 live 执行，也没有本 Thread
   * 自己的停止边界可写（Turn 与停止边界的所有权都归拥有它的 Thread），因此只取消排队 Command。
   */
  private static StopResult touchVersionForCancelledCommands(
      HarnessStore.Transaction tx,
      ThreadState thread,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    ThreadState current = thread;
    if (cancelledCommandCount > 0 || current.status() != status) {
      current =
          new ThreadState(
              thread.id(),
              thread.sessionId(),
              thread.parentThreadId(),
              thread.headEntryId(),
              thread.creationRequestHash(),
              thread.name(),
              thread.yoloEnabled(),
              status,
              thread.nextCommandSequence(),
              Math.addExact(thread.version(), 1L),
              thread.createdAt(),
              effectiveMutationTime(now, thread));
      ThreadState.validateTransition(thread, current);
      tx.updateThread(current);
    }
    return new StopResult(false, current, null, cancelledCommandCount, cancelledUserMessages);
  }

  private static boolean isOwnedBy(Entry openTurn, UUID threadId) {
    return openTurn.payload() instanceof TurnStartPayload start
        && threadId.equals(start.ownerThreadId());
  }

  private static StopResult stopContinuation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    UUID sessionId = path.root().sessionId();
    UUID turnStartId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartId, sessionId, path.head().id(), continuationBarrierStart(path, thread), now));
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
    ThreadState stopped = advanceHeadWithStatus(thread, turnEndId, status, now);
    tx.updateThread(stopped);
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  /**
   * 普通 continueModel obligation 写 CONTINUATION barrier；completed HISTORY phase gap 写精确配对的
   * TURN_PREFIX COMPACTION barrier，使 Stop receipt 与被取消的 durable obligation 同域。
   */
  private static TurnStartPayload continuationBarrierStart(EntryPath path, ThreadState thread) {
    TurnEndPayload due = (TurnEndPayload) path.head().payload();
    Entry referencedStart = null;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(due.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload) {
        referencedStart = entry;
        break;
      }
    }
    if (referencedStart == null) {
      throw new IllegalStateException(
          "continuation TURN_END references missing TURN_START " + due.turnStartEntryId());
    }
    TurnStartPayload source = (TurnStartPayload) referencedStart.payload();
    if (source.compaction() == null || source.compaction().phase() != CompactionPhase.HISTORY) {
      return new TurnStartPayload(TurnStartReason.CONTINUATION, path.baseSettings(), thread.id());
    }
    CompactionTurns.CompactionTurn history = null;
    for (CompactionTurns.CompactionTurn turn : CompactionTurns.scan(path)) {
      if (CompactionTurns.entryAt(path, turn.startIndex()).id().equals(referencedStart.id())) {
        history = turn;
        break;
      }
    }
    if (history == null || history.result() == null || history.resultIndex() < 0) {
      throw new IllegalStateException(
          "completed HISTORY continuation requires its exact compaction result");
    }
    CompactionStart frozen = source.compaction();
    CompactionStart stoppedPrefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            frozen.trigger(),
            frozen.executionModel(),
            frozen.cutEntryId(),
            frozen.turnPrefixStartEntryId(),
            CompactionTurns.entryAt(path, history.resultIndex()).id());
    return new TurnStartPayload(
        TurnStartReason.COMPACTION, path.baseSettings(), thread.id(), null, null, stoppedPrefix);
  }

  private StopResult stopModel(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      UUID stopRequestId,
      int cancelledCommandCount,
      List<CancelledUserMessage> cancelledUserMessages,
      ThreadLifecycleStatus status,
      Instant now) {
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    EntryPayload barrier = modelStopBarrier(checkpoint);
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, CANCELLED_MESSAGE);
    UUID parentId =
        ModelAttemptFailureAppender.append(
            tx,
            path.root().sessionId(),
            path.head().id(),
            model,
            ((TurnStartPayload) path.openTurnStart().orElseThrow().payload()).compaction() != null);
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
    ThreadState stopped = advanceHeadWithStatus(thread, turnEndId, status, now);
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
      ThreadLifecycleStatus status,
      Instant now) {
    // 删除 parent 前的严格物化校验：attached Assistant/result 与已物化失败 attempt 前缀必须与 immutable 事实一致。
    ModelAttemptMaterialization.validateAttached(active.model(), path);
    UUID parentId = active.assistant().id();
    for (ToolInvocation sibling : active.siblings()) {
      ToolInvocation terminal =
          switch (sibling.status()) {
            case WAITING_APPROVAL, WAITING_INPUT, READY -> sibling.cancel(
                TOOL_CANCELLED_ERROR, now);
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
    ThreadState stopped = advanceHeadWithStatus(thread, turnEndId, status, now);
    tx.updateThread(stopped);
    // children 先于 parent 删除（FK 顺序）；Work 由调用方删除。
    tx.deleteToolInvocationsByIds(active.siblings().stream().map(ToolInvocation::id).toList());
    tx.deleteModelInvocation(active.model().id());
    return new StopResult(false, stopped, turnEndId, cancelledCommandCount, cancelledUserMessages);
  }

  private static ThreadState advanceHeadWithStatus(
      ThreadState thread, UUID headEntryId, ThreadLifecycleStatus status, Instant now) {
    ThreadState next =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.parentThreadId(),
            headEntryId,
            thread.creationRequestHash(),
            thread.name(),
            thread.yoloEnabled(),
            status,
            thread.nextCommandSequence(),
            Math.addExact(thread.version(), 1L),
            thread.createdAt(),
            effectiveMutationTime(now, thread));
    ThreadState.validateTransition(thread, next);
    return next;
  }

  private static Instant effectiveMutationTime(Instant now, ThreadState thread) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(thread.updatedAt()) ? thread.updatedAt() : candidate;
  }

  /** 按 sequence 升序还原被取消的真实用户输入（USER_MESSAGE 与用户 CUSTOM_MESSAGE）；SET_* 与运行时提醒不返回。 */
  private static List<CancelledUserMessage> cancelledUserMessages(List<ThreadCommand> cancelled) {
    List<CancelledUserMessage> messages = new ArrayList<>();
    for (ThreadCommand command : cancelled) {
      AgentMessage message =
          switch (command.payload()) {
            case UserMessageCommandPayload user -> user.message();
            case CustomMessageCommandPayload custom -> SystemReminder.isReminder(custom.message())
                ? null
                : custom.message();
            default -> null;
          };
      if (message != null) {
        messages.add(
            new CancelledUserMessage(
                command.sequence(), command.idempotencyKey(), message.contents()));
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

  /** Durable Stop 结果，加上 transaction 提交后需取消的全部 process-local execution。 */
  record Commit(StopResult result, List<UUID> modelExecutionIds, List<UUID> toolExecutionIds) {

    Commit {
      result = Objects.requireNonNull(result, "result");
      modelExecutionIds =
          List.copyOf(Objects.requireNonNull(modelExecutionIds, "modelExecutionIds"));
      toolExecutionIds = List.copyOf(Objects.requireNonNull(toolExecutionIds, "toolExecutionIds"));
    }
  }
}
