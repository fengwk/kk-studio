package fun.fengwk.kkstudio.core.ai.error;

/** Resource cannot be removed because it is still referenced; mapped to HTTP 409. */
public class AiInUseException extends AiDomainException {

  public AiInUseException(String resource, String message) {
    super(DomainErrorCode.IN_USE, resource, message);
  }

  public AiInUseException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.IN_USE, resource, message, cause);
  }
}
