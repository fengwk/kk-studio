package fun.fengwk.kkstudio.core.ai.error;

/** Resource referenced by id does not exist; mapped to HTTP 404. */
public class AiResourceNotFoundException extends AiDomainException {

  public AiResourceNotFoundException(String resource, String message) {
    super(DomainErrorCode.RESOURCE_NOT_FOUND, resource, message);
  }

  public AiResourceNotFoundException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.RESOURCE_NOT_FOUND, resource, message, cause);
  }
}
