package fun.fengwk.kkstudio.ide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;

/** 浏览器使用登记查询的生产装配：控制服务只依赖窄 gateway，事件节点细节留给 browser 包。 */
@Configuration(proxyBeanMethods = false)
public class IdeBrowserUseGatewayConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public IdeBrowserUseGateway ideBrowserUseGateway(IdeBrowserUseClient client) {
    return new IdeBrowserUseClientGateway(client);
  }
}
