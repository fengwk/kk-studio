package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 将 Provider 连接配置适配为标准化 {@link ModelProvider} 的工厂边界。 */
public interface ProviderAdapter {

  /** 返回该 adapter 支持的 Provider 类型。 */
  ProviderType providerType();

  /** 当前协议及连接配置的内联媒体能力；未显式声明时只允许资源文本回退。 */
  default ProviderMediaCapabilities mediaCapabilities() {
    return ProviderMediaCapabilities.NONE;
  }

  /** 使用上层已解析的连接配置创建 Provider。 */
  ModelProvider create(ProviderDescriptor descriptor);
}
