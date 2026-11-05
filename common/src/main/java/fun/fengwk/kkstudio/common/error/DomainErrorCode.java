package fun.fengwk.kkstudio.common.error;

/**
 * 领域失败的稳定机器可读错误码。
 *
 * <p>这些错误码属于 HTTP 契约的一部分：客户端用它们分支处理重试/刷新行为，而无需解析消息。
 */
public enum DomainErrorCode {

  /** 请求体或参数校验失败。 */
  VALIDATION("validation"),

  /** 按 id 或标识引用的资源不存在。 */
  NOT_FOUND("not_found"),

  /** 更新/删除 CAS 在乐观版本竞争中失败；客户端必须刷新。 */
  VERSION_CONFLICT("version_conflict"),

  /** 业务状态冲突（必须携带 reason 细分）。 */
  CONFLICT("conflict"),

  /** 唯一 name / provider+name 冲突（在 create/update 后作为竞争检测到）。 */
  DUPLICATE("duplicate"),

  /** 资源仍被引用而无法移除。 */
  IN_USE("in_use"),

  /** 外部依赖或环境当前不可用；客户端可稍后重试。 */
  UNAVAILABLE("unavailable"),

  /** 资源依赖的外部服务不可达或返回失败。 */
  UPSTREAM_FAILED("upstream_failed"),

  /** 未授权操作（集群内部端点鉴权失败）。 */
  UNAUTHORIZED("unauthorized"),

  /** 内部未预期错误。 */
  INTERNAL("internal");

  private final String code;

  DomainErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
