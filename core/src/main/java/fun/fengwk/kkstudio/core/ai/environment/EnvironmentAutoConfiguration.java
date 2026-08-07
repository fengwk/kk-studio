package fun.fengwk.kkstudio.core.ai.environment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 启用 Environment gateway 组件。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Clock environmentClock() {
    return Clock.systemUTC();
  }
}
