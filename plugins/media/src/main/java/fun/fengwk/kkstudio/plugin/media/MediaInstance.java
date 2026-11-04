package fun.fengwk.kkstudio.plugin.media;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;

/**
 * Media 插件的运行期实例。
 *
 * <p>按一次配置冻结贡献：启用时提供一个 IMAGE 裁剪 adapter，停用时不贡献任何能力；该插件不贡献 Harness Contributor。实例不创建也不持有任何资源，因此
 * {@link #close()} 没有需要释放的对象，也不关闭注入的宿主资源。
 */
final class MediaInstance implements PluginInstance {

  private final List<CanvasFunctionAdapter> functionAdapters;

  MediaInstance(boolean enabled) {
    this.functionAdapters = enabled ? List.of(new MediaFunctionAdapter()) : List.of();
  }

  @Override
  public List<HarnessContributor> harnessContributors() {
    return List.of();
  }

  @Override
  public <T> List<T> contributions(Class<T> type) {
    return PluginInstance.contributionsOf(type, functionAdapters);
  }

  @Override
  public void close() {}
}
