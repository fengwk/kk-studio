package fun.fengwk.kkstudio.harness.runtime.model.worker;

/** 一次 ModelExecutor 调用的最佳努力本地取消控制。 */
public interface ModelExecutionHandle {

  /** 请求取消；调用必须幂等。 */
  void cancel();

  /** 本地 handle 是否已经收到取消请求。 */
  boolean isCancelled();
}
