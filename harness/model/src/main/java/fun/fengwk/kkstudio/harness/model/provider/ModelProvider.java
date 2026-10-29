package fun.fengwk.kkstudio.harness.model.provider;

/** 标准化模型 Provider 的阻塞流边界。 */
public interface ModelProvider {

  /**
   * 在调用线程上阻塞执行一次模型调用：所有 handler 回调都发生在调用线程上，返回时已交付恰好一个终态（{@code onComplete} 或 {@code
   * onError}）。调用线程被中断即取消调用：关闭连接并静默返回，不交付终态；调用方据线程中断状态收敛。
   */
  void stream(ProviderRequest request, ProviderStreamHandler handler);
}
