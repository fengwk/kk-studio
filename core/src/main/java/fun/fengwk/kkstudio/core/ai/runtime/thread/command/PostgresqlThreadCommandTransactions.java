package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationStore;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshotJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ResponseDebtDetector;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * final PostgreSQL {@link ThreadCommandTransactions} 实现。
 *
 * <p>所有 command mutation 锁定 owning Thread，再写 mailbox 或 fencing state。它绝不读取 live Definition，也不创建
 * Provider I/O resource；message payload 只保存本轮可见名称引用。
 */
@Service
public class PostgresqlThreadCommandTransactions implements ThreadCommandTransactions {

  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();
  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();
  private static final SafeStreamSnapshotJsonCodec SNAPSHOT_CODEC =
      new SafeStreamSnapshotJsonCodec();
  private static final ResponseDebtDetector DEBT_DETECTOR = new ResponseDebtDetector();

  private final ThreadCommandMapper mapper;
  private final HarnessIdGenerator idGenerator;
  private final ExecutionActivationStore executionActivationStore;

  @Autowired
  public PostgresqlThreadCommandTransactions(
      ThreadCommandMapper mapper,
      HarnessIdGenerator idGenerator,
      ExecutionActivationStore executionActivationStore) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.executionActivationStore =
        Objects.requireNonNull(executionActivationStore, "executionActivationStore");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public HarnessThread createThread(String title, Instant now) {
    Instant persistedNow = persistenceInstant(now);
    long sessionId = idGenerator.nextSessionId();
    long rootEntryId = idGenerator.nextEntryId();
    long threadId = idGenerator.nextThreadId();
    OffsetDateTime timestamp = offset(persistedNow);

    requireAffected(mapper.insertSession(sessionId, title, timestamp), "insert session");
    requireAffected(
        mapper.insertEntry(
            rootEntryId,
            sessionId,
            null,
            EntryType.ROOT.name(),
            ENTRY_CODEC.encode(new RootEntryPayload()),
            timestamp),
        "insert root entry");
    requireAffected(mapper.insertThread(threadId, rootEntryId, timestamp), "insert thread");
    return new HarnessThread(
        threadId, rootEntryId, 0, false, 0, 0, null, persistedNow, persistedNow);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public HarnessThread updateHead(
      long threadId, long expectedExecutionEpoch, long headEntryId, Instant now) {
    requirePositive(threadId, "threadId");
    requirePositive(headEntryId, "headEntryId");
    Instant persistedNow = persistenceInstant(now);
    ThreadCommandRow thread = lockRebindableThread(threadId, expectedExecutionEpoch, persistedNow);
    if (mapper.findEntry(headEntryId) == null) {
      throw new IllegalArgumentException("unknown entry: " + headEntryId);
    }
    return rebind(thread, expectedExecutionEpoch, headEntryId, persistedNow);
  }

  /**
   * 锁定 Thread 并校验它当前逻辑静止：epoch 匹配、无有效 processor lease、无 queued Input，且当前 epoch 没有仍可提交结果的
   * Invocation/Interaction。
   */
  private ThreadCommandRow lockRebindableThread(
      long threadId, long expectedExecutionEpoch, Instant observedAt) {
    ThreadCommandRow thread = lockThread(threadId);
    requireEpoch(thread, expectedExecutionEpoch);
    if (thread.getProcessorUntil() != null
        && thread.getProcessorUntil().toInstant().isAfter(observedAt)) {
      throw new IllegalStateException("thread is processing: " + threadId);
    }
    if (Boolean.TRUE.equals(thread.getRunnable())) {
      throw new IllegalStateException("thread has pending work: " + threadId);
    }
    if (!mapper.listQueuedInputsForUpdate(threadId).isEmpty()) {
      throw new IllegalStateException("thread has queued inputs: " + threadId);
    }
    if (mapper.hasActiveExecution(threadId, expectedExecutionEpoch)) {
      throw new IllegalStateException("thread has active execution: " + threadId);
    }
    return thread;
  }

  /** epoch fencing rebind：旧代际的外部完成不再能写入新的 head。 */
  private HarnessThread rebind(
      ThreadCommandRow thread,
      long expectedExecutionEpoch,
      long headEntryId,
      Instant persistedNow) {
    long nextEpoch = Math.addExact(expectedExecutionEpoch, 1);
    executionActivationStore.deleteIfExists(ExecutionTargetKind.THREAD, thread.getId());
    requireAffected(
        mapper.rebindHead(
            thread.getId(), expectedExecutionEpoch, nextEpoch, headEntryId, offset(persistedNow)),
        "rebind thread head");
    return new HarnessThread(
        thread.getId(),
        headEntryId,
        thread.getInputSequence(),
        false,
        nextEpoch,
        Math.addExact(thread.getRevision(), 1),
        null,
        thread.getCreatedAt().toInstant(),
        persistedNow);
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
    return existing == null ? Optional.empty() : Optional.of(new EnqueueResult(toInput(existing)));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public EnqueueResult enqueue(
      long threadId,
      ThreadInputPayload payload,
      String idempotencyKey,
      long expectedExecutionEpoch,
      Instant now) {
    requirePositive(threadId, "threadId");
    payload = Objects.requireNonNull(payload, "payload");
    requireNonBlank(idempotencyKey, "idempotencyKey");
    Instant persistedNow = persistenceInstant(now);
    ThreadCommandRow thread = lockThread(threadId);
    ThreadCommandRow existing = mapper.findInputByKeyForUpdate(threadId, idempotencyKey);
    if (existing != null) {
      return new EnqueueResult(toInput(existing));
    }
    requireEpoch(thread, expectedExecutionEpoch);
    if (!payload.type().isMessage() || !(payload instanceof RuntimeEntryInputPayload)) {
      throw new IllegalArgumentException("thread input payload must be a USER/CUSTOM message");
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
    ensureThreadActivation(thread, persistedNow);
    return new EnqueueResult(input);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StopResult stop(long threadId, long expectedExecutionEpoch, Instant now) {
    requirePositive(threadId, "threadId");
    Instant persistedNow = persistenceInstant(now);
    ThreadCommandRow thread = lockThread(threadId);
    requireEpoch(thread, expectedExecutionEpoch);
    OffsetDateTime timestamp = offset(persistedNow);
    long headEntryId = thread.getHeadEntryId();
    long sessionId = thread.getSessionId();

    // 1) 用纯债务检测器判定当前 head 的 response debt，不读取任何 live definition。
    boolean hasDebt = DEBT_DETECTOR.hasDebt(loadPathAsSessionEntries(sessionId, headEntryId));

    // 2) 仅在 head 有 response debt 时才尝试读取该 head 的安全流快照；快照来源必须限制在当前 head 与 epoch。
    SafeStreamSnapshot safeSnapshot = SafeStreamSnapshot.EMPTY;
    boolean safeSnapshotEligible = false;
    if (hasDebt) {
      String snapshotJson =
          mapper.findSafeStreamSnapshotByHead(threadId, expectedExecutionEpoch, headEntryId);
      if (snapshotJson != null) {
        safeSnapshot = SNAPSHOT_CODEC.decode(snapshotJson);
        safeSnapshotEligible = true;
      }
    }

    // 3) 按 (debt, safeSnapshot.hasContent) 决定 barrier 类型；只有 durable assistant execution 阶段
    //    (hasDebt == false) 或没有该 head 的快照时不追加 aborted entry。无快照/空快照时只落 cancellation barrier；
    //    永远不物化 tool fragment，绝不创建 ToolInvocation，绝不重建旧 debt 的 ModelInvocation。
    if (hasDebt) {
      EntryPayload barrierPayload;
      if (safeSnapshotEligible && safeSnapshot.hasContent()) {
        barrierPayload =
            AssistantAbortedEntryPayload.ofTextAndThinking(
                safeSnapshot.text(), safeSnapshot.thinking());
      } else {
        barrierPayload =
            new AssistantErrorEntryPayload(
                new ModelInvocationError(
                    ProviderErrorKind.CANCELLED, "model invocation cancelled"));
      }
      EntryType barrierType = barrierPayload.type();
      String payloadJson = ENTRY_CODEC.encode(barrierPayload);
      long barrierEntryId = idGenerator.nextEntryId();
      requireAffected(
          mapper.insertEntry(
              barrierEntryId, sessionId, headEntryId, barrierType.name(), payloadJson, timestamp),
          "insert aborted barrier entry");
      requireAffected(
          mapper.rebindHead(
              threadId, expectedExecutionEpoch, expectedExecutionEpoch, barrierEntryId, timestamp),
          "rebind head after aborted barrier");
    }

    // 4) 终止排队/安全的 inputs 与 invocations，并 fence epoch。
    long nextEpoch = Math.addExact(expectedExecutionEpoch, 1);
    requireAffected(mapper.fenceAndStopThread(threadId, nextEpoch, timestamp), "fence thread");
    List<ThreadInput> cancelled =
        mapper.listQueuedInputsForUpdate(threadId).stream()
            .map(PostgresqlThreadCommandTransactions::toCancelledInput)
            .toList();
    mapper.cancelQueuedInputs(threadId);
    mapper.cancelSafeModelInvocations(threadId, timestamp);
    mapper.cancelSafeToolInvocations(threadId, timestamp);
    // A stopped Tool can no longer answer a permission prompt, so discard its OPEN product fact.
    mapper.deleteOpenToolPermissionInteractions(threadId);
    mapper.deleteActivationsForStoppedThread(threadId);
    return new StopResult(nextEpoch, cancelled);
  }

  private void ensureThreadActivation(ThreadCommandRow thread, Instant now) {
    boolean activeProcessor =
        thread.getProcessorToken() != null
            && thread.getProcessorUntil() != null
            && thread.getProcessorUntil().toInstant().isAfter(now);
    if (activeProcessor) {
      if (executionActivationStore.lock(ExecutionTargetKind.THREAD, thread.getId()).isEmpty()) {
        throw new IllegalStateException(
            "active thread processor is missing its watchdog activation: " + thread.getId());
      }
      return;
    }
    executionActivationStore.schedule(ExecutionTargetKind.THREAD, thread.getId(), null, now);
  }

  /** 把 {@code harness_entry} 路径还原成 {@link SessionEntry}（仅用于纯 response debt 检测，不被持久化）。 */
  private List<SessionEntry> loadPathAsSessionEntries(long sessionId, long headEntryId) {
    List<ThreadCommandRow> rows = mapper.findEntryPathForPlanner(sessionId, headEntryId);
    List<SessionEntry> entries = new ArrayList<>(rows.size());
    for (ThreadCommandRow row : rows) {
      EntryPayload payload =
          ENTRY_CODEC.decode(EntryType.valueOf(row.getEntryType()), row.getPayloadJson());
      entries.add(new SessionEntry(row.getId(), row.getParentEntryId(), payload));
    }
    return entries;
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

  private static ThreadInput toCancelledInput(ThreadCommandRow row) {
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

  private static Instant persistenceInstant(Instant value) {
    return Objects.requireNonNull(value, "now").truncatedTo(ChronoUnit.MILLIS);
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requireEpoch(ThreadCommandRow row, long expected) {
    if (row.getExecutionEpoch() == null || row.getExecutionEpoch() != expected) {
      throw new IllegalStateException("stale execution epoch");
    }
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
