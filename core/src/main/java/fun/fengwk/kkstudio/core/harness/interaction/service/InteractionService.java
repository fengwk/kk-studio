package fun.fengwk.kkstudio.core.harness.interaction.service;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandler;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandlerRegistry;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionProjection;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.InteractionResponseDTO;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Generic Interaction query/response facade and post-commit best-effort activation bridge. */
@Service
public class InteractionService {
  private final InteractionTransactions transactions;
  private final InteractionHandlerRegistry handlerRegistry;
  private final ActivationNotifier activationNotifier;
  private final Clock clock;

  public InteractionService(
      InteractionTransactions transactions,
      InteractionHandlerRegistry handlerRegistry,
      ActivationNotifier activationNotifier,
      Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Gets one durable Interaction, including its handler-controlled generic projection. */
  public InteractionDTO get(String interactionId) {
    Interaction interaction =
        requireInteraction(parsePositiveDecimal(interactionId, "interactionId"));
    return project(interaction);
  }

  /** Gets the sole OPEN Interaction for an owner, or rejects when that owner has none. */
  public InteractionDTO getOpenByOwner(String ownerKind, String ownerId) {
    ExecutionTarget owner =
        new ExecutionTarget(parseOwnerKind(ownerKind), parsePositiveDecimal(ownerId, "ownerId"));
    Interaction interaction =
        transactions
            .findOpenByOwner(owner)
            .orElseThrow(() -> new IllegalArgumentException("no open interaction for owner"));
    return project(interaction);
  }

  /**
   * Applies a generic response at its received instant, then best-effort signals the next target.
   */
  public InteractionDTO respond(String interactionId, InteractionResponseDTO responseDTO) {
    Objects.requireNonNull(responseDTO, "responseDTO");
    long id = parsePositiveDecimal(interactionId, "interactionId");
    long expectedVersion =
        parseNonNegativeDecimal(responseDTO.getExpectedVersion(), "expectedVersion");
    InteractionResponse response = new InteractionResponse(responseDTO.getResponseJson());
    Interaction interaction = requireInteraction(id);
    Instant receivedAt = clock.instant();
    InteractionTransition transition;
    if (interaction.expiresAt() != null && !receivedAt.isBefore(interaction.expiresAt())) {
      transition = transactions.expire(id, expectedVersion, receivedAt);
    } else {
      InteractionHandler handler = handlerRegistry.require(interaction.handlerType());
      InteractionResolution resolution = handler.resolve(interaction.request(), response);
      transition = transactions.resolve(id, expectedVersion, response, resolution, receivedAt);
    }
    notifyBestEffort(transition.nextTarget());
    return project(transition.interaction());
  }

  private InteractionDTO project(Interaction interaction) {
    InteractionProjection projection =
        handlerRegistry.require(interaction.handlerType()).project(interaction.request());
    InteractionDTO dto = new InteractionDTO();
    dto.setId(Long.toString(interaction.id()));
    dto.setOwnerKind(interaction.owner().kind().name());
    dto.setOwnerId(Long.toString(interaction.owner().id()));
    dto.setHandlerType(interaction.handlerType());
    dto.setProjectionJson(projection.json());
    dto.setStatus(interaction.status().name());
    dto.setResponseJson(interaction.response() == null ? null : interaction.response().json());
    dto.setExpiresAt(interaction.expiresAt());
    dto.setVersion(Long.toString(interaction.version()));
    dto.setCreatedAt(interaction.createdAt());
    dto.setResolvedAt(interaction.resolvedAt());
    return dto;
  }

  private Interaction requireInteraction(long id) {
    return transactions
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown interaction: " + id));
  }

  private void notifyBestEffort(ExecutionTarget target) {
    try {
      activationNotifier.notifyAfterCommit(target);
    } catch (RuntimeException ignored) {
      // Recovery scans durable facts; Redis notification failure never reverses a committed
      // response.
    }
  }

  private static ExecutionTargetKind parseOwnerKind(String ownerKind) {
    if (ownerKind == null || ownerKind.isBlank()) {
      throw new IllegalArgumentException("ownerKind must not be blank");
    }
    try {
      return ExecutionTargetKind.valueOf(ownerKind);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid ownerKind: " + ownerKind, error);
    }
  }

  private static long parsePositiveDecimal(String value, String name) {
    long parsed = parseDecimal(value, name, false);
    if (parsed <= 0) {
      throw new IllegalArgumentException(name + " must be a positive decimal string");
    }
    return parsed;
  }

  private static long parseNonNegativeDecimal(String value, String name) {
    return parseDecimal(value, name, true);
  }

  private static long parseDecimal(String value, String name, boolean zeroAllowed) {
    String expression = zeroAllowed ? "0|[1-9][0-9]*" : "[1-9][0-9]*";
    if (value == null || !value.matches(expression)) {
      throw new IllegalArgumentException(name + " must be a decimal string");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(name + " exceeds signed 64-bit range", error);
    }
  }
}
