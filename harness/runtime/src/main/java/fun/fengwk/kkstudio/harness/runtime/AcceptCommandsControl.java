package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
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
 * <p>承担命令接受（NEW_SESSION / NEW_THREAD / THREAD 三条路径）、initial creation replay 与 ordered replay、 batch
 * shape 校验、cursor admission 校验以及向 Store 写入 Session / ROOT / Thread / Commands / Work。名称不是
 * 创建请求的一部分：初始名称在创建事务内由首条 user-like 文本派生（缺省回退到 id 前缀），后期改名走独立的 rename 控制面。
 */
final class AcceptCommandsControl {

  /** SET_* prefix 的固定顺序：SET_AGENT -&gt; SET_MODEL -&gt; SET_ENVIRONMENT，每类至多一次、全部在消息之前。 */
  private static final List<ThreadCommandType> SET_PREFIX_ORDER =
      List.of(
          ThreadCommandType.SET_AGENT,
          ThreadCommandType.SET_MODEL,
          ThreadCommandType.SET_ENVIRONMENT);

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
      case AcceptCommandsTarget.NewSession target -> acceptNewSession(
          target, commands, join, preflight);
      case AcceptCommandsTarget.NewThread target -> acceptNewThread(
          target, commands, join, preflight);
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, join, preflight);
    };
  }

  private AcceptedCommands acceptNewSession(
      AcceptCommandsTarget.NewSession target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          lockAcceptanceTree(tx, target.threadId(), target.parentThreadId());
          String creationRequestHash =
              ThreadCreationRequestHash.forNewSession(
                  target.sessionId(),
                  target.threadId(),
                  target.rootSettings(),
                  target.parentThreadId(),
                  target.yoloEnabled(),
                  commands);
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
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
          admitJoin(tx, target.threadId(), target.parentThreadId(), join, true);
          Instant now = clock.instant();
          Session session =
              new Session(
                  target.sessionId(), initialSessionName(commands, target.sessionId()), now);
          tx.insertSession(session);
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  target.sessionId(),
                  null,
                  new RootPayload(target.rootSettings()),
                  now));
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  target.parentThreadId(),
                  rootEntryId,
                  creationRequestHash,
                  Names.rootThreadName(),
                  target.yoloEnabled(),
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return attachJoin(
              tx, acceptNewCommandsOnThread(tx, thread, commands, preflight, now), join);
        });
  }

  private AcceptedCommands acceptNewThread(
      AcceptCommandsTarget.NewThread target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          lockAcceptanceTree(tx, target.threadId(), null);
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
            String creationRequestHash =
                ThreadCreationRequestHash.forNewThread(
                    target.sessionId(),
                    target.startEntryId(),
                    target.threadId(),
                    target.yoloEnabled(),
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
          // STOP Turn 是原子控制屏障：NEW_THREAD 的 head 绝不能落在未闭合的 STOP Turn 内（已关闭的 STOPPED 边界可以正常 fork）。
          if (endsInsideUnclosedStopTurn(tx, target.startEntryId())) {
            throw new IllegalArgumentException(
                "start entry " + target.startEntryId() + " is inside an unclosed STOP turn");
          }
          String creationRequestHash =
              ThreadCreationRequestHash.forNewThread(
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
                  null,
                  target.startEntryId(),
                  creationRequestHash,
                  Names.defaultThreadName(target.threadId()),
                  target.yoloEnabled(),
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return attachJoin(
              tx, acceptNewCommandsOnThread(tx, thread, commands, preflight, now), join);
        });
  }

  /** start Entry 的 EntryPath 是否停在未闭合的 STOP Turn 内。 */
  private static boolean endsInsideUnclosedStopTurn(
      HarnessStore.Transaction tx, UUID startEntryId) {
    return tx.loadEntryPath(startEntryId)
        .openTurnStart()
        .map(
            turn ->
                turn.payload() instanceof TurnStartPayload start
                    && start.reason() == TurnStartReason.STOP)
        .orElse(false);
  }

  private AcceptedCommands acceptOnThread(
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      ThreadJoinRequest join,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          lockAcceptanceTree(tx, target.threadId(), null);
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
            return attachJoin(tx, replayThreadBatch(tx, target, commands, thread, existing), join);
          }
          validateThreadBatchAdmission(target, thread);
          validateBatchShape(target, commands);
          admitJoin(tx, thread.id(), thread.parentThreadId(), join, false);
          Instant now = clock.instant();
          return attachJoin(
              tx, acceptNewCommandsOnThread(tx, thread, commands, preflight, now), join);
        });
  }

  private static void lockAcceptanceTree(
      HarnessStore.Transaction tx, UUID childId, UUID newParentId) {
    UUID anchor = newParentId == null ? childId : newParentId;
    List<UUID> chain = tx.findAncestorChain(anchor);
    if (newParentId != null && chain.isEmpty()) {
      throw new IllegalArgumentException("join parent does not exist");
    }
    UUID root = chain.isEmpty() ? anchor : chain.get(chain.size() - 1);
    tx.lockTree(root);
    // The initial lookup is only a hint. Re-read immutable ancestry after taking the tree lock.
    if (!chain.equals(tx.findAncestorChain(anchor))) {
      throw new IllegalStateException("execution tree changed while acquiring its lock");
    }
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
      if (!parent.headEntryId().equals(join.expectedParentHeadEntryId())
          || parent.status().isStopped()) {
        throw new IllegalArgumentException("join parent no longer accepts this invocation");
      }
      if (tx.countUnmatchedJoinsByParent(parentId) >= join.maxConcurrentChildren()) {
        throw new IllegalArgumentException("parent join quota exceeded");
      }
    }
    int depth = creating ? chain.size() + 1 : chain.size();
    if (depth > join.maxDepth()) {
      throw new IllegalArgumentException("join depth quota exceeded");
    }
    UUID root = chain.isEmpty() ? childId : chain.get(chain.size() - 1);
    if (tx.countUnmatchedJoinsInTree(root) >= join.maxConcurrentTreeJoins()) {
      throw new IllegalArgumentException("tree join quota exceeded");
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
            accepted.thread().version(),
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
   * 命令 batch 的 shape admission：SET_* 必须以固定顺序（SET_AGENT -&gt; SET_MODEL -&gt;
   * SET_ENVIRONMENT）、至多一次且全部出现在消息之前；任何 target 都要求<b>恰有一条</b>末尾 user-like 输入，即一条 USER/CUSTOM 消息或一条
   * typed GOAL 命令（typed GOAL 不可与普通消息同批）。非法 batch 是请求校验错误，抛 {@link IllegalArgumentException} 而非业务冲突。
   */
  private static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands) {
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
    if (userLikeCount != 1 || !isUserLike(commands.get(commands.size() - 1))) {
      throw invalidBatch(target, "batches must end with exactly one user-like input");
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
