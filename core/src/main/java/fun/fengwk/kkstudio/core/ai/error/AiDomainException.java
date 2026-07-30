package fun.fengwk.kkstudio.core.ai.error;

/**
 * HTTP-independent typed domain error for the AI catalog (Agent / Model / Provider / Chat).
 *
 * <p>Each subclass maps to exactly one {@link DomainErrorCode} and carries a stable message.
 * Carries a single {@code resource} tag so web translation can return it to the caller.
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
