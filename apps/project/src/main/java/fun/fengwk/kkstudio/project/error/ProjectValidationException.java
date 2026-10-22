package fun.fengwk.kkstudio.project.error;

/**
 * Project/Issue 请求体或参数校验失败；由 Web 边界统一映射为 HTTP 400。
 *
 * <p>携带单个 {@code resource} 标签与脱敏消息，绝不回显领域正文或幂等键。
 */
public class ProjectValidationException extends RuntimeException {

  private final String resource;

  public ProjectValidationException(String resource, String message) {
    super(message);
    this.resource = resource;
  }

  public ProjectValidationException(String resource, String message, Throwable cause) {
    super(message, cause);
    this.resource = resource;
  }

  public String resource() {
    return resource;
  }
}
