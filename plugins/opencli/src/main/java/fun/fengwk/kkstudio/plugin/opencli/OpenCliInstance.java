package fun.fengwk.kkstudio.plugin.opencli;

import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCLI 插件的运行期实例。
 *
 * <p>按一次配置装配 Client、GPT Image 2 与 Seedance 函数适配器；Harness Contributor 为空。 实例拥有其创建的 Client 资源，{@link
 * #close()} 幂等释放自身资源，不关闭宿主共享资源。
 */
final class OpenCliInstance implements PluginInstance {

  private final List<CanvasFunctionAdapter> canvasAdapters;
  private final OpenCliHubClient client;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private OpenCliInstance(List<CanvasFunctionAdapter> canvasAdapters, OpenCliHubClient client) {
    this.canvasAdapters = List.copyOf(canvasAdapters);
    this.client = client;
  }

  static OpenCliInstance create(
      OpenCliSettings settings, ObjectMapper mapper, PluginContext context) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(context, "context");

    OpenCliHubClient client = new OpenCliHubClient(settings, mapper, context.credentialAccess());
    CanvasFunctionAdapter gptImage2Adapter = new GptImage2CanvasFunctionAdapter(settings, client);
    CanvasFunctionAdapter seedanceAdapter = new SeedanceCanvasFunctionAdapter(settings, client);
    return new OpenCliInstance(List.of(gptImage2Adapter, seedanceAdapter), client);
  }

  @Override
  public List<HarnessContributor> harnessContributors() {
    return List.of();
  }

  @Override
  public List<CanvasFunctionAdapter> canvasAdapters() {
    return canvasAdapters;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      client.close();
    }
  }
}
