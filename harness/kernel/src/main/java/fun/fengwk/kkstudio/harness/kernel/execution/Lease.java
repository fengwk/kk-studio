package fun.fengwk.kkstudio.harness.kernel.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * 对 Thread 或 Invocation 的短暂所有权凭证。
 *
 * <p>Lease 语义独立于任何具体 Thread/Invocation 类型，由适配器在事务中签发并写入对应行。
 */
public record Lease(String token, Instant until) {

  public Lease {
    Objects.requireNonNull(token, "token");
    if (token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    Objects.requireNonNull(until, "until");
  }

  /**
   * 判断给定观察时刻是否仍处于 Lease 有效期内。纯函数，不读取系统时钟。
   *
   * @param observedAt 观察时刻，必须非 null
   * @return 当 observedAt 严格早于 until 时返回 true
   * @throws NullPointerException 当 observedAt 为 null 时
   */
  public boolean isActiveAt(Instant observedAt) {
    Objects.requireNonNull(observedAt, "observedAt");
    return observedAt.isBefore(until);
  }
}
