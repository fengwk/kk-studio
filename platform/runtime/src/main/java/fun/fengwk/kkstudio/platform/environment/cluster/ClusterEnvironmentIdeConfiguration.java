package fun.fengwk.kkstudio.platform.environment.cluster;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * 跨节点 IDE 路由的 Platform 装配：注册路由存储、内部认证、内部客户端、组合服务与本节点生命周期。
 *
 * <p>{@link ClusterEnvironmentIdeService} 以 {@link Primary} 暴露为唯一的 {@link EnvironmentIdeService}，本地
 * owner 直接委托 {@code localEnvironmentIdeService}（{@link EnvironmentIdeServiceImpl}），因此既有 controller
 * 无需改动即按 owner 单跳路由。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterEnvironmentIdeConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public JdbcClusterNodeRegistry clusterNodeRegistry(
      JdbcClient jdbcClient,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      ClusterProperties clusterProperties) {
    return new JdbcClusterNodeRegistry(jdbcClient, nodeInstanceId, clusterProperties);
  }

  /** 跨节点内部 HTTP 唯一共享客户端；所有调用都必须自行施加有界响应与总超时。 */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(name = "clusterInternalHttpClient")
  public HttpClient clusterInternalHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  public ClusterInternalAuthentication clusterInternalAuthentication(
      ClusterProperties clusterProperties,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      Clock clock) {
    return new HmacClusterInternalAuthentication(clusterProperties, nodeInstanceId, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public ClusterInternalClient clusterInternalClient(
      @Qualifier("clusterInternalHttpClient") HttpClient httpClient,
      ClusterInternalAuthentication clusterInternalAuthentication,
      ClusterProperties clusterProperties,
      ObjectMapper objectMapper) {
    return new ClusterInternalClient(
        httpClient,
        objectMapper,
        clusterInternalAuthentication,
        ClusterInternalClient.DEFAULT_REQUEST_TIMEOUT,
        clusterProperties.getMaxMetadataResponseBytes());
  }

  /** IDE 组合服务只在本地 delegate 存在时装配；通用认证/注册/HTTP 客户端不依赖 IDE 上下文。 */
  @Bean
  @Primary
  @ConditionalOnBean(name = "localEnvironmentIdeService")
  public ClusterEnvironmentIdeService clusterEnvironmentIdeService(
      EnvironmentIdeServiceImpl localEnvironmentIdeService,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalClient clusterInternalClient) {
    return new ClusterEnvironmentIdeService(
        clusterNodeRegistry, clusterInternalClient, localEnvironmentIdeService);
  }

  @Bean
  public ClusterNodeLifecycle clusterNodeLifecycle(
      JdbcClusterNodeRegistry clusterNodeRegistry, ClusterProperties clusterProperties) {
    return new ClusterNodeLifecycle(clusterNodeRegistry, clusterProperties);
  }
}
