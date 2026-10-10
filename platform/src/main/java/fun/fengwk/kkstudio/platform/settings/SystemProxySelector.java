package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Backend 全局 HTTP 代理的稳定门面：装配期安装为 JVM 默认 selector，运行时每次选择现读当前网络设置。
 *
 * <p>受管客户端持有本实例的引用，因此 settings 保存后新发起的请求自动采用新路由，正在执行的请求由其自身的连接/流继续完成， 不需要重建 client。内部只缓存当前 {@link
 * SystemSettings.Network} 与其不可变的 {@link HttpProxySelector#fixed} 委托； 配置未变不重编
 * bypass，变了才在短临界区内懒更新，并且锁内重读保证并发下不会把缓存回退到更旧的配置。
 *
 * <p>上下文关闭时仅恢复自己安装的默认值，不能覆盖后来由其他上下文或宿主安装的策略。
 */
public final class SystemProxySelector extends ProxySelector implements AutoCloseable {

  private final Supplier<SystemSettings.Network> networkSupplier;
  private final ProxySelector previous;
  private final Object updateLock = new Object();
  private volatile CachedDelegate cached;
  private boolean closed;

  SystemProxySelector(Supplier<SystemSettings.Network> networkSupplier) {
    this.networkSupplier = Objects.requireNonNull(networkSupplier, "networkSupplier");
    synchronized (SystemProxySelector.class) {
      previous = ProxySelector.getDefault();
      ProxySelector.setDefault(this);
    }
  }

  @Override
  public List<Proxy> select(URI uri) {
    return delegate().select(uri);
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException error) {
    delegate().connectFailed(uri, address, error);
  }

  /** 返回当前网络设置对应的不可变委托；只有配置真正变化时才重建，并在锁内重读避免并发回退。 */
  private ProxySelector delegate() {
    SystemSettings.Network network = currentNetwork();
    CachedDelegate snapshot = cached;
    if (snapshot != null && snapshot.network().equals(network)) {
      return snapshot.delegate();
    }
    synchronized (updateLock) {
      SystemSettings.Network latest = currentNetwork();
      CachedDelegate existing = cached;
      if (existing == null || !existing.network().equals(latest)) {
        existing =
            new CachedDelegate(
                latest, HttpProxySelector.fixed(latest.proxyUrl(), latest.noProxyHosts()));
        cached = existing;
      }
      return existing.delegate();
    }
  }

  private SystemSettings.Network currentNetwork() {
    return Objects.requireNonNull(networkSupplier.get(), "network");
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

  private record CachedDelegate(SystemSettings.Network network, ProxySelector delegate) {}
}
