package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;
import fun.fengwk.kkstudio.harness.environment.server.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.ide.IdeBootstrapTicketService;

/**
 * IDE 独立 Origin 数据面的 Web 装配：绑定 {@link IdeGatewayProperties}，提供进程内 stream ticket 服务、反向连接配对注册表，
 * 以及代际关闭时的清理钩子。
 *
 * <p>无 cluster 配置时即单节点直连：本节点的 {@link IdeStreamTicketService}/{@link IdeStreamPairingRegistry}
 * 只服务本节点 建立的流。跨节点路由由 cluster 切片在 owner 定位层处理，本装配不做任何跨节点假设。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdeGatewayProperties.class)
public class IdeGatewayConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public IdeStreamTicketService ideStreamTicketService() {
    return new InMemoryIdeStreamTicketService();
  }

  @Bean
  public IdeStreamPairingRegistry<IdeStreamPeer> ideStreamPairingRegistry() {
    return new IdeStreamPairingRegistry<>();
  }

  /**
   * 代际关闭清理：关闭该 {@code (environment, lease)} 的全部反向流配对，并撤销 stream/bootstrap 票据与浏览器会话。
   *
   * <p>任一子步骤异常都不影响其它清理与会话状态推进，因此整体隔离。
   */
  @Bean
  public IdeLeaseCloseListener ideLeaseCloseListener(
      IdeStreamPairingRegistry<IdeStreamPeer> pairings,
      IdeStreamTicketService streamTickets,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserSessionRegistry sessions) {
    return (environmentId, leaseToken) -> {
      closeQuietly(() -> pairings.closeLease(environmentId, leaseToken));
      closeQuietly(() -> streamTickets.revoke(environmentId, leaseToken));
      closeQuietly(() -> bootstrapTickets.revoke(environmentId, leaseToken));
      closeQuietly(() -> sessions.closeGeneration(environmentId, leaseToken));
    };
  }

  private static void closeQuietly(Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException ignored) {
      // 清理失败不得影响会话状态推进或其它清理步骤。
    }
  }
}
