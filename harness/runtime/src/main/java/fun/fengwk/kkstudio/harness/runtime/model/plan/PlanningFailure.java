package fun.fengwk.kkstudio.harness.runtime.model.plan;

import java.util.Objects;

/**
 * Durable-facing planning failure fact.
 *
 * <p>A failure is an explicit terminal planning outcome; it must not be represented by a fake
 * {@code ProviderRequest}.
 */
public record PlanningFailure(PlanningFailureKind kind, String message) {

  public PlanningFailure {
    kind = Objects.requireNonNull(kind, "kind");
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }
}
