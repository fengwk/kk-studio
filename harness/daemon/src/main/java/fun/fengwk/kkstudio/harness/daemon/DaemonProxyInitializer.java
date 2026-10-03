package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;

import java.net.ProxySelector;
import java.util.Map;

/** 仅在有效运行命令启动时安装本机策略，必须早于任何网络客户端初始化。 */
final class DaemonProxyInitializer {

  private DaemonProxyInitializer() {}

  static void install(Map<String, String> environment) {
    // 必须先于首次访问 JDK DefaultProxySelector；保留显式 JVM 设置。
    if (System.getProperty("java.net.useSystemProxies") == null) {
      System.setProperty("java.net.useSystemProxies", "true");
    }
    ProxySelector fallback = ProxySelector.getDefault();
    ProxySelector.setDefault(HttpProxySelector.fromEnvironment(environment, fallback));
  }
}
