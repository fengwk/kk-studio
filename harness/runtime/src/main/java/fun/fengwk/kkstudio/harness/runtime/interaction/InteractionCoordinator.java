package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Framework-free Interaction query/response orchestration.
 *
 * <p>Owns handler lookup, generic projection, expiry decision and deterministic resolution. Callers
 * remain responsible for decimal/DTO boundaries and best-effort activation notification.
 */
public final class InteractionCoordinator {

  private final InteractionTransactions transactions;
  private final InteractionHandlerRegistry handlerRegistry;
  private final Clock clock;

  public InteractionCoordinator(
      InteractionTransactions transactions,
      InteractionHandlerRegistry handlerRegistry,
      Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Loads one durable Interaction and projects it through its registered handler. */
  public InteractionView get(long interactionId) {
    return project(requireInteraction(interactionId));
  }

  /** Loads the sole OPEN Interaction for an owner and projects it. */
  public InteractionView getOpenByOwner(ExecutionTarget owner) {
    Objects.requireNonNull(owner, "owner");
    Interaction interaction =
        transactions
            .findOpenByOwner(owner)
            .orElseThrow(() -> new IllegalArgumentException("no open interaction for owner"));
    return project(interaction);
  }

  /**
   * Applies a response at the coordinator clock instant.
   *
   * <p>When the interaction has expired at the received instant, follows the EXPIRED path without
   * invoking the handler. Otherwise resolves via the registered handler and durable transaction.
   */
  public InteractionRespondResult respond(
      long interactionId, long expectedVersion, InteractionResponse response) {
    Objects.requireNonNull(response, "response");
    Interaction interaction = requireInteraction(interactionId);
    Instant receivedAt = clock.instant();
    InteractionTransition transition;
    if (interaction.expiresAt() != null && !receivedAt.isBefore(interaction.expiresAt())) {
      transition = transactions.expire(interactionId, expectedVersion, receivedAt);
    } else {
      InteractionHandler handler = handlerRegistry.require(interaction.handlerType());
      InteractionResolution resolution = handler.resolve(interaction.request(), response);
      transition =
          transactions.resolve(interactionId, expectedVersion, response, resolution, receivedAt);
    }
    InteractionView view = project(transition.interaction());
    return new InteractionRespondResult(
        view.interaction(), view.projection(), transition.nextTarget());
  }

  private InteractionView project(Interaction interaction) {
    InteractionProjection projection =
        handlerRegistry.require(interaction.handlerType()).project(interaction.request());
    return new InteractionView(interaction, projection);
  }

  private Interaction requireInteraction(long id) {
    return transactions
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown interaction: " + id));
  }

  /** Domain view pairing a durable Interaction with its handler-controlled projection. */
  public record InteractionView(Interaction interaction, InteractionProjection projection) {
    public InteractionView {
      Objects.requireNonNull(interaction, "interaction");
      Objects.requireNonNull(projection, "projection");
    }
  }

  /**
   * Response outcome: updated Interaction, projection, and the next best-effort activation target.
   */
  public record InteractionRespondResult(
      Interaction interaction, InteractionProjection projection, ExecutionTarget nextTarget) {
    public InteractionRespondResult {
      Objects.requireNonNull(interaction, "interaction");
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(nextTarget, "nextTarget");
    }
  }
}
