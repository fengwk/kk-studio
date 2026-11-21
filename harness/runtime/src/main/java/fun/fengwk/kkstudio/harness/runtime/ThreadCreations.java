package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.Names;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 三条“新建”路径：新执行根 Session、既有 Session 的 NEW_THREAD fork、跨 Session 的 fork 会话，以及同 Session 压缩分支。
 *
 * <p>每个方法都在调用方事务内运行，调用方已按规范锁序持有树锁、Session KEY SHARE 与 Thread 行锁；本类不重新获取 Session 锁。 creation request
 * hash、initial replay、批量形状与 fork 边界校验仍由调用方委托给 {@link AcceptCommandsReplay} / {@link
 * AcceptCommandsValidation} / {@link ThreadCreationRequestHash}。
 */
final class ThreadCreations {

  private ThreadCreations() {}

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
    return AcceptCommandsControl.acceptNewCommandsOnThread(
        tx, thread, commands, preflight, now, rootSettings);
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
      AcceptancePreflight preflight,
      boolean userRequestedCompaction) {
    SpawnRequest.SameSessionBranch branch = request.sameSessionBranch();
    ThreadState parent =
        tx.threads()
            .find(request.parentThreadId())
            .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
    if (parent.kind() == ThreadKind.COMPACTION) {
      throw new IllegalArgumentException("COMPACTION threads cannot spawn children");
    }
    if (parent.executionControl().isStopped()
        && !(userRequestedCompaction && AcceptCommandsValidation.resumeConverged(tx, parent))) {
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
    return AcceptCommandsControl.acceptNewCommandsOnThread(
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
  static AcceptedCommands acceptNewThread(
      AcceptCommandsTarget.NewThread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight,
      HarnessStore store,
      DriverRegistry driverRegistry) {
    AcceptCommandsValidation.validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          // 与创建执行根相同：复用既有 Thread id 时先按真实执行树完成规范加锁，避免 replay 阶段逆序补锁。
          ThreadTreeLocking.LockedAncestors locked =
              ThreadTreeLocking.lockTreeAndAncestors(tx, target.threadId(), false, null);
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
          if (AcceptCommandsControl.isCompactionBranchEntry(
              tx, target.sessionId(), target.startEntryId())) {
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
          return AcceptCommandsControl.acceptNewCommandsOnThread(
              tx, thread, commands, preflight, now, sourceSettings);
        });
  }

  /**
   * NEW_FORKED_SESSION：把既有执行根 {@code sourceThreadId} 在合法切点处的有效上下文复制到新 Session，并创建独立执行根 Thread。
   *
   * <p>来源事实（来源必须是执行根、cut 属于该来源 head 路径、cut 是 ROOT 或已闭合 TURN_END）在创建任何新行之前完成，非法来源零写入。根 settings 从来源
   * branch 在切点处的生效快照推导，不接受调用方给定；只复制有效上下文（最新 complete 压缩摘要与保留尾部，或完整起点历史），不复制 join / commands /
   * invocations，也不订阅源执行。creation request hash 与 client threadId 一起提供 initial creation replay 幂等。
   */
  static AcceptedCommands acceptNewForkedSession(
      AcceptCommandsTarget.NewForkedSession target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight,
      HarnessStore store,
      DriverRegistry driverRegistry) {
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
          ThreadTreeLocking.LockedAncestors locked =
              ThreadTreeLocking.lockTreeAndAncestors(tx, target.threadId(), false, null);
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
          return AcceptCommandsControl.acceptNewCommandsOnThread(
              tx, thread, commands, preflight, now, copiedEntries, rootSettings);
        });
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
}
