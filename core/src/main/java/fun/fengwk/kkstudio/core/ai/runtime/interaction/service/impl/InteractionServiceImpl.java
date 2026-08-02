package fun.fengwk.kkstudio.core.ai.runtime.interaction.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.service.InteractionService;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionRespondResult;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionView;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionResponseDTO;

import java.util.Objects;

/**
 * Thin Core Tool permission Interaction boundary for decimal-string/DTO mapping. Domain projection
 * and approval resolution live in {@link InteractionCoordinator}; its transaction writes wake the
 * durable ExecutionActivation queue directly.
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
  public InteractionDTO getOpenByToolInvocation(String toolInvocationId) {
    return toDto(
        coordinator.getOpenByToolInvocation(
            parsePositiveDecimal(toolInvocationId, "toolInvocationId")));
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
    dto.setToolInvocationId(Long.toString(interaction.toolInvocationId()));
    dto.setProjectionJson(projectionJson);
    dto.setStatus(interaction.status().name());
    dto.setResponseJson(interaction.response() == null ? null : interaction.response().json());
    dto.setVersion(Long.toString(interaction.version()));
    dto.setCreatedAt(interaction.createdAt());
    dto.setResolvedAt(interaction.resolvedAt());
    return dto;
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
