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
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
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
import fun.fengwk.kkstudio.harness.runtime.processor.ModelOutcomeAppender;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
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
import java.util.function.Consumer;

/**
 * 特性本地的同步 Stop transaction。
 *
 * <p>这并非 Store use-case 方法，也不是通用 workflow。它承担 Stop 所需的唯一一次原子分支收尾：按被停止 Turn 的 ownerThreadId 做
 * Session 级精确 replay、Command 取消、Model/Tool 收敛、Entry append、Thread version 一次递增、子 Join 结算与父
 * 通知交付，以及最终的 Work fencing。
 *
 * <p>Stop 把目标 Thread 与完整父子后代一次性置为 {@link ThreadExecutionControl#STOPPED}，为每个受影响节点持久保存 {@link
 * StoppedThreadReceipt}。旧 stopRequestId 的重放直接返回持久保存的整批回执，不以当前树重算旧范围、不停止之后启动的新工作。 输入水位不因 Stop
 * 前进；各节点自己的停止边界与被取消的人类输入写入其回执。
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
  private final ModelOutcomeAppender modelOutcomeAppender;

  StopControl(
      HarnessStore store,
      Clock clock,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.toolResultHistoryMaterializer = toolResultHistoryMaterializer;
    this.modelOutcomeAppender = new ModelOutcomeAppender(toolResultHistoryMaterializer);
  }

  Commit stop(StopCommand command, Consumer<Commit> afterCommit) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(afterCommit, "afterCommit");
    return store.transaction(
        tx -> {
          Commit commit = stop(tx, command);
          // 进程内取消绑定物理 commit：无外层事务时 Stop 事务提交后触发，加入外层事务时只在外层真正提交后触发，回滚不触发。
          store.afterCommit(() -> afterCommit.accept(commit));
          return commit;
        });
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

  /** 单个节点收敛后的状态与其自身停止边界（共享历史无自有边界时为空）。 */
  private record NodeStop(ThreadState thread, UUID stoppedTurnEndEntryId) {}

  /** terminal Model 物化后的事实：推进后的 Thread，以及仍 active 的 Model 与其新物化 siblings（无则 null/空）。 */
  private record Materialized(
      ThreadState thread,
      ModelInvocation model,
      List<ToolInvocation> toolSiblings,
      UUID terminalEntryId,
      UUID finalAnswerEntryId) {
    Materialized {
      toolSiblings = List.copyOf(toolSiblings);
    }
  }

  private Commit stop(HarnessStore.Transaction tx, StopCommand command) {
    // 树锁必须在任何业务行锁之前获取
    ThreadTreeLocks.lockForThread(tx, command.threadId());

    ThreadState targetThread =
        tx.findThread(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));

    // 收集目标线程及其所有永久后代线程，并派生确定的子 stopRequestId
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

    // 祖先链纳入锁序：子 Join 结算需要向仍在运行的祖先投递完成通知（推进其 sequence 并请求 Work）。
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

    // durable receipt replay 先于 version CAS：按 (rootThreadId, rootStopRequestId) 返回持久保存的旧范围，不以当前树重算，
    // 也不停止其后启动的新工作。
    List<StoppedThreadReceipt> stored =
        tx.loadStopReceiptsByRootRequest(command.threadId(), command.stopRequestId());
    if (!stored.isEmpty()) {
      return new Commit(new StopResult(true, lockedTarget, stored), List.of(), List.of());
    }
    // 单节点回执命中但没有该 root 对：该派生 stopRequestId 已被其它 root 使用，派生身份不得伪装成新的 root。
    if (tx.findStopReceipt(command.threadId(), command.stopRequestId()).isPresent()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STOP_REQUEST_ID_REUSED,
          "stopRequestId "
              + command.stopRequestId()
              + " on thread "
              + command.threadId()
              + " is already used by another stop root");
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

    // 首次 Stop：任何节点的身份都不能已被使用——若某节点的 (threadId, stopRequestId) 已存在（来自其它 root 或非 Stop
    // 关闭操作），必须明确冲突，绝不静默跳过部分集合。
    List<ThreadStopCandidate> toStop = new ArrayList<>(candidates.size());
    for (ThreadStopCandidate candidate : candidates) {
      ThreadState locked = lockedThreads.get(candidate.thread().id());
      if (tx.findStopReceipt(locked.id(), candidate.stopRequestId()).isPresent()) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.STOP_REQUEST_ID_REUSED,
            "stop identity ("
                + locked.id()
                + ", "
                + candidate.stopRequestId()
                + ") is already used by another stop root");
      }
      toStop.add(new ThreadStopCandidate(locked, candidate.stopRequestId(), candidate.isTarget()));
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

    // Stage 6: Tools 锁与 ThreadContext 分类（只读：不在遍历中 mutation；terminal 结果物化与收敛统一留到
    // Stage 8 全锁完成后再做）
    List<ThreadStopContext> contexts = new ArrayList<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      List<ThreadCommand> queued = queuedByThread.get(candidate.thread().id());
      ModelInvocation model = modelsByThread.get(candidate.thread().id());
      List<ToolInvocation> siblings = loadAssistantSiblings(tx, candidate.thread(), path, model);
      ThreadContext context = CLASSIFIER.classify(candidate.thread(), path, model, siblings);
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

    // Stage 7: Work 锁与立即删除（覆盖 TerminalPending 节点仍持有的 MODEL/TOOL Work，先于任何物化删除）。
    List<WorkTarget> allWorkTargets = new ArrayList<>();
    for (ThreadStopContext ctx : contexts) {
      allWorkTargets.add(new WorkTarget(WorkTargetType.THREAD, ctx.thread().id()));
      if (ctx.model() != null) {
        allWorkTargets.add(new WorkTarget(WorkTargetType.MODEL, ctx.model().id()));
      }
      for (ToolInvocation sibling : ctx.toolSiblings()) {
        allWorkTargets.add(new WorkTarget(WorkTargetType.TOOL, sibling.id()));
      }
    }
    allWorkTargets.sort(WORK_TARGET_ORDER);
    for (WorkTarget target : allWorkTargets) {
      tx.lockWork(target);
    }
    for (WorkTarget target : allWorkTargets) {
      tx.deleteWork(target);
    }

    // Stage 8: 自底向上收敛。每节点重新读取最新 ThreadState 并按最新事实分类（子节点收敛不改动父节点自身
    // Turn，但重新读取可保证只基于 tx 内最新状态 mutation）；必要时先物化已提交的 terminal Model / Tool batch
    // 结果，再取消 Commands、写自有边界、置 STOPPED、固化排队通知。所需行锁均已在 Stage 4-7 取得，遍历中不再新增锁。
    Set<UUID> modelExecutionIds = new LinkedHashSet<>();
    Set<UUID> toolExecutionIds = new LinkedHashSet<>();
    List<StoppedThreadReceipt> receipts = new ArrayList<>();
    List<ThreadState> stoppedStates = new ArrayList<>();
    List<Materialized> committedTerminals = new ArrayList<>();
    Instant lastNow = null;

    for (ThreadStopContext original : contexts.reversed()) {
      // 不在此阶段新增 Commands/Models/Tools 行锁（Stage 4-7 已按规范顺序取得，WORK 之后禁止再取），
      // 直接复用已锁定的 model / siblings；Thread 状态重新读取以保证基于 tx 内最新事实 mutation。
      ThreadState thread =
          tx.findThread(original.thread().id())
              .orElseThrow(
                  () ->
                      new HarnessRuntimeNotFoundException(
                          "thread " + original.thread().id() + " disappeared during Stop"));
      UUID stopRequestId = original.stopRequestId();
      EntryPath path = tx.loadEntryPath(thread.headEntryId());
      List<ThreadCommand> queued = original.queued();
      ModelInvocation model = original.model();
      List<ToolInvocation> siblings = original.toolSiblings();
      ThreadContext context = CLASSIFIER.classify(thread, path, model, siblings);
      if (context instanceof ThreadContext.ModelTerminalPending pending) {
        // 提交先于 Stop：用共享 appender 把已提交的 terminal Model 结果物化进历史（绝不 409），再正常 Stop。
        Materialized materialized = materializeTerminalModel(tx, thread, path, pending.model());
        if (materialized.terminalEntryId() != null) {
          committedTerminals.add(materialized);
        }
        thread = materialized.thread();
        path = tx.loadEntryPath(thread.headEntryId());
        model = materialized.model();
        siblings = materialized.toolSiblings();
        context = CLASSIFIER.classify(thread, path, model, siblings);
      } else if (context instanceof ThreadContext.ToolTerminalPending pending) {
        thread =
            materializeTerminalToolBatch(tx, thread, path, pending.model(), pending.siblings());
        path = tx.loadEntryPath(thread.headEntryId());
        model = null;
        siblings = List.of();
        context = CLASSIFIER.classify(thread, path, model, siblings);
      }
      if (context instanceof ThreadContext.ModelTerminalPending
          || context instanceof ThreadContext.ToolTerminalPending) {
        throw new IllegalStateException(
            "terminal invocation still pending after materialization for thread " + thread.id());
      }
      Instant now = effectiveNow(clock.instant(), thread, path, queued, context);
      lastNow = now;
      // 只取消人类/可信任务输入与设置；系统 NOTIFICATION 不取消，稍后物化进历史。
      List<ThreadCommand> cancelledCommands =
          queued.stream()
              .filter(commandToCancel -> !commandToCancel.type().isNotification())
              .map(commandToCancel -> commandToCancel.cancel(stopRequestId, now))
              .toList();
      if (!cancelledCommands.isEmpty()) {
        tx.updateCommands(cancelledCommands);
      }
      List<CancelledThreadInput> cancelledInputs = cancelledInputs(cancelledCommands);
      List<ThreadCommand> queuedNotifications =
          queued.stream().filter(queuedCommand -> queuedCommand.type().isNotification()).toList();

      if (context instanceof ThreadContext.ModelActive active) {
        modelExecutionIds.add(active.model().id());
      } else if (context instanceof ThreadContext.ToolActive active) {
        for (ToolInvocation sibling : active.siblings()) {
          if (!sibling.status().isTerminal()) {
            toolExecutionIds.add(sibling.id());
          }
        }
      }

      NodeStop node =
          switch (context) {
            case ThreadContext.IdleOrHistorical ignored -> stopIdle(
                tx, thread, path, stopRequestId, now);
            case ThreadContext.ContinuationDue ignored -> stopContinuation(
                tx, thread, path, stopRequestId, now);
            case ThreadContext.ModelActive active -> stopModel(
                tx, thread, path, active.model(), stopRequestId, now);
            case ThreadContext.ToolActive active -> stopTools(
                tx, thread, path, active, stopRequestId, now);
            case ThreadContext.ModelTerminalPending ignored -> throw new IllegalStateException(
                "terminal Model context escaped the Stop guard");
            case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
                "terminal Tool context escaped the Stop guard");
          };
      ThreadState stoppedThread =
          materializeQueuedNotifications(tx, node.thread(), queuedNotifications, now);
      stoppedStates.add(stoppedThread);
      receipts.add(
          new StoppedThreadReceipt(
              stoppedThread.id(),
              stopRequestId,
              node.stoppedTurnEndEntryId(),
              cancelledCommands.size(),
              cancelledInputs));
    }

    if (receipts.stream().noneMatch(r -> r.threadId().equals(command.threadId()))) {
      throw new IllegalStateException("target thread receipt missing after Stop convergence");
    }

    // Join 结算与父通知：所有节点已 STOPPED；父节点若在本集合内则通知直接固化到历史，否则投递为父命令并唤醒。
    Instant settleNow = lastNow != null ? lastNow : clock.instant();
    // 已提交的最终结果先结算；延迟交付到全部节点关闭之后，避免父节点接到通知后又被本次 Stop 取消。
    for (Materialized terminal : committedTerminals) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx,
          terminal.thread(),
          terminal.terminalEntryId(),
          terminal.finalAnswerEntryId(),
          HarnessStoreTime.notBefore(settleNow, terminal.thread().updatedAt()),
          false);
    }
    for (ThreadState stopped : stoppedStates) {
      UUID boundary = null;
      for (StoppedThreadReceipt receipt : receipts) {
        if (receipt.threadId().equals(stopped.id())) {
          boundary = receipt.stoppedTurnEndEntryId();
          break;
        }
      }
      if (boundary == null) {
        // 共享历史中属于其它 Thread 的 open Turn：本节点没有自有停止边界可用于冻结 Join 结果。
        continue;
      }
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, stopped, boundary, null, settleNow, true);
    }

    tx.insertStopReceipts(command.threadId(), command.stopRequestId(), receipts);

    receipts.sort(Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR));
    ThreadState target = tx.findThread(command.threadId()).orElse(lockedTarget);
    return new Commit(
        new StopResult(false, target, receipts),
        List.copyOf(modelExecutionIds),
        List.copyOf(toolExecutionIds));
  }

  /**
   * 在 Thread 锁内做 Stop 的 durable receipt 查找已由 {@link HarnessStore.Transaction#findStopReceipt} 覆盖；
   * replay 返回持久保存的整批回执，不触碰 version、不写 marker。
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
   * TURN_END、恰好递增一次 version、把执行控制置为 STOPPED。它不消费 Command、从不调度模型，也不计入模型工作轮数。
   */
  private static NodeStop stopIdle(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
      Instant now) {
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isPresent() && !isOwnedBy(openTurn.get(), thread.id())) {
      // 共享历史上属于其它 Thread 的 open Turn：本 Thread 没有自己的 live 执行，也无权替换别人的 Turn，因此没有本 Thread
      // 自己的停止边界可写（所有权各自独立），只取消排队 Command 并置 STOPPED。
      return new NodeStop(touchVersionAsStopped(tx, thread, now), null);
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
      base = normalizeOpenOwnTurn(tx, path, now);
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
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, parentId, stoppedTurnEnd(openTurn.id(), stopRequestId), now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 本 Thread 的 open Turn 前缀不足以按 STOPPED 关闭：按既有 history cut 语义收尾并返回收尾后的 EntryPath。 */
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
  private static NodeStop appendStopBoundaryTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
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
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    return new NodeStop(stopped, turnEndId);
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
   * open Turn 属于其它 Thread 时本 Thread 既没有自己的 live 执行，也没有本 Thread 自己的停止边界可写（Turn 与停止边界的所有权都归 拥有它的
   * Thread），因此只把执行控制置为 STOPPED。
   */
  private static ThreadState touchVersionAsStopped(
      HarnessStore.Transaction tx, ThreadState thread, Instant now) {
    if (thread.executionControl().isStopped()) {
      return thread;
    }
    ThreadState stopped = advanceStopped(thread, thread.headEntryId(), now);
    tx.updateThread(stopped);
    return stopped;
  }

  private static boolean isOwnedBy(Entry openTurn, UUID threadId) {
    return openTurn.payload() instanceof TurnStartPayload start
        && threadId.equals(start.ownerThreadId());
  }

  private static NodeStop stopContinuation(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      UUID stopRequestId,
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
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /**
   * 普通 continueModel obligation 写 CONTINUATION barrier；completed HISTORY phase gap 写精确配对的
   * TURN_PREFIX COMPACTION barrier，使 Stop receipt 与被取消的 durable obligation 同域。
   */
  private static TurnStartPayload continuationBarrierStart(EntryPath path, ThreadState thread) {
    TurnEndPayload due = (TurnEndPayload) path.headIgnoringTrailingNotifications().payload();
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

  private NodeStop stopModel(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      UUID stopRequestId,
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
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    // 严格校验通过后同事务删除 ModelInvocation（closed turn 不保留 Invocation；Work 由调用方删除）。
    tx.deleteModelInvocation(model.id());
    return new NodeStop(stopped, turnEndId);
  }

  private NodeStop stopTools(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ThreadContext.ToolActive active,
      UUID stopRequestId,
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
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    // children 先于 parent 删除（FK 顺序）；Work 由调用方删除。
    tx.deleteToolInvocationsByIds(active.siblings().stream().map(ToolInvocation::id).toList());
    tx.deleteModelInvocation(active.model().id());
    return new NodeStop(stopped, turnEndId);
  }

  /** 把 Thread 置为 STOPPED 并推进 head；输入水位、nextCommandSequence 不变，version 严格 +1。 */
  private static ThreadState advanceStopped(ThreadState thread, UUID headEntryId, Instant now) {
    ThreadState next =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.parentThreadId(),
            headEntryId,
            thread.creationRequestHash(),
            thread.name(),
            thread.yoloPolicy(),
            ThreadExecutionControl.STOPPED,
            thread.inputThroughSequence(),
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

  /** 按 sequence 升序还原被取消的人工输入（USER_MESSAGE 与 GOAL）；CUSTOM_MESSAGE、NOTIFICATION 与 SET_* 不退草稿。 */
  private static List<CancelledThreadInput> cancelledInputs(List<ThreadCommand> cancelled) {
    List<CancelledThreadInput> inputs = new ArrayList<>();
    for (ThreadCommand command : cancelled) {
      if (command.payload() instanceof UserMessageCommandPayload
          || command.payload() instanceof GoalCommandPayload) {
        inputs.add(
            new CancelledThreadInput(
                command.sequence(), command.idempotencyKey(), command.payload()));
      }
    }
    return List.copyOf(inputs);
  }

  /**
   * 停止节点时保留已排队的系统通知：按 sequence 升序物化为历史 {@link NotificationPayload} Entry，并把命令标记为已应用（应用坐标指向 自身
   * Entry）。输入水位不变、也不唤醒模型；STOPPED 节点上的通知只作为历史输入，待显式新输入恢复后的下一个 INPUT 接纳。返回推进 head 后的 STOPPED Thread。
   */
  private static ThreadState materializeQueuedNotifications(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<ThreadCommand> notifications,
      Instant now) {
    if (notifications.isEmpty()) {
      return thread;
    }
    UUID head = thread.headEntryId();
    List<ThreadCommand> applied = new ArrayList<>(notifications.size());
    for (ThreadCommand command : notifications) {
      NotificationCommandPayload payload = (NotificationCommandPayload) command.payload();
      UUID entryId = tx.nextId();
      tx.insertEntry(
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
    }
    tx.updateCommands(applied);
    ThreadState advanced = thread.advanceHead(head, now);
    tx.updateThread(advanced);
    return advanced;
  }

  /**
   * 提交先于 Stop：把已提交的 terminal ModelInvocation 结果交给共享 {@link ModelOutcomeAppender} 物化进历史（ASSISTANT 结果
   * + TURN_END，或 tool batch 的 siblings），关闭 turn 时删除 Model 行，并在终止边界结算已应用 Join。返回推进 head 后的
   * Thread；不产生任何 wake / compaction 计划 （Stop 随后自行收敛该节点）。
   */
  private Materialized materializeTerminalModel(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path, ModelInvocation model) {
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            clock.instant(), thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    TurnStartPayload turnStart =
        (TurnStartPayload)
            path.openTurnStart()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "terminal model must belong to an open TURN_START"))
                .payload();
    ModelOutcomeAppender.Applied applied =
        turnStart.compaction() != null
            ? modelOutcomeAppender.appendCompaction(
                tx, thread, path, model, turnStart.compaction(), mutationNow)
            : modelOutcomeAppender.appendModel(tx, path, model, mutationNow);
    ThreadState advanced = thread.advanceHead(applied.headEntryId(), mutationNow);
    tx.updateThread(advanced);
    // ToolBatch：Model 行被 attach 保留、新 siblings 进入 active Tool phase；其余结果已关闭 turn 且删除 Model 行。
    return applied.toolPhase()
        ? new Materialized(
            advanced,
            model.attachResultEntry(applied.headEntryId(), mutationNow),
            applied.toolInvocations(),
            null,
            null)
        : new Materialized(
            advanced,
            null,
            List.of(),
            applied.terminal() ? applied.headEntryId() : null,
            applied.finalAnswerEntryId());
  }

  /**
   * 全部 terminal 的 Tool sibling batch 物化：交给共享 {@link ModelOutcomeAppender} 按 callIndex 追加 ToolResult
   * 与 COMPLETED TURN_END 并删除行，再推进 head。
   */
  private ThreadState materializeTerminalToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ModelInvocation model,
      List<ToolInvocation> siblings) {
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            clock.instant(), thread.updatedAt(), path.head().createdAt(), model.updatedAt());
    for (ToolInvocation sibling : siblings) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, sibling.updatedAt());
    }
    ModelOutcomeAppender.Applied applied =
        modelOutcomeAppender.appendToolBatch(tx, path, model, siblings, mutationNow);
    ThreadState advanced = thread.advanceHead(applied.headEntryId(), mutationNow);
    tx.updateThread(advanced);
    return advanced;
  }

  /** Model 结果恰为当前 ASSISTANT head 时锁定其 Tool siblings；否则返回空。 */
  private static List<ToolInvocation> loadAssistantSiblings(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path, ModelInvocation model) {
    if (model == null
        || model.resultEntryId() == null
        || !model.resultEntryId().equals(thread.headEntryId())
        || !(path.head().payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      return List.of();
    }
    return tx.lockToolInvocationsByAssistantEntryId(path.head().id());
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
