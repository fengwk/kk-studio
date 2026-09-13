package fun.fengwk.kkstudio.harness.mcp;

/**
 * 已构建 client 的所有权交接：把「构建线程可能仍在运行」的 client 恰好交给一方负责关闭。
 *
 * <p>初始化存在必然的竞态窗口——构建可能恰好在调用方判定「已放弃」之后完成。若只在放弃时关闭传输，那个构建结果就没有任何一方负责关闭。
 *
 * <p>本类把该窗口收敛为一个受监视器保护的二值状态：
 *
 * <ul>
 *   <li>{@link #offer} 时若已知放弃，立即关闭，绝不把 client 交给一个已经失败发起的调用；
 *   <li>{@link #abandon} 时若已收到交付，也立即关闭。
 * </ul>
 *
 * <p>两个分支互斥，因此任何交错下都恰好有一个分支真正关闭，不存在既不交付也不关闭的中间态。
 *
 * @param <T> 被交接的资源类型
 */
final class ClientHandoff<T extends AutoCloseable> {

  private T pending;
  private boolean abandoned;

  /** 构建方交付：调用方尚未放弃则暂存待领，否则当场关闭。 */
  synchronized void offer(T client) {
    if (abandoned) {
      closeQuietly(client);
      return;
    }
    pending = client;
  }

  /** 调用方领取所有权并接管后续关闭责任；仅在成功返回后调用。 */
  synchronized void take() {
    pending = null;
  }

  /** 调用方宣告放弃（超时/中断/失败）：已交付的资源由本方法关闭，未交付的由构建方的 {@link #offer} 负责。 */
  synchronized void abandon() {
    abandoned = true;
    T client = pending;
    pending = null;
    if (client != null) {
      closeQuietly(client);
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
      // 清理失败不覆盖原始异常。
    }
  }
}
