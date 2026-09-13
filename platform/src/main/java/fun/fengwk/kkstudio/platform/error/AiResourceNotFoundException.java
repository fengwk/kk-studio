package fun.fengwk.kkstudio.platform.error;

/** 引用的资源不存在；使用固定消息且不保留底层 cause，避免泄漏资源标识或敏感输入。 */
public class AiResourceNotFoundException extends AiDomainException {

  public AiResourceNotFoundException(String resource) {
    super(DomainErrorCode.RESOURCE_NOT_FOUND, resource, resource + " not found");
  }
}
