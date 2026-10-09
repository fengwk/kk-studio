package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** 归一化后的 Provider 失败。 */
public final class ProviderException extends RuntimeException {

  private final ProviderErrorKind kind;

  /** HTTP error 状态（400–599）；非 HTTP 失败为 null，绝不从 message 文本推断。 */
  private final Integer httpStatus;

  public ProviderException(ProviderErrorKind kind, String message, Throwable cause) {
    this(kind, message, cause, null);
  }

  public ProviderException(ProviderErrorKind kind, String message) {
    this(kind, message, null, null);
  }

  /** 携带 HTTP error 状态的结构化失败；{@code httpStatus} 可为 null 表示非 HTTP 语义。 */
  public ProviderException(ProviderErrorKind kind, String message, Integer httpStatus) {
    this(kind, message, null, httpStatus);
  }

  public ProviderException(
      ProviderErrorKind kind, String message, Throwable cause, Integer httpStatus) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.httpStatus = httpStatus;
  }

  public ProviderErrorKind kind() {
    return kind;
  }

  public Integer httpStatus() {
    return httpStatus;
  }
}
