package fun.fengwk.kkstudio.harness.model.provider;

import java.time.Duration;
import java.util.Objects;

/** Provider 定义的单次模型调用总时长与无流活动超时策略。 */
public record ModelCallTimeoutPolicy(Duration modelCallTimeout, Duration modelCallIdleTimeout) {

  /** 未显式配置 Provider 时的产品默认值。 */
  public static final ModelCallTimeoutPolicy DEFAULT =
      new ModelCallTimeoutPolicy(Duration.ofMinutes(30), Duration.ofSeconds(120));

  public ModelCallTimeoutPolicy {
    modelCallTimeout = positive(modelCallTimeout, "modelCallTimeout");
    modelCallIdleTimeout = positive(modelCallIdleTimeout, "modelCallIdleTimeout");
  }

  private static Duration positive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
