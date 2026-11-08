package fun.fengwk.kkstudio.platform.environment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.environment.update.PlatformBuildInfo;

import java.time.Clock;
import java.util.UUID;

/** 由 Platform 组件扫描装配的 Environment 时钟、节点身份与 Daemon 发布版本。 */
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

  /** 安装脚本固定到运行中 Platform 的构建版本；显式配置只用于打包外的定向测试，生产保持空值并读取 JAR manifest。 */
  @Bean(name = "daemonReleaseVersion")
  @ConditionalOnMissingBean(name = "daemonReleaseVersion")
  public String daemonReleaseVersion(
      @Value("${kk-studio.daemon.release-version:}") String configuredVersion) {
    return configuredVersion == null || configuredVersion.isBlank()
        ? PlatformBuildInfo.version()
        : configuredVersion.strip();
  }
}
