package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinCompletion;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * HarnessRuntime 内部的同步 acceptCommands 控制面。
 *
 * <p>承担命令接受（NEW_SESSION / NEW_THREAD / THREAD 三条路径）、initial creation replay 与 ordered replay、 batch
 * shape 校验、fork 边界 admission、cursor admission 校验以及向 Store 写入 Session / ROOT / Thread / Commands /
 * Work。 Session 与 ROOT Thread 的初始名称在创建事务内由首条 user-like 文本派生（缺省回退到 id 前缀）；NEW_THREAD 的分支名称由调用方给出并进入
 * creation request hash；后期改名走独立的 rename 控制面。
 */
final class AcceptCommandsControl {

  /**
   * SET_* prefix 的固定顺序：SET_AGENT -&gt; SET_MODEL -&gt; SET_ENVIRONMENT -&gt;
   * SET_CONTRIBUTOR_STATE，每类至多一次、全部在消息之前。
   */
  private static final List<ThreadCommandType> SET_PREFIX_ORDER =
      List.of(
          ThreadCommandType.SET_AGENT,
          ThreadCommandType.SET_MODEL,
          ThreadCommandType.SET_ENVIRONMENT,
          ThreadCommandType.SET_CONTRIBUTOR_STATE);

  private final HarnessStore store;
  private final Clock clock;

  AcceptCommandsControl(HarnessStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  AcceptedCommands acceptCommands(AcceptCommandsCommand command, AcceptancePreflight preflight) {
    return acceptCommands(command, null, preflight);
  }

  AcceptedCommands acceptCommandsAndJoin(
      AcceptCommandsCommand command, ThreadJoinRequest join, AcceptancePreflight preflight) {
    Objects.requireNonNull(join, "join");
    return acceptCommands(command, join, preflight);
  }

  private AcceptedCommands acceptCommands(
      AcceptCommandsCommand command, ThreadJoinRequest join, AcceptancePreflight preflight) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(preflight, "preflight");
    List<NewThreadCommand> commands = command.commands();
    return switch (command.target()) {
      case AcceptCommandsTarget.NewRootSession target -> acceptNewRootSession(
          target, commands, join, preflight);
      case AcceptCommandsTarget.NewChildSession target -> acceptNewChildSession(
          target, commands, join, preflight);
      case AcceptCommandsTarget.NewThread target -> acceptNewThread(
          target, commands, join, preflight);
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, join, preflight);
    };
  }

  private static final class LockedAncestors {
    final List<UUID> chain;
    final Map<UUID, ThreadState> threads;

    LockedAncestors(List<UUID> chain, Map<UUID, ThreadState> threads) {
      this.chain = chain;
      this.threads = threads;
    }
  }

  /**
   * NEW_ROOT_SESSION：在树锁内以调用方给定的根开关创建独立执行根 Thread；从 root settings 初始化 Session 与 ROOT Entry。
   * 根开关只初始化该根自身，不来自任何既有执行树。
   */
  private AcceptedCommands acceptNewRootSession(
      AcceptCommandsTarget.NewRootSession target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return acceptNewSession(
        target.sessionId(),
        target.threadId(),
        target.rootSettings(),
        null,
        target.yoloEnabled(),
        commands,
        join,
        preflight);
  }

  /**
   * NEW_CHILD_SESSION：在树锁内派生真实执行根，创建执行子代理 Thread 并写入不可变的 {@code FOLLOW(rootThreadId)}。调用方不提供任何 YOLO
   * 输入，Follow 目标绝不由调用者指定。子 Session 准入沿用同一全局 Join 准入锁与树锁顺序。
   */
  private AcceptedCommands acceptNewChildSession(
      AcceptCommandsTarget.NewChildSession target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return acceptNewSession(
        target.sessionId(),
        target.threadId(),
        target.rootSettings(),
        target.parentThreadId(),
        null,
        commands,
        join,
        preflight);
  }

  /**
   * NEW_ROOT_SESSION / NEW_CHILD_SESSION 的共性创建：{@code parentThreadId} 为空表示独立执行根，使用 {@code
   * rootYoloEnabled} 初始化自身开关；否则在树锁内把 {@code parentThreadId} 的真实执行根派生为不可变 {@code
   * FOLLOW(rootThreadId)}。creation request hash 由稳定 Follow 目标派生，不冻结解析时的 effective 开关值。
   */
  private AcceptedCommands acceptNewSession(
      UUID sessionId,
      UUID threadId,
      BranchSettings rootSettings,
      UUID parentThreadId,
      Boolean rootYoloEnabled,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          // 携带 frozen task 策略的子 Session 准入必须先取全局 Join 准入锁（即使本次额度 unlimited），
          // 否则并发的有限额度请求会在“判定额度 + 创建”之间竞态。
          if (parentThreadId != null) {
            tx.lockJoinAdmission();
          }
          // 子身份必须在树锁内复读：并发重放可能在等待锁期间才看到第一次接受。
          LockedAncestors lockedAncestors =
              parentThreadId != null
                  ? lockTreeAndAncestors(tx, parentThreadId, true, Set.of(threadId))
                  : lockTreeAndAncestors(tx, threadId, false, null);
          ThreadYoloPolicy yoloPolicy;
          if (parentThreadId == null) {
            yoloPolicy = ThreadYoloPolicy.root(rootYoloEnabled);
          } else {
            UUID executionRootId = validateLockedAncestorsYolo(lockedAncestors);
            yoloPolicy = ThreadYoloPolicy.follow(executionRootId);
          }
          String creationRequestHash =
              ThreadCreationRequestHash.forNewSession(
                  sessionId, threadId, rootSettings, parentThreadId, yoloPolicy, commands);
          ThreadState existing = lockedAncestors.threads.get(threadId);
          if (existing != null) {
            return attachJoin(
                tx,
                replayInitial(tx, sessionId, threadId, existing, commands, creationRequestHash),
                join);
          }
          admitJoin(tx, threadId, parentThreadId, join, true);
          Instant now = clock.instant();
          Session session = new Session(sessionId, initialSessionName(commands, sessionId), now);
          tx.insertSession(session);
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(rootSettings), now));
          ThreadState thread =
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  rootEntryId,
                  creationRequestHash,
                  Names.rootThreadName(),
                  yoloPolicy,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.insertThread(thread);

          return attachJoin(
              tx, acceptNewCommandsOnThread(tx, thread, commands, preflight, now), join);
        });
  }

  /**
   * 子 Session 创建边界的一致性校验：已锁定的祖先链必须与链末位真实执行根一致——根自身不得 FOLLOW，任何祖先的不可变 Follow 目标必须等于该根。 祖先链存在历史错根
   * policy 时立即 fail closed，避免在损坏树上静默创建子代理并扩散错误。只使用当前事务已锁定的祖先结果，不额外加锁。
   */
  private static UUID validateLockedAncestorsYolo(LockedAncestors lockedAncestors) {
    List<UUID> chain = lockedAncestors.chain;
    UUID executionRootId = chain.get(chain.size() - 1);
    ThreadState executionRoot =
        Objects.requireNonNull(
            lockedAncestors.threads.get(executionRootId), "locked execution root");
    if (executionRoot.yoloPolicy().isFollow()) {
      throw new IllegalStateException(
          "execution root " + executionRootId + " must not follow another thread");
    }
    for (UUID ancestorId : chain) {
      ThreadState ancestor =
          Objects.requireNonNull(lockedAncestors.threads.get(ancestorId), "locked ancestor thread");
      if (ancestor.yoloPolicy().isFollow()
          && !executionRootId.equals(ancestor.yoloPolicy().rootThreadId())) {
        throw new IllegalStateException(
            "thread "
                + ancestorId
                + " follows "
                + ancestor.yoloPolicy().rootThreadId()
                + " which is not its execution root "
                + executionRootId);
      }
    }
    return executionRootId;
  }

  /**
   * NEW_THREAD：在既有 Session 的合法 fork 边界独立 fork 新执行根。合法边界只有 ROOT 或已闭合 {@code TURN_END}（最新的闭合 {@code
   * TURN_END} 同样合法）；任何 active / 半轮 / 未闭合前缀（USER、tool result、ASSISTANT message、仍在进行的 TURN_START 或
   * STOP barrier）一律确定性拒绝且零写入。
   *
   * <p>不移动源 Thread、不补写任何 synthetic closure / HISTORY_CUT，也不复制 Entry：新 Thread 的 head 直接指向该边界。分支显示名取自
   * target，与 target 的预分配 id 一起进入 creation request hash，并在创建事务内与 Thread 行原子写入。
   */
  private AcceptedCommands acceptNewThread(
      AcceptCommandsTarget.NewThread target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    ThreadYoloPolicy yoloPolicy = ThreadYoloPolicy.root(target.yoloEnabled());
    return store.transaction(
        tx -> {
          // 与创建执行根相同：复用既有 Thread id 时先按真实执行树完成规范加锁，避免 replay 阶段逆序补锁。
          LockedAncestors locked = lockTreeAndAncestors(tx, target.threadId(), false, null);
          ThreadState existing = locked.threads.get(target.threadId());
          if (existing != null) {
            String creationRequestHash =
                ThreadCreationRequestHash.forNewThread(
                    target.sessionId(),
                    target.startEntryId(),
                    target.threadId(),
                    target.threadName(),
                    yoloPolicy,
                    commands);
            return attachJoin(
                tx,
                replayInitial(
                    tx,
                    target.sessionId(),
                    target.threadId(),
                    existing,
                    commands,
                    creationRequestHash),
                join);
          }
          admitJoin(tx, target.threadId(), null, join, true);
          // NEW_THREAD 新建路径用 KEY SHARE：不串行化同 Session 的 sibling 初始创建。
          tx.lockSessionForKeyShare(target.sessionId())
              .orElseThrow(
                  () ->
                      new HarnessRuntimeNotFoundException(
                          "session " + target.sessionId() + " does not exist"));
          Entry startEntry =
              tx.findEntry(target.startEntryId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "entry " + target.startEntryId() + " does not exist"));
          if (!startEntry.sessionId().equals(target.sessionId())) {
            throw new IllegalArgumentException(
                "start entry "
                    + target.startEntryId()
                    + " is not in session "
                    + target.sessionId());
          }
          requireLegalForkBoundary(startEntry);
          String creationRequestHash =
              ThreadCreationRequestHash.forNewThread(
                  target.sessionId(),
                  target.startEntryId(),
                  target.threadId(),
                  target.threadName(),
                  yoloPolicy,
                  commands);
          Instant now = clock.instant();
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  null,
                  target.startEntryId(),
                  creationRequestHash,
                  target.threadName(),
                  yoloPolicy,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return attachJoin(
              tx, acceptNewCommandsOnThread(tx, thread, commands, preflight, now), join);
        });
  }

  /**
   * NEW_THREAD 的合法 fork 边界：只有 ROOT 或已闭合 {@code TURN_END}。其它位置都是 active / 半轮 / 未闭合前缀，必须原子拒绝；这里只按
   * start Entry 自身判定，不写源 Thread、不补写 synthetic closure，也不做任何 source Thread 推断。
   */
  private static void requireLegalForkBoundary(Entry startEntry) {
    if (startEntry.payload().type().isRoot() || startEntry.payload() instanceof TurnEndPayload) {
      return;
    }
    throw new IllegalArgumentException(
        "start entry "
            + startEntry.id()
            + " is not a legal NEW_THREAD fork boundary (ROOT or a closed TURN_END)");
  }

  private AcceptedCommands acceptOnThread(
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          // 携带 frozen task 策略的 resume 准入同样必须先取全局 Join 准入锁（即使额度 unlimited）。
          if (join != null && join.parentThreadId() != null) {
            tx.lockJoinAdmission();
          }
          boolean genuineUserInput = isGenuineUserInput(commands);
          LockedAncestors locked = lockTreeAndAncestors(tx, target.threadId(), false, null);
          List<ThreadJoin> pending = tx.loadPendingDeliveries(target.threadId());
          ThreadState thread = locked.threads.get(target.threadId());
          if (thread == null) {
            throw new HarnessRuntimeNotFoundException(
                "thread " + target.threadId() + " does not exist");
          }
          // exact replay 查找必须先于 cursor/preflight admission。
          List<Optional<ThreadCommand>> existing = new ArrayList<>(commands.size());
          for (NewThreadCommand request : commands) {
            existing.add(
                tx.findCommandByIdempotencyKey(target.threadId(), request.idempotencyKey()));
          }
          long present = existing.stream().filter(Optional::isPresent).count();
          if (present > 0 && present < existing.size()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                "batch on thread "
                    + target.threadId()
                    + " replays only "
                    + present
                    + " of "
                    + existing.size()
                    + " commands");
          }
          if (present == existing.size()) {
            return attachJoin(tx, replayThreadBatch(tx, target, commands, thread, existing), join);
          }
          validateThreadBatchAdmission(target, thread);
          validateBatchShape(target, commands, false);
          if (join != null && !genuineUserInput) {
            // Join 交付只会追加 NOTIFICATION；唤醒父 Thread 必须同时携带真实的末尾任务输入。
            throw invalidBatch(target, "join acceptance requires a trailing task input");
          }
          admitJoin(tx, thread.id(), thread.parentThreadId(), join, false);
          Instant now = clock.instant();
          Session session =
              tx.findSession(thread.sessionId())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "session "
                                  + thread.sessionId()
                                  + " disappeared while thread existed"));
          List<NewThreadCommand> prepared = preflight.prepare(tx, session, commands);
          requirePreflightShape(commands, prepared);
          Instant parentMutationNow = effectiveMutationTime(now, thread);
          long nextSeq = thread.nextCommandSequence();
          List<ThreadCommand> allInserted = new ArrayList<>();
          List<ThreadJoin> deliveries = new ArrayList<>();

          if (genuineUserInput && !pending.isEmpty()) {
            for (ThreadJoin pendingJoin : pending) {
              ThreadJoinCompletion.Delivery delivery =
                  ThreadJoinCompletion.buildDelivery(
                      tx, pendingJoin, thread, nextSeq, parentMutationNow);
              allInserted.add(delivery.command());
              deliveries.add(delivery.delivered());
              nextSeq++;
            }
          }

          List<ThreadCommand> userCommands = new ArrayList<>(prepared.size());
          for (int i = 0; i < prepared.size(); i++) {
            NewThreadCommand request = prepared.get(i);
            userCommands.add(
                new ThreadCommand(
                    thread.id(),
                    nextSeq + i,
                    request.payload(),
                    request.idempotencyKey(),
                    request.requestHash(),
                    null,
                    null,
                    null,
                    parentMutationNow));
          }
          allInserted.addAll(userCommands);

          tx.insertCommands(allInserted);
          for (ThreadJoin delivered : deliveries) {
            tx.updateJoin(delivered);
          }

          ThreadState advanced =
              resumeStoppedTarget(thread, genuineUserInput)
                  ? thread.reserveCommandSequencesAndSetControl(
                      allInserted.size(), ThreadExecutionControl.RUNNABLE, parentMutationNow)
                  : thread.reserveCommandSequences(allInserted.size(), parentMutationNow);
          tx.updateThread(advanced);

          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
          return attachJoin(
              tx,
              new AcceptedCommands(
                  session,
                  requireRootEntry(tx, session.id()),
                  advanced,
                  List.copyOf(userCommands),
                  false),
              join);
        });
  }

  private static LockedAncestors lockTreeAndAncestors(
      HarnessStore.Transaction tx,
      UUID anchorThreadId,
      boolean anchorIsParent,
      Set<UUID> additionalThreadIds) {
    List<UUID> hint = tx.findAncestorChain(anchorThreadId);
    if (anchorIsParent && hint.isEmpty()) {
      throw new IllegalArgumentException("join parent does not exist");
    }
    UUID root = hint.isEmpty() ? anchorThreadId : hint.get(hint.size() - 1);
    tx.lockTree(root);
    List<UUID> chain = tx.findAncestorChain(anchorThreadId);
    if (anchorIsParent && chain.isEmpty()) {
      throw new HarnessRuntimeNotFoundException("join parent no longer exists");
    }
    if (!chain.isEmpty() && !root.equals(chain.get(chain.size() - 1))) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED,
          "thread execution parent differs from the requested tree");
    }
    Set<UUID> allThreadIds = new LinkedHashSet<>(chain);
    if (additionalThreadIds != null) {
      for (UUID id : additionalThreadIds) {
        ThreadState existing = tx.findThread(id).orElse(null);
        if (existing != null) {
          if (!Objects.equals(existing.parentThreadId(), anchorThreadId)) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED,
                "thread " + id + " already belongs to another execution parent");
          }
          allThreadIds.add(id);
        }
      }
    }
    // 先在树锁内确定完整行锁集合，再按 Session/Thread 排序；不能在 Work 后补锁交付子线程。
    for (ThreadJoin pending : tx.loadPendingDeliveries(anchorThreadId)) {
      allThreadIds.add(pending.childThreadId());
    }
    Map<UUID, ThreadState> immutableThreads = new HashMap<>();
    Set<UUID> sessionIds = new LinkedHashSet<>();
    for (UUID id : allThreadIds) {
      ThreadState state =
          tx.findThread(id)
              .orElseThrow(
                  () ->
                      anchorIsParent && chain.contains(id)
                          ? new IllegalArgumentException("join parent does not exist")
                          : new HarnessRuntimeNotFoundException(
                              "thread " + id + " does not exist"));
      immutableThreads.put(id, state);
      sessionIds.add(state.sessionId());
    }
    List<UUID> sortedSessionIds = sessionIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sortedSessionIds) {
      tx.lockSessionForKeyShare(sessionId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "session " + sessionId + " disappeared while thread existed"));
    }
    List<UUID> sortedThreadIds = allThreadIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID id : sortedThreadIds) {
      ThreadState locked =
          tx.lockThread(id)
              .orElseThrow(
                  () ->
                      anchorIsParent && chain.contains(id)
                          ? new IllegalArgumentException("join parent does not exist")
                          : new HarnessRuntimeNotFoundException(
                              "thread " + id + " does not exist"));
      if (!locked.sessionId().equals(immutableThreads.get(id).sessionId())) {
        throw new IllegalStateException(
            "thread " + id + " relocated while acquiring its session lock");
      }
      lockedThreads.put(id, locked);
    }
    return new LockedAncestors(chain, lockedThreads);
  }

  private static void admitJoin(
      HarnessStore.Transaction tx,
      UUID childId,
      UUID parentId,
      ThreadJoinRequest join,
      boolean creating) {
    if (join == null) {
      if (creating && parentId != null) {
        throw new IllegalArgumentException("executing child requires atomic join acceptance");
      }
      return;
    }
    if (!Objects.equals(join.parentThreadId(), parentId)) {
      throw new IllegalArgumentException("join parent differs from immutable child parent");
    }
    List<UUID> chain = tx.findAncestorChain(creating && parentId != null ? parentId : childId);
    if (parentId != null) {
      ThreadState parent =
          tx.findThread(parentId)
              .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
      // 停止父 Thread 不再接受新的执行子任务：不能只依赖 head 变化，STOPPED 是持久的拒绝条件。
      if (parent.executionControl().isStopped()) {
        throw new IllegalArgumentException("join parent is stopped");
      }
      if (!parent.headEntryId().equals(join.expectedParentHeadEntryId())) {
        throw new IllegalArgumentException("join parent no longer accepts this invocation");
      }
      // task 配额按未完成 parent Join 计数：本次准入将新增一个未完成 Join。
      if (tx.countIncompleteChildJoins(parentId) >= join.maxConcurrentChildren()) {
        throw new IllegalArgumentException("parent join quota exceeded");
      }
    }
    int depth = creating ? chain.size() + 1 : chain.size();
    if (depth > join.maxDepth()) {
      throw new IllegalArgumentException("join depth quota exceeded");
    }
    // 全局未完成执行子 Join 上限（跨所有 root，不含 root ticket）：maxConcurrentThreads 来自冻结的 task 全局设置。
    if (parentId != null && tx.countIncompleteSubagentJoins() >= join.maxConcurrentThreads()) {
      throw new IllegalArgumentException("subagent concurrency quota exceeded");
    }
  }

  private static AcceptedCommands attachJoin(
      HarnessStore.Transaction tx, AcceptedCommands accepted, ThreadJoinRequest join) {
    if (join == null) {
      return accepted;
    }
    ThreadJoin existing = tx.findJoin(join.invocationId()).orElse(null);
    if (existing != null) {
      if (!accepted.replayed()
          || !existing.childThreadId().equals(accepted.thread().id())
          || !Objects.equals(existing.parentThreadId(), join.parentThreadId())
          || !existing.requestHash().equals(join.requestHash())
          || !existing.agent().equals(join.agent())
          || !Objects.equals(existing.maxTurns(), join.maxTurns())
          || accepted.acceptedCommands().stream()
              .noneMatch(cmd -> cmd.sequence() == existing.sourceCommandSequence())) {
        throw new IllegalArgumentException("join invocation identity reused");
      }
      return accepted;
    }
    if (accepted.replayed()) {
      throw new IllegalArgumentException("source commands replayed without their join");
    }
    ThreadCommand source = accepted.acceptedCommands().get(accepted.acceptedCommands().size() - 1);
    tx.insertJoin(
        new ThreadJoin(
            join.invocationId(),
            join.requestHash(),
            join.parentThreadId(),
            accepted.thread().id(),
            source.sequence(),
            join.agent(),
            join.maxTurns(),
            0,
            null,
            null,
            null,
            accepted.thread().updatedAt(),
            accepted.thread().updatedAt()));
    return accepted;
  }

  /** 全新 batch 的共性写入：preflight、插入 Commands、推进 version/next sequence、请求 THREAD Work。 */
  private static AcceptedCommands acceptNewCommandsOnThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<NewThreadCommand> requests,
      AcceptancePreflight preflight,
      Instant now) {
    Session session =
        tx.findSession(thread.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + thread.sessionId() + " disappeared while thread existed"));
    List<NewThreadCommand> prepared = preflight.prepare(tx, session, requests);
    requirePreflightShape(requests, prepared);
    List<ThreadCommand> inserted = new ArrayList<>(prepared.size());
    long nextSequence = thread.nextCommandSequence();
    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = prepared.get(i);
      inserted.add(
          new ThreadCommand(
              thread.id(),
              nextSequence + i,
              request.payload(),
              request.idempotencyKey(),
              request.requestHash(),
              null,
              null,
              null,
              now));
    }
    tx.insertCommands(inserted);
    ThreadState advanced = thread.reserveCommandSequences(prepared.size(), now);
    tx.updateThread(advanced);
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    return new AcceptedCommands(
        session, requireRootEntry(tx, session.id()), advanced, List.copyOf(inserted), false);
  }

  /**
   * NEW_SESSION / NEW_THREAD：同 creation request hash + 同 Session 的 client threadId 精确 replay。
   *
   * <p>{@code immutable} 快照只用于在上锁前定位 Session；随后按规范锁序 KEY SHARE Session -&gt; FOR UPDATE Thread
   * 复核后返回当前 projection（禁止混合 unlocked snapshot）。只按本请求 idempotencyKey 顺序重放原始初始命令，验证 requestHash 相等且
   * sequence 从 1 连续，绝不返回该 Thread 后续批次的历史命令。replay 读取的是当前 Thread / Session 行，因此即便其后发生过
   * rename，返回的名称也是当前名称。
   */
  private static AcceptedCommands replayInitial(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID threadId,
      ThreadState immutable,
      List<NewThreadCommand> requests,
      String creationRequestHash) {
    if (!immutable.sessionId().equals(sessionId)
        || !immutable.creationRequestHash().equals(creationRequestHash)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED,
          "thread "
              + threadId
              + " is already associated with a different initial creation request (session "
              + immutable.sessionId()
              + ")");
    }
    tx.lockSessionForKeyShare(immutable.sessionId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "session " + immutable.sessionId() + " disappeared while thread existed"));
    ThreadState thread =
        tx.lockThread(threadId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist"));
    if (!thread.sessionId().equals(sessionId)
        || !thread.creationRequestHash().equals(creationRequestHash)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED,
          "thread "
              + threadId
              + " is already associated with a different initial creation request (session "
              + thread.sessionId()
              + ")");
    }
    List<ThreadCommand> ordered = new ArrayList<>(requests.size());
    for (NewThreadCommand request : requests) {
      ThreadCommand existing =
          tx.findCommandByIdempotencyKey(threadId, request.idempotencyKey())
              .orElseThrow(
                  () ->
                      conflict(
                          HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                          "initial batch on thread "
                              + threadId
                              + " is missing idempotencyKey "
                              + request.idempotencyKey()));
      if (!existing.requestHash().equals(request.requestHash())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.IDEMPOTENCY_KEY_REUSED,
            "idempotencyKey "
                + request.idempotencyKey()
                + " is reused with a different request hash on thread "
                + threadId);
      }
      ordered.add(existing);
    }
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != i + 1L) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "initial commands on thread "
                + threadId
                + " must start at sequence 1 and be contiguous in the request order");
      }
    }
    Session session =
        tx.findSession(sessionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + sessionId + " disappeared while thread existed"));
    return new AcceptedCommands(
        session, requireRootEntry(tx, sessionId), thread, List.copyOf(ordered), true);
  }

  /**
   * Orderly command-set replay：先比较 requestHash（独立于 durable payload 形态），再按请求顺序校验 sequence 连续且首
   * sequence 等于请求的 expected next sequence；重放返回当前 Thread projection，不写任何行。
   */
  private static AcceptedCommands replayThreadBatch(
      HarnessStore.Transaction tx,
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      ThreadState thread,
      List<Optional<ThreadCommand>> found) {
    List<ThreadCommand> ordered = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      ThreadCommand existing = found.get(i).orElseThrow();
      NewThreadCommand request = commands.get(i);
      if (!existing.requestHash().equals(request.requestHash())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.IDEMPOTENCY_KEY_REUSED,
            "idempotencyKey "
                + request.idempotencyKey()
                + " is reused with a different request hash on thread "
                + target.threadId());
      }
      ordered.add(existing);
    }
    long firstSequence = ordered.get(0).sequence();
    if (firstSequence != target.expectedNextCommandSequence()) {
      long expected = target.expectedNextCommandSequence();
      boolean allPrecedingWereDeliveries = true;
      if (firstSequence > expected) {
        for (long seq = expected; seq < firstSequence; seq++) {
          long checkSeq = seq;
          boolean isDelivery =
              tx.findCommand(target.threadId(), checkSeq)
                  .flatMap(c -> tx.findJoin(c.idempotencyKey()))
                  .map(
                      j ->
                          target.threadId().equals(j.parentThreadId())
                              && Objects.equals(j.deliveryCommandSequence(), checkSeq))
                  .orElse(false);
          if (!isDelivery) {
            allPrecedingWereDeliveries = false;
            break;
          }
        }
      } else {
        allPrecedingWereDeliveries = false;
      }
      if (!allPrecedingWereDeliveries) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "existing commands on thread "
                + target.threadId()
                + " start at sequence "
                + firstSequence
                + " while the request expected "
                + target.expectedNextCommandSequence());
      }
    }
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != Math.addExact(firstSequence, (long) i)) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "existing commands on thread "
                + target.threadId()
                + " are not contiguous in the request order");
      }
    }
    Session session =
        tx.findSession(thread.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + thread.sessionId() + " disappeared while thread existed"));
    return new AcceptedCommands(
        session, requireRootEntry(tx, thread.sessionId()), thread, List.copyOf(ordered), true);
  }

  private static Instant effectiveMutationTime(Instant now, ThreadState thread) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(thread.updatedAt()) ? thread.updatedAt() : candidate;
  }

  /** 任何显式新 USER/GOAL/CUSTOM 可信输入都把 STOPPED 目标恢复为 RUNNABLE；不复活任何后代。 */
  private static boolean resumeStoppedTarget(ThreadState thread, boolean genuineUserInput) {
    return genuineUserInput && thread.executionControl().isStopped();
  }

  /** 可信任务输入：USER_MESSAGE、GOAL、CUSTOM_MESSAGE；系统通知只经 NOTIFICATION Command，不是输入来源。 */
  private static boolean isGenuineUserInput(List<NewThreadCommand> commands) {
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof UserMessageCommandPayload
          || command.payload() instanceof GoalCommandPayload
          || command.payload() instanceof CustomMessageCommandPayload) {
        return true;
      }
    }
    return false;
  }

  /**
   * 派生初始 Session 名称：只检查 validate 后的末尾 user-like message（初始 batch 恰以一条 user-like 结尾），取该消息第一个非空白
   * 文本内容（仅 text 内容，不看附件 / resource 名称），规范化折叠为单行并取前 40 个 Unicode 码点（无省略号）；typed GOAL 用其目标正文
   * 派生；无文本时回退为 {@code session-} + session UUID 前 8 位。
   */
  private static String initialSessionName(List<NewThreadCommand> commands, UUID sessionId) {
    NewThreadCommand trailing = commands.get(commands.size() - 1);
    if (trailing.payload() instanceof GoalCommandPayload goal) {
      String name = Names.sessionNameFromUserText(goal.text());
      return name == null ? Names.defaultSessionName(sessionId) : name;
    }
    AgentMessage message = userMessage(trailing);
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        String name = Names.sessionNameFromUserText(text.text());
        if (name != null) {
          return name;
        }
      }
    }
    return Names.defaultSessionName(sessionId);
  }

  private static AgentMessage userMessage(NewThreadCommand command) {
    if (command.payload() instanceof UserMessageCommandPayload payload) {
      return payload.message();
    }
    return ((CustomMessageCommandPayload) command.payload()).message();
  }

  private static Entry requireRootEntry(HarnessStore.Transaction tx, UUID sessionId) {
    return tx.findRootEntry(sessionId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "root entry for session " + sessionId + " disappeared while session existed"));
  }

  private static void requirePreflightShape(
      List<NewThreadCommand> requests, List<NewThreadCommand> prepared) {
    if (prepared == null) {
      throw new IllegalStateException("command preflight returned null");
    }
    if (prepared.size() != requests.size()) {
      throw new IllegalStateException(
          "command preflight must return exactly "
              + requests.size()
              + " commands, got "
              + prepared.size());
    }
    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = requests.get(i);
      NewThreadCommand result = prepared.get(i);
      if (result == null
          || !result.idempotencyKey().equals(request.idempotencyKey())
          || !result.requestHash().equals(request.requestHash())) {
        throw new IllegalStateException(
            "command preflight must preserve idempotencyKey and requestHash at index " + i);
      }
    }
  }

  /**
   * THREAD 全新 batch 的 admission：stale cursor（head / next sequence 不匹配）必须确定性拒绝。SET_*（含
   * SET_ENVIRONMENT）只入队、由 Reducer 于下一个 INPUT 边界收割，不参与 admission——即使 Thread 处于 live Model / Tool 或有
   * queued 消息 / THREAD Work 也照常接受。
   */
  private static void validateThreadBatchAdmission(
      AcceptCommandsTarget.Thread target, ThreadState thread) {
    if (!thread.headEntryId().equals(target.expectedHeadEntryId())
        || thread.nextCommandSequence() != target.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR,
          "thread "
              + thread.id()
              + " head/next command sequence does not match the batch expectation");
    }
  }

  /**
   * 命令 batch 的 shape admission：SET_* 必须以固定顺序（SET_AGENT -&gt; SET_MODEL -&gt; SET_ENVIRONMENT -&gt;
   * SET_CONTRIBUTOR_STATE）、至多一次且全部出现在消息之前。
   *
   * <p>创建类 target（NEW_SESSION / NEW_THREAD）要求<b>恰有一条</b>末尾 user-like 输入；THREAD target 允许
   * settings-only batch（0 条 user-like），最多一条 user-like 且必须位于末尾。非法 batch 是请求校验错误，抛 {@link
   * IllegalArgumentException} 而非业务冲突。
   */
  private static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands) {
    validateBatchShape(target, commands, true);
  }

  private static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands, boolean requireUserLike) {
    int userLikeCount = 0;
    int lastSetOrder = -1;
    boolean sawMessage = false;
    for (NewThreadCommand command : commands) {
      ThreadCommandPayload payload = command.payload();
      if (payload.type().isSetting()) {
        if (sawMessage) {
          throw invalidBatch(target, "SET_* commands must precede all messages");
        }
        int order = SET_PREFIX_ORDER.indexOf(payload.type());
        if (order <= lastSetOrder) {
          throw invalidBatch(
              target, "SET_* prefix must use the fixed order and each type at most once");
        }
        lastSetOrder = order;
        continue;
      }
      sawMessage = true;
      if (isUserLike(command)) {
        userLikeCount++;
      }
    }
    boolean trailingUserLike = !commands.isEmpty() && isUserLike(commands.get(commands.size() - 1));
    if (userLikeCount > 1
        || (requireUserLike && (userLikeCount != 1 || !trailingUserLike))
        || (userLikeCount == 1 && !trailingUserLike)) {
      throw invalidBatch(
          target,
          requireUserLike
              ? "batches must end with exactly one user-like input"
              : "batches may contain at most one user-like input and it must be the last command");
    }
    if (commands.isEmpty()) {
      throw invalidBatch(target, "batches must not be empty");
    }
  }

  private static boolean isUserLike(NewThreadCommand command) {
    return command.payload() instanceof UserMessageCommandPayload
        || command.payload() instanceof CustomMessageCommandPayload
        || command.payload() instanceof GoalCommandPayload;
  }

  private static IllegalArgumentException invalidBatch(
      AcceptCommandsTarget target, String message) {
    return new IllegalArgumentException(target.getClass().getSimpleName() + " batch: " + message);
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }
}
