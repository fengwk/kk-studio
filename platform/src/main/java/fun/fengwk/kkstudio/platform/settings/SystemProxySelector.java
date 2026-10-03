package fun.fengwk.kkstudio.platform.settings;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 启动期固定的 Backend HTTP 代理，同时供受管客户端和读取 JVM 默认值的第三方客户端使用。
 *
 * <p>上下文关闭时仅恢复自己安装的默认值，不能覆盖后来由其他上下文或宿主安装的策略。
 */
public final class SystemProxySelector extends ProxySelector implements AutoCloseable {

  private final ProxySelector delegate;
  private final ProxySelector previous;
  private boolean closed;

  SystemProxySelector(ProxySelector delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    synchronized (SystemProxySelector.class) {
      previous = ProxySelector.getDefault();
      ProxySelector.setDefault(this);
    }
  }

  @Override
  public List<Proxy> select(URI uri) {
    return delegate.select(uri);
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException error) {
    delegate.connectFailed(uri, address, error);
  }

  @Override
  public void close() {
    synchronized (SystemProxySelector.class) {
      closed = true;
      if (ProxySelector.getDefault() == this) {
        ProxySelector restore = previous;
        while (restore instanceof SystemProxySelector installed && installed.closed) {
          restore = installed.previous;
        }
        ProxySelector.setDefault(restore);
      }
    }
  }
}
