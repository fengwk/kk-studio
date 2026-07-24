package fun.fengwk.kkstudio.core.harness.thread.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.session.EntryType;
import fun.fengwk.kkstudio.harness.kernel.session.Session;
import fun.fengwk.kkstudio.harness.kernel.session.SessionEntry;
import fun.fengwk.kkstudio.harness.kernel.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.kernel.thread.InputStatus;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * final PostgreSQL {@link ThreadCommandTransactions} 实现。
 *
 * <p>所有 command mutation 锁定 owning Thread，再写 mailbox 或 fencing state。它绝不读取 live Definition，也不创建
 * Provider I/O resource；配置快照已经由 command service 在 enqueue 前冻结。
 */
@Service
public class PostgresqlThreadCommandTransactions implements ThreadCommandTransactions {

  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();
  private static final RuntimeConfigJsonCodec CONFIG_CODEC = new RuntimeConfigJsonCodec();
  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();

  private final ThreadCommandMapper mapper;
  private final HarnessIdGenerator idGenerator;

  public PostgresqlThreadCommandTransactions(
      ThreadCommandMapper mapper, HarnessIdGenerator idGenerator) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SessionCreation createSession(
      String title, RuntimeConfigSnapshot initialConfig, Instant now) {
    Objects.requireNonNull(initialConfig, "initialConfig");
    Instant persistedNow = persistenceInstant(now);
    long sessionId = idGenerator.nextSessionId();
    long rootEntryId = idGenerator.nextEntryId();
    long configEntryId = idGenerator.nextEntryId();
    long threadId = idGenerator.nextThreadId();
    OffsetDateTime timestamp = offset(persistedNow);

    requireAffected(mapper.insertSession(sessionId, title, threadId, timestamp), "insert session");
    requireAffected(
        mapper.insertEntry(
            rootEntryId,
            sessionId,
            null,
            EntryType.ROOT.name(),
            ENTRY_CODEC.encode(new RootEntryPayload()),
            timestamp),
        "insert root entry");
    requireAffected(
        mapper.insertEntry(
            configEntryId,
            sessionId,
            rootEntryId,
            EntryType.RUNTIME_CONFIG.name(),
            ENTRY_CODEC.encode(initialConfig),
            timestamp),
        "insert runtime config entry");
    requireAffected(
        mapper.insertThread(threadId, sessionId, configEntryId, timestamp), "insert main thread");
    Session session =
        new Session(sessionId, threadId, title, null, null, persistedNow, persistedNow);
    SessionEntry root =
        new SessionEntry(
            rootEntryId, sessionId, null, EntryType.ROOT, new RootEntryPayload(), persistedNow);
    HarnessThread thread =
        new HarnessThread(
            threadId, sessionId, configEntryId, 0, false, 0, null, persistedNow, persistedNow);
    return new SessionCreation(session, root, thread);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public HarnessThread createBranch(long sessionId, long fromEntryId, Instant now) {
    requirePositive(sessionId, "sessionId");
    requirePositive(fromEntryId, "fromEntryId");
    Instant persistedNow = persistenceInstant(now);
    if (mapper.findSession(sessionId) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    if (mapper.findEntry(sessionId, fromEntryId) == null) {
      throw new IllegalArgumentException(
          "entry " + fromEntryId + " does not belong to session " + sessionId);
    }
    long threadId = idGenerator.nextThreadId();
    requireAffected(
        mapper.insertThread(threadId, sessionId, fromEntryId, offset(persistedNow)),
        "insert branch thread");
    return new HarnessThread(
        threadId, sessionId, fromEntryId, 0, false, 0, null, persistedNow, persistedNow);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(long threadId) {
    ThreadCommandRow thread = lockThread(threadId);
    ThreadCommandRow row =
        mapper.findEffectiveRuntimeConfig(thread.getSessionId(), thread.getHeadEntryId(), threadId);
    return row == null ? Optional.empty() : Optional.of(CONFIG_CODEC.decode(row.getPayloadJson()));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
    requirePositive(threadId, "threadId");
    requireNonBlank(idempotencyKey, "idempotencyKey");
    // A missing unique-key row cannot be locked. Lock its owning Thread first so the facade's
    // outer transaction serializes the retry check with live snapshot resolution and enqueue.
    lockThread(threadId);
    ThreadCommandRow existing = mapper.findInputByKeyForUpdate(threadId, idempotencyKey);
    return existing == null
        ? Optional.empty()
        : Optional.of(new EnqueueResult(toInput(existing), threadTarget(threadId)));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public EnqueueResult enqueue(
      long threadId, ThreadInputPayload payload, String idempotencyKey, Instant now) {
    requirePositive(threadId, "threadId");
    payload = Objects.requireNonNull(payload, "payload");
    requireNonBlank(idempotencyKey, "idempotencyKey");
    Instant persistedNow = persistenceInstant(now);
    ThreadCommandRow thread = lockThread(threadId);

    ThreadCommandRow existing = mapper.findInputByKeyForUpdate(threadId, idempotencyKey);
    if (existing != null) {
      return new EnqueueResult(toInput(existing), threadTarget(threadId));
    }

    long sequence = Math.addExact(thread.getInputSequence(), 1);
    requireAffected(
        mapper.advanceInputSequenceAndMarkRunnable(threadId, sequence, offset(persistedNow)),
        "advance input sequence");
    long inputId = idGenerator.nextInputId();
    requireAffected(
        mapper.insertInput(
            inputId,
            threadId,
            sequence,
            payload.type().name(),
            INPUT_CODEC.encode(payload),
            idempotencyKey,
            offset(persistedNow)),
        "insert thread input");
    ThreadInput input =
        new ThreadInput(
            inputId,
            threadId,
            sequence,
            payload.type(),
            payload,
            idempotencyKey,
            InputStatus.QUEUED,
            persistedNow,
            null);
    return new EnqueueResult(input, threadTarget(threadId));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StopResult stop(long threadId, Instant now) {
    requirePositive(threadId, "threadId");
    Instant persistedNow = persistenceInstant(now);
    ThreadCommandRow thread = lockThread(threadId);
    List<ThreadInput> cancelled =
        mapper.listQueuedInputsForUpdate(threadId).stream()
            .map(row -> toCancelledInput(row, persistedNow))
            .toList();
    long nextEpoch = Math.addExact(thread.getExecutionEpoch(), 1);
    OffsetDateTime timestamp = offset(persistedNow);
    requireAffected(mapper.fenceAndStopThread(threadId, nextEpoch, timestamp), "fence thread");
    mapper.cancelQueuedInputs(threadId);
    mapper.cancelSafeModelInvocations(threadId, timestamp);
    mapper.cancelSafeToolInvocations(threadId, timestamp);
    return new StopResult(nextEpoch, cancelled, threadTarget(threadId));
  }

  private ThreadCommandRow lockThread(long threadId) {
    requirePositive(threadId, "threadId");
    ThreadCommandRow thread = mapper.findThreadForUpdate(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return thread;
  }

  private static ThreadInput toInput(ThreadCommandRow row) {
    ThreadInputType type = ThreadInputType.valueOf(row.getInputType());
    return new ThreadInput(
        row.getId(),
        row.getThreadId(),
        row.getSequence(),
        type,
        INPUT_CODEC.decode(type, row.getPayloadJson()),
        row.getIdempotencyKey(),
        InputStatus.valueOf(row.getStatus()),
        row.getCreatedAt().toInstant(),
        row.getAppliedAt() == null ? null : row.getAppliedAt().toInstant());
  }

  private static ThreadInput toCancelledInput(ThreadCommandRow row, Instant cancelledAt) {
    ThreadInputType type = ThreadInputType.valueOf(row.getInputType());
    return new ThreadInput(
        row.getId(),
        row.getThreadId(),
        row.getSequence(),
        type,
        INPUT_CODEC.decode(type, row.getPayloadJson()),
        row.getIdempotencyKey(),
        InputStatus.CANCELLED,
        row.getCreatedAt().toInstant(),
        null);
  }

  private static ExecutionTarget threadTarget(long threadId) {
    return new ExecutionTarget(ExecutionTargetKind.THREAD, threadId);
  }

  private static Instant persistenceInstant(Instant value) {
    return Objects.requireNonNull(value, "now").truncatedTo(ChronoUnit.MILLIS);
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }
}
