package fun.fengwk.kkstudio.harness.runtime.store.testing;

import java.util.UUID;

/** 测试用确定性 UUID 工厂：{@code id(n)} 生成 {@code 00000000-0000-0000-0000-00000000000n}。 */
public final class TestIds {

  private TestIds() {}

  /** 把测试中的稳定序号映射为确定性 UUID，等价于 InMemoryHarnessStore 从 0 起连续 nextId 的第 n 个分配。 */
  public static UUID id(long n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must not be negative");
    }
    return new UUID(0L, n);
  }
}
