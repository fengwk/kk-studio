package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;

import java.time.Instant;
import java.util.Objects;

/** Immutable durable Interaction fact with status-specific response and time invariants. */
public record Interaction(
    long id,
    ExecutionTarget owner,
    String handlerType,
    InteractionRequest request,
    InteractionStatus status,
    InteractionResponse response,
    Instant expiresAt,
    long version,
    Instant createdAt,
    Instant resolvedAt) {

  public Interaction {
    if (id <= 0) {
      throw new IllegalArgumentException("interaction id must be positive");
    }
    owner = Objects.requireNonNull(owner, "owner");
    handlerType = requireHandlerType(handlerType);
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (version < 0) {
      throw new IllegalArgumentException("interaction version must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("interaction expiresAt must be after createdAt");
    }
    switch (status) {
      case OPEN -> requireOpen(response, resolvedAt);
      case RESOLVED -> requireResolved(response, resolvedAt, createdAt);
      case CANCELLED -> requireCancelledOrExpired(response, resolvedAt, createdAt, "cancelled");
      case EXPIRED -> requireExpired(response, resolvedAt, createdAt, expiresAt);
    }
  }

  private static String requireHandlerType(String handlerType) {
    if (handlerType == null || handlerType.isBlank()) {
      throw new IllegalArgumentException("interaction handlerType must not be blank");
    }
    if (handlerType.length() > 64) {
      throw new IllegalArgumentException("interaction handlerType must be <= 64 chars");
    }
    return handlerType;
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

  private static void requireCancelledOrExpired(
      InteractionResponse response, Instant resolvedAt, Instant createdAt, String status) {
    if (response != null || resolvedAt == null || resolvedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException(
          status + " interaction requires no response and resolvedAt not before createdAt");
    }
  }

  private static void requireExpired(
      InteractionResponse response, Instant resolvedAt, Instant createdAt, Instant expiresAt) {
    requireCancelledOrExpired(response, resolvedAt, createdAt, "expired");
    if (expiresAt == null || resolvedAt.isBefore(expiresAt)) {
      throw new IllegalArgumentException(
          "EXPIRED interaction requires expiresAt not after resolvedAt");
    }
  }
}
