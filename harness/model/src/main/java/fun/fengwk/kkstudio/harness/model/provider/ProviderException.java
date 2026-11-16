package fun.fengwk.kkstudio.harness.model.provider;

import fun.fengwk.kkstudio.harness.model.ModelFailureMetadata;

import java.util.Objects;

/** 归一化后的 Provider 失败。 */
public final class ProviderException extends RuntimeException {

  private final ProviderErrorKind kind;
  private final Integer httpStatus;
  private final String providerErrorCode;
  private final ModelFailureMetadata metadata;

  public ProviderException(ProviderErrorKind kind, String message) {
    this(kind, message, null, null, null);
  }

  public ProviderException(ProviderErrorKind kind, String message, Integer httpStatus) {
    this(kind, message, httpStatus, null, null);
  }

  public ProviderException(
      ProviderErrorKind kind,
      String message,
      Integer httpStatus,
      String providerErrorCode,
      ModelFailureMetadata metadata) {
    super(message);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.httpStatus = httpStatus;
    this.providerErrorCode = providerErrorCode;
    this.metadata = metadata;
  }

  public ProviderErrorKind kind() {
    return kind;
  }

  public Integer httpStatus() {
    return httpStatus;
  }

  public String providerErrorCode() {
    return providerErrorCode;
  }

  public ModelFailureMetadata metadata() {
    return metadata;
  }
}
