package fun.fengwk.kkstudio.harness.runtime.store;

import java.util.Comparator;
import java.util.UUID;

/**
 * Harness 各存储实现共享的确定性 UUID 排序。
 *
 * <p>按 16 字节大端序（mostSignificantBits 再 leastSignificantBits，均按无符号比较），与 PostgreSQL {@code uuid} 列的
 * byte-wise 排序一致；in-memory 与 PostgreSQL store 必须统一使用本排序保证锁顺序与候选选取一致。
 */
public final class UuidOrder {

  public static final Comparator<UUID> COMPARATOR = UuidOrder::compare;

  private UuidOrder() {}

  public static int compare(UUID left, UUID right) {
    int mostSignificant =
        Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    if (mostSignificant != 0) {
      return mostSignificant;
    }
    return Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }
}
