package fun.fengwk.kkstudio.plugin.media;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;

/**
 * Media 插件的运行期实例。
 *
 * <p>按一次配置冻结两域贡献：启用时提供一个 IMAGE 裁剪 adapter，停用时两域贡献都为空，Harness 始终为空。实例不创建也不持有任何资源，因此 {@link #close()}
 * 没有需要释放的对象，也不关闭注入的宿主资源。
 */
final class MediaInstance implements PluginInstance {

  private static final List<HarnessContributor> NO_CONTRIBUTORS = List.of();

  private final List<CanvasFunctionAdapter> canvasAdapters;

  MediaInstance(boolean enabled) {
    this.canvasAdapters = enabled ? List.of(new MediaFunctionAdapter()) : List.of();
  }

  @Override
  public List<HarnessContributor> harnessContributors() {
    return NO_CONTRIBUTORS;
  }

  @Override
  public List<CanvasFunctionAdapter> canvasAdapters() {
    return canvasAdapters;
  }

  @Override
  public void close() {}
}
