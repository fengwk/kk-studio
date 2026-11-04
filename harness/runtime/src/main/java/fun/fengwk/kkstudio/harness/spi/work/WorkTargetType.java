package fun.fengwk.kkstudio.harness.spi.work;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Work mailbox target 类型。
 *
 * <p>内核只拥有 {@link #THREAD} 与 {@link #TOOL}；具体 Driver 用 {@link #driver(String, int)} 定义自己的类型（如
 * Builtin 的 {@code MODEL}）。{@link #rank()} 提供跨类型稳定加锁顺序，{@link #environmentAffinity()} 声明该类型的持久化
 * Work 是否允许携带环境亲和性。同一 {@code value} 的实例必须使用相同的 rank 与亲和性声明；相等性只按 value 判断，便于数据库与 Driver 各自构造同一类型。
 */
public final class WorkTargetType {

  private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,15}");

  /** 由 ThreadProcessor 消费，用于规划新 Turn 或应用已完成的调用结果。 */
  public static final WorkTargetType THREAD = new WorkTargetType("THREAD", false, 0);

  /** 由 ToolProcessor 消费，用于预检、分派或恢复单个工具调用；允许环境亲和性。 */
  public static final WorkTargetType TOOL = new WorkTargetType("TOOL", true, 2);

  private final String value;
  private final boolean environmentAffinity;
  private final int rank;

  private WorkTargetType(String value, boolean environmentAffinity, int rank) {
    if (value == null || !VALUE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("work target type must match " + VALUE_PATTERN.pattern());
    }
    if (rank < 0) {
      throw new IllegalArgumentException("work target rank must not be negative");
    }
    this.value = value;
    this.environmentAffinity = environmentAffinity;
    this.rank = rank;
  }

  /** 具体 Driver 的 Work 类型；当前所有 Driver Work 都不带环境亲和性。 */
  public static WorkTargetType driver(String value, int rank) {
    return new WorkTargetType(value, false, rank);
  }

  public String value() {
    return value;
  }

  public boolean environmentAffinity() {
    return environmentAffinity;
  }

  public int rank() {
    return rank;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WorkTargetType type && value.equals(type.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
