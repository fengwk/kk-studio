package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.CommandStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Command 聚合原语：只能以 QUEUED 插入，生命周期只能 QUEUED-&gt;APPLIED / CANCELLED 或 terminal exact-idempotent。
 *
 * <p>锁序 Thread -&gt; commands：读取 mailbox、插入与更新都要求目标 Thread 已锁定；COMMAND 的树锁例外只对同一执行树生效。
 */
final class InMemoryCommandStore implements CommandStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryCommandStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public Optional<ThreadCommand> findByIdempotencyKey(UUID threadId, UUID idempotencyKey) {
    tx.checkOpen();
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    for (ThreadCommand command : state.commands.values()) {
      if (command.threadId().equals(threadId) && command.idempotencyKey().equals(idempotencyKey)) {
        return Optional.of(command);
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<ThreadCommand> find(UUID threadId, long sequence) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    for (ThreadCommand command : state.commands.values()) {
      if (command.threadId().equals(threadId) && command.sequence() == sequence) {
        return Optional.of(command);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<ThreadCommand> loadQueued(UUID threadId) {
    tx.checkOpen();
    // 锁序 Thread -> commands：先锁 Thread 才能读它的 mailbox。
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(threadId));
    tx.locks().requireCanLockRank(InMemoryHarnessLocks.LockRank.COMMAND, threadId);
    List<ThreadCommand> queued =
        state.commands.values().stream()
            .filter(
                command ->
                    command.threadId().equals(threadId)
                        && command.state() == ThreadCommandState.QUEUED)
            .sorted(Comparator.comparingLong(ThreadCommand::sequence))
            .toList();
    for (ThreadCommand command : queued) {
      tx.locks().lock(InMemoryHarnessLocks.LockKey.command(command.threadId(), command.sequence()));
    }
    return queued;
  }

  @Override
  public boolean hasAppliedNotificationAfter(UUID threadId, long afterSequence) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return state.commands.values().stream()
        .anyMatch(
            command ->
                command.threadId().equals(threadId)
                    && command.sequence() > afterSequence
                    && command.state() == ThreadCommandState.APPLIED
                    && command.type().isNotification());
  }

  @Override
  public List<ThreadCommand> loadCancelledByRequest(UUID threadId, UUID stopRequestId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    return state.commands.values().stream()
        .filter(
            command ->
                command.threadId().equals(threadId)
                    && stopRequestId.equals(command.stopRequestId()))
        .sorted(Comparator.comparingLong(ThreadCommand::sequence))
        .toList();
  }

  @Override
  public void insert(List<ThreadCommand> commands) {
    tx.checkOpen();
    InMemoryHarnessLocks locks = tx.locks();
    List<ThreadCommand> copied =
        List.copyOf(commands).stream().sorted(InMemoryHarnessLocks.COMMAND_LOCK_ORDER).toList();
    Set<CommandSequenceKey> sequences = new HashSet<>();
    Set<CommandClientKey> idempotencyKeys = new HashSet<>();
    for (ThreadCommand command : copied) {
      HarnessStoreTime.requireMillisecondPrecision(command.cancelledAt());
      HarnessStoreTime.requireMillisecondPrecision(command.createdAt());
      if (!sequences.add(new CommandSequenceKey(command.threadId(), command.sequence()))) {
        throw new IllegalArgumentException(
            "duplicate command sequence "
                + command.sequence()
                + " on thread "
                + command.threadId());
      }
      if (!idempotencyKeys.add(
          new CommandClientKey(command.threadId(), command.idempotencyKey()))) {
        throw new IllegalArgumentException(
            "duplicate idempotencyKey "
                + command.idempotencyKey()
                + " on thread "
                + command.threadId());
      }
      if (command.state() != ThreadCommandState.QUEUED) {
        throw new IllegalArgumentException("inserted commands must be QUEUED");
      }
      if (!state.threads.containsKey(command.threadId())) {
        throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
      }
      // 锁序 Thread -> commands：enqueue 必须先锁定目标 Thread，且 COMMAND 的树锁例外只对同一执行树生效。
      locks.requireLocked(InMemoryHarnessLocks.LockKey.thread(command.threadId()));
      locks.requireCanLockRank(InMemoryHarnessLocks.LockRank.COMMAND, command.threadId());
      requireUniqueCommandKey(command);
    }
    for (ThreadCommand command : copied) {
      state.commands.put(
          new InMemoryState.CommandKey(command.threadId(), command.sequence()), command);
      locks.lock(InMemoryHarnessLocks.LockKey.command(command.threadId(), command.sequence()));
    }
  }

  private void requireUniqueCommandKey(ThreadCommand command) {
    for (ThreadCommand existing : state.commands.values()) {
      if (existing.threadId().equals(command.threadId())
          && existing.sequence() == command.sequence()) {
        throw new IllegalArgumentException(
            "command sequence "
                + command.sequence()
                + " already used on thread "
                + command.threadId());
      }
      if (existing.threadId().equals(command.threadId())
          && existing.idempotencyKey().equals(command.idempotencyKey())) {
        throw new IllegalArgumentException(
            "idempotencyKey "
                + command.idempotencyKey()
                + " already used on thread "
                + command.threadId());
      }
    }
  }

  @Override
  public void update(List<ThreadCommand> commands) {
    tx.checkOpen();
    InMemoryHarnessLocks locks = tx.locks();
    List<ThreadCommand> copied =
        List.copyOf(commands).stream().sorted(InMemoryHarnessLocks.COMMAND_LOCK_ORDER).toList();
    for (ThreadCommand command : copied) {
      HarnessStoreTime.requireMillisecondPrecision(command.cancelledAt());
      HarnessStoreTime.requireMillisecondPrecision(command.createdAt());
      ThreadCommand stored =
          state.commands.get(new InMemoryState.CommandKey(command.threadId(), command.sequence()));
      if (stored == null) {
        throw new IllegalArgumentException(
            "command " + command.threadId() + "/" + command.sequence() + " does not exist");
      }
      locks.requireLocked(
          InMemoryHarnessLocks.LockKey.command(command.threadId(), command.sequence()));
      requireSameCommandIdentity(stored, command);
      // 锁序 Thread -> commands：harvest / stop 更新 mailbox 前必须先锁定相关 Thread；身份检查先行，
      // 伪造身份的 update 仍以 IllegalArgumentException 拒绝。
      locks.requireLocked(InMemoryHarnessLocks.LockKey.thread(command.threadId()));
      requireValidCommandLifecycle(stored, command);
      requireValidAppliedEntry(command);
    }
    for (ThreadCommand command : copied) {
      state.commands.put(
          new InMemoryState.CommandKey(command.threadId(), command.sequence()), command);
    }
  }

  /**
   * appliedEntryId（若有）必须与命令类型精确匹配：NOTIFICATION 必须引用自身四字段完全一致的 NOTIFICATION Entry；standalone
   * 分支设置命令（SET_AGENT / SET_MODEL / SET_ENVIRONMENT）可以引用本 Thread 拥有的 SETTINGS 快照 Entry；其它命令必须引用本
   * Thread 拥有的 TURN_START Entry（不得借用同 Session 的任意 Entry）。
   */
  private void requireValidAppliedEntry(ThreadCommand command) {
    UUID appliedEntryId = command.appliedEntryId();
    if (appliedEntryId == null) {
      return;
    }
    Entry applied = tx.requireExistingEntry(appliedEntryId);
    ThreadState thread = state.threads.get(command.threadId());
    if (thread == null) {
      throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
    }
    if (!applied.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException("consumed entry must be in the command thread's session");
    }
    if (command.type().isNotification()) {
      if (!(applied.payload() instanceof NotificationPayload notification)
          || !(command.payload() instanceof NotificationCommandPayload submitted)) {
        throw new IllegalArgumentException(
            "NOTIFICATION command must apply its own NOTIFICATION entry");
      }
      if (!notification.notificationId().equals(submitted.notificationId())
          || notification.kind() != submitted.kind()
          || !notification.sourceThreadId().equals(submitted.sourceThreadId())
          || !notification.message().equals(submitted.message())) {
        throw new IllegalArgumentException(
            "NOTIFICATION applied entry must match the command payload");
      }
      return;
    }
    if (isBranchSettingCommand(command.type())
        && applied.payload() instanceof SettingsPayload settings
        && command.threadId().equals(settings.ownerThreadId())) {
      return;
    }
    if (!(applied.payload() instanceof TurnStartPayload turnStartPayload)
        || !command.threadId().equals(turnStartPayload.ownerThreadId())) {
      throw new IllegalArgumentException(
          "appliedEntryId must reference a TURN_START or SETTINGS entry owned by the command"
              + " thread");
    }
  }

  private static boolean isBranchSettingCommand(ThreadCommandType type) {
    return type == ThreadCommandType.SET_AGENT
        || type == ThreadCommandType.SET_MODEL
        || type == ThreadCommandType.SET_ENVIRONMENT;
  }

  /**
   * QUEUED 只能推进为 APPLIED / CANCELLED；terminal 行只接受 exact-idempotent 重放（相同 marker），禁止
   * terminal-&gt;QUEUED、APPLIED&lt;-&gt;CANCELLED 或 terminal marker 改变。
   */
  private static void requireValidCommandLifecycle(ThreadCommand stored, ThreadCommand command) {
    if (stored.appliedEntryId() != null || stored.cancelledAt() != null) {
      if (!Objects.equals(stored.appliedEntryId(), command.appliedEntryId())
          || !Objects.equals(stored.cancelledAt(), command.cancelledAt())) {
        throw new IllegalArgumentException(
            "terminal commands must be updated exactly idempotently");
      }
      return;
    }
    boolean consumed = command.appliedEntryId() != null;
    boolean cancelled = command.cancelledAt() != null;
    if (consumed == cancelled) {
      // 两者都缺失：QUEUED -> QUEUED；两者都存在时已被 record contract 直接拒绝。
      throw new IllegalArgumentException(
          "queued commands may only transition to APPLIED or CANCELLED");
    }
  }

  private static void requireSameCommandIdentity(ThreadCommand stored, ThreadCommand command) {
    if (!stored.threadId().equals(command.threadId())
        || !stored.payload().equals(command.payload())
        || !stored.idempotencyKey().equals(command.idempotencyKey())
        || !stored.requestHash().equals(command.requestHash())
        || stored.sequence() != command.sequence()
        || !stored.createdAt().equals(command.createdAt())) {
      throw new IllegalArgumentException(
          "command identity (thread/payload/idempotencyKey/requestHash/sequence/createdAt) must not change");
    }
  }

  private record CommandSequenceKey(UUID threadId, long sequence) {}

  private record CommandClientKey(UUID threadId, UUID idempotencyKey) {}
}
