package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryNormalization;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
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
 * StoppedThreadReceipt}。旧 stopRequestId 的重放直接返回持久保存的整批回执，不以当前树重算旧范围、不停止之后启动的新工作。被取消的人类输入写入回执；
 * 已排队的系统通知物化为历史并同步推进输入水位。
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
  private final AgentDriver driver;

  StopControl(HarnessStore store, AgentDriver driver) {
    this.store = Objects.requireNonNull(store, "store");
    this.driver = Objects.requireNonNull(driver, "driver");
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
      LockedDriverState driverState,
      List<ToolInvocation> toolSiblings,
      ThreadContext context) {}

  /** 单个节点收敛后的状态与其自身停止边界（共享历史无自有边界时为空）。 */
  private record NodeStop(ThreadState thread, UUID stoppedTurnEndEntryId) {}

  private record MaterializedNotifications(UUID headEntryId, long inputThroughSequence) {}

  /** terminal Driver generation 物化后的事实：推进后的 Thread 与可结算的终止边界。 */
  private record Materialized(ThreadState thread, UUID terminalEntryId, UUID finalAnswerEntryId) {}

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

    // 子树内所有其它节点的直接父都在停止集合内；子 Join 只向直接父交付，因此外部只补目标的一个直接父。
    List<UUID> parentThreadIds = new ArrayList<>();
    List<UUID> parentSessionIds = new ArrayList<>();
    UUID parentThreadId = targetThread.parentThreadId();
    if (parentThreadId != null) {
      ThreadState parent = tx.findThread(parentThreadId).orElse(null);
      if (parent != null) {
        parentThreadIds.add(parent.id());
        parentSessionIds.add(parent.sessionId());
      }
    }

    // 规范锁序：Sessions (KEY SHARE) -> Threads (FOR UPDATE, UuidOrder) -> Commands -> Models -> Tools
    // -> Work
    List<UUID> sessionIdsList = new ArrayList<>();
    for (ThreadStopCandidate c : candidates) {
      sessionIdsList.add(c.thread().sessionId());
    }
    sessionIdsList.addAll(parentSessionIds);
    List<UUID> sessionIds =
        sessionIdsList.stream().distinct().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sessionIds) {
      tx.lockSessionForKeyShare(sessionId).orElseThrow();
    }

    List<UUID> threadIdsToLock = new ArrayList<>();
    for (ThreadStopCandidate c : candidates) {
      threadIdsToLock.add(c.thread().id());
    }
    threadIdsToLock.addAll(parentThreadIds);
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

    // Stage 5: Driver 状态锁
    Map<UUID, LockedDriverState> driverStatesByThread = new HashMap<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      driverStatesByThread.put(
          candidate.thread().id(), driver.lockState(tx, candidate.thread(), path));
    }

    // Stage 6: Tools 锁与 ThreadContext 分类（只读：不在遍历中 mutation；terminal 结果物化与收敛统一留到
    // Stage 8 全锁完成后再做）
    List<ThreadStopContext> contexts = new ArrayList<>();
    for (ThreadStopCandidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      List<ThreadCommand> queued = queuedByThread.get(candidate.thread().id());
      LockedDriverState driverState = driverStatesByThread.get(candidate.thread().id());
      List<ToolInvocation> siblings =
          loadAssistantSiblings(tx, candidate.thread(), path, driverState);
      ThreadContext context = CLASSIFIER.classify(candidate.thread(), path, driverState, siblings);
      contexts.add(
          new ThreadStopContext(
              candidate.thread(),
              candidate.stopRequestId(),
              candidate.isTarget(),
              path,
              queued,
              driverState,
              siblings,
              context));
    }

    // Stage 7: Work 锁与立即删除（覆盖 TerminalPending 节点仍持有的 MODEL/TOOL Work，先于任何物化删除）。
    List<WorkTarget> allWorkTargets = new ArrayList<>();
    for (ThreadStopContext ctx : contexts) {
      allWorkTargets.add(new WorkTarget(WorkTargetType.THREAD, ctx.thread().id()));
      if (ctx.driverState().executionId() != null) {
        allWorkTargets.add(new WorkTarget(WorkTargetType.MODEL, ctx.driverState().executionId()));
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
    Instant now = tx.now();

    for (ThreadStopContext original : contexts.reversed()) {
      // 不在此阶段新增 Commands/Models/Tools 行锁（Stage 4-7 已按规范顺序取得，WORK 之后禁止再取），
      // 直接复用已锁定的 model / siblings；Thread 状态重新读取以保证基于 tx 内最新事实 mutation。
      ThreadState thread = tx.findThread(original.thread().id()).orElseThrow();
      UUID stopRequestId = original.stopRequestId();
      EntryPath path = tx.loadEntryPath(thread.headEntryId());
      List<ThreadCommand> queued = original.queued();
      LockedDriverState driverState = original.driverState();
      List<ToolInvocation> siblings = original.toolSiblings();
      ThreadContext context = CLASSIFIER.classify(thread, path, driverState, siblings);
      if (context instanceof ThreadContext.ModelTerminalPending pending) {
        // 提交先于 Stop：用 Driver 的公共物化把已提交的 terminal 结果写入历史（绝不 409），再正常 Stop。
        Materialized materialized =
            materializeTerminalModel(tx, thread, path, pending.driverState());
        if (materialized.terminalEntryId() != null) {
          committedTerminals.add(materialized);
        }
        thread = materialized.thread();
        path = tx.loadEntryPath(thread.headEntryId());
        driverState = driver.lockState(tx, thread, path);
        siblings = loadAssistantSiblings(tx, thread, path, driverState);
        context = CLASSIFIER.classify(thread, path, driverState, siblings);
      } else if (context instanceof ThreadContext.ToolTerminalPending pending) {
        thread = materializeTerminalToolBatch(tx, thread, path, driverState, pending.siblings());
        path = tx.loadEntryPath(thread.headEntryId());
        driverState = driver.lockState(tx, thread, path);
        siblings = List.of();
        context = CLASSIFIER.classify(thread, path, driverState, siblings);
      }
      if (context instanceof ThreadContext.ModelTerminalPending
          || context instanceof ThreadContext.ToolTerminalPending) {
        throw new IllegalStateException(
            "terminal invocation still pending after materialization for thread " + thread.id());
      }
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

      if (context instanceof ThreadContext.ToolActive active) {
        for (ToolInvocation sibling : active.siblings()) {
          if (!sibling.status().isTerminal()) {
            toolExecutionIds.add(sibling.id());
          }
        }
      }

      // 所有上下文都先撤销 Driver 执行权：Model/Tool 由 Driver 写私有边界，idle/continuation 只收敛私有状态。
      // 返回的 opaque executionId 在本事务提交后交给既有本地 canceller。
      DriverStopResult driverStop =
          driver.stop(tx, thread, path, driverState, commit(tx), stopRequestId);
      modelExecutionIds.addAll(driverStop.executionIds());

      NodeStop node =
          switch (context) {
            case ThreadContext.IdleOrHistorical ignored -> stopIdle(
                tx, thread, path, queuedNotifications, stopRequestId, now);
            case ThreadContext.ContinuationDue ignored -> stopContinuation(
                tx, thread, path, stopRequestId, now);
            case ThreadContext.ModelActive active -> stopModel(
                tx, thread, path, driverStop, stopRequestId, now);
            case ThreadContext.ToolActive active -> stopTools(
                tx, thread, path, driverState, active, stopRequestId, now);
            case ThreadContext.ModelTerminalPending ignored -> throw new IllegalStateException(
                "terminal Model context escaped the Stop guard");
            case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
                "terminal Tool context escaped the Stop guard");
          };
      ThreadState stoppedThread =
          isForeignOpenTurn(path, thread.id())
              ? node.thread()
              : materializeQueuedNotifications(tx, node.thread(), queuedNotifications, now);
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
    // 已提交的最终结果先结算；延迟交付到全部节点关闭之后，避免父节点接到通知后又被本次 Stop 取消。
    for (Materialized terminal : committedTerminals) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx,
          terminal.thread(),
          terminal.terminalEntryId(),
          terminal.finalAnswerEntryId(),
          now,
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
        // 没有自有停止边界时不能冻结 Join 结果。
        continue;
      }
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, stopped, boundary, null, now, true);
    }

    tx.insertStopReceipts(command.threadId(), command.stopRequestId(), receipts);

    receipts.sort(Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR));
    ThreadState target = tx.findThread(command.threadId()).orElse(lockedTarget);
    return new Commit(
        new StopResult(false, target, receipts),
        List.copyOf(modelExecutionIds),
        List.copyOf(toolExecutionIds));
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
   * TURN_END、恰好递增一次 version、把执行控制置为 STOPPED。共享 open Turn 仅在有排队通知时在本分支做 history cut， 并在 STOP turn
   * 前物化通知；它不消费人工输入、从不调度模型，也不计入模型工作轮数。
   */
  private static NodeStop stopIdle(
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
          tx.loadEntryPath(notifications.headEntryId()),
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
    tx.insertEntry(
        new Entry(
            turnEndId, sessionId, parentId, stoppedTurnEnd(openTurn.id(), stopRequestId), now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  /** 按既有 history cut 语义收尾当前分支的 open Turn，并返回收尾后的 EntryPath。 */
  private static EntryPath normalizeOpenTurn(
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
    ThreadState stopped = advanceStopped(thread, turnEndId, inputThroughSequence, now);
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

  private static boolean isOwnedBy(Entry openTurn, UUID threadId) {
    return openTurn.payload() instanceof TurnStartPayload start
        && threadId.equals(start.ownerThreadId());
  }

  private static ThreadState touchVersionAsStopped(
      HarnessStore.Transaction tx, ThreadState thread, Instant now) {
    if (thread.executionControl().isStopped()) {
      return thread;
    }
    ThreadState stopped = advanceStopped(thread, thread.headEntryId(), now);
    tx.updateThread(stopped);
    return stopped;
  }

  private static boolean isForeignOpenTurn(EntryPath path, UUID threadId) {
    return path.openTurnStart().filter(turn -> !isOwnedBy(turn, threadId)).isPresent();
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
    TurnEndPayload due = (TurnEndPayload) path.headIgnoringTrailingControlEntries().payload();
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
        CompactionStart.pending(
            CompactionPhase.TURN_PREFIX,
            frozen.trigger(),
            frozen.cutEntryId(),
            frozen.turnPrefixStartEntryId(),
            CompactionTurns.entryAt(path, history.resultIndex()).id());
    return new TurnStartPayload(
        TurnStartReason.COMPACTION,
        path.baseSettings(),
        thread.id(),
        null,
        null,
        stoppedPrefix,
        null);
  }

  private NodeStop stopModel(
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
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            barrierId,
            stoppedTurnEnd(turn.id(), stopRequestId),
            now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    return new NodeStop(stopped, turnEndId);
  }

  private NodeStop stopTools(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState,
      ThreadContext.ToolActive active,
      UUID stopRequestId,
      Instant now) {
    UUID turnStartEntryId =
        path.openTurnStart()
            .orElseThrow(() -> new IllegalStateException("tool stop requires an open TURN_START"))
            .id();
    UUID parentId = active.assistant().id();
    for (ToolInvocation sibling : active.siblings()) {
      ToolInvocation terminal =
          switch (sibling.status()) {
            case WAITING_APPROVAL, WAITING_INPUT, READY -> sibling.cancel(
                TOOL_CANCELLED_ERROR, now);
            case RUNNING -> sibling.unknown(TOOL_UNKNOWN_ERROR, now);
            case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> sibling;
          };
      ToolOutcomeAppender.Applied applied =
          ToolOutcomeAppender.append(tx, path.root().sessionId(), parentId, terminal, now);
      parentId = applied.headEntryId();
    }
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            parentId,
            stoppedTurnEnd(turnStartEntryId, stopRequestId),
            now));
    ThreadState stopped = advanceStopped(thread, turnEndId, now);
    tx.updateThread(stopped);
    tx.deleteToolInvocationsByIds(active.siblings().stream().map(ToolInvocation::id).toList());
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
            thread.spawnTrigger(),
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
   * Entry），输入水位同步覆盖这些已物化通知；不唤醒模型。STOPPED 节点上的通知只作为历史输入，待显式新输入恢复后的下一个 INPUT 接纳。返回推进 head 后的 STOPPED
   * Thread。
   */
  private static ThreadState materializeQueuedNotifications(
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
    tx.updateThread(advanced);
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
      inputThroughSequence = Math.max(inputThroughSequence, command.sequence());
    }
    if (!applied.isEmpty()) {
      tx.updateCommands(applied);
    }
    return new MaterializedNotifications(head, inputThroughSequence);
  }

  /**
   * 提交先于 Stop：让 Driver 把已提交的 terminal generation 物化进历史（ASSISTANT 结果 + TURN_END，或 tool batch 的
   * siblings），关闭 turn 时由 Driver 删除私有状态；Stop 随后自行收敛该节点。
   */
  private Materialized materializeTerminalModel(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState) {
    Instant mutationNow = tx.now();
    DriverApplied applied = driver.applyResponse(tx, thread, path, driverState, commit(tx));
    ThreadState current = tx.findThread(thread.id()).orElseThrow();
    ThreadState advanced;
    if (current.headEntryId().equals(applied.headEntryId())) {
      advanced = current;
    } else {
      if (!current.equals(thread)) {
        throw new IllegalStateException(
            "driver changed the thread without applying its declared head");
      }
      advanced = thread.advanceHead(applied.headEntryId(), mutationNow);
      tx.updateThread(advanced);
    }
    return new Materialized(
        advanced, applied.terminal() ? applied.headEntryId() : null, applied.finalAnswerEntryId());
  }

  /** 全部 terminal 的 Tool sibling batch 物化：公共结果追加后由 Driver 完成附加校验与私有状态清理。 */
  private ThreadState materializeTerminalToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState,
      List<ToolInvocation> siblings) {
    Instant mutationNow = tx.now();
    UUID turnStartEntryId =
        path.openTurnStart()
            .orElseThrow(() -> new IllegalStateException("tool batch requires an open TURN_START"))
            .id();
    UUID parentId = path.head().id();
    for (ToolInvocation sibling : siblings) {
      parentId =
          ToolOutcomeAppender.append(tx, path.root().sessionId(), parentId, sibling, mutationNow)
              .headEntryId();
    }
    UUID turnEndId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnEndId,
            path.root().sessionId(),
            parentId,
            new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, true, null, null),
            mutationNow));
    tx.deleteToolInvocationsByIds(siblings.stream().map(ToolInvocation::id).toList());
    ThreadState advanced = thread.advanceHead(turnEndId, mutationNow);
    tx.updateThread(advanced);
    driver.completeToolBatch(tx, thread, path, driverState, siblings, commit(tx));
    return advanced;
  }

  /** Assistant head 上属于本 Thread 的 Tool siblings；共享的 foreign turn 不得锁定原 owner 的工具。 */
  private static List<ToolInvocation> loadAssistantSiblings(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState) {
    Entry semanticHead = path.headIgnoringTrailingControlEntries();
    if (isForeignOpenTurn(path, thread.id())
        || (driverState.requestHeadEntryId() != null && !driverState.resultAt(thread.headEntryId()))
        || !(semanticHead.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      return List.of();
    }
    return tx.lockToolInvocationsByAssistantEntryId(semanticHead.id());
  }

  private static DriverCommit commit(HarnessStore.Transaction tx) {
    return new DriverCommit() {
      @Override
      public UUID nextId() {
        return tx.nextId();
      }

      @Override
      public Instant now() {
        return tx.now();
      }

      @Override
      public void insertEntry(Entry entry) {
        tx.insertEntry(entry);
      }
    };
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
