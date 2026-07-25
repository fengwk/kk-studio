package fun.fengwk.kkstudio.harness.model.provider;

/** 标准化模型 Provider 的异步流边界。 */
public interface ModelProvider {

  /** 启动请求并立即返回可取消流。 */
  ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler);
}
