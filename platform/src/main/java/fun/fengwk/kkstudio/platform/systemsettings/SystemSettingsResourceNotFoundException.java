package fun.fengwk.kkstudio.platform.systemsettings;

/** 单行聚合缺失（id=1 行不存在）；映射为 HTTP 404。 */
public class SystemSettingsResourceNotFoundException extends SystemSettingsDomainException {

  public SystemSettingsResourceNotFoundException(String resource, String message) {
    super(SystemSettingsErrorCode.RESOURCE_NOT_FOUND, resource, message);
  }
}
