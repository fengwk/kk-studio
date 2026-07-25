package fun.fengwk.kkstudio.core.harness.interaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.interaction.service.impl.InteractionServiceImpl;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionRespondResult;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionView;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionProjection;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.InteractionResponseDTO;

import java.time.Instant;

/**
 * Thin Core wrapper tests: decimal boundary, DTO mapping, and notifier isolation. Domain
 * handler/expiry semantics are covered in runtime InteractionCoordinatorTest.
 */
class InteractionServiceTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");
  private static final ExecutionTarget OWNER =
      new ExecutionTarget(ExecutionTargetKind.THREAD, 100L);

  /** Committed success survives notifier failure and is mapped to share DTO fields. */
  @Test
  void mapsResolvedResultAndIsolatesNotifierFailure() {
    InteractionCoordinator coordinator = mock(InteractionCoordinator.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    Interaction resolved = resolved(open(null));
    when(coordinator.respond(eq(1L), eq(0L), any()))
        .thenReturn(
            new InteractionRespondResult(
                resolved, new InteractionProjection("{\"question\":true}"), OWNER));
    doThrow(new RuntimeException("redis down")).when(notifier).notifyAfterCommit(OWNER);
    InteractionService service = new InteractionServiceImpl(coordinator, notifier);

    InteractionDTO result = service.respond("1", response("0", "{\"yes\":true}"));

    assertEquals("RESOLVED", result.getStatus());
    assertEquals("1", result.getVersion());
    assertEquals("{\"question\":true}", result.getProjectionJson());
    verify(notifier).notifyAfterCommit(OWNER);
  }

  /** Invalid decimal version and raw JSON are rejected before domain orchestration. */
  @Test
  void rejectsInvalidResponseBoundaryBeforeCoordinator() {
    InteractionCoordinator coordinator = mock(InteractionCoordinator.class);
    InteractionService service =
        new InteractionServiceImpl(coordinator, mock(ActivationNotifier.class));

    assertThrows(
        IllegalArgumentException.class, () -> service.respond("1", response("01", "true")));
    assertThrows(
        IllegalArgumentException.class, () -> service.respond("1", response("0", "{} {}")));
    verify(coordinator, never()).respond(anyLong(), anyLong(), any());
  }

  /** Owner kind/id are parsed as exact generic execution-target values before a query is issued. */
  @Test
  void rejectsInvalidOwnerBoundaryBeforeCoordinator() {
    InteractionCoordinator coordinator = mock(InteractionCoordinator.class);
    InteractionService service =
        new InteractionServiceImpl(coordinator, mock(ActivationNotifier.class));

    assertThrows(IllegalArgumentException.class, () -> service.getOpenByOwner("TOOL", "1"));
    assertThrows(IllegalArgumentException.class, () -> service.getOpenByOwner("THREAD", "01"));
    verify(coordinator, never()).getOpenByOwner(any());
  }

  /** get maps projection and identity fields without notifier side effects. */
  @Test
  void mapsGetProjection() {
    InteractionCoordinator coordinator = mock(InteractionCoordinator.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    Interaction open = open(null);
    when(coordinator.get(1L))
        .thenReturn(new InteractionView(open, new InteractionProjection("{\"p\":1}")));
    InteractionService service = new InteractionServiceImpl(coordinator, notifier);

    InteractionDTO dto = service.get("1");
    assertEquals("1", dto.getId());
    assertEquals("THREAD", dto.getOwnerKind());
    assertEquals("{\"p\":1}", dto.getProjectionJson());
    verifyNoNotifier(notifier);
  }

  private static void verifyNoNotifier(ActivationNotifier notifier) {
    verify(notifier, never()).notifyAfterCommit(any());
  }

  private static Interaction open(Instant expiresAt) {
    return new Interaction(
        1L,
        OWNER,
        "confirm",
        new InteractionRequest("{\"question\":true}"),
        InteractionStatus.OPEN,
        null,
        expiresAt,
        0L,
        Instant.parse("2026-01-01T00:00:00Z"),
        null);
  }

  private static Interaction resolved(Interaction open) {
    return new Interaction(
        open.id(),
        open.owner(),
        open.handlerType(),
        open.request(),
        InteractionStatus.RESOLVED,
        new InteractionResponse("{\"yes\":true}"),
        open.expiresAt(),
        1L,
        open.createdAt(),
        NOW);
  }

  private static InteractionResponseDTO response(String expectedVersion, String responseJson) {
    InteractionResponseDTO dto = new InteractionResponseDTO();
    dto.setExpectedVersion(expectedVersion);
    dto.setResponseJson(responseJson);
    return dto;
  }
}
