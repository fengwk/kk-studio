package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinCompletion;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行终止边界上的 Join 冻结与父 Thread 通知交付，以及短预算提醒物化。
 *
 * <p>本类不再维护递归空闲生命周期，也不再按 Thread version 匹配 Join：普通终止（最终回答 / 不可继续失败）只有在源输入已应用、且本 Thread 收敛（无未完成的直接子
 * Join、无未送达子回执、无待处理输入）时才冻结 Join；Stop 的强制结算不受该收敛判据限制。冻结结果与终态 Entry、父通知在同一事务提交。 父 Thread 为 STOPPED
 * 时通知直接固化到历史而不唤醒模型。
 */
public final class ThreadLifecycleCoordinator {

  public static final String DEFAULT_MAX_TURNS_REMINDER_TEXT =
      """
      The delegated task has reached its suggested turn budget. Wrap up your current work and report the result, including any incomplete work, to the requesting agent.""";
  public static final String DEFAULT_MAX_TURNS_REMINDER_TAG = "task-budget";

  /** 无状态协调器：所有操作都以显式事务句柄与显式时钟驱动。 */
  public ThreadLifecycleCoordinator() {}

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
    List<UUID> chain = ThreadTreeLocks.lockForThread(tx, threadId);
    if (chain.isEmpty()) {
      // 目标线程在取得树锁前已被并发删除：合法缺失，不抛异常也不重试。
      return null;
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
   * 在执行终止边界冻结本次 Thread 上尚未完成的 Join，并向父 Thread 交付系统通知。
   *
   * <p>普通终止（{@code includeUnappliedSource == false}）只在该 Thread 收敛时结算：源输入已应用、没有未完成的直接子
   * Join、没有未送达的子回执且没有待处理输入；否则保留未匹配 Join，等待后续真实输入或子结算再次到达终止边界。Stop 强制结算（{@code
   * includeUnappliedSource == true}）不受收敛判据限制。
   *
   * @param tx 当前事务句柄，不能为 null
   * @param child 已锁定且刚到达终止边界的子 Thread
   * @param terminalEntryId 冻结的终态 Entry（最终回答的 TURN_END、不可继续失败或 Stop 收尾），不能为 null
   * @param finalAnswerEntryId 可空最终回答入口；不借用源输入之前的回答
   * @param now 当前时间
   * @param includeUnappliedSource true 表示 Stop 场景：源输入被执行前取消也算本次结算；普通终止只结算已应用源输入的 Join
   */
  public static void matchAndDeliverTerminalJoins(
      HarnessStore.Transaction tx,
      ThreadState child,
      UUID terminalEntryId,
      UUID finalAnswerEntryId,
      Instant now,
      boolean includeUnappliedSource) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(child, "child");
    Objects.requireNonNull(terminalEntryId, "terminalEntryId");
    Objects.requireNonNull(now, "now");
    List<ThreadJoin> incomplete = tx.loadIncompleteJoins(child.id());
    if (incomplete.isEmpty()) {
      return;
    }
    if (!includeUnappliedSource && !isQuiescent(tx, child)) {
      // 本次终态不是委派收敛点：子执行还欠未完成的直接子 Join、未送达的子回执或待处理输入，只保留未匹配 Join，
      // 不向父结算、不自唤醒忙轮询；后续真实输入/子结算路径会再次到达终止边界。
      return;
    }
    List<ThreadJoin> matched = new ArrayList<>(incomplete.size());
    for (ThreadJoin join : incomplete) {
      if (!includeUnappliedSource) {
        ThreadCommand source =
            tx.findCommand(join.childThreadId(), join.sourceCommandSequence()).orElse(null);
        if (source == null || source.appliedEntryId() == null) {
          // 输入尚未应用时，旧回合完成不能结算新 Join。
          continue;
        }
      }
      Instant joinNow = HarnessStoreTime.notBefore(now, join.updatedAt());
      ThreadJoin current = join.match(terminalEntryId, finalAnswerEntryId, joinNow);
      tx.updateJoin(current);
      matched.add(current);
    }
    if (!matched.isEmpty()) {
      deliverMatchedJoins(tx, matched, now);
    }
  }

  /**
   * 普通终止的委派收敛判据：子执行没有尚未冻结结果的直接子 Join、没有已冻结但未送达的子回执，且本 Thread 没有待处理输入（QUEUED 消息/通知，或已物化但未越过输入水位的
   * APPLIED 通知）。
   *
   * <p>直接子 Join 自身也遵循同一判据，A-B-C 及更深的委派因此自底向上自然收敛，不需要遍历全树历史空闲节点。
   */
  private static boolean isQuiescent(HarnessStore.Transaction tx, ThreadState child) {
    return tx.countIncompleteChildJoins(child.id()) == 0
        && tx.loadPendingDeliveries(child.id()).isEmpty()
        && !ThreadInputDemand.hasInputDemand(tx, child);
  }

  /**
   * 对一批刚冻结的 Join 执行父 Thread 交付：构造 NOTIFICATION 命令并预留父序列；父为 RUNNABLE 时请求 THREAD wake，父为 STOPPED 时把
   * 通知直接固化到历史而不唤醒模型。root ticket（parentThreadId 为空）不投递父通知。
   */
  private static void deliverMatchedJoins(
      HarnessStore.Transaction tx, List<ThreadJoin> matched, Instant now) {
    Map<UUID, ThreadState> parents = new HashMap<>();
    Map<UUID, Long> nextSequence = new HashMap<>();
    Map<UUID, Integer> deliveryCounts = new HashMap<>();
    Map<UUID, List<ThreadJoinCompletion.Delivery>> deliveriesByParent = new HashMap<>();
    for (ThreadJoin join : matched) {
      UUID parentId = join.parentThreadId();
      if (parentId == null) {
        continue;
      }
      ThreadState parent = parents.get(parentId);
      if (parent == null) {
        parent =
            tx.findThread(parentId)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "parent thread " + parentId + " not found while delivering join"));
        parents.put(parentId, parent);
        nextSequence.put(parentId, parent.nextCommandSequence());
      }
      long sequence = nextSequence.get(parentId);
      ThreadJoinCompletion.Delivery delivery =
          ThreadJoinCompletion.buildDelivery(tx, join, parent, sequence, now);
      deliveriesByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(delivery);
      nextSequence.put(parentId, sequence + 1);
      deliveryCounts.merge(parentId, 1, Integer::sum);
    }
    if (deliveriesByParent.isEmpty()) {
      return;
    }
    List<ThreadCommand> commands = new ArrayList<>();
    for (List<ThreadJoinCompletion.Delivery> deliveries : deliveriesByParent.values()) {
      for (ThreadJoinCompletion.Delivery delivery : deliveries) {
        commands.add(delivery.command());
      }
    }
    tx.insertCommands(commands);
    for (List<ThreadJoinCompletion.Delivery> deliveries : deliveriesByParent.values()) {
      for (ThreadJoinCompletion.Delivery delivery : deliveries) {
        tx.updateJoin(delivery.delivered());
      }
    }
    for (Map.Entry<UUID, List<ThreadJoinCompletion.Delivery>> entry :
        deliveriesByParent.entrySet()) {
      ThreadState parent = parents.get(entry.getKey());
      int count = deliveryCounts.get(entry.getKey());
      Instant parentNow = HarnessStoreTime.notBefore(now, parent.updatedAt());
      List<ThreadJoinCompletion.Delivery> deliveries = entry.getValue();
      if (parent.executionControl().isStopped()) {
        materializeNotifications(tx, parent, deliveries, count, parentNow);
      } else {
        ThreadState advanced = parent.reserveCommandSequences(count, parentNow);
        tx.updateThread(advanced);
        tx.requestWork(new WorkTarget(WorkTargetType.THREAD, parent.id()), now);
      }
    }
  }

  /** 已停止父 Thread：把交付通知按序列顺序直接追加为历史 NOTIFICATION Entry 并标记命令已应用（不唤醒模型）。 */
  private static void materializeNotifications(
      HarnessStore.Transaction tx,
      ThreadState parent,
      List<ThreadJoinCompletion.Delivery> deliveries,
      int count,
      Instant now) {
    List<ThreadCommand> applied = new ArrayList<>(deliveries.size());
    UUID head = parent.headEntryId();
    for (ThreadJoinCompletion.Delivery delivery : deliveries) {
      NotificationCommandPayload payload =
          (NotificationCommandPayload) delivery.command().payload();
      UUID entryId = tx.nextId();
      tx.insertEntry(
          new Entry(
              entryId,
              parent.sessionId(),
              head,
              new NotificationPayload(
                  payload.notificationId(),
                  payload.kind(),
                  payload.sourceThreadId(),
                  payload.message()),
              now));
      applied.add(delivery.command().markApplied(entryId));
      head = entryId;
    }
    tx.updateCommands(applied);
    ThreadState advanced = parent.reserveCommandSequencesAndAdvanceHead(count, head, now);
    tx.updateThread(advanced);
  }

  /** 在已确定继续运行的边界把 max-turn 短预算提醒作为 NOTIFICATION 物化到历史（按 Join 幂等，不分配邮箱 sequence）。 */
  public ThreadState remindSoftBudgetIfDue(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path, Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(now, "now");

    List<ThreadJoin> joins = tx.loadIncompleteJoins(thread.id());
    if (joins.isEmpty()) {
      return thread;
    }
    ThreadState current = thread;
    EntryPath currentPath = path;
    for (ThreadJoin join : joins) {
      if (join.maxTurns() == null || join.maxTurns() <= 0 || join.reminderTurn() > 0) {
        continue;
      }
      int actualTurns = countActualTurns(tx, currentPath, join);
      if (actualTurns < join.maxTurns()) {
        continue;
      }
      UUID notificationId =
          UUID.nameUUIDFromBytes(
              (DEFAULT_MAX_TURNS_REMINDER_TAG + ":" + join.invocationId())
                  .getBytes(StandardCharsets.UTF_8));
      if (containsNotification(currentPath, notificationId)) {
        continue;
      }
      Instant mutationNow =
          HarnessStoreTime.notBefore(now, current.updatedAt(), currentPath.head().createdAt());
      UUID entryId = tx.nextId();
      Entry entry =
          new Entry(
              entryId,
              current.sessionId(),
              currentPath.head().id(),
              new NotificationPayload(
                  notificationId,
                  NotificationKind.TASK_BUDGET,
                  join.childThreadId(),
                  new AgentMessage(
                      AgentMessageRole.USER,
                      List.of(new TextMessageContent(DEFAULT_MAX_TURNS_REMINDER_TEXT)))),
              mutationNow);
      tx.insertEntry(entry);
      current = current.advanceHead(entryId, mutationNow);
      tx.updateThread(current);
      List<Entry> extended = new ArrayList<>(currentPath.entries());
      extended.add(entry);
      currentPath = new EntryPath(extended);
      Instant joinNow = HarnessStoreTime.notBefore(mutationNow, join.updatedAt());
      tx.updateJoin(join.remind(actualTurns, joinNow));
    }
    return current;
  }

  private static boolean containsNotification(EntryPath path, UUID notificationId) {
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof NotificationPayload notification
          && notification.notificationId().equals(notificationId)) {
        return true;
      }
    }
    return false;
  }

  /** 从 join 源命令的已应用 Entry 起计模型工作轮，忽略 COMPACTION / STOP。 */
  public static int countActualTurns(HarnessStore.Transaction tx, EntryPath path, ThreadJoin join) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(join, "join");

    ThreadCommand sourceCommand =
        tx.findCommand(join.childThreadId(), join.sourceCommandSequence()).orElse(null);
    if (sourceCommand == null || sourceCommand.appliedEntryId() == null) {
      return 0;
    }
    UUID appliedStart = sourceCommand.appliedEntryId();
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
