package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.ForkMode;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * HarnessRuntime 内部的同步 acceptCommands 控制面。
 *
 * <p>编排命令接受（NEW_ROOT_SESSION / THREAD 两条路径），向 Store 写入 Session / ROOT / Thread / Commands / Work。批量形状、cursor 与执行配置校验委托给 {@link
 * AcceptCommandsValidation}，initial creation replay 与 ordered replay 委托给 {@link
 * AcceptCommandsReplay}。Session 与 ROOT Thread 的初始名称在创建事务内由首条 user-like 文本派生（缺省回退到 id 前缀）；后期改名走独立的 rename 控制面。
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
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, preflight);
    };
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
          ThreadTreeLocking.LockedAncestors locked =
              ThreadTreeLocking.lockTreeAndAncestors(tx, target.threadId(), false, null);
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
          return ThreadCreations.createSessionWithThread(
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

  private AcceptedCommands acceptOnThread(
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          ThreadTreeLocking.LockedAncestors locked =
              ThreadTreeLocking.lockTreeAndAncestors(tx, target.threadId(), false, null);
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
    AcceptCommandsValidation.validateExecutionCommandsOnThread(
        currentSettings, commands, driverRegistry);
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

  static boolean isCompactionBranchEntry(
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

  static AcceptedCommands acceptNewCommandsOnThread(
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

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }
}
