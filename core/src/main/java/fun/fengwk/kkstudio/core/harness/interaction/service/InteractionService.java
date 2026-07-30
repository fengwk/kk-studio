package fun.fengwk.kkstudio.core.harness.interaction.service;

import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.InteractionResponseDTO;

/**
 * Generic Interaction query/response application boundary.
 *
 * <p>Implementations own decimal/DTO adaptation. Domain orchestration and durable target mutation
 * are delegated to harness-runtime and its PostgreSQL transaction adapter.
 */
public interface InteractionService {

  /** Gets one durable Interaction, including its handler-controlled generic projection. */
  InteractionDTO get(String interactionId);

  /** Gets the sole OPEN Interaction for an owner, or rejects when that owner has none. */
  InteractionDTO getOpenByOwner(String ownerKind, String ownerId);

  /** Applies a generic response at its received instant. */
  InteractionDTO respond(String interactionId, InteractionResponseDTO responseDTO);
}
