package fun.fengwk.kkstudio.platform.daemon.server;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单连接的回调批队列。
 *
 * <p>入站信封在协议锁内只登记回调，真正的回调在锁外按登记顺序执行，避免业务回调重入连接状态。队列有固定上限：超过上限即视为该连接的调用方已无法被及时通知，由调用方转为失败收尾。
 *
 * <p>{@link #drain} 允许重入：只有一个线程执行队列，其它线程登记后立即返回。
 */
final class DaemonCallbackQueue {

  private static final int MAX_PENDING_BATCHES = 64;

  private final Object gate = new Object();
  private final ArrayDeque<List<Runnable>> pending = new ArrayDeque<>();
  private boolean draining;

  /** 队列是否已达批数上限，调用方据此拒绝继续接收。 */
  boolean full() {
    return size() >= MAX_PENDING_BATCHES;
  }

  /** 当前待执行批数，仅用于状态判断与可观测性。 */
  int size() {
    synchronized (gate) {
      return pending.size();
    }
  }

  /** 登记一批回调；空批次不占配额。 */
  void enqueue(List<Runnable> callbacks) {
    if (callbacks.isEmpty()) {
      return;
    }
    synchronized (gate) {
      pending.addLast(callbacks);
    }
  }

  /**
   * 在锁外按登记顺序执行全部回调。
   *
   * <p>单个回调抛出的 {@link RuntimeException} 交给 {@code onFailure} 记录，不阻断同批与后续批次。
   */
  void drain(Consumer<RuntimeException> onFailure) {
    synchronized (gate) {
      if (draining) {
        return;
      }
      draining = true;
    }
    while (true) {
      List<Runnable> batch;
      synchronized (gate) {
        batch = pending.pollFirst();
        if (batch == null) {
          draining = false;
          return;
        }
      }
      for (Runnable callback : batch) {
        try {
          callback.run();
        } catch (RuntimeException error) {
          onFailure.accept(error);
        }
      }
    }
  }
}
