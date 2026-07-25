package fun.fengwk.kkstudio.harness.runtime.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionRespondResult;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator.InteractionView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handler lookup, projection, expiry and deterministic resolution for InteractionCoordinator. */
class InteractionCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");
  private static final ExecutionTarget OWNER =
      new ExecutionTarget(ExecutionTargetKind.THREAD, 100L);

  /** Resolution uses the registered handler and returns projection + next activation target. */
  @Test
  void resolvesWithHandlerAndReturnsProjection() {
    Interaction open = open(null);
    Interaction resolved = resolved(open);
    FakeHandler handler = new FakeHandler("confirm");
    FakeTransactions transactions = new FakeTransactions();
    transactions.byId.put(open.id(), open);
    transactions.resolveResult = new InteractionTransition(resolved, OWNER);

    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new InteractionHandlerRegistry(List.of(handler)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    InteractionRespondResult result =
        coordinator.respond(1L, 0L, new InteractionResponse("{\"yes\":true}"));

    assertEquals(InteractionStatus.RESOLVED, result.interaction().status());
    assertEquals("{\"question\":true}", result.projection().json());
    assertSame(OWNER, result.nextTarget());
    assertTrue(handler.resolveCalled);
    assertEquals(1, transactions.resolveCalls);
    assertEquals(0, transactions.expireCalls);
  }

  /** A response at expiry never invokes the handler and follows the EXPIRED path. */
  @Test
  void expiresLateResponseWithoutInvokingHandler() {
    Interaction open = open(NOW);
    Interaction expired = expired(open);
    FakeHandler handler = new FakeHandler("confirm");
    FakeTransactions transactions = new FakeTransactions();
    transactions.byId.put(open.id(), open);
    transactions.expireResult = new InteractionTransition(expired, OWNER);

    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new InteractionHandlerRegistry(List.of(handler)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    InteractionRespondResult result =
        coordinator.respond(1L, 0L, new InteractionResponse("{\"late\":true}"));

    assertEquals(InteractionStatus.EXPIRED, result.interaction().status());
    assertEquals(false, handler.resolveCalled);
    assertEquals(0, transactions.resolveCalls);
    assertEquals(1, transactions.expireCalls);
    assertSame(OWNER, result.nextTarget());
  }

  /** Projection is always produced through the registered handler. */
  @Test
  void getProjectsThroughHandler() {
    Interaction open = open(null);
    FakeHandler handler = new FakeHandler("confirm");
    handler.projection = new InteractionProjection("{\"ui\":1}");
    FakeTransactions transactions = new FakeTransactions();
    transactions.byId.put(1L, open);

    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new InteractionHandlerRegistry(List.of(handler)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    InteractionView view = coordinator.get(1L);
    assertEquals("{\"ui\":1}", view.projection().json());
    assertSame(open, view.interaction());
  }

  @Test
  void getOpenByOwnerRejectsWhenMissing() {
    FakeHandler handler = new FakeHandler("confirm");
    FakeTransactions transactions = new FakeTransactions();
    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new InteractionHandlerRegistry(List.of(handler)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class, () -> coordinator.getOpenByOwner(OWNER));
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

  private static final class FakeHandler implements InteractionHandler {
    private final String type;
    boolean resolveCalled;
    InteractionProjection projection;

    FakeHandler(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public InteractionProjection project(InteractionRequest request) {
      return projection == null ? new InteractionProjection(request.json()) : projection;
    }

    @Override
    public InteractionResolution resolve(InteractionRequest request, InteractionResponse response) {
      resolveCalled = true;
      return new InteractionResolution(
          new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD), OWNER);
    }
  }

  private static final class FakeTransactions implements InteractionTransactions {
    final Map<Long, Interaction> byId = new HashMap<>();
    InteractionTransition resolveResult;
    InteractionTransition expireResult;
    int resolveCalls;
    int expireCalls;

    @Override
    public Interaction create(InteractionCreate create) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Interaction> find(long interactionId) {
      return Optional.ofNullable(byId.get(interactionId));
    }

    @Override
    public Optional<Interaction> findOpenByOwner(ExecutionTarget owner) {
      return Optional.empty();
    }

    @Override
    public InteractionTransition resolve(
        long interactionId,
        long expectedVersion,
        InteractionResponse response,
        InteractionResolution resolution,
        Instant resolvedAt) {
      resolveCalls++;
      return resolveResult;
    }

    @Override
    public InteractionTransition cancel(
        long interactionId,
        long expectedVersion,
        InteractionOwnerDirective ownerDirective,
        ExecutionTarget nextTarget,
        Instant resolvedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InteractionTransition expire(
        long interactionId, long expectedVersion, Instant resolvedAt) {
      expireCalls++;
      return expireResult;
    }
  }
}
