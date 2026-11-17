package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelFailureMetadata;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.time.Duration;

/** Retry-After may lengthen backoff, but its bounded value cannot exceed the local max delay. */
class ModelFailureRetryTest {

  @Test
  void retryAfterMergesWithBackoffAndRespectsLocalMaximum() {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "HTTP 503",
            503,
            null,
            new ModelFailureMetadata(null, null, true, 20_000L, null, null, null));

    assertEquals(
        Duration.ofSeconds(20),
        ModelFailureRetry.delay(error, Duration.ofSeconds(5), Duration.ofSeconds(30)));
    assertEquals(
        Duration.ofSeconds(10),
        ModelFailureRetry.delay(error, Duration.ofSeconds(5), Duration.ofSeconds(10)));
  }
}
