package fun.fengwk.kkstudio.platform.error;

/** 更新/删除 CAS 在乐观版本竞争中失败；映射为 HTTP 409。 */
public class AiVersionConflictException extends AiDomainException {

  private final String expectedVersion;
  private final String actualVersion;

  public AiVersionConflictException(String resource, String expectedVersion, String actualVersion) {
    super(
        DomainErrorCode.VERSION_CONFLICT,
        resource,
        resource + " version conflict: expected=" + expectedVersion + " actual=" + actualVersion);
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }

  public String expectedVersion() {
    return expectedVersion;
  }

  public String actualVersion() {
    return actualVersion;
  }
}
