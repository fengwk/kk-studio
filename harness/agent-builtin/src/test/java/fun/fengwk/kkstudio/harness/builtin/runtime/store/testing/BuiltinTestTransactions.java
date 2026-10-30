package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;

/**
 * 测试 seed 取得 Builtin 私有视图的入口。
 *
 * <p>测试必须与生产代码走同一条 {@link BuiltinStore#in(HarnessStore.Transaction)} 边界，不能假设 Runtime 事务句柄本身实现
 * {@link BuiltinStore.Transaction}：内存实现与 PostgreSQL 实现的视图取得方式可以不同，但都只能由所属 Store 投影。
 */
public final class BuiltinTestTransactions {

  private BuiltinTestTransactions() {}

  public static BuiltinStore.Transaction builtin(HarnessStore store, HarnessStore.Transaction tx) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(tx, "tx");
    if (store instanceof BuiltinStore builtinStore) {
      return builtinStore.in(tx);
    }
    throw new IllegalArgumentException("test store does not implement BuiltinStore");
  }

  public static BuiltinAgentDriver builtinDriver(HarnessStore store, TurnResolver resolver) {
    if (store instanceof BuiltinStore builtin) {
      return new BuiltinAgentDriver(builtin, resolver);
    }
    throw new IllegalArgumentException("test runtime requires a BuiltinStore");
  }
}
