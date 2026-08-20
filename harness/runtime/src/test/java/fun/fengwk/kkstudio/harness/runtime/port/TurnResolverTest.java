package fun.fengwk.kkstudio.harness.runtime.port;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;

import java.util.List;

/** TurnResolver public contract：sealed result surface 与严格 value validation。 */
class TurnResolverTest {

  @Test
  void resultIsSealedWithExactlyResolvedAndRejected() {
    List<Class<?>> permitted = List.of(TurnResolver.Result.class.getPermittedSubclasses());
    assertEquals(2, permitted.size());
    assertTrue(permitted.contains(TurnResolver.Resolved.class));
    assertTrue(permitted.contains(TurnResolver.Rejected.class));
  }

  @Test
  void resolvedRequiresSpecAndPositiveBudgets() {
    TurnResolver.Resolved resolved =
        new TurnResolver.Resolved(PortTestData.modelRequest(), 100_000, 16_384);
    assertEquals(PortTestData.modelRequest(), resolved.spec());
    assertEquals(100_000, resolved.contextWindow());
    assertEquals(16_384, resolved.maxOutputTokens());
    assertThrows(
        NullPointerException.class, () -> new TurnResolver.Resolved(null, 100_000, 16_384));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnResolver.Resolved(PortTestData.modelRequest(), 0, 16_384));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnResolver.Resolved(PortTestData.modelRequest(), 100_000, 0));
  }

  @Test
  void rejectedRequiresAnAssistantError() {
    TurnResolver.Rejected rejected =
        new TurnResolver.Rejected(new AssistantError("PLANNING_FAILED", "agent not found"));
    assertEquals("PLANNING_FAILED", rejected.error().code());
    assertThrows(NullPointerException.class, () -> new TurnResolver.Rejected(null));
  }

  @Test
  void resolveIsASynchronousNoSideEffectContractMethod() {
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Rejected(new AssistantError("PLANNING_FAILED", "rejected"));
    TurnResolver.Result result = resolver.resolve(id(1L), null, null);
    assertTrue(result instanceof TurnResolver.Rejected);
    assertEquals("rejected", ((TurnResolver.Rejected) result).error().message());
  }

  @Test
  void modelRequestSpecStaysFrozenInTheResult() {
    ModelRequestSpec spec = PortTestData.modelRequest();
    TurnResolver.Resolved resolved = new TurnResolver.Resolved(spec, 100_000, 16_384);
    assertEquals(spec, resolved.spec());
  }
}
