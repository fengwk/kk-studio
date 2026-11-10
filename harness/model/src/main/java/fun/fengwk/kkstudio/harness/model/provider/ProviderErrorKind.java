package fun.fengwk.kkstudio.harness.model.provider;

/** 用于 Runtime 重试与压缩决策的 Provider 错误分类（PLAN 7.4）。 */
public enum ProviderErrorKind {
  /** 可重试的瞬态网络错误或服务过载。 */
  TRANSIENT,

  /** 请求 token 总量超出模型上下文窗口；回合以 ASSISTANT_ERROR(CONTEXT_LIMIT) 关闭并触发 OVERFLOW 压缩。 */
  CONTEXT_LIMIT,

  /** HTTP 413 或本地计量的请求体超出已知限制；存在可省略旧媒体时允许一次重投影。 */
  REQUEST_BODY_LIMIT,

  /** 单个媒体超出该端点已知的尺寸、数量、边长或时长限制。 */
  MEDIA_LIMIT,

  /** 凭据缺失、无效或已过期导致认证失败。 */
  AUTHENTICATION,

  /** 账户余额不足或配额超限引发的账单错误。 */
  BILLING,

  /** 参数校验失败或不支持的请求格式。 */
  INVALID_REQUEST,

  /** Provider 返回或回调了无法接受的异常数据。 */
  INVALID_RESPONSE,

  /** 请求已被主动取消。 */
  CANCELLED,

  /** 模型组内没有可用候选；本地调度失败，不是上游 Provider 错误且不可重试。 */
  NO_AVAILABLE_MODEL
}
