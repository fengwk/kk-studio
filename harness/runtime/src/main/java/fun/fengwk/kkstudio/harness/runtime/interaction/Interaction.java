package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.time.Instant;
import java.util.Objects;

/** Immutable durable Tool permission Interaction fact with status-specific response invariants. */
public record Interaction(
    long id,
    long toolInvocationId,
    InteractionRequest request,
    InteractionStatus status,
    InteractionResponse response,
    long version,
    Instant createdAt,
    Instant resolvedAt) {

  public Interaction {
    if (id <= 0) {
      throw new IllegalArgumentException("interaction id must be positive");
    }
    if (toolInvocationId <= 0) {
      throw new IllegalArgumentException("toolInvocationId must be positive");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (version < 0) {
      throw new IllegalArgumentException("interaction version must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    switch (status) {
      case OPEN -> requireOpen(response, resolvedAt);
      case RESOLVED -> requireResolved(response, resolvedAt, createdAt);
    }
  }

  private static void requireOpen(InteractionResponse response, Instant resolvedAt) {
    if (response != null || resolvedAt != null) {
      throw new IllegalArgumentException("OPEN interaction must not have response or resolvedAt");
    }
  }

  private static void requireResolved(
      InteractionResponse response, Instant resolvedAt, Instant createdAt) {
    if (response == null || resolvedAt == null || resolvedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException(
          "RESOLVED interaction requires response and resolvedAt not before createdAt");
    }
  }
}
