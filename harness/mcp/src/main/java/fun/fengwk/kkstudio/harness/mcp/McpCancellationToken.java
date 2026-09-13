package fun.fengwk.kkstudio.harness.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单次 MCP 操作的调用级取消令牌。
 *
 * <p>取消是协议级事实：令牌绑定到某个正在执行的请求后，取消会立即结束该请求的等待，并按 MCP 协议通知服务端停止执行该请求。 取消只影响本次操作，绝不关闭或失效共享的 MCP
 * client，因此同版本其它并发调用不受影响。
 *
 * <p>取消先于请求建立（尚未拿到 request id）时不会丢失：监听器在注册时若发现已经取消，会立即执行。
 */
public final class McpCancellationToken {

  /**
   * 「永不取消」共享令牌：不可取消，因此既不改变状态也不保留监听器。
   *
   * <p>它被跨调用共享，因此绝不允许出现「共享单例被 cancel 后永久污染」或「每次注册回调都累积监听器」的情况。
   */
  private static final McpCancellationToken NONE = new McpCancellationToken(false);

  private final boolean cancellable;
  private boolean cancelled;
  private final List<Runnable> listeners;

  /** 创建一个正常可取消的令牌。 */
  public McpCancellationToken() {
    this(true);
  }

  private McpCancellationToken(boolean cancellable) {
    this.cancellable = cancellable;
    this.listeners = cancellable ? new ArrayList<>() : null;
  }

  /**
   * 返回一个「永不取消」的令牌，供确实没有取消需求的调用方使用。
   *
   * <p>它不是「无限预算」的替代品：deadline 仍然必须显式提供。
   */
  public static McpCancellationToken none() {
    return NONE;
  }

  /** 是否已请求取消；{@link #none()} 恒为 false。 */
  public synchronized boolean isCancelled() {
    return cancelled;
  }

  /**
   * 请求取消本次操作；幂等，且可从任意线程调用。
   *
   * <p>对 {@link #none()} 是空操作：共享令牌必须保持永久未取消状态。
   */
  public void cancel() {
    if (!cancellable) {
      return;
    }
    List<Runnable> toRun = null;
    synchronized (this) {
      if (cancelled) {
        return;
      }
      cancelled = true;
      if (listeners != null && !listeners.isEmpty()) {
        toRun = new ArrayList<>(listeners);
        listeners.clear();
      }
    }
    if (toRun != null) {
      for (Runnable listener : toRun) {
        listener.run();
      }
    }
  }

  /**
   * 注册取消回调。
   *
   * <p>若注册时已经取消，回调会立即执行一次；因此「先取消再建立请求」不会丢失取消意图。
   *
   * <p>对 {@link #none()} 不保留任何监听器：该令牌永无取消事件，保留回调只会让共享单例无界增长。
   */
  public void onCancel(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    if (!cancellable) {
      return;
    }
    boolean runImmediately = false;
    synchronized (this) {
      if (cancelled) {
        runImmediately = true;
      } else {
        listeners.add(listener);
      }
    }
    if (runImmediately) {
      listener.run();
    }
  }

  /** 已保留的监听器数量；仅供同包测试观测共享单例绝不累积监听器，且取消后清除监听器引用。 */
  synchronized int listenerCount() {
    return cancellable && !cancelled && listeners != null ? listeners.size() : 0;
  }
}
