package fun.fengwk.kkstudio.harness.daemon;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * 关停及构造失败的收尾聚合。首个 Error 保持主因，后到 Error 优先于普通异常，其余失败以 suppressed 保留并避免 self-suppression。
 *
 * <p>日志仅输出固定阶段与异常类名，不附原始异常或正文。
 */
final class DaemonShutdownFailures {

  private static final Logger LOG = System.getLogger(DaemonShutdownFailures.class.getName());

  /** 关停失败时对外发布的固定安全原因：不含阶段、异常类型、message 或路径。 */
  static final String SHUTDOWN_FAILURE_REASON = "daemon shutdown failed";

  /** 聚合结果；首个失败决定其初始类型，后续按优先级合入。 */
  private Throwable primary;

  /** 记录一次收尾失败：先输出安全 WARNING，再按统一优先级合入聚合。 */
  void record(String phase, Throwable failure) {
    LOG.log(Level.WARNING, "daemon " + phase + " failed: " + failure.getClass().getSimpleName());
    merge(failure);
  }

  /** 是否已记录关停失败。 */
  boolean hasFailure() {
    return primary != null;
  }

  /**
   * 首个 Error 主因；没有 Error 时返回 {@code null}。
   *
   * <p>返回的对象已携带其余全部失败作为 {@code suppressed}，可直接抛出以保持原 Error 的对象身份。
   */
  Error error() {
    return primary instanceof Error error ? error : null;
  }

  /**
   * 非 Error 主因的聚合结果：{@link RuntimeException} 主因保持对象身份，checked 主因按固定安全消息包装。
   *
   * @throws IllegalStateException 尚未记录任何失败时
   */
  RuntimeException exception() {
    if (primary == null) {
      throw new IllegalStateException("daemon shutdown has no recorded failure");
    }
    if (primary instanceof RuntimeException runtime) {
      return runtime;
    }
    return new IllegalStateException(SHUTDOWN_FAILURE_REASON, primary);
  }

  private void merge(Throwable failure) {
    if (primary == null) {
      primary = failure;
      return;
    }
    if (primary == failure) {
      return;
    }
    if (!(failure instanceof Error) || primary instanceof Error) {
      primary.addSuppressed(failure);
      return;
    }
    // 后到的 Error 提升到首个普通异常之上，原主因不丢失。
    failure.addSuppressed(primary);
    primary = failure;
  }
}
