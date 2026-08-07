package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Environment 路由绑定的 canonical durable 逻辑名称。
 *
 * <p>{@code EnvironmentName} 是 BranchSettings 与 daemon envelope 中唯一持久化的逻辑路由身份：小写、有界且无歧义（不含 空白或
 * {@code '/'}，不存在大小写折叠问题），同一名称在同一时刻至多由一个 live daemon 持有。展示名不再作为独立元数据存在—— 名称本身就是展示与路由共用的规范身份。
 */
public record EnvironmentName(String value) {

  /** 名称长度上限（UTF-8 字节数等于字符数，因为只允许 ASCII）。 */
  public static final int MAX_LENGTH = 64;

  /** 规范路由名称：小写字母/数字段，以单个 {@code '-'} 分隔；不以 {@code '-'} 开头或结尾。 */
  private static final Pattern CANONICAL = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  public EnvironmentName {
    Objects.requireNonNull(value, "value");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("environmentName must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "environmentName must be at most " + MAX_LENGTH + " characters: " + value);
    }
    if (!CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "environmentName must be a bounded lowercase route name "
              + "(lowercase letters and digits joined by single '-', no whitespace, no '/', "
              + "no leading/trailing '-'): "
              + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
