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

  /**
   * 无网络地把请求物化为 {@link #create(ProviderDescriptor)} 的 Provider 经 {@link ModelProvider#stream} 发送的最终
   * UTF-8 JSON 请求体。
   *
   * <p>实现必须复用正式发送路径上的同一 encoder 与同一 configuration，因此返回字节与该请求实际发送的 body 逐字节一致， 包括 native
   * protocolOptions 合并结果、prompt cache 标记与 assistant replay 回放结果。本方法不解析 endpoint、不触发
   * transport/网络调用，也不生成任何认证 Header；编码失败时原样抛出 {@link ProviderException}（例如超限或非法请求）。
   *
   * <p>默认实现表示该 adapter 不提供请求体预览能力，此时调用方必须显式处理该异常，而不是把请求当作可预览。
   *
   * @param request 与正式发送相同的请求
   * @param descriptor 与正式发送相同的连接描述
   * @return 与正式发送逐字节一致的最终 UTF-8 请求体
   */
  default byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    throw new UnsupportedOperationException(
        providerType() + " adapter does not support request body preview");
  }
}
