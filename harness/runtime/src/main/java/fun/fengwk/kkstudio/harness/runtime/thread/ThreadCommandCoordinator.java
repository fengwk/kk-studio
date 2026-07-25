package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework-free Thread command orchestration.
 *
 * <p>Owns typed payload construction, content/role validation, current-config selection, snapshot
 * replacement via {@link RuntimeConfigSource}, and durable {@link ThreadCommandTransactions} calls.
 * Callers remain responsible for outer transactions, decimal/DTO boundaries and after-commit
 * activation.
 *
 * <p>Idempotent retries always short-circuit through {@link #findExistingInput(long, String)}
 * before any live resource resolution or payload validation that depends on changed request fields.
 */
public final class ThreadCommandCoordinator {

  private final ThreadCommandTransactions transactions;
  private final RuntimeConfigSource configSource;
  private final Clock clock;

  public ThreadCommandCoordinator(
      ThreadCommandTransactions transactions, RuntimeConfigSource configSource, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.configSource = Objects.requireNonNull(configSource, "configSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Creates a branch Thread from a session entry. */
  public HarnessThread createBranch(long sessionId, long fromEntryId) {
    return transactions.createBranch(sessionId, fromEntryId, clock.instant());
  }

  /**
   * Looks up a previously persisted input by stable idempotency key.
   *
   * <p>Command facades must call this before parsing live resource ids that may have become
   * invalid, so retries short-circuit without re-validating live definitions/models.
   */
  public Optional<ThreadCommandTransactions.EnqueueResult> findExistingInput(
      long threadId, String idempotencyKey) {
    return transactions.findExistingInput(threadId, idempotencyKey);
  }

  /** Enqueues a user message after content validation; retries short-circuit first. */
  public ThreadCommandTransactions.EnqueueResult submitUserMessage(
      long threadId, String content, String idempotencyKey) {
    Optional<ThreadCommandTransactions.EnqueueResult> existing =
        findExistingInput(threadId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER,
            List.<AgentMessageContent>of(new TextMessageContent(requireContent(content))));
    return enqueue(
        threadId,
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE, new MessageEntryPayload(message)),
        idempotencyKey);
  }

  /** Enqueues a custom system/user message; retries short-circuit before role/content checks. */
  public ThreadCommandTransactions.EnqueueResult submitCustomMessage(
      long threadId, String role, String content, String idempotencyKey) {
    Optional<ThreadCommandTransactions.EnqueueResult> existing =
        findExistingInput(threadId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    AgentMessage message =
        new AgentMessage(
            parseCustomRole(role),
            List.<AgentMessageContent>of(new TextMessageContent(requireContent(content))));
    return enqueue(
        threadId,
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE, new CustomMessageEntryPayload(message)),
        idempotencyKey);
  }

  /** Freezes a pure YOLO policy replacement onto the current thread config and enqueues it. */
  public ThreadCommandTransactions.EnqueueResult queueYolo(
      long threadId, boolean yoloEnabled, String idempotencyKey) {
    Optional<ThreadCommandTransactions.EnqueueResult> existing =
        findExistingInput(threadId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    RuntimeConfigSnapshot current =
        transactions
            .lockAndFindCurrentConfig(threadId)
            .orElseThrow(
                () -> new IllegalStateException("thread has no runtime config: " + threadId));
    RuntimeConfigSnapshot frozen = current.withYoloEnabled(yoloEnabled);
    return enqueue(
        threadId, new RuntimeConfigInputPayload(ThreadInputType.SET_YOLO, frozen), idempotencyKey);
  }

  /**
   * Resolves a live agent definition into a frozen snapshot and enqueues SET_AGENT.
   *
   * <p>Callers that must parse a live definition id from external input should short-circuit via
   * {@link #findExistingInput(long, String)} first so deleted/invalid ids never reach this method
   * on an idempotent retry.
   */
  public ThreadCommandTransactions.EnqueueResult queueAgent(
      long threadId, long definitionId, boolean defaultYolo, String idempotencyKey) {
    Optional<ThreadCommandTransactions.EnqueueResult> existing =
        findExistingInput(threadId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    boolean yolo =
        transactions
            .lockAndFindCurrentConfig(threadId)
            .map(snapshot -> snapshot.policy().yoloEnabled())
            .orElse(defaultYolo);
    RuntimeConfigSnapshot frozen = configSource.resolveAgent(definitionId, yolo);
    return enqueue(
        threadId, new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, frozen), idempotencyKey);
  }

  /**
   * Replaces the model on the current thread config via live model resolution and enqueues
   * SET_MODEL.
   *
   * <p>Same idempotency ordering as {@link #queueAgent(long, long, boolean, String)}.
   */
  public ThreadCommandTransactions.EnqueueResult queueModel(
      long threadId, long modelId, String variant, String idempotencyKey) {
    Optional<ThreadCommandTransactions.EnqueueResult> existing =
        findExistingInput(threadId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    RuntimeConfigSnapshot current =
        transactions
            .lockAndFindCurrentConfig(threadId)
            .orElseThrow(
                () -> new IllegalStateException("thread has no runtime config: " + threadId));
    RuntimeConfigSnapshot frozen = configSource.replaceModel(current, modelId, variant);
    return enqueue(
        threadId, new RuntimeConfigInputPayload(ThreadInputType.SET_MODEL, frozen), idempotencyKey);
  }

  /** Epoch-fencing stop of the thread. */
  public ThreadCommandTransactions.StopResult stop(long threadId) {
    return transactions.stop(threadId, clock.instant());
  }

  private ThreadCommandTransactions.EnqueueResult enqueue(
      long threadId, ThreadInputPayload payload, String idempotencyKey) {
    return transactions.enqueue(threadId, payload, idempotencyKey, clock.instant());
  }

  private static String requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    return content;
  }

  private static AgentMessageRole parseCustomRole(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("role must not be blank");
    }
    AgentMessageRole role;
    try {
      role = AgentMessageRole.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unsupported custom message role: " + value, error);
    }
    if (role != AgentMessageRole.SYSTEM && role != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be system or user");
    }
    return role;
  }
}
