package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

/** JVM 默认 selector 的所有权与关闭恢复守卫；测试始终恢复进入前的全局状态。 */
@ResourceLock("jvm-proxy-selector")
class SystemProxySelectorTest {

  /** 安装的对象与受管 bean 是同一实例，选择及连接失败原样委托，重复关闭不污染默认值。 */
  @Test
  void installsDelegatesAndRestoresPreviousDefault() {
    ProxySelector previous = ProxySelector.getDefault();
    ProxySelector delegate = mock(ProxySelector.class);
    URI uri = URI.create("https://example.com/media");
    when(delegate.select(uri)).thenReturn(List.of(Proxy.NO_PROXY));
    InetSocketAddress address = new InetSocketAddress("localhost", 3128);
    IOException error = new IOException("test failure");
    try (SystemProxySelector selector = new SystemProxySelector(delegate)) {
      assertSame(selector, ProxySelector.getDefault());
      assertEquals(List.of(Proxy.NO_PROXY), selector.select(uri));
      selector.connectFailed(uri, address, error);
      verify(delegate).connectFailed(uri, address, error);
      selector.close();
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      ProxySelector.setDefault(previous);
    }
    assertSame(previous, ProxySelector.getDefault());
  }

  /** 后来的宿主安装不属于当前上下文，销毁时不得覆盖；null 委托也不能破坏全局状态。 */
  @Test
  void doesNotOverwriteAnExternalReplacement() {
    ProxySelector previous = ProxySelector.getDefault();
    ProxySelector replacement = mock(ProxySelector.class);
    try (SystemProxySelector selector = new SystemProxySelector(mock(ProxySelector.class))) {
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
    try {
      for (boolean firstClosesFirst : List.of(true, false)) {
        try (SystemProxySelector first = new SystemProxySelector(mock(ProxySelector.class));
            SystemProxySelector second = new SystemProxySelector(mock(ProxySelector.class))) {
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
}
