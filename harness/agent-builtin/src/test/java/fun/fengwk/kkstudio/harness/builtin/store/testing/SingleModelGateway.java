package fun.fengwk.kkstudio.harness.builtin.store.testing;

import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.function.Function;

/** 测试专用单模型网关；模型组不是此类测试的有效输入。 */
public abstract class SingleModelGateway implements ModelGateway {
  public static SingleModelGateway of(Function<Execution, Admission> admit) {
    return new SingleModelGateway() {
      @Override
      public Admission admit(Execution execution) {
        return admit.apply(execution);
      }
    };
  }

  @Override
  public final boolean isGroupMemberCooling(
      HarnessStore.Transaction tx, String group, long version, String model, String variant) {
    throw new UnsupportedOperationException("single-model test gateway");
  }

  @Override
  public final void freezeGroupMember(
      HarnessStore.Transaction tx,
      String group,
      long version,
      String model,
      String variant,
      Instant until) {
    throw new UnsupportedOperationException("single-model test gateway");
  }
}
