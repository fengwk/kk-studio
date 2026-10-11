package fun.fengwk.kkstudio.platform.environment.cluster;

/** 内部请求认证失败；消息固定且绝不携带秘密，供 web 层映射为 401。 */
public class ClusterInternalAuthenticationException extends RuntimeException {

  public ClusterInternalAuthenticationException(String message) {
    super(message);
  }
}
