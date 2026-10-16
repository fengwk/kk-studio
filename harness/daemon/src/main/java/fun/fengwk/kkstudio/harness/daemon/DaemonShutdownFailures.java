package fun.fengwk.kkstudio.harness.daemon;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * 关停与构造失败收尾的统一聚合：每次失败按固定阶段输出安全日志，并按同一优先级合入唯一的聚合结果。
 *
 * <p>优先级规则：保留首个 {@link Error} 的对象身份；后到的 {@link Error} 提升到之前的普通异常之上；其余失败作为 {@code suppressed}
 * 附加。同一个失败对象只记录一次，避免 self-suppression。调用方据此在全部清理完成后抛出聚合结果： Error 主因保持身份向上传播，普通异常主因携带全部其它失败。
 *
 * <p>日志只输出固定阶段与异常类名，绝不输出 message、Throwable、堆栈、路径或载荷：堆栈可能携带原始取值。
 */
final class DaemonShutdownFailures {

  private static final Logger LOG = System.getLogger(DaemonShutdownFailures.class.getName());

  /** 关停失败时对外发布的固定安全原因：不含阶段、异常类型、message 或路径。 */
  static final String SHUTDOWN_FAILURE_REASON = "daemon shutdown failed";

  /** checked 失败不能直接抛出时的固定安全包装消息。 */
  private static final String UNCHECKED_MESSAGE = "daemon shutdown failed";

  /** 聚合结果；首个失败决定其初始类型，后续按优先级合入。 */
  private Throwable primary;

  /** 记录一次收尾失败：先输出安全 WARNING，再按统一优先级合入聚合。 */
  void record(String phase, Throwable failure) {
    if (failure == null) {
      return;
    }
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
    return new IllegalStateException(UNCHECKED_MESSAGE, primary);
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
