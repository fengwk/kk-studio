package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.util.List;
import java.util.Optional;

/**
 * {@link StudioPlugin#configure(PluginConfig, PluginContext)} 产出的、由单个 Plugin 拥有的运行期实例。
 *
 * <p>两个域的贡献列表都已冻结且不返回 null，任一域都可以为空。实例是它所持资源的唯一所有者：{@link #close()} 只释放实例自己创建的资源，绝不关闭注入的 宿主资源（共享
 * client、transport、资源网关等）。
 */
public interface PluginInstance extends AutoCloseable {

  /** 本实例贡献的 Harness Contributor，冻结且非 null（可为空列表）。 */
  List<HarnessContributor> harnessContributors();

  /** 本实例贡献的 Canvas Function Adapter，冻结且非 null（可为空列表）。 */
  List<CanvasFunctionAdapter> canvasAdapters();

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
}
