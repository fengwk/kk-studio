package fun.fengwk.kkstudio.core.harness.interaction.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.interaction.service.InteractionService;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionRespondResult;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionView;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.InteractionResponseDTO;

import java.util.Objects;

/**
 * Thin Core Interaction boundary for decimal-string/DTO mapping. Domain projection, expiry and
 * handler resolution live in {@link InteractionCoordinator}; its transaction writes wake the
 * durable execution-target queue directly.
 */
@Service
public class InteractionServiceImpl implements InteractionService {
  private final InteractionCoordinator coordinator;

  public InteractionServiceImpl(InteractionCoordinator coordinator) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  @Override
  public InteractionDTO get(String interactionId) {
    return toDto(coordinator.get(parsePositiveDecimal(interactionId, "interactionId")));
  }

  @Override
  public InteractionDTO getOpenByOwner(String ownerKind, String ownerId) {
    ExecutionTarget owner =
        new ExecutionTarget(parseOwnerKind(ownerKind), parsePositiveDecimal(ownerId, "ownerId"));
    return toDto(coordinator.getOpenByOwner(owner));
  }

  @Override
  public InteractionDTO respond(String interactionId, InteractionResponseDTO responseDTO) {
    Objects.requireNonNull(responseDTO, "responseDTO");
    long id = parsePositiveDecimal(interactionId, "interactionId");
    long expectedVersion =
        parseNonNegativeDecimal(responseDTO.getExpectedVersion(), "expectedVersion");
    // Construct response before domain load so invalid JSON is rejected at the boundary.
    InteractionResponse response = new InteractionResponse(responseDTO.getResponseJson());
    InteractionRespondResult result = coordinator.respond(id, expectedVersion, response);
    return toDto(result.interaction(), result.projection().json());
  }

  private InteractionDTO toDto(InteractionView view) {
    return toDto(view.interaction(), view.projection().json());
  }

  private InteractionDTO toDto(Interaction interaction, String projectionJson) {
    InteractionDTO dto = new InteractionDTO();
    dto.setId(Long.toString(interaction.id()));
    dto.setOwnerKind(interaction.owner().kind().name());
    dto.setOwnerId(Long.toString(interaction.owner().id()));
    dto.setHandlerType(interaction.handlerType());
    dto.setProjectionJson(projectionJson);
    dto.setStatus(interaction.status().name());
    dto.setResponseJson(interaction.response() == null ? null : interaction.response().json());
    dto.setExpiresAt(interaction.expiresAt());
    dto.setVersion(Long.toString(interaction.version()));
    dto.setCreatedAt(interaction.createdAt());
    dto.setResolvedAt(interaction.resolvedAt());
    return dto;
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
