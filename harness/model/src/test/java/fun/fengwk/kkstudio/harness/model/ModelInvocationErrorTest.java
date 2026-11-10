package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

/** {@link ModelInvocationError} 的重试候选判定、httpStatus 边界与原始 provider code 保留。 */
class ModelInvocationErrorTest {

  @Test
  void limitAndCancelledAreNeverRetryCandidates() {
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.CONTEXT_LIMIT, "too long", 400)
            .retryCandidate());
    assertFalse(
        new ModelInvocationError(ProviderErrorKind.REQUEST_BODY_LIMIT, "body too large")
            .retryCandidate());
    assertFalse(new ModelInvocationError(ProviderErrorKind.MEDIA_LIMIT, "media").retryCandidate());
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

  @Test
  void providerErrorCodeIsPreservedVerbatimForDiagnostics() {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.CONTEXT_LIMIT, "too long", 400, "context_length_exceeded");
    assertEquals("context_length_exceeded", error.providerErrorCode());

    assertNull(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m").providerErrorCode());
    assertNull(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", null, "  ").providerErrorCode());
  }

  @Test
  void providerErrorCodeIsTruncatedAtDiagnosticBound() {
    String longCode = "x".repeat(ModelInvocationError.MAX_PROVIDER_CODE_LENGTH + 10);
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "m", null, longCode);
    assertEquals(ModelInvocationError.MAX_PROVIDER_CODE_LENGTH, error.providerErrorCode().length());
  }
}
