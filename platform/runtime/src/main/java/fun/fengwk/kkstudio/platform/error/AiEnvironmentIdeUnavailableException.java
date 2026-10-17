package fun.fengwk.kkstudio.platform.error;

/** 目标 Environment 的 IDE 通道当前不可用；映射为 HTTP 409，客户端可稍后重试。 */
public class AiEnvironmentIdeUnavailableException extends AiDomainException {

  public AiEnvironmentIdeUnavailableException(String message) {
    super(DomainErrorCode.IDE_UNAVAILABLE, "environment", message);
  }

  public AiEnvironmentIdeUnavailableException(String message, Throwable cause) {
    super(DomainErrorCode.IDE_UNAVAILABLE, "environment", message, cause);
  }
}
