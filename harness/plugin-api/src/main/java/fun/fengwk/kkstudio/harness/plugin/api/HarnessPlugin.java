package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 受信任的 build-time 插件单元：声明自身 descriptor 并通过 {@link PluginRegistrar} 贡献能力。
 *
 * <p>插件是 classpath 构建期单元，不是运行时安装物；贡献在 {@link PluginCatalog} 冻结前完成并校验。插件不得接触 HarnessStore / gateway
 * / transaction / lock——它只能读取不可变 {@link BranchView} 并返回声明式 {@link AppendCustomEntry}。
 */
public interface HarnessPlugin {

  PluginDescriptor descriptor();

  /** 向 registrar 贡献本插件的全部能力；同一 catalog 构建中只调用一次。 */
  void contribute(PluginRegistrar registrar);

  /** 便捷工厂：以 descriptor 与贡献回调构造插件。 */
  static HarnessPlugin of(PluginDescriptor descriptor, Consumer<PluginRegistrar> contributor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(contributor, "contributor");
    return new HarnessPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public void contribute(PluginRegistrar registrar) {
        contributor.accept(registrar);
      }
    };
  }
}
