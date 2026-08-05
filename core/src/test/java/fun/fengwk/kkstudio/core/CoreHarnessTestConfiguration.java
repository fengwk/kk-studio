package fun.fengwk.kkstudio.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;

/**
 * Core 测试上下文的 Harness 装配基座。
 *
 * <p>生产 {@link EnvironmentReadyListener} 由 web 组合根（dispatcher wake）提供；core 不再是组合根后， 测试上下文用 no-op
 * 桥接满足 {@code EnvironmentDaemonGateway} 的构造依赖。
 */
@Configuration(proxyBeanMethods = false)
public class CoreHarnessTestConfiguration {

  @Bean
  public EnvironmentReadyListener environmentReadyListener() {
    return environmentId -> {};
  }
}
