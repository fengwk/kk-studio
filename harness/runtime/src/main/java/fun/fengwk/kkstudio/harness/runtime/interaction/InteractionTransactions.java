package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.time.Instant;
import java.util.Optional;

/**
 * Use-case atomic persistence port for durable Interactions.
 *
 * <p>Implementations atomically resolve an OPEN Tool permission fact, apply the supplied approval
 * decision, and make its owned target dispatchable or terminal in the same transaction.
 */
public interface InteractionTransactions {

  Optional<Interaction> find(long interactionId);

  Optional<Interaction> findOpenByToolInvocation(long toolInvocationId);

  InteractionTransition resolve(
      long interactionId,
      long expectedVersion,
      InteractionResponse response,
      ToolPermissionDecision decision,
      Instant resolvedAt);
}
