package fun.fengwk.kkstudio.platform.error;

/** 唯一 name / provider+name 冲突；映射为 HTTP 409。 */
public class AiDuplicateException extends AiDomainException {

  public AiDuplicateException(String resource, String message) {
    super(DomainErrorCode.DUPLICATE, resource, message);
  }

  public AiDuplicateException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.DUPLICATE, resource, message, cause);
  }
}
