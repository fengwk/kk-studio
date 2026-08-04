package fun.fengwk.kkstudio.harness.runtime.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;

import java.util.List;

/** TurnResolver public contract: sealed result surface and strict value validation. */
class TurnResolverTest {

  @Test
  void resultIsSealedWithExactlyResolvedAndRejected() {
    List<Class<?>> permitted = List.of(TurnResolver.Result.class.getPermittedSubclasses());
    assertEquals(2, permitted.size());
    assertTrue(permitted.contains(TurnResolver.Resolved.class));
    assertTrue(permitted.contains(TurnResolver.Rejected.class));
  }

  @Test
  void resolvedRequiresARequest() {
    TurnResolver.Resolved resolved = new TurnResolver.Resolved(PortTestData.modelRequest());
    assertEquals(PortTestData.modelRequest(), resolved.request());
    assertThrows(NullPointerException.class, () -> new TurnResolver.Resolved(null));
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
    // the port is a single-method functional contract; an anonymous implementation must be possible
    TurnResolver resolver =
        (threadId, path, yoloEnabled) ->
            new TurnResolver.Rejected(new AssistantError("PLANNING_FAILED", "rejected"));
    TurnResolver.Result result = resolver.resolve(1L, null, true);
    assertTrue(result instanceof TurnResolver.Rejected);
    assertEquals("rejected", ((TurnResolver.Rejected) result).error().message());
  }

  @Test
  void modelInvocationRequestStaysFrozenInTheResult() {
    ModelInvocationRequest request = PortTestData.modelRequest();
    TurnResolver.Resolved resolved = new TurnResolver.Resolved(request);
    assertEquals(request, resolved.request());
  }
}
