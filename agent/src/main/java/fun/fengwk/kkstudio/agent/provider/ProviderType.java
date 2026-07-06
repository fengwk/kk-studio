package fun.fengwk.kkstudio.agent.provider;

/**
 * ProviderType 枚举定义当前系统支持的供应商类型。
 *
 * <p>约束： - 新增 provider 时，先补充新的枚举值。 - 然后在 ProviderManagerImpl 中注册对应工厂。 - 最后提供对应的 Provider 实现类。
 *
 * @author fengwk
 */
public enum ProviderType {
  openai, // openai 兼容协议
  openai_response,
  anthropic,
  google,
  ;
}
