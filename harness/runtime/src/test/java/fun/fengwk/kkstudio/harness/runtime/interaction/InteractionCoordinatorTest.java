package fun.fengwk.kkstudio.harness.runtime.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Tests product-specific projection, decision forwarding, and version-preserving coordination. */
class InteractionCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");

  @Test
  void respondsWithStrictApprovalDecisionAndCoordinatorClock() {
    Interaction open = open();
    Interaction resolved = resolved(open);
    FakeTransactions transactions = new FakeTransactions();
    transactions.byId.put(open.id(), open);
    transactions.resolveResult = new InteractionTransition(resolved);
    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new ToolPermissionInteractionCodec(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    InteractionCoordinator.InteractionRespondResult result =
        coordinator.respond(
            open.id(), open.version(), new InteractionResponse("{\"approved\":true}"));

    assertEquals(InteractionStatus.RESOLVED, result.interaction().status());
    assertEquals(ToolPermissionDecision.APPROVE, transactions.decision);
    assertEquals(open.id(), transactions.id);
    assertEquals(open.version(), transactions.version);
    assertEquals(NOW, transactions.resolvedAt);
    assertEquals(
        "{\"tool\":\"bash\",\"workdir\":\"/work\",\"arguments\":\"{}\"}",
        result.projection().json());
  }

  @Test
  void getsOpenInteractionByToolInvocationAndProjectsIt() {
    Interaction open = open();
    FakeTransactions transactions = new FakeTransactions();
    transactions.openByToolInvocation.put(open.toolInvocationId(), open);
    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            transactions,
            new ToolPermissionInteractionCodec(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    InteractionCoordinator.InteractionView result =
        coordinator.getOpenByToolInvocation(open.toolInvocationId());

    assertSame(open, result.interaction());
    assertEquals(open.toolInvocationId(), transactions.openLookupToolInvocationId);
    assertEquals(
        "{\"tool\":\"bash\",\"workdir\":\"/work\",\"arguments\":\"{}\"}",
        result.projection().json());
  }

  @Test
  void rejectsUnknownInteractionBeforeResolution() {
    InteractionCoordinator coordinator =
        new InteractionCoordinator(
            new FakeTransactions(),
            new ToolPermissionInteractionCodec(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.respond(1L, 0L, new InteractionResponse("{\"approved\":false}")));
  }

  private static Interaction open() {
    return new Interaction(
        1L,
        41L,
        new InteractionRequest(
            "{\"invocationId\":41,\"threadId\":7,\"tool\":\"bash\",\"workdir\":\"/work\",\"arguments\":\"{}\"}"),
        InteractionStatus.OPEN,
        null,
        0L,
        Instant.parse("2026-01-01T00:00:00Z"),
        null);
  }

  private static Interaction resolved(Interaction open) {
    return new Interaction(
        open.id(),
        open.toolInvocationId(),
        open.request(),
        InteractionStatus.RESOLVED,
        new InteractionResponse("{\"approved\":true}"),
        open.version() + 1,
        open.createdAt(),
        NOW);
  }

  private static final class FakeTransactions implements InteractionTransactions {
    final Map<Long, Interaction> byId = new HashMap<>();
    final Map<Long, Interaction> openByToolInvocation = new HashMap<>();
    InteractionTransition resolveResult;
    long id;
    long version;
    long openLookupToolInvocationId;
    ToolPermissionDecision decision;
    Instant resolvedAt;

    @Override
    public Optional<Interaction> find(long interactionId) {
      return Optional.ofNullable(byId.get(interactionId));
    }

    @Override
    public Optional<Interaction> findOpenByToolInvocation(long toolInvocationId) {
      openLookupToolInvocationId = toolInvocationId;
      return Optional.ofNullable(openByToolInvocation.get(toolInvocationId));
    }

    @Override
    public InteractionTransition resolve(
        long interactionId,
        long expectedVersion,
        InteractionResponse response,
        ToolPermissionDecision approvalDecision,
        Instant receivedAt) {
      id = interactionId;
      version = expectedVersion;
      decision = approvalDecision;
      resolvedAt = receivedAt;
      return resolveResult;
    }
  }
}
