package fun.fengwk.kkstudio.core.ai.error;

/** 资源仍被引用而无法移除；映射为 HTTP 409。 */
public class AiInUseException extends AiDomainException {

  public AiInUseException(String resource, String message) {
    super(DomainErrorCode.IN_USE, resource, message);
  }

  public AiInUseException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.IN_USE, resource, message, cause);
  }
}
