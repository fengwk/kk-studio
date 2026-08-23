package fun.fengwk.kkstudio.platform.ai.error;

/**
 * 与 HTTP 无关的 AI catalog（Agent / Model / Provider / Chat）类型化领域错误。
 *
 * <p>每个子类都映射到恰好一个 {@link DomainErrorCode} 并携带稳定消息。携带单个 {@code resource} 标签，便于 web 翻译层返回给调用方。
 */
public abstract class AiDomainException extends RuntimeException {

  private final DomainErrorCode code;
  private final String resource;

  protected AiDomainException(DomainErrorCode code, String resource, String message) {
    super(message);
    this.code = code;
    this.resource = resource;
  }

  protected AiDomainException(
      DomainErrorCode code, String resource, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.resource = resource;
  }

  public DomainErrorCode code() {
    return code;
  }

  public String resource() {
    return resource;
  }
}
