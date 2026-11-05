package fun.fengwk.kkstudio.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 带稳定错误码的通用领域异常。
 *
 * <p>{@link DomainErrorCode} 属于 HTTP 契约；{@code resource} 是稳定的内部资源标识；{@code reason}
 * 是模块内稳定的原因标识；{@code message} 描述失败本身；错误码、固定消息、版本坐标或结构化 errors 由命名工厂构造，统一定义领域失败契约。
 */
public class DomainException extends RuntimeException {

  private static final Pattern REASON_PATTERN = Pattern.compile("^[A-Z0-9]+(_[A-Z0-9]+)*$");

  private final DomainErrorCode code;
  private final String resource;
  private final String reason;
  private final String expectedVersion;
  private final String actualVersion;
  private final Map<String, Object> errors;

  private DomainException(
      DomainErrorCode code,
      String resource,
      String reason,
      String message,
      Throwable cause,
      String expectedVersion,
      String actualVersion,
      Map<String, Object> errors) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.resource = resource;
    this.reason = reason;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
    this.errors = errors != null ? Collections.unmodifiableMap(new LinkedHashMap<>(errors)) : null;
  }

  private static void validateReason(String reason) {
    if (reason == null || !REASON_PATTERN.matcher(reason).matches()) {
      throw new IllegalArgumentException(
          "reason must be an uppercase underscore string: " + reason);
    }
  }

  /** 请求体或参数校验失败。 */
  public static DomainException validation(String resource, String message) {
    return new DomainException(
        DomainErrorCode.VALIDATION, resource, null, message, null, null, null, null);
  }

  public static DomainException validation(String resource, String message, Throwable cause) {
    return new DomainException(
        DomainErrorCode.VALIDATION, resource, null, message, cause, null, null, null);
  }

  /** 请求校验失败并携带结构化 reason（如 IDE_CLOSE_CANCELLED）。 */
  public static DomainException validationReason(String reason, String message) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.VALIDATION, null, reason, message, null, null, null, null);
  }

  /** 引用的资源不存在；固定消息且不保留底层 cause，避免泄漏资源标识或敏感输入。 */
  public static DomainException notFound(String resource) {
    return new DomainException(
        DomainErrorCode.NOT_FOUND, resource, null, resource + " not found", null, null, null, null);
  }

  /** 引用的资源不存在，并携带自定义消息。 */
  public static DomainException notFound(String resource, String message) {
    return new DomainException(
        DomainErrorCode.NOT_FOUND, resource, null, message, null, null, null, null);
  }

  /** 更新/删除 CAS 在乐观版本竞争中失败。 */
  public static DomainException versionConflict(
      String resource, String expectedVersion, String actualVersion) {
    return new DomainException(
        DomainErrorCode.VERSION_CONFLICT,
        resource,
        null,
        resource + " version conflict: expected=" + expectedVersion + " actual=" + actualVersion,
        null,
        expectedVersion,
        actualVersion,
        null);
  }

  /** 业务状态冲突；必须携带 reason 细分。 */
  public static DomainException conflict(String reason, String message) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.CONFLICT, null, reason, message, null, null, null, null);
  }

  public static DomainException conflict(String reason, String message, Throwable cause) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.CONFLICT, null, reason, message, cause, null, null, null);
  }

  public static DomainException conflict(
      String reason, String message, Map<String, Object> errors) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.CONFLICT, null, reason, message, null, null, null, errors);
  }

  /** 唯一 name / provider+name 冲突。 */
  public static DomainException duplicate(String resource, String message) {
    return new DomainException(
        DomainErrorCode.DUPLICATE, resource, null, message, null, null, null, null);
  }

  public static DomainException duplicate(String resource, String message, Throwable cause) {
    return new DomainException(
        DomainErrorCode.DUPLICATE, resource, null, message, cause, null, null, null);
  }

  /** 资源仍被引用而无法移除。 */
  public static DomainException inUse(String resource, String message) {
    return new DomainException(
        DomainErrorCode.IN_USE, resource, null, message, null, null, null, null);
  }

  public static DomainException inUse(String resource, String message, Throwable cause) {
    return new DomainException(
        DomainErrorCode.IN_USE, resource, null, message, cause, null, null, null);
  }

  /** 外部依赖或运行环境不可用；必须携带 reason 细分。 */
  public static DomainException unavailable(String reason, String message) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.UNAVAILABLE, null, reason, message, null, null, null, null);
  }

  public static DomainException unavailable(String reason, String message, Throwable cause) {
    validateReason(reason);
    return new DomainException(
        DomainErrorCode.UNAVAILABLE, null, reason, message, cause, null, null, null);
  }

  /** 资源依赖的外部服务不可达或返回失败。 */
  public static DomainException upstream(String resource, String message) {
    return new DomainException(
        DomainErrorCode.UPSTREAM_FAILED, resource, null, message, null, null, null, null);
  }

  public DomainErrorCode code() {
    return code;
  }

  public String resource() {
    return resource;
  }

  public String reason() {
    return reason;
  }

  /** 仅 {@link DomainErrorCode#VERSION_CONFLICT} 携带期望版本。 */
  public String expectedVersion() {
    return expectedVersion;
  }

  /** 仅 {@link DomainErrorCode#VERSION_CONFLICT} 携带实际版本。 */
  public String actualVersion() {
    return actualVersion;
  }

  /** 结构化错误详情。 */
  public Map<String, Object> errors() {
    return errors;
  }
}
