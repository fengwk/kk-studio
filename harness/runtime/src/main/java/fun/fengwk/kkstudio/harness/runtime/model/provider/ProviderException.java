package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** 归一化后的 Provider 失败。 */
public final class ProviderException extends RuntimeException {

  private final ProviderErrorKind kind;

  /** HTTP 状态（100–599，含非预期 3xx）；非 HTTP 失败为 null，绝不从 message 文本推断。 */
  private final Integer httpStatus;

  public ProviderException(ProviderErrorKind kind, String message) {
    this(kind, message, null);
  }

  /** 携带结构化 HTTP 状态（100–599）的失败；{@code httpStatus} 可为 null 表示非 HTTP 语义。 */
  public ProviderException(ProviderErrorKind kind, String message, Integer httpStatus) {
    super(message);
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
