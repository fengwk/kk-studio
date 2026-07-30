package fun.fengwk.kkstudio.core.ai.error;

/** Unique name / provider+name conflict; mapped to HTTP 409. */
public class AiDuplicateException extends AiDomainException {

  public AiDuplicateException(String resource, String message) {
    super(DomainErrorCode.DUPLICATE, resource, message);
  }

  public AiDuplicateException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.DUPLICATE, resource, message, cause);
  }
}
