package fun.fengwk.kkstudio.platform.settings;

/**
 * System settings 领域失败的稳定机器可读错误码，属于 HTTP 契约的一部分。
 *
 * <p>与 {@code DomainErrorCode} 对齐但独立维护：settings 是单行强类型聚合，不需要 duplicate/in_use 这类多资源错误。
 */
public enum SystemSettingsErrorCode {

  /** 请求体校验失败。 */
  VALIDATION("validation"),

  /** 单行聚合缺失（理论上只可能由外部 DDL 删除导致）。 */
  RESOURCE_NOT_FOUND("resource_not_found"),

  /** expectedVersion CAS 在乐观版本竞争中失败；客户端必须刷新后重试。 */
  VERSION_CONFLICT("version_conflict");

  private final String code;

  SystemSettingsErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
