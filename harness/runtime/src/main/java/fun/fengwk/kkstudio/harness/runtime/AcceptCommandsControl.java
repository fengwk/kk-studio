package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * HarnessRuntime 内部的同步 acceptCommands 控制面。
 *
 * <p>承担命令接受（NEW_SESSION / ENTRY / THREAD 三条路径）、initial creation replay 与 ordered replay、 batch
 * shape 校验、cursor admission 校验以及向 Store 写入 Session / ROOT / Thread / Commands / Work。
 */
final class AcceptCommandsControl {

  /** SET_* prefix 的固定顺序：SET_ENVIRONMENT -&gt; SET_AGENT -&gt; SET_MODEL，每类至多一次、全部在消息之前。 */
  private static final List<ThreadCommandType> SET_PREFIX_ORDER =
      List.of(
          ThreadCommandType.SET_ENVIRONMENT,
          ThreadCommandType.SET_AGENT,
          ThreadCommandType.SET_MODEL);

  private final HarnessStore store;
  private final Clock clock;

  AcceptCommandsControl(HarnessStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  AcceptedCommands acceptCommands(AcceptCommandsCommand command, AcceptancePreflight preflight) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(preflight, "preflight");
    List<NewThreadCommand> commands = command.commands();
    return switch (command.target()) {
      case AcceptCommandsTarget.NewSession target -> acceptNewSession(target, commands, preflight);
      case AcceptCommandsTarget.Entry target -> acceptEntry(target, commands, preflight);
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, preflight);
    };
  }

  private AcceptedCommands acceptNewSession(
      AcceptCommandsTarget.NewSession target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          String creationRequestHash =
              ThreadCreationRequestHash.forNewSession(
                  target.sessionId(),
                  target.threadId(),
                  target.rootSettings(),
                  target.subagentContext(),
                  target.yoloEnabled(),
                  commands);
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          Instant now = clock.instant();
          tx.insertSession(new Session(target.sessionId(), now));
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  target.sessionId(),
                  null,
                  new RootPayload(target.rootSettings(), target.subagentContext()),
                  now));
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  rootEntryId,
                  creationRequestHash,
                  target.yoloEnabled(),
                  1,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  private AcceptedCommands acceptEntry(
      AcceptCommandsTarget.Entry target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
            String creationRequestHash =
                ThreadCreationRequestHash.forEntry(
                    target.sessionId(),
                    target.startEntryId(),
                    target.threadId(),
                    target.yoloEnabled(),
                    commands);
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, creationRequestHash);
          }
          // ENTRY 新建路径用 KEY SHARE：不串行化同 Session 的 sibling 初始创建。
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
          String creationRequestHash =
              ThreadCreationRequestHash.forEntry(
                  target.sessionId(),
                  target.startEntryId(),
                  target.threadId(),
                  target.yoloEnabled(),
                  commands);
          Instant now = clock.instant();
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  target.startEntryId(),
                  creationRequestHash,
                  target.yoloEnabled(),
                  1,
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
          ThreadState immutable =
              tx.findThread(target.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + target.threadId() + " does not exist"));
          UUID sessionId = immutable.sessionId();
          tx.lockSessionForKeyShare(sessionId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "session " + sessionId + " disappeared while thread existed"));
          ThreadState thread =
              tx.lockThread(target.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + target.threadId() + " does not exist"));
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
            return replayThreadBatch(tx, target, commands, thread, existing);
          }
          validateThreadBatchAdmission(target, thread);
          validateBatchShape(target, commands);
          Instant now = clock.instant();
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
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
   * NEW_SESSION / ENTRY：同 creation request hash + 同 Session 的 client threadId 精确 replay。
   *
   * <p>{@code immutable} 快照只用于在上锁前定位 Session；随后按规范锁序 KEY SHARE Session -&gt; FOR UPDATE Thread
   * 复核后返回当前 projection（禁止混合 unlocked snapshot）。只按本请求 idempotencyKey 顺序重放原始初始命令，验证 requestHash 相等且
   * sequence 从 1 连续，绝不返回该 Thread 后续批次的历史命令。
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
    Session session =
        tx.findSession(thread.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + thread.sessionId() + " disappeared while thread existed"));
    return new AcceptedCommands(
        session, requireRootEntry(tx, thread.sessionId()), thread, List.copyOf(ordered), true);
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
   * SET_WORKSPACE_PATH）只入队、由 Reducer 于下一个 INPUT 边界收割，不参与 admission——即使 Thread 处于 live Model / Tool
   * 或 有 queued 消息 / THREAD Work 也照常接受。
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
   * 命令 batch 的 shape admission：SET_* 必须以固定顺序（SET_ENVIRONMENT -&gt; SET_AGENT -&gt;
   * SET_MODEL）、至多一次且全部出现在消息之前；初始 target 必须恰有一条 user-like message 结尾（SYSTEM CUSTOM_MESSAGE
   * 只允许在前缀）；THREAD 要么是恰一条 SYSTEM CUSTOM_MESSAGE steering，要么是不含 SYSTEM CUSTOM_MESSAGE、<b>恰有一条</b>末尾
   * user-like 的用户 batch。非法 batch 是请求校验错误，抛 {@link IllegalArgumentException} 而非业务冲突。
   */
  private static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands) {
    int userLikeCount = 0;
    int systemCount = 0;
    int lastSetOrder = -1;
    boolean sawMessage = false;
    for (NewThreadCommand command : commands) {
      ThreadCommandPayload payload = command.payload();
      if (payload instanceof SetAgentCommandPayload
          || payload instanceof SetModelCommandPayload
          || payload instanceof SetEnvironmentCommandPayload) {
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
      } else {
        systemCount++;
      }
    }
    if (target instanceof AcceptCommandsTarget.NewSession
        || target instanceof AcceptCommandsTarget.Entry) {
      if (userLikeCount != 1 || !isUserLike(commands.get(commands.size() - 1))) {
        throw invalidBatch(target, "initial batches must end with exactly one user-like message");
      }
      return;
    }
    // THREAD
    if (systemCount > 0) {
      if (commands.size() != 1 || userLikeCount != 0) {
        throw invalidBatch(
            target, "thread steering must be exactly one SYSTEM CUSTOM_MESSAGE and nothing else");
      }
      return;
    }
    // THREAD user batch：恰一条末尾 user-like（不是至少一条）。
    if (userLikeCount != 1 || !isUserLike(commands.get(commands.size() - 1))) {
      throw invalidBatch(
          target, "thread user batches must contain exactly one trailing user-like message");
    }
  }

  private static boolean isUserLike(NewThreadCommand command) {
    if (command.payload() instanceof UserMessageCommandPayload) {
      return true;
    }
    return command.payload() instanceof CustomMessageCommandPayload custom
        && custom.message().role() == AgentMessageRole.USER;
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
