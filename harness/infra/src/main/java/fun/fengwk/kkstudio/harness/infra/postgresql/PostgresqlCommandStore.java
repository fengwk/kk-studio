package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockRank;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.CommandStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ThreadCommand 聚合的 PostgreSQL 实现。 */
final class PostgresqlCommandStore extends PostgresqlHarnessView implements CommandStore {

  private record CommandSequenceKey(UUID threadId, long sequence) {}

  private record CommandIdempotencyKey(UUID threadId, UUID idempotencyKey) {}

  PostgresqlCommandStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public Optional<ThreadCommand> findByIdempotencyKey(UUID threadId, UUID idempotencyKey) {
    checkOpen();
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return queryOne(
        """
        select *
        from harness_thread_command
        where thread_id = ? and idempotency_key = ?
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId,
        idempotencyKey);
  }

  @Override
  public List<ThreadCommand> loadQueued(UUID threadId) {
    checkOpen();
    requireLocked(LockKey.thread(threadId));
    requireCanLockRank(LockRank.COMMAND, threadId);
    List<ThreadCommand> commands =
        queryList(
            """
            select *
            from harness_thread_command
            where thread_id = ?
              and applied_entry_id is null
              and cancelled_at is null
            order by sequence
            for update
            """,
            PostgresqlHarnessRows.COMMAND,
            threadId);
    for (ThreadCommand command : commands) {
      lockResource(LockKey.command(command.threadId(), command.sequence()));
    }
    return commands;
  }

  @Override
  public boolean hasAppliedNotificationAfter(UUID threadId, long afterSequence) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Boolean found =
        queryForObject(
            """
            select exists(
              select 1
              from harness_thread_command
              where thread_id = ? and sequence > ?
                and command_type = 'NOTIFICATION' and applied_entry_id is not null
            )
            """,
            Boolean.class,
            threadId,
            afterSequence);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public List<ThreadCommand> loadCancelledByRequest(UUID threadId, UUID stopRequestId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    return queryList(
        """
        select *
        from harness_thread_command
        where thread_id = ? and stop_request_id = ?
        order by sequence
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId,
        stopRequestId);
  }

  @Override
  public void insert(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied =
        List.copyOf(commands).stream()
            .sorted(PostgresqlHarnessTransaction.COMMAND_LOCK_ORDER)
            .toList();
    Set<CommandSequenceKey> sequences = new HashSet<>();
    Set<CommandIdempotencyKey> keys = new HashSet<>();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
      if (!sequences.add(new CommandSequenceKey(command.threadId(), command.sequence()))) {
        throw new IllegalArgumentException(
            "duplicate command sequence "
                + command.sequence()
                + " on thread "
                + command.threadId());
      }
      if (!keys.add(new CommandIdempotencyKey(command.threadId(), command.idempotencyKey()))) {
        throw new IllegalArgumentException(
            "duplicate idempotencyKey "
                + command.idempotencyKey()
                + " on thread "
                + command.threadId());
      }
      if (command.state() != ThreadCommandState.QUEUED) {
        throw new IllegalArgumentException("inserted commands must be QUEUED");
      }
      if (tx.threads().find(command.threadId()).isEmpty()) {
        throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
      }
      // 锁序 Thread -> commands：enqueue 必须先锁定目标 Thread，且 COMMAND 的树锁例外只对同一执行树生效。
      requireLocked(LockKey.thread(command.threadId()));
      requireCanLockRank(LockRank.COMMAND, command.threadId());
      requireUniqueCommandKey(command);
    }
    for (ThreadCommand command : copied) {
      updateJdbc(
          """
          insert into harness_thread_command (
              thread_id, sequence, command_type, payload, idempotency_key,
              request_hash, applied_entry_id, stop_request_id,
              cancelled_at, created_at
          ) values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
          """,
          command.threadId(),
          command.sequence(),
          command.type().name(),
          PostgresqlHarnessRows.COMMAND_PAYLOADS.encode(command.payload()),
          command.idempotencyKey(),
          command.requestHash(),
          command.appliedEntryId(),
          command.stopRequestId(),
          PostgresqlHarnessRows.timestamp(command.cancelledAt()),
          PostgresqlHarnessRows.timestamp(command.createdAt()));
      lockResource(LockKey.command(command.threadId(), command.sequence()));
    }
  }

  @Override
  public void update(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied =
        List.copyOf(commands).stream()
            .sorted(PostgresqlHarnessTransaction.COMMAND_LOCK_ORDER)
            .toList();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
      ThreadCommand stored =
          tx.commands()
              .find(command.threadId(), command.sequence())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "command "
                              + command.threadId()
                              + "/"
                              + command.sequence()
                              + " does not exist"));
      requireLocked(LockKey.command(command.threadId(), command.sequence()));
      requireSameCommandIdentity(stored, command);
      requireLocked(LockKey.thread(command.threadId()));
      requireValidCommandLifecycle(stored, command);
      requireValidAppliedEntry(command);
    }
    for (ThreadCommand command : copied) {
      int updated =
          updateJdbc(
              """
              update harness_thread_command
              set applied_entry_id = ?, stop_request_id = ?, cancelled_at = ?
              where thread_id = ? and sequence = ?
              """,
              command.appliedEntryId(),
              command.stopRequestId(),
              PostgresqlHarnessRows.timestamp(command.cancelledAt()),
              command.threadId(),
              command.sequence());
      requireSingleUpdate(updated, "command", command.threadId() + "/" + command.sequence());
    }
  }

  @Override
  public Optional<ThreadCommand> find(UUID threadId, long sequence) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return queryOne(
        "select * from harness_thread_command where thread_id = ? and sequence = ?",
        PostgresqlHarnessRows.COMMAND,
        threadId,
        sequence);
  }

  private void requireUniqueCommandKey(ThreadCommand command) {
    Boolean sequenceExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and sequence = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.sequence());
    if (Boolean.TRUE.equals(sequenceExists)) {
      throw new IllegalArgumentException(
          "command sequence "
              + command.sequence()
              + " already used on thread "
              + command.threadId());
    }
    Boolean clientExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and idempotency_key = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.idempotencyKey());
    if (Boolean.TRUE.equals(clientExists)) {
      throw new IllegalArgumentException(
          "idempotencyKey "
              + command.idempotencyKey()
              + " already used on thread "
              + command.threadId());
    }
  }

  /**
   * {@code appliedEntryId}（若有）必须与命令类型精确匹配：{@code NOTIFICATION} 必须引用自身四字段完全一致的 NOTIFICATION
   * Entry；standalone 分支设置命令（SET_AGENT / SET_MODEL / SET_ENVIRONMENT）可以引用本 Thread 拥有的 SETTINGS 快照
   * Entry；其它命令 必须引用本 Thread 拥有的 TURN_START Entry（不得借用同 Session 的任意 Entry）。
   */
  private void requireValidAppliedEntry(ThreadCommand command) {
    UUID appliedEntryId = command.appliedEntryId();
    if (appliedEntryId == null) {
      return;
    }
    Entry applied = requireExistingEntry(appliedEntryId);
    ThreadState thread =
        tx.threads()
            .find(command.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + command.threadId() + " does not exist"));
    if (!applied.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException("applied entry must be in the command thread's session");
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
    if (!(applied.payload() instanceof TurnStartPayload turnStart)
        || !command.threadId().equals(turnStart.ownerThreadId())) {
      throw new IllegalArgumentException(
          "applied entry must be a TURN_START or SETTINGS entry owned by the command thread");
    }
  }

  private static boolean isBranchSettingCommand(ThreadCommandType type) {
    return type == ThreadCommandType.SET_AGENT
        || type == ThreadCommandType.SET_MODEL
        || type == ThreadCommandType.SET_ENVIRONMENT;
  }

  private static void requireValidCommandLifecycle(ThreadCommand stored, ThreadCommand command) {
    if (stored.appliedEntryId() != null || stored.cancelledAt() != null) {
      if (!Objects.equals(stored.appliedEntryId(), command.appliedEntryId())
          || !Objects.equals(stored.cancelledAt(), command.cancelledAt())) {
        throw new IllegalArgumentException(
            "terminal commands must be updated exactly idempotently");
      }
      return;
    }
    boolean applied = command.appliedEntryId() != null;
    boolean cancelled = command.cancelledAt() != null;
    if (applied == cancelled) {
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
}
