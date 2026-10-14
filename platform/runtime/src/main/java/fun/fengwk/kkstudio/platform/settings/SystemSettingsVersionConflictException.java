package fun.fengwk.kkstudio.platform.settings;

/** {@code expectedVersion} CAS 在乐观版本竞争中失败；映射为 HTTP 409。 */
public class SystemSettingsVersionConflictException extends SystemSettingsDomainException {

  private final String expectedVersion;
  private final String actualVersion;

  public SystemSettingsVersionConflictException(
      String resource, String expectedVersion, String actualVersion) {
    super(
        SystemSettingsErrorCode.VERSION_CONFLICT,
        resource,
        "system settings version conflict: expected="
            + expectedVersion
            + " actual="
            + actualVersion);
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
