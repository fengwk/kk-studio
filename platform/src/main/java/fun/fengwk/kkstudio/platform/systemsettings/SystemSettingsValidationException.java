package fun.fengwk.kkstudio.platform.systemsettings;

/** 请求体校验失败；映射为 HTTP 400。 */
public class SystemSettingsValidationException extends SystemSettingsDomainException {

  public SystemSettingsValidationException(String resource, String message) {
    super(SystemSettingsErrorCode.VALIDATION, resource, message);
  }

  public SystemSettingsValidationException(String resource, String message, Throwable cause) {
    super(SystemSettingsErrorCode.VALIDATION, resource, message, cause);
  }
}
