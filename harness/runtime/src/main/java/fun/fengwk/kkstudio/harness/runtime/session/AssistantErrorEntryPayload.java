package fun.fengwk.kkstudio.harness.runtime.session;

/**
 * Assistant-side failure recorded as a durable Session Entry. UI/audit-only; never projected into
 * Provider Context. {@link SessionContextBuilder} must skip it explicitly so a failed attempt
 * cannot pollute the next model request.
 *
 * <p>{@code plannedEntryId} equals the {@code plannedAssistantEntryId} the Thread processor
 * allocated for the failed attempt, so live {@code assistant_failed} SSE events and the durable
 * Entry share a single id for the timeline to dedupe.
 */
public record AssistantErrorEntryPayload(
    String kind, String message, int retryAttempt, Integer maxRetries, boolean retryScheduled)
    implements SessionEntryPayload {

  public AssistantErrorEntryPayload {
    kind = requireText(kind, "kind");
    if (message == null) {
      throw new IllegalArgumentException("message must not be null");
    }
    if (retryAttempt < 0) {
      throw new IllegalArgumentException("retryAttempt must be non-negative");
    }
    if (maxRetries != null && maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be non-negative when present");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.ASSISTANT_ERROR;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
