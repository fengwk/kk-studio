package fun.fengwk.kkstudio.plugin.minimaxmavis;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiniMax Mavis 的运行期实例：启用时提供完整的 15 个模型工具，停用时两域都没有贡献。
 *
 * <p>实例是它自己创建资源的唯一所有者：工具执行器、capability 缓存与资源访问都在 {@code configure} 中装配，{@link #close()}
 * 幂等地只关闭这个执行器， 绝不关闭 Spring 注入的 client、transport 或资源网关。两域贡献列表在构造时冻结，不返回 null。
 */
@Slf4j
final class MiniMaxMavisInstance implements PluginInstance {

  /** 每个实例一个可取消的调用执行器；生成类调用最长 600 秒，因此线程数按并发调用而不是 CPU 规模确定。 */
  private static final int TOOL_CONCURRENCY = 8;

  private final List<HarnessContributor> harnessContributors;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private MiniMaxMavisInstance(
      List<HarnessContributor> harnessContributors, ExecutorService executor) {
    this.harnessContributors = List.copyOf(harnessContributors);
    this.executor = executor;
  }

  /** 停用实例：没有任何新贡献，也没有需要释放的实例资源。 */
  static MiniMaxMavisInstance disabled() {
    return new MiniMaxMavisInstance(List.of(), null);
  }

  /** 启用实例：装配完整 15 个工具；装配中途失败时立即释放已经创建的执行器，绝不泄漏给宿主。 */
  static MiniMaxMavisInstance enabled(MavisClient client, PluginContext context) {
    ExecutorService executor = newToolExecutor();
    try {
      MiniMaxMavisHarnessContributor contributor =
          MiniMaxMavisHarnessContributor.create(
              new MiniMaxMavisHarnessContributor.ToolDependencies(
                  context.credentialAccess(),
                  client,
                  new MiniMaxMavisCapabilityCache(client),
                  new MiniMaxMavisResourceAccess(context.resourceGateway().orElse(null)),
                  executor));
      return new MiniMaxMavisInstance(List.of(contributor), executor);
    } catch (RuntimeException | Error error) {
      releaseExecutor(executor);
      throw error;
    }
  }

  /** 释放实例执行器；次要关闭失败只记录类型，不替代原始构建失败。 */
  private static void releaseExecutor(ExecutorService executor) {
    try {
      executor.shutdownNow();
    } catch (RuntimeException error) {
      log.warn("MiniMax Mavis tool executor shutdown failed: {}", error.getClass().getName());
    }
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
    if (closed.compareAndSet(false, true) && executor != null) {
      releaseExecutor(executor);
    }
  }

  /** 执行器策略：固定并发、virtual thread、满负载即拒绝（fail fast），绝不排队重放额度型调用。 */
  private static ExecutorService newToolExecutor() {
    return new ThreadPoolExecutor(
        TOOL_CONCURRENCY,
        TOOL_CONCURRENCY,
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        Thread.ofVirtual().name("minimax-mavis-tool-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
