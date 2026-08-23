package fun.fengwk.kkstudio.platform.error;

/** 按 id 引用的资源不存在；映射为 HTTP 404。 */
public class AiResourceNotFoundException extends AiDomainException {

  public AiResourceNotFoundException(String resource, String message) {
    super(DomainErrorCode.RESOURCE_NOT_FOUND, resource, message);
  }

  public AiResourceNotFoundException(String resource, String message, Throwable cause) {
    super(DomainErrorCode.RESOURCE_NOT_FOUND, resource, message, cause);
  }
}
