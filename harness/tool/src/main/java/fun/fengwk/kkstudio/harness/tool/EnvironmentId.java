package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;
import java.util.UUID;

/**
 * Environment 路由绑定的 canonical durable 身份。
 *
 * <p>Environment 展示名可复用并可重新绑定到不同根目录，因此不能用于标识历史。只有 canonical 小写 UUID 文本 才是 durable 路由身份；展示名绝不进入携带该值的
 * durable 协议类型。
 */
public record EnvironmentId(String value) {

  public EnvironmentId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("environmentId must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("environmentId must not contain surrounding whitespace");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "environmentId must be a canonical lowercase UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(
          "environmentId must be a canonical lowercase UUID: " + value);
    }
    if (parsed.getMostSignificantBits() == 0 && parsed.getLeastSignificantBits() == 0) {
      throw new IllegalArgumentException("environmentId must not be nil");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
