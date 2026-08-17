package fun.fengwk.kkstudio.core.ai.runtime;

/**
 * 观察循环的信号门：按类别计数并支持带超时的等待。
 *
 * <p>计数而非布尔位，保证「先 signal 后 wait」的 wake 不丢失；多个信号合并为一次唤醒，由调用方在唤醒后按类别计数差决定重读哪类事实。{@link #awaitChange}
 * 只做限时睡眠，不读取任何 durable 事实；唤醒后是否重读 snapshot 完全由调用方依据计数差决定。
 *
 * <p>core 内部工具类，不对外承诺 API 稳定性。
 */
public final class ChangeGate {

  private long revision;
  private long descendants;
  private long cancel;

  /** durable revision / resync 唤醒。 */
  public synchronized void revision() {
    revision++;
    notifyAll();
  }

  /** registry descendant status 变化唤醒。 */
  public synchronized void descendants() {
    descendants++;
    notifyAll();
  }

  /** 取消唤醒（主动打断等待，使观察循环尽快检查取消标记）。 */
  public synchronized void cancel() {
    cancel++;
    notifyAll();
  }

  public synchronized State snapshot() {
    return new State(revision, descendants, cancel);
  }

  /**
   * 等待任意类别计数相对 {@code since} 前进；返回 false 表示 timeoutNanos 内无信号（调用方按自家 deadline 结束）。
   *
   * <p>timeoutNanos 非正时只做一次立即检查（不等待）；计时用相对 elapsed（wait 实际流逝累减），无绝对 deadline，避免 nanoTime + timeout
   * 溢出；spurious wake 后按剩余时间继续等待，不 busy-loop。
   */
  public synchronized boolean awaitChange(State since, long timeoutNanos)
      throws InterruptedException {
    if (timeoutNanos <= 0L) {
      return changed(since);
    }
    long remaining = timeoutNanos;
    while (!changed(since)) {
      if (remaining <= 0) {
        return false;
      }
      long started = System.nanoTime();
      // 毫秒数钳制到 Long.MAX/1e6，保证 Object.wait 内部 ns 换算不溢出。
      long millis = Math.min(remaining / 1_000_000, Long.MAX_VALUE / 1_000_000);
      wait(millis, (int) (remaining % 1_000_000));
      remaining -= System.nanoTime() - started;
    }
    return true;
  }

  private boolean changed(State since) {
    return revision != since.revision || descendants != since.descendants || cancel != since.cancel;
  }

  public record State(long revision, long descendants, long cancel) {}
}
