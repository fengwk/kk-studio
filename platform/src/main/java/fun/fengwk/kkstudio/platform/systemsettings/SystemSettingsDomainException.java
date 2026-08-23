package fun.fengwk.kkstudio.platform.systemsettings;

/**
 * System settings 的与 HTTP 无关的类型化领域错误基类。
 *
 * <p>每个子类映射到恰好一个 {@link SystemSettingsErrorCode}，携带稳定消息与资源标签，由 web 翻译层转成 {@link
 * fun.fengwk.convention4j.api.result.Result} 错误信封。
 */
public abstract class SystemSettingsDomainException extends RuntimeException {

  private final SystemSettingsErrorCode code;
  private final String resource;

  protected SystemSettingsDomainException(
      SystemSettingsErrorCode code, String resource, String message) {
    super(message);
    this.code = code;
    this.resource = resource;
  }

  protected SystemSettingsDomainException(
      SystemSettingsErrorCode code, String resource, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.resource = resource;
  }

  public SystemSettingsErrorCode code() {
    return code;
  }

  public String resource() {
    return resource;
  }
}
