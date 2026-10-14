package fun.fengwk.kkstudio.platform.environment.update;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.time.Duration;

/**
 * 受管更新装配：官方发布解析器是唯一的外部事实源，默认基于 JDK HttpClient 的只读探测实现。
 *
 * <p>探测客户端在构造时隐式继承 JVM 默认代理，因此依赖 {@code systemProxySelector} 先完成安装。解析器刻意不提供任意版本/任意 URL
 * 入口，替换实现只能改变「如何读取官方校验文件」，不能改变目标版本来源。
 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentUpdateConfiguration {

  /** 官方发布只读探测的超时：解析失败即视为发布不可用，绝不让管理请求无限等待。 */
  private static final Duration RELEASE_FEED_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  @ConditionalOnMissingBean(DaemonReleaseProvider.class)
  @DependsOn("systemProxySelector")
  public DaemonReleaseProvider daemonReleaseProvider() {
    return new OfficialDaemonReleaseProvider(
        new OfficialDaemonReleaseProvider.HttpOfficialReleaseFeed(RELEASE_FEED_TIMEOUT));
  }
}
