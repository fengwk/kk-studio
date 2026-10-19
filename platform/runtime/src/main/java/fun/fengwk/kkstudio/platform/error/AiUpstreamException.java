package fun.fengwk.kkstudio.platform.error;

/** 资源依赖的外部服务不可达或返回失败；映射为 HTTP 502，消息只描述上游结果。 */
public class AiUpstreamException extends AiDomainException {

  public AiUpstreamException(String resource, String message) {
    super(DomainErrorCode.UPSTREAM_FAILED, resource, message);
  }
}
