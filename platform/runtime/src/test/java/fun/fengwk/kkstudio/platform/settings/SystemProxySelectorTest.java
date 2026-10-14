package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** JVM 默认 selector 的所有权、动态选路与关闭恢复守卫；测试始终恢复进入前的全局状态。 */
@ResourceLock("jvm-proxy-selector")
class SystemProxySelectorTest {

  private static final URI TARGET = URI.create("https://target.example/media");

  /** 安装的对象与受管 bean 是同一实例，选择走当前网络设置，重复关闭不污染默认值。 */
  @Test
  void installsSelectsCurrentNetworkAndRestoresPreviousDefault() {
    ProxySelector previous = ProxySelector.getDefault();
    InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", 3128);
    IOException error = new IOException("test failure");
    try (SystemProxySelector selector =
        new SystemProxySelector(() -> network("http://proxy.example:3128", ""))) {
      assertSame(selector, ProxySelector.getDefault());
      Proxy selected = selector.select(TARGET).getFirst();
      assertEquals(Proxy.Type.HTTP, selected.type());
      assertEquals(InetSocketAddress.createUnresolved("proxy.example", 3128), selected.address());
      selector.connectFailed(TARGET, address, error);
      selector.close();
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      ProxySelector.setDefault(previous);
    }
    assertSame(previous, ProxySelector.getDefault());
  }

  /** 供应商返回值变化立即选新路由；等值网络（含不同实例）复用同一不可变委托，避免无谓重编 bypass。 */
  @Test
  void followsSupplierAndReusesDelegateForUnchangedNetwork() {
    AtomicReference<SystemSettings.Network> current =
        new AtomicReference<>(network("http://a.example:3128", ""));
    ProxySelector previous = ProxySelector.getDefault();
    try (SystemProxySelector selector = new SystemProxySelector(current::get)) {
      Proxy first = selector.select(TARGET).getFirst();
      assertEquals("a.example", ((InetSocketAddress) first.address()).getHostString());
      // 同网络值再次选择复用同一 Proxy 实例，证明未重建委托。
      assertSame(first, selector.select(TARGET).getFirst());
      current.set(network("http://a.example:3128", ""));
      assertSame(first, selector.select(TARGET).getFirst());
      // 路由变化后新选择采用新代理。
      current.set(network("http://b.example:3128", ""));
      Proxy second = selector.select(TARGET).getFirst();
      assertEquals("b.example", ((InetSocketAddress) second.address()).getHostString());
      assertNotSame(first, second);
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** 绕过规则变化后同一请求重新选路；代理关闭（null）时明确直连而非继承宿主。 */
  @Test
  void appliesBypassAndDirectNetworkChanges() {
    AtomicReference<SystemSettings.Network> current =
        new AtomicReference<>(network("http://proxy.example:3128", ""));
    ProxySelector previous = ProxySelector.getDefault();
    URI bypassed = URI.create("http://bypass.example/media");
    try (SystemProxySelector selector = new SystemProxySelector(current::get)) {
      assertEquals(Proxy.Type.HTTP, selector.select(bypassed).getFirst().type());
      current.set(network("http://proxy.example:3128", "bypass.example"));
      assertEquals(Proxy.NO_PROXY, selector.select(bypassed).getFirst());
      current.set(network(null, ""));
      assertEquals(Proxy.NO_PROXY, selector.select(TARGET).getFirst());
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** 锁内重读最新网络设置，较旧的一次读取不能让缓存回退到旧配置。 */
  @Test
  void lockReReadsLatestNetworkWithoutRegression() {
    AtomicInteger calls = new AtomicInteger();
    Supplier<SystemSettings.Network> supplier =
        () ->
            calls.getAndIncrement() == 0
                ? network("http://old.example:3128", "")
                : network("http://new.example:3128", "");
    ProxySelector previous = ProxySelector.getDefault();
    try (SystemProxySelector selector = new SystemProxySelector(supplier)) {
      Proxy selected = selector.select(TARGET).getFirst();
      assertEquals("new.example", ((InetSocketAddress) selected.address()).getHostString());
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** 后来的宿主安装不属于当前上下文，销毁时不得覆盖；null 供应商也不能破坏全局状态。 */
  @Test
  void doesNotOverwriteAnExternalReplacement() {
    ProxySelector previous = ProxySelector.getDefault();
    ProxySelector replacement =
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
          }

          @Override
          public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        };
    try (SystemProxySelector selector = new SystemProxySelector(() -> network(null, ""))) {
      ProxySelector.setDefault(replacement);
      selector.close();
      assertSame(replacement, ProxySelector.getDefault());
      assertThrows(NullPointerException.class, () -> new SystemProxySelector(null));
      assertSame(replacement, ProxySelector.getDefault());
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** 多上下文以任一顺序销毁时，都不能恢复已经关闭的上下文 selector。 */
  @Test
  void overlappingContextsRestoreTheLastLiveOwner() {
    ProxySelector previous = ProxySelector.getDefault();
    Supplier<SystemSettings.Network> supplier = () -> network(null, "");
    try {
      for (boolean firstClosesFirst : List.of(true, false)) {
        try (SystemProxySelector first = new SystemProxySelector(supplier);
            SystemProxySelector second = new SystemProxySelector(supplier)) {
          if (firstClosesFirst) {
            first.close();
            assertSame(second, ProxySelector.getDefault());
            second.close();
          } else {
            second.close();
            assertSame(first, ProxySelector.getDefault());
            first.close();
          }
          assertSame(previous, ProxySelector.getDefault());
        }
      }
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  private static SystemSettings.Network network(String proxyUrl, String noProxyHosts) {
    return new SystemSettings.Network(proxyUrl, noProxyHosts);
  }
}
