package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
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
