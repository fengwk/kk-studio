package fun.fengwk.kkstudio.project.error;

/**
 * Project/Issue 更新或删除的 CAS 在乐观版本竞争中失败；由 Web 边界统一映射为 HTTP 409。
 *
 * <p>响应仅透出资源类型与期望/实际版本，协助前端判断 CAS 重试。
 */
public class ProjectVersionConflictException extends RuntimeException {

  private final String resource;
  private final String expectedVersion;
  private final String actualVersion;

  public ProjectVersionConflictException(
      String resource, String expectedVersion, String actualVersion) {
    super(resource + " version conflict: expected=" + expectedVersion + " actual=" + actualVersion);
    this.resource = resource;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }

  public String resource() {
    return resource;
  }

  public String expectedVersion() {
    return expectedVersion;
  }

  public String actualVersion() {
    return actualVersion;
  }
}
