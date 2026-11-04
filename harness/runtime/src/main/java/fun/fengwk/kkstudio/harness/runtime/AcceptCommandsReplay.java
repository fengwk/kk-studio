package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code acceptCommands} 的精确重放：初始创建 replay 与 THREAD ordered replay。
 *
 * <p>重放只读取已锁定或当前事务内的 durable 事实，不写任何行。初始创建按 creation request hash 与 client threadId 命中同一
 * Thread；THREAD 批次先按 idempotencyKey 判定 partial / 全量 replay，再验证每个 requestHash 相等与 sequence
 * 连续。锁序由调用方在进入本类前完成，本类不补锁、不改写 projection。
 */
final class AcceptCommandsReplay {

  private AcceptCommandsReplay() {}

  /**
   * THREAD 路径的 exact replay 查找：先于 cursor/preflight admission 执行。部分命中是 {@code
   * PARTIAL_COMMAND_REPLAY}；全部命中时按请求顺序校验 requestHash 与 sequence，返回重放结果；没有命中时返回 empty。
   */
  static Optional<AcceptedCommands> findThreadReplay(
      HarnessStore.Transaction tx,
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      ThreadState thread,
      boolean cursorFreeBatch) {
    List<Optional<ThreadCommand>> existing = new ArrayList<>(commands.size());
    for (NewThreadCommand request : commands) {
      existing.add(tx.commands().findByIdempotencyKey(target.threadId(), request.idempotencyKey()));
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
      return Optional.of(
          replayThreadBatch(tx, target, commands, thread, existing, cursorFreeBatch));
    }
    return Optional.empty();
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
    tx.sessions().lockForKeyShare(immutable.sessionId()).orElseThrow();
    ThreadState thread =
        tx.threads()
            .lock(threadId)
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
          tx.commands()
              .findByIdempotencyKey(threadId, request.idempotencyKey())
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
    Session session = tx.sessions().find(sessionId).orElseThrow();
    return new AcceptedCommands(
        session,
        tx.entries().findRoot(sessionId).orElseThrow(),
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
    Session session = tx.sessions().find(thread.sessionId()).orElseThrow();
    return new AcceptedCommands(
        session,
        tx.entries().findRoot(thread.sessionId()).orElseThrow(),
        thread,
        List.copyOf(ordered),
        true,
        false);
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }
}
