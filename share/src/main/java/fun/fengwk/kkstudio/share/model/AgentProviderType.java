package fun.fengwk.kkstudio.share.model;

/**
 * Provider / Model 资源侧供应商类型。
 *
 * <p>Wire value 使用 lowercase 枚举名，与 HTTP DTO 和持久化字段保持一致。Harness 运行时另有独立的 {@code
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType}，由 Turn resource 解析层做映射。
 */
public enum AgentProviderType {
  openai,
  openai_response,
  anthropic,
  google,
}
