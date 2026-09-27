package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 在树锁与祖先行锁下推进递归空闲、冻结首次匹配的 join 并投递父线程消息。 */
public final class ThreadLifecycleCoordinator {

  public static final String DEFAULT_MAX_TURNS_REMINDER_TEXT =
      """
      The delegated task has reached its suggested turn budget. Wrap up your current work and report the result, including any incomplete work, to the requesting agent.""";

  private final ThreadContextProbe threadContextProbe;
  private final AutomaticCompactionPlanner automaticCompactionPlanner;
  private final Supplier<CompactionConfig> compactionConfigSupplier;
  private final Clock clock;

  public ThreadLifecycleCoordinator(
      ThreadContextProbe threadContextProbe,
      AutomaticCompactionPlanner automaticCompactionPlanner,
      Supplier<CompactionConfig> compactionConfigSupplier,
      Clock clock) {
    this.threadContextProbe = Objects.requireNonNull(threadContextProbe, "threadContextProbe");
    this.automaticCompactionPlanner =
        Objects.requireNonNull(automaticCompactionPlanner, "automaticCompactionPlanner");
    this.compactionConfigSupplier =
        Objects.requireNonNull(compactionConfigSupplier, "compactionConfigSupplier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 获取指定 Thread 所在执行树的根锁，并按规范锁序锁定祖先链全部 Session（KEY SHARE）与 Thread（FOR UPDATE）。
   *
   * @param tx 当前事务句柄，不能为 null
   * @param threadId 目标线程 ID，不能为 null
   * @return 目标线程锁定后的当前状态，若任何 Session/Thread 不存在或被并发删除则返回 null
   */
  public static ThreadState lockThreadWithAncestors(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    List<UUID> chain = tx.findAncestorChain(threadId);
    UUID root = chain.isEmpty() ? threadId : chain.get(chain.size() - 1);
    tx.lockTree(root);
    if (!chain.equals(tx.findAncestorChain(threadId))) {
      throw new IllegalStateException("execution tree changed while acquiring its lock");
    }
    Map<UUID, ThreadState> immutableThreads = new HashMap<>();
    for (UUID id : chain) {
      ThreadState state = tx.findThread(id).orElse(null);
      if (state == null) {
        return null;
      }
      immutableThreads.put(id, state);
    }
    List<UUID> sessionIds =
        immutableThreads.values().stream()
            .map(ThreadState::sessionId)
            .distinct()
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    for (UUID sessionId : sessionIds) {
      if (tx.lockSessionForKeyShare(sessionId).isEmpty()) {
        return null;
      }
    }
    List<UUID> sortedThreadIds = chain.stream().sorted(UuidOrder.COMPARATOR).toList();
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID id : sortedThreadIds) {
      ThreadState locked = tx.lockThread(id).orElse(null);
      if (locked == null) {
        return null;
      }
      if (!locked.sessionId().equals(immutableThreads.get(id).sessionId())) {
        throw new IllegalStateException(
            "thread " + id + " relocated while acquiring its session lock");
      }
      lockedThreads.put(id, locked);
    }
    return lockedThreads.get(threadId);
  }

  /**
   * 在关闭 Turn 时原子推进 head 与递归生命周期状态，若达到真正空闲则匹配 Join 并向祖先链递归传播。
   *
   * @param tx 当前事务句柄，不能为 null
   * @param thread 待推进的 Thread 当前已锁定状态，不能为 null
   * @param headEntryId 新 head Entry ID，不能为 null
   * @param now 当前时间戳，不能为 null
   * @return 推进后的 ThreadState
   */
  public ThreadState advanceHeadAndPropagateIdle(
      HarnessStore.Transaction tx, ThreadState thread, UUID headEntryId, Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(now, "now");

    int activeChildren = tx.countActiveChildren(thread.id());
    if (activeChildren > 0) {
      ThreadState advanced =
          advanceHeadWithStatus(thread, headEntryId, ThreadLifecycleStatus.WAITING_CHILDREN, now);
      tx.updateThread(advanced);
      return advanced;
    }

    ThreadState advanced =
        advanceHeadWithStatus(thread, headEntryId, ThreadLifecycleStatus.IDLE, now);
    tx.updateThread(advanced);

    Instant mutationNow = effectiveMutationTime(now, advanced);
    List<WorkTarget> workToRequest = new ArrayList<>();
    matchAndDeliverJoins(tx, advanced, mutationNow, workToRequest);

    if (advanced.parentThreadId() != null) {
      ThreadState parent = tx.findThread(advanced.parentThreadId()).orElse(null);
      if (parent != null) {
        propagateIdleInternal(tx, parent, mutationNow, workToRequest);
      }
    }

    workToRequest.sort(
        Comparator.comparing(WorkTarget::type).thenComparing(WorkTarget::id, UuidOrder.COMPARATOR));
    for (WorkTarget target : workToRequest) {
      tx.requestWork(target, now);
    }

    return advanced;
  }

  /**
   * 递归评估并传播 Thread 及其祖先链的空闲状态。
   *
   * @param tx 当前事务句柄，不能为 null
   * @param startingThread 起始 Thread 状态，不能为 null
   * @param now 当前时间戳，不能为 null
   */
  public void propagateIdle(HarnessStore.Transaction tx, ThreadState startingThread, Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(startingThread, "startingThread");
    Objects.requireNonNull(now, "now");

    List<WorkTarget> workToRequest = new ArrayList<>();
    propagateIdleInternal(tx, startingThread, now, workToRequest);
    workToRequest.sort(
        Comparator.comparing(WorkTarget::type).thenComparing(WorkTarget::id, UuidOrder.COMPARATOR));
    for (WorkTarget target : workToRequest) {
      tx.requestWork(target, now);
    }
  }

  private void propagateIdleInternal(
      HarnessStore.Transaction tx,
      ThreadState startingThread,
      Instant now,
      List<WorkTarget> workToRequest) {
    ThreadState current = startingThread;
    while (current != null) {
      if (hasLocalWork(tx, current)) {
        if (current.status() != ThreadLifecycleStatus.ACTIVE) {
          Instant mutationNow = effectiveMutationTime(now, current);
          current = current.changeLifecycleStatus(ThreadLifecycleStatus.ACTIVE, mutationNow);
          tx.updateThread(current);
        }
        break;
      }

      int activeChildren = tx.countActiveChildren(current.id());
      if (activeChildren > 0) {
        if (current.status() != ThreadLifecycleStatus.WAITING_CHILDREN) {
          Instant mutationNow = effectiveMutationTime(now, current);
          current =
              current.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, mutationNow);
          tx.updateThread(current);
        }
        break;
      }

      Instant mutationNow = effectiveMutationTime(now, current);
      if (current.status() != ThreadLifecycleStatus.IDLE) {
        current = current.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, mutationNow);
        tx.updateThread(current);
      }

      matchAndDeliverJoins(tx, current, mutationNow, workToRequest);

      if (current.parentThreadId() == null) {
        break;
      }
      current = tx.findThread(current.parentThreadId()).orElse(null);
    }
  }

  /** 叶到根结算已停止的线程；STOPPED 父线程的 join 只匹配不投递。 */
  public static List<ThreadState> settleStoppedTree(
      HarnessStore.Transaction tx, List<ThreadState> orderedThreads, Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(orderedThreads, "orderedThreads");
    Objects.requireNonNull(now, "now");

    List<ThreadState> settled = new ArrayList<>(orderedThreads.size());
    List<WorkTarget> workToRequest = new ArrayList<>();

    for (ThreadState thread : orderedThreads) {
      int activeChildren = tx.countActiveChildren(thread.id());
      ThreadLifecycleStatus expectedStatus =
          activeChildren > 0 ? ThreadLifecycleStatus.WAITING_CHILDREN : ThreadLifecycleStatus.IDLE;
      ThreadState current = thread;
      if (current.status() != expectedStatus) {
        Instant mutationNow = effectiveMutationTime(now, current);
        current = current.changeLifecycleStatus(expectedStatus, mutationNow);
        tx.updateThread(current);
      }
      settled.add(current);

      if (current.status() == ThreadLifecycleStatus.IDLE) {
        List<ThreadJoin> matchable = tx.loadMatchableJoins(current.id(), current.version());
        for (ThreadJoin join : matchable) {
          Instant joinMutationNow = HarnessStoreTime.notBefore(now, join.updatedAt());
          ThreadJoin matched =
              join.match(current.version(), current.headEntryId(), joinMutationNow);
          tx.updateJoin(matched);

          if (matched.parentThreadId() != null) {
            ThreadState parent =
                tx.findThread(matched.parentThreadId())
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "parent thread " + matched.parentThreadId() + " not found"));
            EntryPath parentPath = tx.loadEntryPath(parent.headEntryId());
            boolean parentStopped =
                parentPath.head().payload() instanceof TurnEndPayload end
                    && end.outcome() == TurnEndOutcome.STOPPED;
            if (!parentStopped) {
              ThreadJoinReceipt receipt =
                  ThreadJoinProjector.INSTANCE
                      .project(tx, matched)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "cannot project receipt for matched join "
                                      + matched.invocationId()));
              String xml = receipt.renderCompletionXml();
              long seq = parent.nextCommandSequence();
              String requestHash = sha256Hex(xml);
              Instant parentMutationNow = HarnessStoreTime.notBefore(now, parent.updatedAt());
              Instant deliveryMutationNow =
                  HarnessStoreTime.notBefore(parentMutationNow, matched.updatedAt());
              ThreadCommand cmd =
                  new ThreadCommand(
                      parent.id(),
                      seq,
                      new CustomMessageCommandPayload(AgentMessage.user(xml)),
                      matched.invocationId(),
                      requestHash,
                      null,
                      null,
                      null,
                      parentMutationNow);
              tx.insertCommands(List.of(cmd));
              parent = parent.reserveCommandSequences(1, parentMutationNow);
              tx.updateThread(parent);

              ThreadJoin delivered = matched.delivered(seq, deliveryMutationNow);
              tx.updateJoin(delivered);
              workToRequest.add(new WorkTarget(WorkTargetType.THREAD, parent.id()));
            }
          }
        }
      }
    }

    workToRequest.sort(
        Comparator.comparing(WorkTarget::type).thenComparing(WorkTarget::id, UuidOrder.COMPARATOR));
    for (WorkTarget target : workToRequest) {
      tx.requestWork(target, now);
    }

    return List.copyOf(settled);
  }

  /** 匹配指定已空闲 Thread 上的所有 matchable join，若父级正常则原子交付 CUSTOM_MESSAGE。 */
  private void matchAndDeliverJoins(
      HarnessStore.Transaction tx,
      ThreadState thread,
      Instant now,
      List<WorkTarget> workToRequest) {
    List<ThreadJoin> matchable = tx.loadMatchableJoins(thread.id(), thread.version());
    for (ThreadJoin join : matchable) {
      Instant joinMutationNow = HarnessStoreTime.notBefore(now, join.updatedAt());
      ThreadJoin matched = join.match(thread.version(), thread.headEntryId(), joinMutationNow);
      tx.updateJoin(matched);

      if (matched.parentThreadId() != null) {
        ThreadState parent =
            tx.findThread(matched.parentThreadId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "parent thread " + matched.parentThreadId() + " not found"));
        EntryPath parentPath = tx.loadEntryPath(parent.headEntryId());
        boolean parentStopped =
            parentPath.head().payload() instanceof TurnEndPayload end
                && end.outcome() == TurnEndOutcome.STOPPED;
        if (!parentStopped) {
          ThreadJoinReceipt receipt =
              ThreadJoinProjector.INSTANCE
                  .project(tx, matched)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "cannot project receipt for matched join " + matched.invocationId()));
          String xml = receipt.renderCompletionXml();
          long seq = parent.nextCommandSequence();
          String requestHash = sha256Hex(xml);
          Instant parentMutationNow = HarnessStoreTime.notBefore(now, parent.updatedAt());
          Instant deliveryMutationNow =
              HarnessStoreTime.notBefore(parentMutationNow, matched.updatedAt());
          ThreadCommand cmd =
              new ThreadCommand(
                  parent.id(),
                  seq,
                  new CustomMessageCommandPayload(AgentMessage.user(xml)),
                  matched.invocationId(),
                  requestHash,
                  null,
                  null,
                  null,
                  parentMutationNow);
          tx.insertCommands(List.of(cmd));
          parent = parent.reserveCommandSequences(1, parentMutationNow);
          tx.updateThread(parent);

          ThreadJoin delivered = matched.delivered(seq, deliveryMutationNow);
          tx.updateJoin(delivered);
          workToRequest.add(new WorkTarget(WorkTargetType.THREAD, parent.id()));
        }
      }
    }
  }

  /** 判断 Thread 本地是否仍有未完成的命令、Invocation、续写或压缩义务。 */
  public boolean hasLocalWork(HarnessStore.Transaction tx, ThreadState thread) {
    List<ThreadCommand> queued = tx.loadQueuedCommands(thread.id());
    boolean hasQueuedMsg = hasQueuedUserMessage(queued);
    EntryPath path = tx.loadEntryPath(thread.headEntryId());
    ThreadContext context = threadContextProbe.probe(tx, thread, path);
    return switch (context) {
      case ThreadContext.ModelTerminalPending ignored -> true;
      case ThreadContext.ModelActive ignored -> true;
      case ThreadContext.ToolTerminalPending ignored -> true;
      case ThreadContext.ToolActive ignored -> true;
      case ThreadContext.ContinuationDue ignored -> true;
      case ThreadContext.IdleOrHistorical ignored -> {
        CompactionConfig compactionConfig =
            compactionConfigSupplier != null ? compactionConfigSupplier.get() : null;
        boolean compactionDue =
            compactionConfig != null
                && automaticCompactionPlanner.plan(thread, path, compactionConfig, hasQueuedMsg)
                    != null;
        yield hasQueuedMsg || compactionDue;
      }
    };
  }

  private static boolean hasQueuedUserMessage(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.type().isMessage()) {
        return true;
      }
    }
    return false;
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

  private static String sha256Hex(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** 在已确定继续运行的边界原子入队预算提醒并推进 reminderTurn；终态空闲不调用。 */
  public ThreadState remindSoftBudgetIfDue(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path, Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(now, "now");

    List<ThreadJoin> joins = tx.loadMatchableJoins(thread.id(), Long.MAX_VALUE);
    if (joins.isEmpty()) {
      return thread;
    }

    ThreadState current = thread;
    List<ThreadCommand> threadCommands = null;
    for (ThreadJoin join : joins) {
      if (join.maxTurns() == null || join.maxTurns() <= 0) {
        continue;
      }
      if (join.parentThreadId() != null) {
        ThreadState parent = tx.findThread(join.parentThreadId()).orElse(null);
        if (parent != null) {
          EntryPath parentPath = tx.loadEntryPath(parent.headEntryId());
          if (parentPath.head().payload() instanceof TurnEndPayload end
              && end.outcome() == TurnEndOutcome.STOPPED) {
            continue;
          }
        }
      }

      if (threadCommands == null) {
        threadCommands = tx.loadCommandsByThread(current.id());
      }
      int actualTurns = countActualTurns(threadCommands, path, join);
      if (actualTurns >= join.maxTurns() && actualTurns > join.reminderTurn()) {
        UUID idempotencyKey =
            UUID.nameUUIDFromBytes(
                ("reminder:" + join.invocationId() + ":" + actualTurns)
                    .getBytes(StandardCharsets.UTF_8));
        if (tx.findCommandByIdempotencyKey(current.id(), idempotencyKey).isPresent()) {
          continue;
        }

        CustomMessageCommandPayload payload =
            new CustomMessageCommandPayload(
                SystemReminder.message(DEFAULT_MAX_TURNS_REMINDER_TEXT));
        String reqHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
        Instant cmdMutationNow = effectiveMutationTime(now, current);
        ThreadCommand cmd =
            new ThreadCommand(
                current.id(),
                current.nextCommandSequence(),
                payload,
                idempotencyKey,
                reqHash,
                null,
                null,
                null,
                cmdMutationNow);
        tx.insertCommands(List.of(cmd));
        current = current.reserveCommandSequences(1, cmdMutationNow);
        tx.updateThread(current);

        Instant joinMutationNow = HarnessStoreTime.notBefore(cmdMutationNow, join.updatedAt());
        ThreadJoin reminded = join.remind(actualTurns, joinMutationNow);
        tx.updateJoin(reminded);
      }
    }
    return current;
  }

  /** 从 join 源命令的已应用 TURN_START 起计模型工作轮，忽略 COMPACTION / STOP。 */
  public static int countActualTurns(
      List<ThreadCommand> threadCommands, EntryPath path, ThreadJoin join) {
    Objects.requireNonNull(threadCommands, "threadCommands");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(join, "join");

    ThreadCommand sourceCommand = null;
    for (ThreadCommand cmd : threadCommands) {
      if (cmd.sequence() == join.sourceCommandSequence()) {
        sourceCommand = cmd;
        break;
      }
    }
    if (sourceCommand == null || sourceCommand.appliedTurnStartEntryId() == null) {
      return 0;
    }
    UUID appliedStart = sourceCommand.appliedTurnStartEntryId();
    List<Entry> entries = path.entries();
    int startIndex = -1;
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(appliedStart)) {
        startIndex = i;
        break;
      }
    }
    if (startIndex < 0) {
      return 0;
    }
    int count = 0;
    for (int i = startIndex; i < entries.size(); i++) {
      if (entries.get(i).payload() instanceof TurnStartPayload turn
          && turn.reason() != TurnStartReason.COMPACTION
          && turn.reason() != TurnStartReason.STOP) {
        count++;
      }
    }
    return count;
  }
}
