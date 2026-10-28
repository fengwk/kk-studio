package fun.fengwk.kkstudio.ide.browser;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterProperties;

import java.net.http.HttpClient;
import java.util.UUID;

/** 浏览器 IDE 使用登记的本地状态与跨事件节点查询客户端装配。 */
@Configuration(proxyBeanMethods = false)
public class IdeBrowserUseConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public IdeBrowserUseRegistry ideBrowserUseRegistry() {
    return new IdeBrowserUseRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  public IdeBrowserUseClient ideBrowserUseClient(
      @Qualifier("clusterInternalHttpClient") HttpClient httpClient,
      ObjectMapper objectMapper,
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry nodeRegistry,
      IdeBrowserUseRegistry registry,
      @Qualifier("nodeInstanceId") UUID nodeInstanceId,
      ClusterProperties clusterProperties) {
    return new IdeBrowserUseClient(
        httpClient,
        objectMapper,
        authentication,
        nodeRegistry,
        registry,
        nodeInstanceId,
        IdeBrowserUseClient.DEFAULT_REQUEST_TIMEOUT,
        clusterProperties.getMaxMetadataResponseBytes());
  }
}
