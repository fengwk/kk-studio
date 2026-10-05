package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** 进程全局代理状态每次在 finally 中恢复，避免污染后续网络测试。 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("defaultProxySelector")
class DaemonProxyInitializerTest {

  /** 意图：未指定系统属性时开启 OS 代理，同时保存原 selector 为协议独立回退。 */
  @Test
  void installsEnvironmentBeforeClientsAndRetainsFallback() {
    withRestoredGlobals(
        () -> {
          System.clearProperty("java.net.useSystemProxies");
          ProxySelector.setDefault(HttpProxySelector.fixed("http://os.invalid:9000", ""));
          DaemonProxyInitializer.install(
              Map.of("http_proxy", "http://env.invalid:8080", "no_proxy", "local.invalid"));
          assertEquals("true", System.getProperty("java.net.useSystemProxies"));
          ProxySelector selector = ProxySelector.getDefault();
          assertEquals(
              HttpProxySelector.fixed("http://env.invalid:8080", "")
                  .select(URI.create("http://remote.invalid")),
              selector.select(URI.create("http://remote.invalid")));
          assertEquals(
              HttpProxySelector.fixed("http://os.invalid:9000", "")
                  .select(URI.create("https://remote.invalid")),
              selector.select(URI.create("https://remote.invalid")));
          assertEquals(
              Proxy.NO_PROXY, selector.select(URI.create("https://local.invalid")).getFirst());
        });
  }

  /** 意图：用户形式 no_proxy 含 CIDR 时安装成功；网段内数值 IP 与域名规则直连，外部走代理或 OS 回退。 */
  @Test
  void installsEnvironmentWithCidrBypassWithoutNetworkAccess() {
    withRestoredGlobals(
        () -> {
          System.clearProperty("java.net.useSystemProxies");
          ProxySelector.setDefault(HttpProxySelector.fixed("http://os.invalid:9000", ""));
          DaemonProxyInitializer.install(
              Map.of(
                  "https_proxy",
                  "http://env.invalid:8080",
                  "no_proxy",
                  "studio.internal,.kk1.fun,192.168.0.0/16,100.64.0.0/10,2001:db8::/32"));
          ProxySelector selector = ProxySelector.getDefault();
          for (String direct :
              List.of(
                  "http://192.168.10.20",
                  "https://100.100.0.1",
                  "http://[2001:db8::9]",
                  "http://studio.internal",
                  "http://api.kk1.fun")) {
            assertEquals(Proxy.NO_PROXY, selector.select(URI.create(direct)).getFirst(), direct);
          }
          // 外部目标：HTTPS 走环境代理，HTTP 走保留的 OS 回退，证明 CIDR 命中不触及两者。
          assertEquals(
              "env.invalid",
              addressHost(selector.select(URI.create("https://203.0.113.1")).getFirst()));
          assertEquals(
              "os.invalid",
              addressHost(selector.select(URI.create("http://203.0.113.1")).getFirst()));
        });
  }

  /** 意图：显式 JVM 禁用 OS 代理不会被覆盖；无默认 selector 时仍明确 DIRECT。 */
  @Test
  void preservesExplicitJvmPropertyAndNullFallback() {
    withRestoredGlobals(
        () -> {
          System.setProperty("java.net.useSystemProxies", "false");
          ProxySelector.setDefault(null);
          DaemonProxyInitializer.install(Map.of());
          assertEquals("false", System.getProperty("java.net.useSystemProxies"));
          assertEquals(
              Proxy.NO_PROXY,
              ProxySelector.getDefault().select(URI.create("http://remote.invalid")).getFirst());
        });
  }

  /** 意图：错误环境配置不安装半成品 selector，更不切换为静默 DIRECT。 */
  @Test
  void invalidEnvironmentLeavesDefaultSelectorUntouched() {
    withRestoredGlobals(
        () -> {
          ProxySelector previous = ProxySelector.getDefault();
          assertThrows(
              IllegalArgumentException.class,
              () -> DaemonProxyInitializer.install(Map.of("https_proxy", "invalid")));
          assertSame(previous, ProxySelector.getDefault());
        });
  }

  private static String addressHost(Proxy proxy) {
    return ((InetSocketAddress) proxy.address()).getHostString();
  }

  private static void withRestoredGlobals(Runnable test) {
    String property = System.getProperty("java.net.useSystemProxies");
    ProxySelector selector = ProxySelector.getDefault();
    try {
      test.run();
    } finally {
      ProxySelector.setDefault(selector);
      if (property == null) {
        System.clearProperty("java.net.useSystemProxies");
      } else {
        System.setProperty("java.net.useSystemProxies", property);
      }
    }
  }
}
