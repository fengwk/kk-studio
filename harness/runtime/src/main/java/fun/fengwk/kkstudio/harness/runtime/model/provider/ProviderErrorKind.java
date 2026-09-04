package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 用于 Runtime 重试与压缩决策的 Provider 错误分类。 */
public enum ProviderErrorKind {
  /** 可重试的瞬态网络错误或服务过载。 */
  TRANSIENT,

  /** 请求上下文超出模型最大窗口限制。 */
  OVERFLOW,

  /** 凭据缺失、无效或已过期导致认证失败。 */
  AUTHENTICATION,

  /** 账户余额不足或配额超限引发的账单错误。 */
  BILLING,

  /** 参数校验失败或不支持的请求格式。 */
  INVALID_REQUEST,

  /** Provider 返回或回调了无法接受的异常数据。 */
  INVALID_RESPONSE,

  /** 请求已被主动取消。 */
  CANCELLED
}
