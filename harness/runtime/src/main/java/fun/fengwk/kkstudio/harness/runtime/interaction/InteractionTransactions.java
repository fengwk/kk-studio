package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;

import java.time.Instant;
import java.util.Optional;

/**
 * Use-case atomic persistence port for durable Interactions.
 *
 * <p>Implementations create an OPEN fact while suspending the owner, and atomically terminalize the
 * fact, apply the supplied deterministic resolution, and make the returned target dispatchable.
 * They validate positive ids, OPEN status, and exact expected version. Notifications are
 * deliberately outside this port and occur only after commit.
 */
public interface InteractionTransactions {

  Interaction create(InteractionCreate create);

  Optional<Interaction> find(long interactionId);

  Optional<Interaction> findOpenByOwner(ExecutionTarget owner);

  InteractionTransition resolve(
      long interactionId,
      long expectedVersion,
      InteractionResponse response,
      InteractionResolution resolution,
      Instant resolvedAt);

  InteractionTransition cancel(
      long interactionId,
      long expectedVersion,
      InteractionOwnerDirective ownerDirective,
      ExecutionTarget nextTarget,
      Instant resolvedAt);

  InteractionTransition expire(long interactionId, long expectedVersion, Instant resolvedAt);
}
