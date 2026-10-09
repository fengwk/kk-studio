package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

/** {@link ModelInvocationError} 的重试候选判定与 httpStatus 边界。 */
class ModelInvocationErrorTest {

  @Test
  void overflowAndCancelledAreNeverRetryCandidates() {
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.OVERFLOW, "too long", 400).retryCandidate());
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled").retryCandidate());
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled", 499).retryCandidate());
  }

  @Test
  void httpStatusMakesAnyKindACandidate() {
    assertTrue(
        new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth", 401).retryCandidate());
    assertTrue(new ModelInvocationError(ProviderErrorKind.BILLING, "quota", 429).retryCandidate());
    assertTrue(
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad", 400).retryCandidate());
    assertTrue(
        new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, "redirect", 301)
            .retryCandidate());
  }

  @Test
  void nonHttpNonTransientKindsAreNotCandidates() {
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth").retryCandidate());
    assertFalse(new ModelInvocationError(ProviderErrorKind.BILLING, "quota").retryCandidate());
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad").retryCandidate());
    assertTrue(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "down").retryCandidate());
    assertTrue(
        new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, "broken").retryCandidate());
  }

  @Test
  void httpStatusBoundaryRejectsOutsideStructuredRange() {
    assertTrue(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", 100).httpStatus() == 100);
    assertTrue(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", 599).httpStatus() == 599);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", 99));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", 600));
  }
}
