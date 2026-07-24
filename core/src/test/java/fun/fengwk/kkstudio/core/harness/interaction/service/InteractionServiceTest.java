package fun.fengwk.kkstudio.core.harness.interaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandler;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandlerRegistry;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerDirective;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionProjection;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.InteractionResponseDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Service tests cover handler resolution, expected-version input, expiry, and notifier isolation.
 */
class InteractionServiceTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");
  private static final ExecutionTarget OWNER =
      new ExecutionTarget(ExecutionTargetKind.THREAD, 100L);
  private static final ExecutionTarget NEXT = OWNER;

  /**
   * Resolution comes from the registered handler and committed success survives notifier failure.
   */
  @Test
  void resolvesWithHandlerAndIsolatesNotifierFailure() {
    InteractionTransactions transactions = mock(InteractionTransactions.class);
    InteractionHandler handler = mock(InteractionHandler.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    Interaction open = open(null);
    Interaction resolved = resolved(open);
    when(handler.type()).thenReturn("confirm");
    when(handler.resolve(open.request(), new InteractionResponse("{\"yes\":true}")))
        .thenReturn(new InteractionResolution(resumeThread(), NEXT));
    when(handler.project(any()))
        .thenAnswer(
            call ->
                new InteractionProjection(call.getArgument(0, InteractionRequest.class).json()));
    when(transactions.find(open.id())).thenReturn(Optional.of(open));
    when(transactions.resolve(eq(open.id()), eq(0L), any(), any(), eq(NOW)))
        .thenReturn(new InteractionTransition(resolved, NEXT));
    doThrow(new RuntimeException("redis down")).when(notifier).notifyAfterCommit(NEXT);
    InteractionService service = service(transactions, handler, notifier);

    InteractionDTO result = service.respond("1", response("0", "{\"yes\":true}"));

    assertEquals("RESOLVED", result.getStatus());
    assertEquals("1", result.getVersion());
    assertEquals("{\"question\":true}", result.getProjectionJson());
    verify(notifier).notifyAfterCommit(NEXT);
  }

  /** A response at expiry never invokes the handler and atomically follows the EXPIRED path. */
  @Test
  void expiresLateResponseWithoutInvokingHandler() {
    InteractionTransactions transactions = mock(InteractionTransactions.class);
    InteractionHandler handler = mock(InteractionHandler.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    Interaction open = open(NOW);
    Interaction expired = expired(open);
    when(handler.type()).thenReturn("confirm");
    when(handler.project(any()))
        .thenAnswer(
            call ->
                new InteractionProjection(call.getArgument(0, InteractionRequest.class).json()));
    when(transactions.find(open.id())).thenReturn(Optional.of(open));
    when(transactions.expire(open.id(), 0L, NOW))
        .thenReturn(new InteractionTransition(expired, OWNER));
    InteractionService service = service(transactions, handler, notifier);

    InteractionDTO result = service.respond("1", response("0", "{\"late\":true}"));

    assertEquals("EXPIRED", result.getStatus());
    verify(handler, never()).resolve(any(), any());
    verify(transactions, never()).resolve(any(Long.class), any(Long.class), any(), any(), any());
    verify(notifier).notifyAfterCommit(OWNER);
  }

  /** Invalid decimal version and raw JSON are rejected before reads or terminal mutations. */
  @Test
  void rejectsInvalidResponseBoundaryBeforeTransaction() {
    InteractionTransactions transactions = mock(InteractionTransactions.class);
    InteractionHandler handler = mock(InteractionHandler.class);
    when(handler.type()).thenReturn("confirm");
    InteractionService service = service(transactions, handler, mock(ActivationNotifier.class));

    assertThrows(
        IllegalArgumentException.class, () -> service.respond("1", response("01", "true")));
    assertThrows(
        IllegalArgumentException.class, () -> service.respond("1", response("0", "{} {}")));
    verify(transactions, never()).find(any(Long.class));
  }

  /** Owner kind/id are parsed as exact generic execution-target values before a query is issued. */
  @Test
  void rejectsInvalidOwnerBoundaryBeforeTransaction() {
    InteractionTransactions transactions = mock(InteractionTransactions.class);
    InteractionHandler handler = mock(InteractionHandler.class);
    when(handler.type()).thenReturn("confirm");
    InteractionService service = service(transactions, handler, mock(ActivationNotifier.class));

    assertThrows(IllegalArgumentException.class, () -> service.getOpenByOwner("TOOL", "1"));
    assertThrows(IllegalArgumentException.class, () -> service.getOpenByOwner("THREAD", "01"));
    verify(transactions, never()).findOpenByOwner(any());
  }

  private static InteractionService service(
      InteractionTransactions transactions,
      InteractionHandler handler,
      ActivationNotifier notifier) {
    return new InteractionService(
        transactions,
        new InteractionHandlerRegistry(List.of(handler)),
        notifier,
        Clock.fixed(NOW, ZoneOffset.UTC));
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

  private static Interaction expired(Interaction open) {
    return new Interaction(
        open.id(),
        open.owner(),
        open.handlerType(),
        open.request(),
        InteractionStatus.EXPIRED,
        null,
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

  private static InteractionOwnerDirective resumeThread() {
    return new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD);
  }
}
