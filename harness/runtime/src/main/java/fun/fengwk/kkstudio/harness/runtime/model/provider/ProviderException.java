package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** 归一化后的 Provider 失败。 */
public final class ProviderException extends RuntimeException {

  private final ProviderErrorKind kind;

  public ProviderException(ProviderErrorKind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public ProviderException(ProviderErrorKind kind, String message) {
    this(kind, message, null);
  }

  public ProviderErrorKind kind() {
    return kind;
  }
}
