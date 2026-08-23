package fun.fengwk.kkstudio.platform.ai.error;

/**
 * AI catalog 领域失败的稳定机器可读错误码。
 *
 * <p>这些错误码属于 HTTP 契约的一部分：客户端用它们分支处理重试/刷新行为，而无需解析消息。 可以新增错误码；既有错误码冻结不变。
 */
public enum DomainErrorCode {

  /** 请求体或参数校验失败。 */
  VALIDATION("validation"),

  /** 按 id 引用的资源不存在。 */
  RESOURCE_NOT_FOUND("resource_not_found"),

  /** 更新/删除 CAS 在乐观版本竞争中失败；客户端必须刷新。 */
  VERSION_CONFLICT("version_conflict"),

  /** 唯一 name / provider+name 冲突（在 create/update 后作为竞争检测到）。 */
  DUPLICATE("duplicate"),

  /** 资源仍被引用而无法移除。 */
  IN_USE("in_use");

  private final String code;

  DomainErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
