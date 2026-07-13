package fun.fengwk.kkstudio.harness.model.provider;

/** 一次 Provider 流的取消控制。 */
public interface ProviderStream {

  /** 请求取消流；调用必须幂等。 */
  void cancel();

  /** 流是否已经收到取消请求。 */
  boolean isCancelled();
}
