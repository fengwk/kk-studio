package fun.fengwk.kkstudio.platform.error;

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
  IN_USE("in_use"),

  /** 目标 Environment 的 IDE 通道当前不可用（未就绪、更新中、排队拒绝或超时）。 */
  IDE_UNAVAILABLE("ide_unavailable"),

  /** 目标 Environment 当前没有可用的 READY 路由或运行快照；客户端可稍后重试。 */
  ENVIRONMENT_UNAVAILABLE("environment_unavailable"),

  /** 资源依赖的外部服务（如 Provider 模型列表）不可达或返回失败。 */
  UPSTREAM_FAILED("upstream_failed");

  private final String code;

  DomainErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
