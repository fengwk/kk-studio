package fun.fengwk.kkstudio.platform.settings;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backend 唯一全局 HTTP 代理装配：装配期安装默认 selector，运行时每次请求现读 settings 快照。
 *
 * <p>禁用时代理地址为 null，明确直连而非继承宿主环境。
 */
@Configuration(proxyBeanMethods = false)
public class SystemNetworkConfiguration {

  @Bean(name = "systemProxySelector", destroyMethod = "close")
  public SystemProxySelector systemProxySelector(SystemSettingsSnapshot snapshot) {
    return new SystemProxySelector(() -> snapshot.get().network());
  }
}
