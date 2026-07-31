package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Framework-free Interaction query/response orchestration.
 *
 * <p>Owns Tool permission projection and deterministic approval resolution. Callers remain
 * responsible only for decimal/DTO boundaries; the transaction makes the durable target
 * dispatchable.
 */
public final class InteractionCoordinator {

  private final InteractionTransactions transactions;
  private final ToolPermissionInteractionCodec codec;
  private final Clock clock;

  public InteractionCoordinator(
      InteractionTransactions transactions, ToolPermissionInteractionCodec codec, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Loads one durable Tool permission Interaction and projects its safe preview. */
  public InteractionView get(long interactionId) {
    return project(requireInteraction(interactionId));
  }

  /** Loads the sole OPEN Interaction for a Tool invocation and projects its safe preview. */
  public InteractionView getOpenByToolInvocation(long toolInvocationId) {
    Interaction interaction =
        transactions
            .findOpenByToolInvocation(toolInvocationId)
            .orElseThrow(
                () -> new IllegalArgumentException("no open interaction for tool invocation"));
    return project(interaction);
  }

  /**
   * Applies a response at the coordinator clock instant.
   *
   * <p>The strict codec validates the persisted request and received response before the durable
   * transaction applies the resulting approval decision.
   */
  public InteractionRespondResult respond(
      long interactionId, long expectedVersion, InteractionResponse response) {
    Objects.requireNonNull(response, "response");
    Interaction interaction = requireInteraction(interactionId);
    Instant receivedAt = clock.instant();
    ToolPermissionDecision decision = codec.resolve(interaction, response);
    InteractionTransition transition =
        transactions.resolve(interactionId, expectedVersion, response, decision, receivedAt);
    InteractionView view = project(transition.interaction());
    return new InteractionRespondResult(view.interaction(), view.projection());
  }

  private InteractionView project(Interaction interaction) {
    return new InteractionView(interaction, codec.project(interaction.request()));
  }

  private Interaction requireInteraction(long id) {
    return transactions
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown interaction: " + id));
  }

  /** Domain view pairing a durable Tool permission Interaction with its safe projection. */
  public record InteractionView(Interaction interaction, InteractionProjection projection) {
    public InteractionView {
      Objects.requireNonNull(interaction, "interaction");
      Objects.requireNonNull(projection, "projection");
    }
  }

  /** Response outcome: updated Interaction and safe Tool permission projection. */
  public record InteractionRespondResult(
      Interaction interaction, InteractionProjection projection) {
    public InteractionRespondResult {
      Objects.requireNonNull(interaction, "interaction");
      Objects.requireNonNull(projection, "projection");
    }
  }
}
