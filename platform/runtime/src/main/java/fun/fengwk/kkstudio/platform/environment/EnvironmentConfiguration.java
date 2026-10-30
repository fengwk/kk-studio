package fun.fengwk.kkstudio.platform.environment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

/** 由 Platform 组件扫描装配的 Environment 时钟与节点身份。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Clock environmentClock() {
    return Clock.systemUTC();
  }

  @Bean(name = "nodeInstanceId")
  @ConditionalOnMissingBean(name = "nodeInstanceId")
  public UUID nodeInstanceId() {
    return UUID.randomUUID();
  }
}
