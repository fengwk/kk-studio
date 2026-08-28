package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 受信任的 build-time 贡献者单元：声明自身 descriptor 并通过 {@link HarnessRegistrar} 贡献能力。
 *
 * <p>Contributor 是 classpath 构建期单元，不是运行时安装物；贡献在 {@link HarnessCatalog} 冻结前完成并校验。Contributor 不得接触
 * HarnessStore / gateway / transaction / lock——它只能读取不可变 {@link BranchView} 并返回声明式 {@link
 * AppendCustomEntry}。
 */
public interface HarnessContributor {

  ContributorDescriptor descriptor();

  /** 向 registrar 贡献本 contributor 的全部能力；同一 catalog 构建中只调用一次。 */
  void contribute(HarnessRegistrar registrar);

  /** 便捷工厂：以 descriptor 与贡献回调构造 contributor。 */
  static HarnessContributor of(
      ContributorDescriptor descriptor, Consumer<HarnessRegistrar> contributor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(contributor, "contributor");
    return new HarnessContributor() {
      @Override
      public ContributorDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public void contribute(HarnessRegistrar registrar) {
        contributor.accept(registrar);
      }
    };
  }
}
