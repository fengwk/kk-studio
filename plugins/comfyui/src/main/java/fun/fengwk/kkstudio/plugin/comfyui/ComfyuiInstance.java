package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ComfyUI 插件的运行期实例：按一次配置装配一个共享客户端与每个冻结工作流各自的 adapter。
 *
 * <p>实例拥有它创建的客户端；{@link #close()} 幂等释放自身资源，绝不关闭注入的宿主资源。停用或没有工作流时两域贡献都为空，Harness 始终为空。
 */
final class ComfyuiInstance implements PluginInstance {

  private final List<CanvasFunctionAdapter> canvasAdapters;
  private final StandardComfyuiClient client;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private ComfyuiInstance(
      List<CanvasFunctionAdapter> canvasAdapters, StandardComfyuiClient client) {
    this.canvasAdapters = canvasAdapters;
    this.client = client;
  }

  static ComfyuiInstance create(
      ComfyuiSettings settings, ObjectMapper mapper, PluginContext context) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(context, "context");
    if (!settings.enabled() || settings.workflows().isEmpty()) {
      return new ComfyuiInstance(List.of(), null);
    }
    StandardComfyuiClient client =
        new StandardComfyuiClient(
            settings.baseUrl(),
            settings.authMode(),
            Duration.ofMillis(settings.connectTimeoutMillis()),
            Duration.ofMillis(settings.requestTimeoutMillis()),
            mapper,
            context.credentialAccess());
    try {
      boolean h3 =
          settings.workflows().stream()
              .anyMatch(workflow -> workflow.strategy() == ComfyuiWorkflow.Strategy.H3);
      H3MediaPreflight mediaPreflight = null;
      H3PromptRequestBuilder promptBuilder = null;
      PluginPromptTasks promptTasks = null;
      PluginPromptResources promptResources = null;
      if (h3) {
        // H3 策略只能在绑定 Prompt 任务与媒体端口的部署里装配；未绑定即确定性失败，不做静默降级。
        promptTasks =
            context
                .promptTasks()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "ComfyUI H3 workflows require the Prompt task port"));
        promptResources =
            context
                .promptResources()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "ComfyUI H3 workflows require the Prompt media port"));
        mediaPreflight = new H3MediaPreflight();
        promptBuilder = new H3PromptRequestBuilder();
      }
      List<CanvasFunctionAdapter> adapters = new ArrayList<>(settings.workflows().size());
      for (ComfyuiWorkflow workflow : settings.workflows()) {
        adapters.add(
            new ComfyuiCanvasFunctionAdapter(
                settings,
                workflow,
                client,
                mediaPreflight,
                promptBuilder,
                promptTasks,
                promptResources));
      }
      return new ComfyuiInstance(List.copyOf(adapters), client);
    } catch (RuntimeException | Error error) {
      client.close();
      throw error;
    }
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
    if (closed.compareAndSet(false, true) && client != null) {
      client.close();
    }
  }
}
