package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionResponseDTO;

/**
 * Tool permission Interaction query/response application boundary.
 *
 * <p>Implementations own decimal/DTO adaptation. Domain orchestration and durable activation
 * mutation are delegated to harness-runtime and its PostgreSQL transaction adapter.
 */
public interface InteractionService {

  /** Gets one durable Tool permission Interaction, including its safe projection. */
  InteractionDTO get(String interactionId);

  /** Gets the sole OPEN Interaction for a Tool invocation, or rejects when it has none. */
  InteractionDTO getOpenByToolInvocation(String toolInvocationId);

  /** Applies a Tool permission approval response at its received instant. */
  InteractionDTO respond(String interactionId, InteractionResponseDTO responseDTO);
}
