package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * MiniMax Mavis 的运行期实例：启用时提供完整的 15 个模型工具，停用时两域都没有贡献。
 *
 * <p>实例不持有需要关闭的资源：工具在调用线程上阻塞执行，capability 缓存、资源访问与并发许可都在 {@code configure} 中装配；调用线程退出前 Runtime
 * 不会释放钉住本实例的目录租用，因此实例关闭时不会有在途调用。两域贡献列表在构造时冻结，不返回 null。
 */
final class MiniMaxMavisInstance implements PluginInstance {

  /** 每个实例的并发调用上限；生成类调用最长 600 秒，因此满负载即拒绝（fail fast），绝不排队重放额度型调用。 */
  private static final int TOOL_CONCURRENCY = 8;

  private final List<HarnessContributor> harnessContributors;

  private MiniMaxMavisInstance(List<HarnessContributor> harnessContributors) {
    this.harnessContributors = List.copyOf(harnessContributors);
  }

  /** 停用实例：没有任何新贡献。 */
  static MiniMaxMavisInstance disabled() {
    return new MiniMaxMavisInstance(List.of());
  }

  /** 启用实例：装配完整 15 个工具。 */
  static MiniMaxMavisInstance enabled(MavisClient client, PluginContext context) {
    MiniMaxMavisHarnessContributor contributor =
        MiniMaxMavisHarnessContributor.create(
            new MiniMaxMavisHarnessContributor.ToolDependencies(
                context.credentialAccess(),
                client,
                new MiniMaxMavisCapabilityCache(client),
                new MiniMaxMavisResourceAccess(context.resourceGateway().orElse(null)),
                new Semaphore(TOOL_CONCURRENCY)));
    return new MiniMaxMavisInstance(List.of(contributor));
  }

  @Override
  public List<HarnessContributor> harnessContributors() {
    return harnessContributors;
  }

  @Override
  public List<CanvasFunctionAdapter> canvasAdapters() {
    // MiniMax Mavis 只贡献模型工具，不提供 Canvas Function。
    return List.of();
  }

  @Override
  public void close() {
    // 没有实例级资源需要释放。
  }
}
