package fun.fengwk.kkstudio.platform.environment.cluster;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * 与 IDE 路由无关的公共集群装配。
 *
 * <p>{@code cluster_node} 租约既服务于 IDE 单跳路由，也服务于 realtime 跨节点发布判定；该配置不依赖 IDE 的 transport /
 * service，因此在未启用 IDE 的运行形态中仍能提供节点存在快照和按 Thread 的订阅租约服务。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterPresenceConfiguration {

  /** 实时跨节点发布所需的轻量节点存在快照；与 IDE 路由共享同一 {@code cluster_node} 租约事实。 */
  @Bean
  @ConditionalOnMissingBean
  public ClusterNodePresence clusterNodePresence(
      JdbcClient jdbcClient, @Qualifier("nodeInstanceId") UUID nodeInstanceId) {
    return new ClusterNodePresence(jdbcClient, nodeInstanceId);
  }

  /** 按 Thread 的 realtime 订阅租约服务；与 {@link ClusterNodeLifecycle} 共用 15 秒心跳 / 45 秒租约节奏，不创建独立调度器。 */
  @Bean
  @ConditionalOnMissingBean
  public RealtimeSubscriptionService realtimeSubscriptionService(
      JdbcClient jdbcClient,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      ClusterProperties clusterProperties) {
    return new RealtimeSubscriptionService(jdbcClient, nodeInstanceId, clusterProperties);
  }
}
