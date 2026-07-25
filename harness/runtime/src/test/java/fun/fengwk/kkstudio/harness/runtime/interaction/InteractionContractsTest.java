package fun.fengwk.kkstudio.harness.runtime.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.List;

/** Unit coverage for strict raw-JSON and status/version boundaries of the pure Runtime contract. */
class InteractionContractsTest {

  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final ExecutionTarget OWNER = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);

  /**
   * A raw value is accepted unchanged, while malformed, duplicate, and trailing input is rejected.
   */
  @Test
  void validatesStrictRawJsonValuesWithoutNormalizingThem() {
    InteractionRequest request = new InteractionRequest(" {\"answer\": [true, null]} ");

    assertEquals(" {\"answer\": [true, null]} ", request.json());
    assertThrows(IllegalArgumentException.class, () -> new InteractionResponse(""));
    assertThrows(
        IllegalArgumentException.class, () -> new InteractionResponse("{\"a\":1,\"a\":2}"));
    assertThrows(IllegalArgumentException.class, () -> new InteractionResponse("{} {}"));
  }

  /**
   * Lifecycle facts reject incompatible response/time/version combinations before reaching
   * adapters.
   */
  @Test
  void enforcesStatusSpecificInteractionInvariants() {
    InteractionRequest request = new InteractionRequest("null");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interaction(
                1L,
                OWNER,
                "confirm",
                request,
                InteractionStatus.OPEN,
                new InteractionResponse("true"),
                null,
                0L,
                CREATED_AT,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interaction(
                1L,
                OWNER,
                "confirm",
                request,
                InteractionStatus.EXPIRED,
                null,
                null,
                0L,
                CREATED_AT,
                CREATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interaction(
                1L,
                OWNER,
                "confirm",
                request,
                InteractionStatus.OPEN,
                null,
                null,
                -1L,
                CREATED_AT,
                null));
  }

  /**
   * The registry validates handler identities once and preserves the exact handler selected by
   * type.
   */
  @Test
  void requiresKnownUniqueHandlers() {
    InteractionHandler handler = new EchoHandler("confirm");
    InteractionHandlerRegistry registry = new InteractionHandlerRegistry(List.of(handler));

    assertEquals(handler, registry.require("confirm"));
    assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InteractionHandlerRegistry(List.of(handler, new EchoHandler("confirm"))));
  }

  private record EchoHandler(String type) implements InteractionHandler {
    @Override
    public InteractionProjection project(InteractionRequest request) {
      return new InteractionProjection(request.json());
    }

    @Override
    public InteractionResolution resolve(InteractionRequest request, InteractionResponse response) {
      return new InteractionResolution(
          new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD), OWNER);
    }
  }
}
