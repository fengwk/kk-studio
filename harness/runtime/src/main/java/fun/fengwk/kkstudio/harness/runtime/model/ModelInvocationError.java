package fun.fengwk.kkstudio.harness.runtime.model;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.util.Objects;

/**
 * Minimum terminal error snapshot persisted as the {@code error} JSONB column on {@code
 * harness_model_invocation} for {@code FAILED} / {@code UNKNOWN} statuses.
 *
 * <p>The snapshot is intentionally narrow: a non-null {@link ProviderErrorKind} plus a non-blank
 * human-readable {@code message}. The terminal-state adapter constructs it from the original {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderException} classification. It is persisted
 * only for terminal {@code FAILED}/{@code UNKNOWN}; {@code RETRY_WAIT} carries no terminal payload.
 *
 * <p>Strict JSON encoding/decoding lives in {@link ModelInvocationErrorJsonCodec}.
 */
public record ModelInvocationError(ProviderErrorKind kind, String message) {

  public ModelInvocationError {
    kind = Objects.requireNonNull(kind, "kind");
    message = requireNonBlank(message, "message");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
