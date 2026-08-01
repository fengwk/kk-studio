package fun.fengwk.kkstudio.harness.runtime.thread;

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
 * <p>Each response-producing message carries its own compact {@link TurnSettings} reference. Live
 * definitions are intentionally absent from this command boundary; they are resolved later by the
 * planner for the specific provider query.
 */
public final class ThreadCommandCoordinator {

  private final ThreadCommandTransactions transactions;
  private final Clock clock;

  public ThreadCommandCoordinator(ThreadCommandTransactions transactions, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Atomically creates the Session, ROOT entry, and already-bound Thread. */
  public HarnessThread createThread(String title) {
    return transactions.createThread(title, clock.instant());
  }

  public HarnessThread updateHead(long threadId, long expectedExecutionEpoch, long headEntryId) {
    return transactions.updateHead(threadId, expectedExecutionEpoch, headEntryId, clock.instant());
  }

  /**
   * Looks up a previously persisted input by stable idempotency key.
   *
   * <p>Callers should use this before constructing a request whose changed settings may no longer
   * be valid; an idempotent retry then returns the original input unchanged.
   */
  public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
    return transactions.findExistingInput(threadId, idempotencyKey).map(this::toEnqueueResult);
  }

  /** Enqueues a user turn after content validation; retries short-circuit first. */
  public EnqueueResult submitUserMessage(
      long threadId,
      TurnSettings settings,
      String content,
      String idempotencyKey,
      long expectedExecutionEpoch) {
    Optional<EnqueueResult> existing = findExistingInput(threadId, idempotencyKey);
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
            ThreadInputType.USER_MESSAGE, new MessageEntryPayload(message, settings, null)),
        idempotencyKey,
        expectedExecutionEpoch);
  }

  /** Enqueues a custom system/user turn; retries short-circuit before role/content checks. */
  public EnqueueResult submitCustomMessage(
      long threadId,
      TurnSettings settings,
      String role,
      String content,
      String idempotencyKey,
      long expectedExecutionEpoch) {
    Optional<EnqueueResult> existing = findExistingInput(threadId, idempotencyKey);
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
            ThreadInputType.CUSTOM_MESSAGE, new CustomMessageEntryPayload(message, settings)),
        idempotencyKey,
        expectedExecutionEpoch);
  }

  /** Fences the current execution epoch and applies the existing durable stop semantics. */
  public StopResult stop(long threadId, long expectedExecutionEpoch) {
    ThreadCommandTransactions.StopResult result =
        transactions.stop(threadId, expectedExecutionEpoch, clock.instant());
    return new StopResult(result.executionEpoch(), result.cancelledInputs());
  }

  private EnqueueResult enqueue(
      long threadId,
      ThreadInputPayload payload,
      String idempotencyKey,
      long expectedExecutionEpoch) {
    return toEnqueueResult(
        transactions.enqueue(
            threadId, payload, idempotencyKey, expectedExecutionEpoch, clock.instant()));
  }

  private EnqueueResult toEnqueueResult(ThreadCommandTransactions.EnqueueResult result) {
    return new EnqueueResult(result.input());
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

  public record EnqueueResult(ThreadInput input) {
    public EnqueueResult {
      Objects.requireNonNull(input, "input");
    }
  }

  /** Inbound stop outcome for command facades: fenced epoch and cancelled inputs. */
  public record StopResult(long executionEpoch, List<ThreadInput> cancelledInputs) {
    public StopResult {
      cancelledInputs = List.copyOf(Objects.requireNonNull(cancelledInputs, "cancelledInputs"));
    }
  }
}
