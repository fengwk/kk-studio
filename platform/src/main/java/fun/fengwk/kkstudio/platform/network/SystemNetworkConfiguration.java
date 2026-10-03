package fun.fengwk.kkstudio.platform.network;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/** 单一系统代理只在启动时读取；修改 settings 后必须重启，禁用时明确直连而非继承环境。 */
@Configuration(proxyBeanMethods = false)
public class SystemNetworkConfiguration {

  @Bean(name = "systemProxySelector", destroyMethod = "close")
  public SystemProxySelector systemProxySelector(SystemSettingsSnapshot snapshot) {
    SystemSettings.Network network = snapshot.get().network();
    return new SystemProxySelector(
        HttpProxySelector.fixed(network.proxyUrl(), network.noProxyHosts()));
  }
}
