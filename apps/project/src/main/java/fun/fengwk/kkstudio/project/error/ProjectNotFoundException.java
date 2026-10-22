package fun.fengwk.kkstudio.project.error;

/**
 * 按 id 引用的 Project/Issue 资源不存在；由 Web 边界统一映射为 HTTP 404。
 *
 * <p>使用固定消息且不保留底层 cause，避免泄漏资源标识或敏感输入。
 */
public class ProjectNotFoundException extends RuntimeException {

  private final String resource;

  public ProjectNotFoundException(String resource) {
    super(resource + " not found");
    this.resource = resource;
  }

  public String resource() {
    return resource;
  }
}
