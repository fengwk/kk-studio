package fun.fengwk.kkstudio.harness.builtin.port;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ModelGateway public contract：execution key、admission 三态、终态三态与 value validation。 */
class ModelGatewayTest {

  private static final UUID CONNECTION_GENERATION_ID = new UUID(0L, 1L);

  @Test
  void admissionAndOutcomeAreSealedWithExactlyTheDocumentedVariants() {
    List<Class<?>> admission = List.of(ModelGateway.Admission.class.getPermittedSubclasses());
    assertEquals(3, admission.size());
    assertTrue(admission.contains(ModelGateway.Admitted.class));
    assertTrue(admission.contains(ModelGateway.Busy.class));
    assertTrue(admission.contains(ModelGateway.Rejected.class));
    List<Class<?>> outcome = List.of(ModelGateway.Outcome.class.getPermittedSubclasses());
    assertEquals(3, outcome.size());
    assertTrue(outcome.contains(ModelGateway.Succeeded.class));
    assertTrue(outcome.contains(ModelGateway.Failed.class));
    assertTrue(outcome.contains(ModelGateway.Unknown.class));
  }

  @Test
  void executionFreezesTheKeyAndTheRequest() {
    ModelGateway.Execution execution =
        new ModelGateway.Execution(
            id(42L),
            3,
            ProviderType.OPENAI,
            CONNECTION_GENERATION_ID,
            PortTestData.providerRequest());
    assertEquals(id(42L), execution.invocationId());
    assertEquals(3, execution.proposedAttempt());
    assertEquals(ProviderType.OPENAI, execution.providerType());
    assertEquals(CONNECTION_GENERATION_ID, execution.providerConnectionGenerationId());
    assertEquals(PortTestData.providerRequest(), execution.request());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelGateway.Execution(
                id(1L),
                0,
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                PortTestData.providerRequest()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelGateway.Execution(
                id(1L), 1, ProviderType.OPENAI, CONNECTION_GENERATION_ID, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelGateway.Execution(
                id(1L), 1, null, CONNECTION_GENERATION_ID, PortTestData.providerRequest()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelGateway.Execution(
                id(1L), 1, ProviderType.OPENAI, null, PortTestData.providerRequest()));
  }

  @Test
  void busyRejectedAndOutcomesValidateTheirFacts() {
    ModelInvocationError error = new ModelInvocationError(ProviderErrorKind.TRANSIENT, "busy");
    assertThrows(NullPointerException.class, () -> new ModelGateway.Busy(null));
    assertThrows(IllegalArgumentException.class, () -> new ModelGateway.Busy(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ModelGateway.Busy(Duration.ofNanos(1_500_000)));
    assertEquals(Duration.ofSeconds(5), new ModelGateway.Busy(Duration.ofSeconds(5)).retryAfter());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Rejected(null));
    assertEquals(error, new ModelGateway.Rejected(error).error());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Succeeded(null));
    assertThrows(NullPointerException.class, () -> new ModelGateway.Failed(null));
    assertEquals(error, new ModelGateway.Failed(error).error());
    assertThrows(NullPointerException.class, () -> new ModelGateway.Unknown(null));
    assertEquals(error, new ModelGateway.Unknown(error).error());
  }

  /** 契约示例：admit 之后 run 在调用线程上同步推送增量并返回终态，close 与 run 都可释放名额。 */
  @Test
  void admittedRunsSynchronouslyAndDeliversEventsToTheSink() {
    List<ProviderStreamEvent> received = new ArrayList<>();
    ModelGateway gateway =
        execution ->
            new ModelGateway.Admitted() {
              @Override
              public ModelGateway.Outcome run(ModelGateway.Sink sink) {
                sink.onEvent(new ProviderStreamEvent.TextDelta("hello"));
                return new ModelGateway.Succeeded(
                    new ProviderCompletion(
                        new ProviderResponse(
                            "hello",
                            null,
                            List.of(),
                            GenerationStopReason.COMPLETE,
                            new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L),
                            null,
                            null,
                            null),
                        null));
              }

              @Override
              public void close() {
                // 名额释放幂等
              }
            };
    ModelGateway.Admission admission =
        gateway.admit(
            new ModelGateway.Execution(
                id(7L),
                1,
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                PortTestData.providerRequest()));
    ModelGateway.Admitted admitted = (ModelGateway.Admitted) admission;
    ModelGateway.Outcome outcome = admitted.run(received::add);
    assertEquals(List.of(new ProviderStreamEvent.TextDelta("hello")), received);
    assertTrue(outcome instanceof ModelGateway.Succeeded);
  }
}
