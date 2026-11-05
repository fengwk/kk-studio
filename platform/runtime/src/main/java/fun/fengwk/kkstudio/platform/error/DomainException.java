package fun.fengwk.kkstudio.platform.error;

/**
 * 带稳定错误码的领域异常。
 *
 * <p>全仓领域失败只使用这一种异常。{@link DomainErrorCode} 属于 HTTP 契约；{@code resource} 是稳定的内部资源标识； {@code message}
 * 只描述失败本身，不回显底层异常文本。错误码、固定消息与可选版本坐标由命名工厂构造，调用方不再为每种错误码引入独立类型。
 */
public class DomainException extends RuntimeException {

  private final DomainErrorCode code;
  private final String resource;
  private final String expectedVersion;
  private final String actualVersion;

  private DomainException(
      DomainErrorCode code,
      String resource,
      String message,
      Throwable cause,
      String expectedVersion,
      String actualVersion) {
    super(message, cause);
    this.code = code;
    this.resource = resource;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }

  /** 请求体或参数校验失败。 */
  public static DomainException validation(String resource, String message) {
    return new DomainException(DomainErrorCode.VALIDATION, resource, message, null, null, null);
  }

  public static DomainException validation(String resource, String message, Throwable cause) {
    return new DomainException(DomainErrorCode.VALIDATION, resource, message, cause, null, null);
  }

  /** 引用的资源不存在；固定消息且不保留底层 cause，避免泄漏资源标识或敏感输入。 */
  public static DomainException notFound(String resource) {
    return new DomainException(
        DomainErrorCode.RESOURCE_NOT_FOUND, resource, resource + " not found", null, null, null);
  }

  /** 更新/删除 CAS 在乐观版本竞争中失败。 */
  public static DomainException versionConflict(
      String resource, String expectedVersion, String actualVersion) {
    return new DomainException(
        DomainErrorCode.VERSION_CONFLICT,
        resource,
        resource + " version conflict: expected=" + expectedVersion + " actual=" + actualVersion,
        null,
        expectedVersion,
        actualVersion);
  }

  /** 唯一 name / provider+name 冲突。 */
  public static DomainException duplicate(String resource, String message) {
    return new DomainException(DomainErrorCode.DUPLICATE, resource, message, null, null, null);
  }

  public static DomainException duplicate(String resource, String message, Throwable cause) {
    return new DomainException(DomainErrorCode.DUPLICATE, resource, message, cause, null, null);
  }

  /** 资源仍被引用而无法移除。 */
  public static DomainException inUse(String resource, String message) {
    return new DomainException(DomainErrorCode.IN_USE, resource, message, null, null, null);
  }

  public static DomainException inUse(String resource, String message, Throwable cause) {
    return new DomainException(DomainErrorCode.IN_USE, resource, message, cause, null, null);
  }

  /** 目标 Environment 的 IDE 通道当前不可用。 */
  public static DomainException ideUnavailable(String message) {
    return new DomainException(
        DomainErrorCode.IDE_UNAVAILABLE, "environment", message, null, null, null);
  }

  public static DomainException ideUnavailable(String message, Throwable cause) {
    return new DomainException(
        DomainErrorCode.IDE_UNAVAILABLE, "environment", message, cause, null, null);
  }

  /** 目标 Environment 当前没有可用的 READY 路由或运行快照。 */
  public static DomainException environmentUnavailable(String message) {
    return new DomainException(
        DomainErrorCode.ENVIRONMENT_UNAVAILABLE, "environment", message, null, null, null);
  }

  public static DomainException environmentUnavailable(String message, Throwable cause) {
    return new DomainException(
        DomainErrorCode.ENVIRONMENT_UNAVAILABLE, "environment", message, cause, null, null);
  }

  /** 资源依赖的外部服务不可达或返回失败。 */
  public static DomainException upstream(String resource, String message) {
    return new DomainException(
        DomainErrorCode.UPSTREAM_FAILED, resource, message, null, null, null);
  }

  public DomainErrorCode code() {
    return code;
  }

  public String resource() {
    return resource;
  }

  /** 仅 {@link DomainErrorCode#VERSION_CONFLICT} 携带期望版本。 */
  public String expectedVersion() {
    return expectedVersion;
  }

  /** 仅 {@link DomainErrorCode#VERSION_CONFLICT} 携带实际版本。 */
  public String actualVersion() {
    return actualVersion;
  }
}
