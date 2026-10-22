package fun.fengwk.kkstudio.project.error;

/**
 * 请求键被另一个请求正文复用；确定性冲突，由 Web 边界统一映射为 HTTP 409。
 *
 * <p>调用方必须把它当作「同键异指纹」的终态拒绝：不重试、不按新请求继续执行。
 */
public class ProjectDuplicateException extends RuntimeException {

  private final String resource;

  public ProjectDuplicateException(String resource, String message) {
    super(message);
    this.resource = resource;
  }

  public ProjectDuplicateException(String resource, String message, Throwable cause) {
    super(message, cause);
    this.resource = resource;
  }

  public String resource() {
    return resource;
  }
}
