package fun.fengwk.kkstudio.platform.error;

/** 目标 Environment 当前没有可用的 READY 路由或运行快照；映射为 HTTP 409，客户端可稍后重试。 */
public class AiEnvironmentUnavailableException extends AiDomainException {

  public AiEnvironmentUnavailableException(String message) {
    super(DomainErrorCode.ENVIRONMENT_UNAVAILABLE, "environment", message);
  }

  public AiEnvironmentUnavailableException(String message, Throwable cause) {
    super(DomainErrorCode.ENVIRONMENT_UNAVAILABLE, "environment", message, cause);
  }
}
