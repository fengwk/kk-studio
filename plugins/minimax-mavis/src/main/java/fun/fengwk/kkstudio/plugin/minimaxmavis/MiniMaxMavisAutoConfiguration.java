package fun.fengwk.kkstudio.plugin.minimaxmavis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MiniMax Mavis Plugin 的唯一装配入口，由 {@code AutoConfiguration.imports} 声明。
 *
 * <p>这是「插件是否安装」的唯一开关：本 JAR 在 classpath 上（web 的 runtime dependency 决定），就出现 Studio Plugin、Harness
 * Contributor、 后台凭据刷新与 15 个模型工具；把它从依赖里去掉并重新构建，这些 bean 与副作用全部消失，数据库中没有任何 enabled 开关或残留状态需要清理。
 *
 * <p>启动期只做静态冻结：加载 15 份工具 schema、构造 descriptor 与 contributor，不访问 Mavis、不解析凭据。凭据解析、capability catalog
 * 对齐与工具调用都发生在调用时。资源网关是可选依赖：没有绑定实现时，涉及会话资源或媒体输出的调用在发送前确定性失败，不影响其余 15 个工具的注册。
 */
@AutoConfiguration
public class MiniMaxMavisAutoConfiguration {

  /** 每个 Plugin 一个可取消的调用执行器；生成类调用最长 600 秒，因此线程数按并发调用而不是 CPU 规模确定。 */
  private static final int TOOL_CONCURRENCY = 8;

  @Bean
  @ConditionalOnMissingBean
  public MavisHttpTransport mavisHttpTransport() {
    return new JdkMavisHttpTransport();
  }

  @Bean
  @ConditionalOnMissingBean
  public MavisClient mavisClient(MavisHttpTransport mavisHttpTransport) {
    return new MavisClient(mavisHttpTransport);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisCapabilityCache mavisCapabilityCache(MavisClient mavisClient) {
    return new MiniMaxMavisCapabilityCache(mavisClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisResourceAccess mavisResourceAccess(
      ObjectProvider<PluginResourceGateway> resourceGatewayProvider) {
    return new MiniMaxMavisResourceAccess(resourceGatewayProvider.getIfAvailable());
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisAuthHandler mavisAuthHandler(MavisClient mavisClient) {
    return new MiniMaxMavisAuthHandler(mavisClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisCredentialRefresher mavisCredentialRefresher(MavisClient mavisClient) {
    return new MiniMaxMavisCredentialRefresher(mavisClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public StudioPlugin mavisStudioPlugin(
      MiniMaxMavisAuthHandler mavisAuthHandler,
      MiniMaxMavisCredentialRefresher mavisCredentialRefresher) {
    return new MiniMaxMavisPlugin(mavisAuthHandler, mavisCredentialRefresher);
  }

  @Bean(name = "mavisToolExecutor", destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "mavisToolExecutor")
  public ExecutorService mavisToolExecutor() {
    return new ThreadPoolExecutor(
        TOOL_CONCURRENCY,
        TOOL_CONCURRENCY,
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        Thread.ofVirtual().name("minimax-mavis-tool-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxMavisHarnessContributor mavisHarnessContributor(
      PluginCredentialStore pluginCredentialStore,
      MavisClient mavisClient,
      MiniMaxMavisCapabilityCache mavisCapabilityCache,
      MiniMaxMavisResourceAccess mavisResourceAccess,
      @Qualifier("mavisToolExecutor") ExecutorService mavisToolExecutor) {
    return MiniMaxMavisHarnessContributor.create(
        new MiniMaxMavisHarnessContributor.ToolDependencies(
            pluginCredentialStore,
            mavisClient,
            mavisCapabilityCache,
            mavisResourceAccess,
            mavisToolExecutor));
  }
}
