package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;

import java.time.Instant;
import java.util.Objects;

/** Input for atomically recording an OPEN Interaction and suspending its owning Thread. */
public record InteractionCreate(
    ExecutionTarget owner,
    String handlerType,
    InteractionRequest request,
    InteractionOwnerDirective ownerDirective,
    Instant expiresAt,
    Instant createdAt) {
  public InteractionCreate {
    owner = Objects.requireNonNull(owner, "owner");
    if (handlerType == null || handlerType.isBlank() || handlerType.length() > 64) {
      throw new IllegalArgumentException(
          "interaction handlerType must be non-blank and <= 64 chars");
    }
    request = Objects.requireNonNull(request, "request");
    ownerDirective = Objects.requireNonNull(ownerDirective, "ownerDirective");
    if (ownerDirective.action() != InteractionOwnerAction.SUSPEND_THREAD) {
      throw new IllegalArgumentException("interaction create directive must suspend the owner");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("interaction expiresAt must be after createdAt");
    }
  }
}
