package fun.fengwk.kkstudio.core.ai.error;

/** Request body or parameter failed validation; mapped to HTTP 400. */
public class AiValidationException extends AiDomainException {

  public AiValidationException(String resource, String message) {
    super(DomainErrorCode.VALIDATION, resource, message);
  }

  public AiValidationException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.VALIDATION, resource, message, cause);
  }
}
