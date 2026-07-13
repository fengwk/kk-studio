package fun.fengwk.kkstudio.harness.model.provider;

/** 用于 Runtime 重试与压缩决策的 Provider 错误分类。 */
public enum ProviderErrorKind {
  TRANSIENT,
  OVERFLOW,
  AUTHENTICATION,
  BILLING,
  INVALID_REQUEST,
  CANCELLED
}
