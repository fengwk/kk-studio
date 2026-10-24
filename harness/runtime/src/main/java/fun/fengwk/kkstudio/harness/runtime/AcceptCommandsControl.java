package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

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

  AcceptCommandsControl(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  AcceptedCommands acceptCommands(AcceptCommandsCommand command, AcceptancePreflight preflight) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(preflight, "preflight");
    List<NewThreadCommand> commands = command.commands();
    return switch (command.target()) {
      case AcceptCommandsTarget.NewRootSession target -> acceptNewRootSession(
          target, commands, preflight);
      case AcceptCommandsTarget.NewThread target -> acceptNewThread(target, commands, preflight);
      case AcceptCommandsTarget.NewForkedSession target -> acceptNewForkedSession(
          target, commands, preflight);
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, preflight);
    };
  }

  static final class LockedAncestors {
    final List<UUID> chain;
    final Map<UUID, ThreadState> threads;

    LockedAncestors(List<UUID> chain, Map<UUID, ThreadState> threads) {
      this.chain = chain;
      this.threads = threads;
    }
  }

  /**
   * NEW_ROOT_SESSION：在树锁内以调用方给定的根开关创建用户发起的独立执行根 Thread；从 root settings 初始化 Session 与 ROOT Entry。
   * 根开关只初始化该根自身，不来自任何既有执行树。带票据的任务创建走 {@link AgentSpawner}。
   */
  private AcceptedCommands acceptNewRootSession(
      AcceptCommandsTarget.NewRootSession target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          LockedAncestors locked = lockTreeAndAncestors(tx, target.threadId(), false, null);
          String creationRequestHash =
              ThreadCreationRequestHash.forNewSession(
                  target.sessionId(),
                  target.threadId(),
                  target.rootSettings(),
                  null,
                  target.yoloEnabled(),
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
                  commands);
          ThreadState existing = locked.threads.get(target.threadId());
          if (existing != null) {
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          return createSessionWithThread(
              tx,
              target.sessionId(),
              target.threadId(),
              null,
              target.threadId(),
              target.rootSettings(),
              target.yoloEnabled(),
              SpawnTrigger.USER,
              ThreadConstraints.NONE,
              creationRequestHash,
              commands,
              preflight);
        });
  }

  /**
   * 创建 Session、ROOT Entry 与 Thread 并写入首批命令：用户根会话与带票据的任务创建共用的唯一创建路径。{@code parentThreadId}
   * 为空表示执行根（自身即 {@code rootThreadId}），调用方负责此前的树锁、重放判定与准入。
   */
  static AcceptedCommands createSessionWithThread(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID threadId,
      UUID parentThreadId,
      UUID rootThreadId,
      BranchSettings rootSettings,
      boolean yoloEnabled,
      SpawnTrigger trigger,
      ThreadConstraints constraints,
      String creationRequestHash,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    Instant now = tx.now();
    Session session = new Session(sessionId, initialSessionName(commands, sessionId), now);
    tx.insertSession(session);
    UUID rootEntryId = tx.nextId();
    tx.insertEntry(new Entry(rootEntryId, sessionId, null, new RootPayload(rootSettings), now));
    ThreadState thread =
        new ThreadState(
            threadId,
            sessionId,
            parentThreadId,
            rootEntryId,
            creationRequestHash,
            Names.rootThreadName(),
            rootThreadId,
            yoloEnabled,
            trigger,
            constraints,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.insertThread(thread);
    return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
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
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
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
                    target.yoloEnabled(),
                    commands);
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
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
                  target.yoloEnabled(),
                  commands);
          Instant now = tx.now();
          // fork 事实节点：作为新 Thread head 的第一个 Entry，只记录模式与切点，不复制共享前缀、不移动源 Thread。
          UUID forkEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  forkEntryId,
                  target.sessionId(),
                  target.startEntryId(),
                  ForkPayload.branch(target.startEntryId()),
                  now));
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  null,
                  forkEntryId,
                  creationRequestHash,
                  target.threadName(),
                  target.threadId(),
                  target.yoloEnabled(),
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  /**
   * NEW_THREAD 的合法 fork 边界：只有 ROOT 或已闭合 {@code TURN_END}。其它位置都是 active / 半轮 / 未闭合前缀，必须原子拒绝；这里只按
   * start Entry 自身判定，不写源 Thread、不补写 synthetic closure，也不做任何 source Thread 推断。
   */
  private static void requireLegalForkBoundary(Entry startEntry) {
    requireLegalForkBoundary(startEntry, "NEW_THREAD");
  }

  private static void requireLegalForkBoundary(Entry startEntry, String target) {
    if (startEntry.payload().type().isRoot() || startEntry.payload() instanceof TurnEndPayload) {
      return;
    }
    throw new IllegalArgumentException(
        "start entry "
            + startEntry.id()
            + " is not a legal "
            + target
            + " fork boundary (ROOT or a closed TURN_END)");
  }

  /**
   * NEW_FORKED_SESSION：把既有执行根 {@code sourceThreadId} 在合法切点处的有效上下文复制到新 Session，并创建独立执行根 Thread。
   *
   * <p>来源事实（来源必须是执行根、cut 属于该来源 head 路径、cut 是 ROOT 或已闭合 TURN_END）在创建任何新行之前完成，非法来源零写入。根 settings 从来源
   * branch 在切点处的生效快照推导，不接受调用方给定；只复制有效上下文（最新 complete 压缩摘要与保留尾部，或完整起点历史），不复制 join / commands /
   * invocations，也不订阅源执行。creation request hash 与 client threadId 一起提供 initial creation replay 幂等。
   */
  private AcceptedCommands acceptNewForkedSession(
      AcceptCommandsTarget.NewForkedSession target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          // initial creation replay 必须先于来源校验：已接受的 fork 不依赖来源是否仍然存在。
          String creationRequestHash =
              ThreadCreationRequestHash.forNewForkedSession(
                  target.sourceThreadId(),
                  target.startEntryId(),
                  target.sessionId(),
                  target.threadId(),
                  target.yoloEnabled(),
                  commands);
          LockedAncestors locked = lockTreeAndAncestors(tx, target.threadId(), false, null);
          ThreadState existing = locked.threads.get(target.threadId());
          if (existing != null) {
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          // 来源事实与切点校验先于任何写入：来源必须是执行根，cut 必须在其当前 head 路径上且是合法边界。
          ThreadState sourceThread =
              tx.findThread(target.sourceThreadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "source thread " + target.sourceThreadId() + " does not exist"));
          if (sourceThread.parentThreadId() != null) {
            throw new IllegalArgumentException(
                "fork source thread " + target.sourceThreadId() + " must be an execution root");
          }
          UUID sourceSessionId = sourceThread.sessionId();
          tx.lockSessionForKeyShare(sourceSessionId)
              .orElseThrow(
                  () ->
                      new HarnessRuntimeNotFoundException(
                          "source session " + sourceSessionId + " does not exist"));
          Entry startEntry =
              tx.findEntry(target.startEntryId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "entry " + target.startEntryId() + " does not exist"));
          if (!startEntry.sessionId().equals(sourceSessionId)) {
            throw new IllegalArgumentException(
                "start entry "
                    + target.startEntryId()
                    + " is not in the source session "
                    + sourceSessionId);
          }
          requireLegalForkBoundary(startEntry, "NEW_FORKED_SESSION");
          EntryPath sourceCutPath = tx.loadEntryPath(target.startEntryId());
          boolean onSourcePath =
              tx.loadEntryPath(sourceThread.headEntryId()).entries().stream()
                  .anyMatch(entry -> entry.id().equals(target.startEntryId()));
          if (!onSourcePath) {
            throw new IllegalArgumentException(
                "start entry " + target.startEntryId() + " is not on the source thread path");
          }
          BranchSettings rootSettings = tx.loadBranchSettings(target.startEntryId());
          Instant now = tx.now();
          Session session =
              new Session(
                  target.sessionId(), initialSessionName(commands, target.sessionId()), now);
          tx.insertSession(session);
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(rootEntryId, target.sessionId(), null, new RootPayload(rootSettings), now));
          Entry lastCopied =
              SessionForkCopy.copyEffectiveContext(
                  tx, target.sessionId(), rootEntryId, sourceCutPath, now);
          UUID forkParentId = lastCopied == null ? rootEntryId : lastCopied.id();
          UUID forkEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  forkEntryId,
                  target.sessionId(),
                  forkParentId,
                  ForkPayload.session(target.sourceThreadId(), target.startEntryId()),
                  now));
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  null,
                  forkEntryId,
                  creationRequestHash,
                  Names.rootThreadName(),
                  target.threadId(),
                  target.yoloEnabled(),
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  private AcceptedCommands acceptOnThread(
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          LockedAncestors locked = lockTreeAndAncestors(tx, target.threadId(), false, null);
          ThreadState thread = locked.threads.get(target.threadId());
          if (thread == null) {
            throw new HarnessRuntimeNotFoundException(
                "thread " + target.threadId() + " does not exist");
          }
          return acceptOnLockedThread(tx, thread, target, commands, preflight, null);
        });
  }

  /**
   * THREAD 路径的事务内主体：调用方已按规范锁序锁定树与目标 Thread。{@code ticket} 非空表示本次接受同时登记任务票据（{@link AgentSpawner}
   * 续用既有 Thread），票据的准入与登记复用 {@link SpawnTickets}；用户直接输入恒为 null。
   */
  static AcceptedCommands acceptOnLockedThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight,
      SpawnRequest ticket) {
    boolean genuineUserInput = isGenuineUserInput(commands);
    boolean cursorFreeBatch =
        ticket == null && AcceptCommandsCommand.isPureUserMessageBatch(commands);
    if (cursorFreeBatch == target.hasCursor()) {
      throw new IllegalArgumentException(
          cursorFreeBatch
              ? "expectedHeadEntryId and expectedNextCommandSequence must be omitted for a single USER_MESSAGE batch"
              : "expectedHeadEntryId and expectedNextCommandSequence are required for this command batch");
    }
    // exact replay 查找必须先于 cursor/preflight admission；纯消息 replay 也不依赖 head 变化。
    List<Optional<ThreadCommand>> existing = new ArrayList<>(commands.size());
    for (NewThreadCommand request : commands) {
      existing.add(tx.findCommandByIdempotencyKey(target.threadId(), request.idempotencyKey()));
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
      return SpawnTickets.attach(
          tx, replayThreadBatch(tx, target, commands, thread, existing, cursorFreeBatch), ticket);
    }
    if (resumeStoppedTarget(thread, genuineUserInput) && !resumeConverged(tx, thread)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.EXIT_UNCONFIRMED,
          "thread " + thread.id() + " is stopped and its exit or child joins are not converged");
    }
    validateThreadBatchAdmission(target, thread, cursorFreeBatch);
    validateBatchShape(target, commands, false);
    // 票据请求（带父）由统一 spawn 在写前校验为恰一条末尾 user-like；此处保留既有 THREAD target 的形状规则。
    SpawnTickets.admit(tx, ticket, false);
    Instant now = tx.now();
    Session session = tx.findSession(thread.sessionId()).orElseThrow();
    List<NewThreadCommand> prepared = preflight.prepare(tx, session, commands);
    requirePreflightShape(commands, prepared);
    long nextSeq = thread.nextCommandSequence();
    List<ThreadCommand> allInserted = new ArrayList<>(prepared.size());

    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = prepared.get(i);
      allInserted.add(
          new ThreadCommand(
              thread.id(),
              nextSeq + i,
              request.payload(),
              request.idempotencyKey(),
              request.requestHash(),
              null,
              null,
              null,
              now));
    }

    tx.insertCommands(allInserted);

    ThreadState advanced =
        resumeStoppedTarget(thread, genuineUserInput)
            ? thread.reserveCommandSequencesAndSetControl(
                allInserted.size(), ThreadExecutionControl.RUNNABLE, now)
            : thread.reserveCommandSequences(allInserted.size(), now);
    tx.updateThread(advanced);

    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    return SpawnTickets.attach(
        tx,
        new AcceptedCommands(
            session,
            tx.findRootEntry(session.id()).orElseThrow(),
            advanced,
            List.copyOf(allInserted),
            false,
            false),
        ticket);
  }

  static LockedAncestors lockTreeAndAncestors(
      HarnessStore.Transaction tx,
      UUID anchorThreadId,
      boolean anchorIsParent,
      Set<UUID> additionalThreadIds) {
    ThreadState anchor = tx.findThread(anchorThreadId).orElse(null);
    if (anchorIsParent && anchor == null) {
      throw new IllegalArgumentException("join parent does not exist");
    }
    tx.lockTree(anchor == null ? anchorThreadId : anchor.rootThreadId());
    List<UUID> chain = tx.findAncestorChain(anchorThreadId);
    if (anchorIsParent && chain.isEmpty()) {
      throw new HarnessRuntimeNotFoundException("join parent no longer exists");
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
    // 先在树锁内确定完整行锁集合，再按 Session/Thread 排序。
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
      sessionIds.add(state.sessionId());
    }
    List<UUID> sortedSessionIds = sessionIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sortedSessionIds) {
      tx.lockSessionForKeyShare(sessionId).orElseThrow();
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
      lockedThreads.put(id, locked);
    }
    return new LockedAncestors(chain, lockedThreads);
  }

  /** 全新 batch 的共性写入：preflight、插入 Commands、推进 version/next sequence、请求 THREAD Work。 */
  static AcceptedCommands acceptNewCommandsOnThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<NewThreadCommand> requests,
      AcceptancePreflight preflight,
      Instant now) {
    Session session = tx.findSession(thread.sessionId()).orElseThrow();
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
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    return new AcceptedCommands(
        session,
        tx.findRootEntry(session.id()).orElseThrow(),
        advanced,
        List.copyOf(inserted),
        false,
        false);
  }

  /**
   * NEW_SESSION / NEW_THREAD：同 creation request hash + 同 Session 的 client threadId 精确 replay。
   *
   * <p>{@code immutable} 快照只用于在上锁前定位 Session；随后按规范锁序 KEY SHARE Session -&gt; FOR UPDATE Thread
   * 复核后返回当前 projection（禁止混合 unlocked snapshot）。只按本请求 idempotencyKey 顺序重放原始初始命令，验证 requestHash 相等且
   * sequence 从 1 连续，绝不返回该 Thread 后续批次的历史命令。replay 读取的是当前 Thread / Session 行，因此即便其后发生过
   * rename，返回的名称也是当前名称。
   */
  static AcceptedCommands replayInitial(
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
    tx.lockSessionForKeyShare(immutable.sessionId()).orElseThrow();
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
    Session session = tx.findSession(sessionId).orElseThrow();
    return new AcceptedCommands(
        session,
        tx.findRootEntry(sessionId).orElseThrow(),
        thread,
        List.copyOf(ordered),
        true,
        false);
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
      List<Optional<ThreadCommand>> found,
      boolean cursorFreeBatch) {
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
    long firstSequence = ordered.getFirst().sequence();
    if (!cursorFreeBatch && firstSequence != target.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
          "existing commands on thread "
              + target.threadId()
              + " start at sequence "
              + firstSequence
              + " while the request expected "
              + target.expectedNextCommandSequence());
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
    Session session = tx.findSession(thread.sessionId()).orElseThrow();
    return new AcceptedCommands(
        session,
        tx.findRootEntry(thread.sessionId()).orElseThrow(),
        thread,
        List.copyOf(ordered),
        true,
        false);
  }

  /** 恢复输入要求本 Thread 已无未确认执行，且直接子 Join 已收敛。SET 等维护输入不恢复，因此不受此限制。 */
  private static boolean resumeConverged(HarnessStore.Transaction tx, ThreadState thread) {
    return !ExecutionExitLedger.hasUnconfirmed(tx, thread.id())
        && tx.countIncompleteChildJoins(thread.id()) == 0;
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
  static String initialSessionName(List<NewThreadCommand> commands, UUID sessionId) {
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
   * THREAD 全新 batch 的 admission：cursor-bound 批次必须精确匹配 head / next sequence，stale cursor 确定性拒绝；单条纯
   * USER_MESSAGE 批次按锁内当前邮箱尾部入队，不读取客户端的 head / sequence。SET_*（含 SET_ENVIRONMENT）只入队、由 Reducer 于下一个
   * INPUT 边界收割，不参与 admission——即使 Thread 处于 live Model / Tool 或有 queued 消息 / THREAD Work 也照常接受。
   */
  private static void validateThreadBatchAdmission(
      AcceptCommandsTarget.Thread target, ThreadState thread, boolean cursorFreeBatch) {
    if (cursorFreeBatch) {
      return;
    }
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
    validateBatchShape(target.getClass().getSimpleName(), commands, requireUserLike);
  }

  /**
   * 与 target 无关的 batch shape 校验：统一 spawn 在写入任何行之前复用同一组规则，避免复制第二套或伪造 accept target。
   *
   * @param targetLabel 用于错误消息的来源标签
   */
  static void validateBatchShape(
      String targetLabel, List<NewThreadCommand> commands, boolean requireUserLike) {
    int userLikeCount = 0;
    int lastSetOrder = -1;
    boolean sawMessage = false;
    for (NewThreadCommand command : commands) {
      ThreadCommandPayload payload = command.payload();
      if (payload.type().isSetting()) {
        if (sawMessage) {
          throw invalidBatch(targetLabel, "SET_* commands must precede all messages");
        }
        int order = SET_PREFIX_ORDER.indexOf(payload.type());
        if (order <= lastSetOrder) {
          throw invalidBatch(
              targetLabel, "SET_* prefix must use the fixed order and each type at most once");
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
          targetLabel,
          requireUserLike
              ? "batches must end with exactly one user-like input"
              : "batches may contain at most one user-like input and it must be the last command");
    }
    if (commands.isEmpty()) {
      throw invalidBatch(targetLabel, "batches must not be empty");
    }
  }

  private static boolean isUserLike(NewThreadCommand command) {
    return command.payload() instanceof UserMessageCommandPayload
        || command.payload() instanceof CustomMessageCommandPayload
        || command.payload() instanceof GoalCommandPayload;
  }

  private static IllegalArgumentException invalidBatch(String targetLabel, String message) {
    return new IllegalArgumentException(targetLabel + " batch: " + message);
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }
}
