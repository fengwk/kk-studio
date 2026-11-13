package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.Names;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.ForkMode;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

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
 * <p>编排命令接受（NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION / THREAD 四条路径），向 Store 写入 Session / ROOT
 * / Thread / Commands / Work。批量形状、fork 边界、cursor 与执行配置校验委托给 {@link
 * AcceptCommandsValidation}，initial creation replay 与 ordered replay 委托给 {@link
 * AcceptCommandsReplay}。Session 与 ROOT Thread 的初始名称在创建事务内由首条 user-like 文本派生（缺省回退到 id 前缀）；NEW_THREAD
 * 的分支名称由调用方给出并进入 creation request hash；后期改名走独立的 rename 控制面。
 */
final class AcceptCommandsControl {

  private final HarnessStore store;
  private final DriverRegistry driverRegistry;

  AcceptCommandsControl(HarnessStore store, DriverRegistry driverRegistry) {
    this.store = Objects.requireNonNull(store, "store");
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
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
    AcceptCommandsValidation.validateBatchShape(target, commands);
    AcceptCommandsValidation.validateExecutionCommandsOnNewRoot(
        driverRegistry, target.rootSettings(), commands);
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
                  ThreadKind.USER,
                  ThreadConstraints.NONE,
                  commands);
          ThreadState existing = locked.threads.get(target.threadId());
          if (existing != null) {
            return AcceptCommandsReplay.replayInitial(
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
              ThreadKind.USER,
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
      ThreadKind kind,
      ThreadConstraints constraints,
      String creationRequestHash,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    Instant now = tx.now();
    Session session = new Session(sessionId, initialSessionName(commands, sessionId), now);
    tx.sessions().insert(session);
    UUID rootEntryId = tx.nextId();
    tx.entries()
        .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(rootSettings), now));
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
            kind,
            constraints,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.threads().insert(thread);
    return acceptNewCommandsOnThread(tx, thread, commands, preflight, now, rootSettings);
  }

  /**
   * Creates a ticketed COMPACTION branch sharing its owner's Session and the prefix at a closed
   * cut.
   *
   * <p>调用方必须已按锁阶梯持有该 Session 的 KEY SHARE 并锁定父 Thread 行；本方法不再重复获取 Session 锁，避免破坏 Session -&gt;
   * Thread 的固定锁序。
   */
  static AcceptedCommands createSameSessionBranchWithThread(
      HarnessStore.Transaction tx,
      SpawnRequest request,
      String creationRequestHash,
      AcceptancePreflight preflight) {
    SpawnRequest.SameSessionBranch branch = request.sameSessionBranch();
    ThreadState parent =
        tx.threads()
            .find(request.parentThreadId())
            .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
    if (parent.kind() == ThreadKind.COMPACTION) {
      throw new IllegalArgumentException("COMPACTION threads cannot spawn children");
    }
    if (parent.executionControl().isStopped()) {
      throw new IllegalArgumentException("join parent is stopped");
    }
    if (!parent.sessionId().equals(request.sessionId())) {
      throw new IllegalArgumentException("same-session branch must reuse its owner's Session");
    }
    if (tx.threads().listChildren(parent.id()).stream()
        .filter(child -> child.kind() == ThreadKind.COMPACTION)
        .anyMatch(
            child ->
                tx.joins().loadIncomplete(child.id()).stream()
                    .anyMatch(join -> join.delivery() == JoinDelivery.SETTLE_OWNER_TURN))) {
      throw new IllegalArgumentException("a compaction branch is already active for this Thread");
    }
    // Session KEY SHARE 必须由调用方在锁定 Thread 行之前取得；此处再锁会破坏 Session -> Thread 锁阶梯。
    Entry cutEntry =
        tx.entries()
            .find(branch.startEntryId())
            .orElseThrow(() -> new IllegalArgumentException("compaction cut entry does not exist"));
    if (!cutEntry.sessionId().equals(request.sessionId())) {
      throw new IllegalArgumentException("compaction cut entry belongs to a different Session");
    }
    AcceptCommandsValidation.requireLegalForkBoundary(cutEntry);
    boolean cutOnParentPath =
        tx.entries().loadPath(parent.headEntryId()).entries().stream()
            .anyMatch(entry -> entry.id().equals(branch.startEntryId()));
    if (!cutOnParentPath) {
      throw new IllegalArgumentException("compaction cut entry is not on the owner's current path");
    }
    Instant now = tx.now();
    UUID forkEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                forkEntryId,
                request.sessionId(),
                branch.startEntryId(),
                ForkPayload.branch(branch.startEntryId()),
                now));
    UUID settingsEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                settingsEntryId,
                request.sessionId(),
                forkEntryId,
                new SettingsPayload(request.settings(), request.threadId()),
                now));
    ThreadState thread =
        new ThreadState(
            request.threadId(),
            request.sessionId(),
            parent.id(),
            settingsEntryId,
            creationRequestHash,
            "Compaction",
            parent.rootThreadId(),
            false,
            ThreadKind.COMPACTION,
            request.constraints().merge(parent.constraints()),
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.threads().insert(thread);
    return acceptNewCommandsOnThread(
        tx, thread, request.commands(), preflight, now, request.settings());
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
    AcceptCommandsValidation.validateBatchShape(target, commands);
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
            return AcceptCommandsReplay.replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          // NEW_THREAD 新建路径用 KEY SHARE：不串行化同 Session 的 sibling 初始创建。
          tx.sessions()
              .lockForKeyShare(target.sessionId())
              .orElseThrow(
                  () ->
                      DomainException.notFound(
                          "session", "session " + target.sessionId() + " does not exist"));
          Entry startEntry =
              tx.entries()
                  .find(target.startEntryId())
                  .orElseThrow(
                      () ->
                          DomainException.notFound(
                              "entry", "entry " + target.startEntryId() + " does not exist"));
          if (!startEntry.sessionId().equals(target.sessionId())) {
            throw DomainException.validation(
                "startEntry",
                "start entry "
                    + target.startEntryId()
                    + " is not in session "
                    + target.sessionId());
          }
          AcceptCommandsValidation.requireLegalForkBoundary(startEntry);
          if (isCompactionBranchEntry(tx, target.sessionId(), target.startEntryId())) {
            throw DomainException.validation(
                "startEntry", "COMPACTION branch entries cannot be fork sources");
          }
          BranchSettings sourceSettings = tx.entries().loadBranchSettings(target.startEntryId());
          AcceptCommandsValidation.validateForkExecutionCommands(
              driverRegistry, sourceSettings, commands, "NEW_THREAD");
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
          tx.entries()
              .insert(
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
                  ThreadKind.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.threads().insert(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now, sourceSettings);
        });
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
    AcceptCommandsValidation.validateBatchShape(target, commands);
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
            return AcceptCommandsReplay.replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          // 来源事实与切点校验先于任何写入：来源必须是执行根，cut 必须在其当前 head 路径上且是合法边界。
          ThreadState sourceThread =
              tx.threads()
                  .find(target.sourceThreadId())
                  .orElseThrow(
                      () ->
                          DomainException.notFound(
                              "thread",
                              "source thread " + target.sourceThreadId() + " does not exist"));
          if (sourceThread.parentThreadId() != null) {
            throw DomainException.validation(
                "sourceThread",
                "fork source thread " + target.sourceThreadId() + " must be an execution root");
          }
          UUID sourceSessionId = sourceThread.sessionId();
          tx.sessions()
              .lockForKeyShare(sourceSessionId)
              .orElseThrow(
                  () ->
                      DomainException.notFound(
                          "session", "source session " + sourceSessionId + " does not exist"));
          Entry startEntry =
              tx.entries()
                  .find(target.startEntryId())
                  .orElseThrow(
                      () ->
                          DomainException.notFound(
                              "entry", "entry " + target.startEntryId() + " does not exist"));
          if (!startEntry.sessionId().equals(sourceSessionId)) {
            throw DomainException.validation(
                "startEntry",
                "start entry "
                    + target.startEntryId()
                    + " is not in the source session "
                    + sourceSessionId);
          }
          AcceptCommandsValidation.requireLegalForkBoundary(startEntry, "NEW_FORKED_SESSION");
          EntryPath sourceCutPath = tx.entries().loadPath(target.startEntryId());
          boolean onSourcePath =
              tx.entries().loadPath(sourceThread.headEntryId()).entries().stream()
                  .anyMatch(entry -> entry.id().equals(target.startEntryId()));
          if (!onSourcePath) {
            throw DomainException.validation(
                "startEntry",
                "start entry " + target.startEntryId() + " is not on the source thread path");
          }
          BranchSettings rootSettings = tx.entries().loadBranchSettings(target.startEntryId());
          AcceptCommandsValidation.validateForkExecutionCommands(
              driverRegistry, rootSettings, commands, "NEW_FORKED_SESSION");
          Instant now = tx.now();
          Session session =
              new Session(
                  target.sessionId(), initialSessionName(commands, target.sessionId()), now);
          tx.sessions().insert(session);
          UUID rootEntryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      rootEntryId, target.sessionId(), null, new RootPayload(rootSettings), now));
          List<Entry> copiedEntries =
              SessionForkCopy.copyEffectiveContext(
                  tx, target.sessionId(), rootEntryId, sourceCutPath, now);
          UUID forkParentId = copiedEntries.isEmpty() ? rootEntryId : copiedEntries.getLast().id();
          UUID forkEntryId = tx.nextId();
          tx.entries()
              .insert(
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
                  ThreadKind.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  now,
                  now);
          tx.threads().insert(thread);
          return acceptNewCommandsOnThread(
              tx, thread, commands, preflight, now, copiedEntries, rootSettings);
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
            throw DomainException.notFound(
                "thread", "thread " + target.threadId() + " does not exist");
          }
          return acceptOnLockedThread(
              tx, thread, target, commands, preflight, null, driverRegistry);
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
    return acceptOnLockedThread(tx, thread, target, commands, preflight, ticket, null);
  }

  static AcceptedCommands acceptOnLockedThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight,
      SpawnRequest ticket,
      DriverRegistry driverRegistry) {
    if (thread.kind() == ThreadKind.COMPACTION) {
      throw DomainException.validation("threadId", "COMPACTION threads do not accept input");
    }
    AcceptCommandsValidation.requireRootForEnvironment(thread, commands);
    boolean genuineUserInput = AcceptCommandsValidation.isGenuineUserInput(commands);
    boolean cursorFreeBatch =
        ticket == null && AcceptCommandsCommand.isPureUserMessageBatch(commands);
    if (cursorFreeBatch == target.hasCursor()) {
      throw DomainException.validation(
          "target",
          cursorFreeBatch
              ? "expectedHeadEntryId and expectedNextCommandSequence must be omitted for a single USER_MESSAGE batch"
              : "expectedHeadEntryId and expectedNextCommandSequence are required for this command batch");
    }
    // exact replay 查找必须先于 cursor/preflight admission；纯消息 replay 也不依赖 head 变化。
    Optional<AcceptedCommands> replay =
        AcceptCommandsReplay.findThreadReplay(tx, target, commands, thread, cursorFreeBatch);
    if (replay.isPresent()) {
      return SpawnTickets.attach(tx, replay.get(), ticket);
    }
    if (AcceptCommandsValidation.resumeStoppedTarget(thread, genuineUserInput)
        && !AcceptCommandsValidation.resumeConverged(tx, thread)) {
      throw conflict(
          HarnessConflictReason.EXIT_UNCONFIRMED,
          "thread " + thread.id() + " is stopped and its exit or child joins are not converged");
    }
    AcceptCommandsValidation.validateThreadBatchAdmission(target, thread, cursorFreeBatch);
    AcceptCommandsValidation.validateBatchShape(target, commands, false);
    BranchSettings currentSettings = tx.entries().loadBranchSettings(thread.headEntryId());
    if (driverRegistry != null) {
      AcceptCommandsValidation.validateExecutionCommandsOnThread(
          currentSettings, commands, driverRegistry);
    }
    // 票据请求（带父）由统一 spawn 在写前校验为恰一条末尾 user-like；此处保留既有 THREAD target 的形状规则。
    SpawnTickets.admit(tx, ticket, false);
    Instant now = tx.now();
    Session session = tx.sessions().find(thread.sessionId()).orElseThrow();
    List<NewThreadCommand> prepared =
        preflight.prepare(session, commands, List.of(), currentSettings);
    AcceptCommandsValidation.requirePreflightShape(commands, prepared);
    AcceptCommandsValidation.requireRootForEnvironment(thread, prepared);
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

    tx.commands().insert(allInserted);

    ThreadState advanced =
        AcceptCommandsValidation.resumeStoppedTarget(thread, genuineUserInput)
            ? thread.reserveCommandSequencesAndSetControl(
                allInserted.size(), ThreadExecutionControl.RUNNABLE, now)
            : thread.reserveCommandSequences(allInserted.size(), now);
    tx.threads().update(advanced);

    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    return SpawnTickets.attach(
        tx,
        new AcceptedCommands(
            session,
            tx.entries().findRoot(session.id()).orElseThrow(),
            advanced,
            List.copyOf(allInserted),
            false,
            false),
        ticket);
  }

  private static boolean isCompactionBranchEntry(
      HarnessStore.Transaction tx, UUID sessionId, UUID entryId) {
    for (ThreadState thread : tx.threads().listBySession(sessionId)) {
      if (thread.kind() != ThreadKind.COMPACTION) {
        continue;
      }
      boolean inCompactionBranch = false;
      for (Entry entry : tx.entries().loadPath(thread.headEntryId()).entries()) {
        if (entry.payload() instanceof ForkPayload fork && fork.mode() == ForkMode.BRANCH) {
          inCompactionBranch = true;
        }
        if (inCompactionBranch && entry.id().equals(entryId)) {
          return true;
        }
      }
    }
    return false;
  }

  static LockedAncestors lockTreeAndAncestors(
      HarnessStore.Transaction tx,
      UUID anchorThreadId,
      boolean anchorIsParent,
      Set<UUID> additionalThreadIds) {
    ThreadState anchor = tx.threads().find(anchorThreadId).orElse(null);
    if (anchorIsParent && anchor == null) {
      throw new IllegalArgumentException("join parent does not exist");
    }
    tx.threads().lockTree(anchor == null ? anchorThreadId : anchor.rootThreadId());
    List<UUID> chain = tx.threads().findAncestorChain(anchorThreadId);
    if (anchorIsParent && chain.isEmpty()) {
      throw DomainException.notFound("thread", "join parent no longer exists");
    }
    Set<UUID> allThreadIds = new LinkedHashSet<>(chain);
    if (additionalThreadIds != null) {
      for (UUID id : additionalThreadIds) {
        ThreadState existing = tx.threads().find(id).orElse(null);
        if (existing != null) {
          if (!Objects.equals(existing.parentThreadId(), anchorThreadId)) {
            throw conflict(
                HarnessConflictReason.THREAD_ID_REUSED,
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
          tx.threads()
              .find(id)
              .orElseThrow(
                  () ->
                      anchorIsParent && chain.contains(id)
                          ? new IllegalArgumentException("join parent does not exist")
                          : DomainException.notFound("thread", "thread " + id + " does not exist"));
      sessionIds.add(state.sessionId());
    }
    List<UUID> sortedSessionIds = sessionIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sortedSessionIds) {
      tx.sessions().lockForKeyShare(sessionId).orElseThrow();
    }
    List<UUID> sortedThreadIds = allThreadIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID id : sortedThreadIds) {
      ThreadState locked =
          tx.threads()
              .lock(id)
              .orElseThrow(
                  () ->
                      anchorIsParent && chain.contains(id)
                          ? new IllegalArgumentException("join parent does not exist")
                          : DomainException.notFound("thread", "thread " + id + " does not exist"));
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
      Instant now,
      BranchSettings branchSettings) {
    return acceptNewCommandsOnThread(
        tx, thread, requests, preflight, now, List.of(), branchSettings);
  }

  private static AcceptedCommands acceptNewCommandsOnThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<NewThreadCommand> requests,
      AcceptancePreflight preflight,
      Instant now,
      List<Entry> copiedEntries,
      BranchSettings branchSettings) {
    Session session = tx.sessions().find(thread.sessionId()).orElseThrow();
    List<NewThreadCommand> prepared =
        preflight.prepare(session, requests, copiedEntries, branchSettings);
    AcceptCommandsValidation.requirePreflightShape(requests, prepared);
    AcceptCommandsValidation.requireRootForEnvironment(thread, prepared);
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
    tx.commands().insert(inserted);
    ThreadState advanced = thread.reserveCommandSequences(prepared.size(), now);
    tx.threads().update(advanced);
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    return new AcceptedCommands(
        session,
        tx.entries().findRoot(session.id()).orElseThrow(),
        advanced,
        List.copyOf(inserted),
        false,
        false);
  }

  /**
   * 派生初始 Session 名称：只检查 validate 后的末尾 user-like message（初始 batch 恰以一条 user-like 结尾），取该消息第一个非空白
   * 文本内容（仅 text 内容，不看附件 / resource 名称），规范化折叠为单行并取前 40 个 Unicode 码点（无省略号）；无文本时回退为 {@code session-} +
   * session UUID 前 8 位。
   */
  static String initialSessionName(List<NewThreadCommand> commands, UUID sessionId) {
    NewThreadCommand trailing = commands.get(commands.size() - 1);
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

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }
}
