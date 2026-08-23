package fun.fengwk.kkstudio.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.platform.ai.environment.gateway.EnvironmentReadyListener;

/**
 * Platform 测试上下文的 Harness 装配基座。
 *
 * <p>生产 {@link EnvironmentReadyListener} 由 web 组合根（dispatcher wake）提供；platform 不再是组合根后， 测试上下文用
 * no-op 桥接满足 {@code EnvironmentDaemonGateway} 的构造依赖。internal Thread change source 同样由 web
 * 组合根提供（PostgreSQL LISTEN 适配）；本测试上下文提供不产生信号的占位 bean 满足构造依赖——platform 测试从不真正等待任务完成。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformHarnessTestConfiguration {

  @Bean
  public EnvironmentReadyListener environmentReadyListener() {
    return environmentName -> {};
  }

  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource() {
    return (threadId, onChange) -> () -> {};
  }
}
