package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link Plugin#configure(PluginConfig, PluginContext)} 产出的、由单个 Plugin 拥有的运行期实例。
 *
 * <p>实例对外只有两类能力：Harness 域贡献由 {@link #harnessContributors()} 直接声明，平台据此构建 {@code
 * HarnessCatalog}；其它产品域的贡献按类型读取，见 {@link #contributions(Class)}。两份列表都在 configure 中冻结且不返回
 * null，未贡献时为空列表。
 *
 * <p>实例是它所持资源的唯一所有者：{@link #close()} 只释放实例自己创建的资源，绝不关闭注入的 宿主资源（共享 client、transport、资源网关等）。
 */
public interface PluginInstance extends AutoCloseable {

  /** 本实例贡献的 Harness Contributor，冻结且非 null（可为空列表）。 */
  List<HarnessContributor> harnessContributors();

  /**
   * 本实例贡献的指定产品域能力，冻结且非 null（未贡献该类型时为空列表）。
   *
   * <p>产品域能力类型属于对应产品模块（当前只有 Canvas 的 Function adapter），平台不认识它们：读取方按自己拥有的类型取用，读到的永远是 configure
   * 期冻结的那一份，实现不得据此推导、按需创建或重新枚举贡献。
   *
   * @param type 产品域能力类型，不能为 null
   */
  <T> List<T> contributions(Class<T> type);

  /**
   * 本实例可选声明的显式只读连接检查能力；未声明时为 {@link Optional#empty()}。
   *
   * <p>宿主只在一次显式管理面检查请求内调用它，绝不因此重新 configure 或另建实例。
   */
  default Optional<PluginConnectionCheck> connectionCheck() {
    return Optional.empty();
  }

  /** 释放实例拥有的资源；不抛 checked exception。 */
  @Override
  void close();

  /**
   * 从一次 configure 冻结的产品域贡献集合中取出指定类型的贡献，保持冻结顺序。
   *
   * <p>实例只需要持有一份不可变贡献集合，无需为每类产品能力维护并列字段；未命中任何贡献时返回空列表。
   *
   * @param type 产品域能力类型，不能为 null
   * @param frozen 冻结的贡献集合，不能为 null
   */
  static <T> List<T> contributionsOf(Class<T> type, List<?> frozen) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(frozen, "frozen");
    List<T> matched = new ArrayList<>(frozen.size());
    for (Object contribution : frozen) {
      if (type.isInstance(contribution)) {
        matched.add(type.cast(contribution));
      }
    }
    return List.copyOf(matched);
  }
}
