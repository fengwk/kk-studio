package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.StopNodeConverger.ThreadStopContext;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * StoppedThreadReceipt}。已持久保存的 stopRequestId 重放直接返回整批回执，不以当前树重算、不停止之后启动的新工作。被取消的人类输入写入回执；
 * 已排队的系统通知物化为历史并同步推进输入水位。
 *
 * <p>本类只编排锁序、canonical 阶段与 Join 结算：单节点收敛在 {@link StopNodeConverger}，Turn / 通知写入在 {@link
 * StopTurnWrites}，回执组装在 {@link StopReceipts}。
 */
final class StopControl {

  private static final Comparator<WorkTarget> WORK_TARGET_ORDER =
      Comparator.comparingInt((WorkTarget target) -> workTypeRank(target.type()))
          .thenComparing(target -> target.type().value())
          .thenComparing(WorkTarget::id, UuidOrder.COMPARATOR);
  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private final HarnessStore store;
  private final DriverRegistry driverRegistry;
  private final StopNodeConverger nodeConverger;

  StopControl(HarnessStore store, DriverRegistry driverRegistry) {
    this.store = Objects.requireNonNull(store, "store");
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
    this.nodeConverger = new StopNodeConverger(driverRegistry);
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

  private Commit stop(HarnessStore.Transaction tx, StopCommand command) {
    // 树锁必须在任何业务行锁之前获取
    ThreadTreeLocks.lockForThread(tx, command.threadId());
    StopTreeCollector.Tree tree = StopTreeCollector.collect(tx, command);
    Map<UUID, ThreadState> lockedThreads = lockSessionsAndThreads(tx, tree);
    ThreadState lockedTarget = lockedThreads.get(command.threadId());
    Optional<Commit> replayed = replayStoredStop(tx, command, lockedTarget);
    if (replayed.isPresent()) {
      return replayed.get();
    }
    List<StopTreeCollector.Candidate> toStop = requireUnusedStopIdentities(tx, tree, lockedThreads);
    List<ThreadStopContext> contexts = lockStopRows(tx, toStop);
    return converge(tx, command, lockedTarget, contexts);
  }

  /** 规范锁序：Sessions (KEY SHARE) → Threads (FOR UPDATE, UuidOrder)。 */
  private static Map<UUID, ThreadState> lockSessionsAndThreads(
      HarnessStore.Transaction tx, StopTreeCollector.Tree tree) {
    for (UUID sessionId : tree.sessionIds()) {
      tx.sessions().lockForKeyShare(sessionId).orElseThrow();
    }
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID threadId : tree.threadIds()) {
      ThreadState locked =
          tx.threads()
              .lock(threadId)
              .orElseThrow(
                  () ->
                      DomainException.notFound("thread", "thread " + threadId + " does not exist"));
      lockedThreads.put(threadId, locked);
    }
    return lockedThreads;
  }

  /**
   * durable receipt replay 先于 version CAS：按 (rootThreadId, rootStopRequestId) 返回持久保存的记录，不以当前树重算，
   * 也不停止其后启动的新工作。
   */
  private static Optional<Commit> replayStoredStop(
      HarnessStore.Transaction tx, StopCommand command, ThreadState lockedTarget) {
    List<StoppedThreadReceipt> stored =
        tx.stops().loadByRootRequest(command.threadId(), command.stopRequestId());
    if (!stored.isEmpty()) {
      return Optional.of(StopReceipts.replay(lockedTarget, stored));
    }
    // 单节点回执命中但没有该 root 对：该派生 stopRequestId 已被其它 root 使用，派生身份不得伪装成新的 root。
    if (tx.stops().findReceipt(command.threadId(), command.stopRequestId()).isPresent()) {
      throw conflict(
          HarnessConflictReason.STOP_REQUEST_ID_REUSED,
          "stopRequestId "
              + command.stopRequestId()
              + " on thread "
              + command.threadId()
              + " is already used by another stop root");
    }
    if (lockedTarget.version() != command.expectedVersion()) {
      throw conflict(
          HarnessConflictReason.STALE_VERSION,
          "thread "
              + lockedTarget.id()
              + " version "
              + lockedTarget.version()
              + " does not match expected "
              + command.expectedVersion());
    }
    return Optional.empty();
  }

  /**
   * 首次 Stop：任何节点的身份都不能已被使用——若某节点的 (threadId, stopRequestId) 已存在（来自其它 root 或非 Stop 关闭操作），
   * 必须明确冲突，绝不静默跳过部分集合。
   */
  private static List<StopTreeCollector.Candidate> requireUnusedStopIdentities(
      HarnessStore.Transaction tx,
      StopTreeCollector.Tree tree,
      Map<UUID, ThreadState> lockedThreads) {
    List<StopTreeCollector.Candidate> toStop = new ArrayList<>(tree.candidates().size());
    for (StopTreeCollector.Candidate candidate : tree.candidates()) {
      ThreadState locked = lockedThreads.get(candidate.thread().id());
      if (tx.stops().findReceipt(locked.id(), candidate.stopRequestId()).isPresent()) {
        throw conflict(
            HarnessConflictReason.STOP_REQUEST_ID_REUSED,
            "stop identity ("
                + locked.id()
                + ", "
                + candidate.stopRequestId()
                + ") is already used by another stop root");
      }
      toStop.add(new StopTreeCollector.Candidate(locked, candidate.stopRequestId()));
    }
    return toStop;
  }

  /**
   * 按规范锁序取得 Commands → Driver 私有行 → Tool siblings → Work 的锁与只读事实。
   *
   * <p>遍历中只做只读读取，terminal 结果物化与收敛统一留到全锁完成后再做；Work 锁覆盖 TerminalPending 节点仍持有的 MODEL/TOOL Work，并先锁后删。
   */
  private List<ThreadStopContext> lockStopRows(
      HarnessStore.Transaction tx, List<StopTreeCollector.Candidate> toStop) {
    Map<UUID, List<ThreadCommand>> queuedByThread = new HashMap<>();
    Map<UUID, EntryPath> pathsByThread = new HashMap<>();
    Map<UUID, EntryCurrentState> statesByThread = new HashMap<>();
    for (StopTreeCollector.Candidate candidate : toStop) {
      EntryPath path = tx.entries().loadPath(candidate.thread().headEntryId());
      pathsByThread.put(candidate.thread().id(), path);
      statesByThread.put(candidate.thread().id(), EntryCurrentState.from(path));
      queuedByThread.put(
          candidate.thread().id(), tx.commands().loadQueued(candidate.thread().id()));
    }

    Map<UUID, LockedDriverState> driverStatesByThread = new HashMap<>();
    for (StopTreeCollector.Candidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      EntryCurrentState state = statesByThread.get(candidate.thread().id());
      AgentDriver driver = driverRegistry.of(path.baseSettings());
      driverStatesByThread.put(
          candidate.thread().id(), driver.lockState(tx, candidate.thread(), state));
    }

    List<ThreadStopContext> contexts = new ArrayList<>();
    for (StopTreeCollector.Candidate candidate : toStop) {
      EntryPath path = pathsByThread.get(candidate.thread().id());
      EntryCurrentState state = statesByThread.get(candidate.thread().id());
      LockedDriverState driverState = driverStatesByThread.get(candidate.thread().id());
      List<ToolInvocation> siblings =
          StopNodeConverger.loadAssistantSiblings(tx, candidate.thread(), path, driverState);
      ThreadContext context = CLASSIFIER.classify(candidate.thread(), state, driverState, siblings);
      contexts.add(
          new ThreadStopContext(
              candidate.thread(),
              candidate.stopRequestId(),
              path,
              queuedByThread.get(candidate.thread().id()),
              driverState,
              siblings,
              context));
    }

    lockAndDeleteWork(tx, contexts);
    return contexts;
  }

  /** Work 锁与立即删除（覆盖 TerminalPending 节点仍持有的 MODEL/TOOL Work，先于任何物化删除）。 */
  private static void lockAndDeleteWork(
      HarnessStore.Transaction tx, List<ThreadStopContext> contexts) {
    List<WorkTarget> allWorkTargets = new ArrayList<>();
    for (ThreadStopContext ctx : contexts) {
      allWorkTargets.add(new WorkTarget(WorkTargetType.THREAD, ctx.thread().id()));
      if (ctx.driverState().workTarget() != null) {
        allWorkTargets.add(ctx.driverState().workTarget());
      }
      for (ToolInvocation sibling : ctx.toolSiblings()) {
        allWorkTargets.add(new WorkTarget(WorkTargetType.TOOL, sibling.id()));
      }
    }
    allWorkTargets.sort(WORK_TARGET_ORDER);
    for (WorkTarget target : allWorkTargets) {
      tx.work().lock(target);
    }
    for (WorkTarget target : allWorkTargets) {
      tx.work().delete(target);
    }
  }

  /** 自底向上逐节点收敛，然后结算 Join、落回执并组装提交结果。 */
  private Commit converge(
      HarnessStore.Transaction tx,
      StopCommand command,
      ThreadState lockedTarget,
      List<ThreadStopContext> contexts) {
    Set<UUID> driverExecutionIds = new LinkedHashSet<>();
    Set<UUID> toolExecutionIds = new LinkedHashSet<>();
    List<StoppedThreadReceipt> receipts = new ArrayList<>();
    List<ThreadState> stoppedStates = new ArrayList<>();
    List<StopNodeConverger.Materialized> committedTerminals = new ArrayList<>();
    Instant now = tx.now();

    for (ThreadStopContext original : contexts.reversed()) {
      StopNodeConverger.Converged node =
          nodeConverger.converge(tx, original, committedTerminals, now);
      driverExecutionIds.addAll(node.driverExecutionIds());
      toolExecutionIds.addAll(node.toolExecutionIds());
      stoppedStates.add(node.thread());
      receipts.add(node.receipt());
    }

    StopReceipts.requireTargetReceipt(receipts, command.threadId());
    deliverJoins(tx, committedTerminals, stoppedStates, receipts, now);
    tx.stops().insertReceipts(command.threadId(), command.stopRequestId(), receipts);
    ThreadState target = tx.threads().find(command.threadId()).orElse(lockedTarget);
    return StopReceipts.commit(target, receipts, driverExecutionIds, toolExecutionIds);
  }

  /**
   * Join 结算与父通知：所有节点已 STOPPED；父节点若在本集合内则通知直接固化到历史，否则投递为父命令并唤醒。已提交的最终结果先结算；
   * 延迟交付到全部节点关闭之后，避免父节点接到通知后又被本次 Stop 取消。
   */
  private static void deliverJoins(
      HarnessStore.Transaction tx,
      List<StopNodeConverger.Materialized> committedTerminals,
      List<ThreadState> stoppedStates,
      List<StoppedThreadReceipt> receipts,
      Instant now) {
    for (StopNodeConverger.Materialized terminal : committedTerminals) {
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx,
          terminal.thread(),
          terminal.terminalEntryId(),
          terminal.finalAnswerEntryId(),
          now,
          false);
    }
    for (ThreadState stopped : stoppedStates) {
      UUID boundary = stoppedBoundary(receipts, stopped);
      if (boundary == null) {
        // 没有自有停止边界时不能冻结 Join 结果。
        continue;
      }
      ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
          tx, stopped, boundary, null, now, true);
    }
  }

  private static UUID stoppedBoundary(List<StoppedThreadReceipt> receipts, ThreadState stopped) {
    for (StoppedThreadReceipt receipt : receipts) {
      if (receipt.threadId().equals(stopped.id())) {
        return receipt.stoppedTurnEndEntryId();
      }
    }
    return null;
  }

  private static int workTypeRank(WorkTargetType type) {
    return type.rank();
  }

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }

  /** Durable Stop 结果，加上 transaction 提交后需取消的全部 process-local execution。 */
  record Commit(StopResult result, List<UUID> driverExecutionIds, List<UUID> toolExecutionIds) {

    Commit {
      result = Objects.requireNonNull(result, "result");
      driverExecutionIds =
          List.copyOf(Objects.requireNonNull(driverExecutionIds, "driverExecutionIds"));
      toolExecutionIds = List.copyOf(Objects.requireNonNull(toolExecutionIds, "toolExecutionIds"));
    }
  }
}
