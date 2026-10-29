package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

/**
 * 测试 seed 的事务类型收窄：内存 store 的 transaction 同时实现 Builtin typed view。
 *
 * <p>生产代码不得使用该 helper；它只让既有模型 seed 测试继续显式通过 {@link BuiltinStore.Transaction} 访问私有 CRUD。
 */
public final class BuiltinTestTransactions {

  private BuiltinTestTransactions() {}

  public static BuiltinStore.Transaction builtin(HarnessStore.Transaction tx) {
    if (tx instanceof BuiltinStore.Transaction builtin) {
      return builtin;
    }
    throw new IllegalArgumentException(
        "test transaction does not implement BuiltinStore.Transaction");
  }

  public static BuiltinAgentDriver builtinDriver(HarnessStore store, TurnResolver resolver) {
    if (store instanceof BuiltinStore builtin) {
      return new BuiltinAgentDriver(builtin, resolver);
    }
    throw new IllegalArgumentException("test runtime requires a BuiltinStore");
  }
}
