package fun.fengwk.kkstudio.harness.runtime;

/**
 * 子代理并发额度（父级并发子任务数或全局并发子代理数）已满，本次派生未创建任何子执行。
 *
 * <p>调用方据此区分“额度满、稍后可重试”与其它准入拒绝；它仍是 {@link IllegalArgumentException}，保持准入拒绝的既有处理路径。
 */
public final class SpawnQuotaExceededException extends IllegalArgumentException {

  public SpawnQuotaExceededException(String message) {
    super(message);
  }
}
