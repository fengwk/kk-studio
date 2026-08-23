package fun.fengwk.kkstudio.platform.ai.error;

/** 请求体或参数校验失败；映射为 HTTP 400。 */
public class AiValidationException extends AiDomainException {

  public AiValidationException(String resource, String message) {
    super(DomainErrorCode.VALIDATION, resource, message);
  }

  public AiValidationException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.VALIDATION, resource, message, cause);
  }
}
