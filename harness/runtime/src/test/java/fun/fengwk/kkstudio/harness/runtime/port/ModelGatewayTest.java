package fun.fengwk.kkstudio.harness.runtime.port;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;

/** ModelGateway public contract：execution key、admission certainty 与 value validation。 */
class ModelGatewayTest {

  @Test
  void startResultIsSealedWithExactlyFourAdmissionOutcomes() {
    List<Class<?>> permitted = List.of(ModelGateway.StartResult.class.getPermittedSubclasses());
    assertEquals(4, permitted.size());
    assertTrue(permitted.contains(ModelGateway.Started.class));
    assertTrue(permitted.contains(ModelGateway.Busy.class));
    assertTrue(permitted.contains(ModelGateway.Rejected.class));
    assertTrue(permitted.contains(ModelGateway.Indeterminate.class));
  }

  @Test
  void executionFreezesTheKeyAndTheRequest() {
    ModelGateway.Execution execution =
        new ModelGateway.Execution(id(42L), 3, ProviderType.OPENAI, PortTestData.providerRequest());
    assertEquals(id(42L), execution.invocationId());
    assertEquals(3, execution.proposedAttempt());
    assertEquals(ProviderType.OPENAI, execution.providerType());
    assertEquals(PortTestData.providerRequest(), execution.request());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelGateway.Execution(
                id(1L), 0, ProviderType.OPENAI, PortTestData.providerRequest()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelGateway.Execution(
                id(1L), -1, ProviderType.OPENAI, PortTestData.providerRequest()));
    assertThrows(
        NullPointerException.class,
        () -> new ModelGateway.Execution(id(1L), 1, ProviderType.OPENAI, null));
    assertThrows(
        NullPointerException.class,
        () -> new ModelGateway.Execution(id(1L), 1, null, PortTestData.providerRequest()));
  }

  @Test
  void startedRequiresANonNullHandle() {
    ModelGateway.Handle handle = () -> {};
    ModelGateway.Started started = new ModelGateway.Started(handle);
    assertNotNull(started.handle());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Started(null));
  }

  @Test
  void busyAndRejectedAndIndeterminateValidateTheirFacts() {
    ModelInvocationError error = new ModelInvocationError(ProviderErrorKind.TRANSIENT, "busy");
    assertThrows(NullPointerException.class, () -> new ModelGateway.Busy(null));
    assertThrows(IllegalArgumentException.class, () -> new ModelGateway.Busy(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelGateway.Busy(Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelGateway.Busy(Duration.ofNanos(1_500_000)));
    assertEquals(Duration.ofSeconds(5), new ModelGateway.Busy(Duration.ofSeconds(5)).retryAfter());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Rejected(null));
    assertEquals(error, new ModelGateway.Rejected(error).error());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Indeterminate(null));
    assertEquals(error, new ModelGateway.Indeterminate(error).error());
  }

  @Test
  void listenerSurfaceCoversStreamAndTheThreeTerminalOutcomes() {
    ModelGateway.Listener listener =
        new ModelGateway.Listener() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onSucceeded(ProviderResponse response) {}

          @Override
          public void onFailed(ModelInvocationError error) {}

          @Override
          public void onUnknown(ModelInvocationError error) {}
        };
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    listener.onSucceeded(null);
    listener.onFailed(null);
    listener.onUnknown(null);
  }

  @Test
  void startAcceptsAValidExecutionAndListener() {
    ModelGateway gateway =
        (execution, listener) ->
            new ModelGateway.Started(
                () -> {
                  // 尽力而为、幂等的取消
                });
    ModelGateway.StartResult result =
        gateway.start(
            new ModelGateway.Execution(
                id(7L), 1, ProviderType.OPENAI, PortTestData.providerRequest()),
            events());
    assertTrue(result instanceof ModelGateway.Started);
  }

  private static ModelGateway.Listener events() {
    return new ModelGateway.Listener() {
      @Override
      public void onEvent(ProviderStreamEvent event) {}

      @Override
      public void onSucceeded(ProviderResponse response) {}

      @Override
      public void onFailed(ModelInvocationError error) {}

      @Override
      public void onUnknown(ModelInvocationError error) {}
    };
  }
}
